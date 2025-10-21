package com.bitchat.android.nostr

import android.util.Log
import androidx.annotation.MainThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

/**
 * Manages location notes (kind=1 text notes with geohash tags)
 * iOS-compatible implementation with LiveData for Android UI binding
 */
@MainThread
class LocationNotesManager private constructor() {
    
    companion object {
        private const val TAG = "LocationNotesManager"
        private const val MAX_NOTES_IN_MEMORY = 500
        
        @Volatile
        private var INSTANCE: LocationNotesManager? = null
        
        fun getInstance(): LocationNotesManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LocationNotesManager().also { INSTANCE = it }
            }
        }
    }
    
    /**
     * Note data class matching iOS implementation
     */
    data class Note(
        val id: String,
        val pubkey: String,
        val content: String,
        val createdAt: Int,
        val nickname: String?
    ) {
        /**
         * Display name for the note - matches iOS exactly
         * Format: "nickname#abcd" or "anon#abcd" where abcd is last 4 chars of pubkey
         */
        val displayName: String
            get() {
                val suffix = pubkey.takeLast(4)
                val nick = nickname?.trim()
                return if (!nick.isNullOrEmpty()) {
                    "$nick#$suffix"
                } else {
                    "anon#$suffix"
                }
            }
    }
    
    /**
     * Manager state enum
     */
    enum class State {
        IDLE,
        LOADING,
        READY,
        NO_RELAYS
    }
    
    // Published state (LiveData for Android)
    private val _notes = MutableLiveData<List<Note>>(emptyList())
    val notes: LiveData<List<Note>> = _notes
    
    private val _geohash = MutableLiveData<String?>(null)
    val geohash: LiveData<String?> = _geohash
    
    private val _initialLoadComplete = MutableLiveData(false)
    val initialLoadComplete: LiveData<Boolean> = _initialLoadComplete
    
    private val _state = MutableLiveData(State.IDLE)
    val state: LiveData<State> = _state
    
    private val _errorMessage = MutableLiveData<String?>(null)
    val errorMessage: LiveData<String?> = _errorMessage
    
    // Private state
    private var subscriptionIDs: MutableMap<String, String> = mutableMapOf()
    private val noteIDs = mutableSetOf<String>() // For deduplication
    private var subscribedGeohashes: Set<String> = emptySet()
    
    // Dependencies (injected via setters for flexibility)
    private var relayLookup: (() -> NostrRelayManager)? = null
    private var subscribeFunc: ((NostrFilter, String, (NostrEvent) -> Unit) -> String)? = null
    private var unsubscribeFunc: ((String) -> Unit)? = null
    private var sendEventFunc: ((NostrEvent, List<String>?) -> Unit)? = null
    private var deriveIdentityFunc: ((String) -> NostrIdentity)? = null
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    /**
     * Initialize dependencies
     */
    fun initialize(
        relayManager: () -> NostrRelayManager,
        subscribe: (NostrFilter, String, (NostrEvent) -> Unit) -> String,
        unsubscribe: (String) -> Unit,
        sendEvent: (NostrEvent, List<String>?) -> Unit,
        deriveIdentity: (String) -> NostrIdentity
    ) {
        this.relayLookup = relayManager
        this.subscribeFunc = subscribe
        this.unsubscribeFunc = unsubscribe
        this.sendEventFunc = sendEvent
        this.deriveIdentityFunc = deriveIdentity
    }
    
    /**
     * Set geohash and start subscription
     * iOS: Validates building-level precision (8 characters)
     */
    fun setGeohash(newGeohash: String) {
        val normalized = newGeohash.lowercase()
        
        if (_geohash.value == normalized) {
            Log.d(TAG, "Geohash unchanged, skipping: $normalized")
            return
        }
        
        // Validate geohash (building-level precision: 8 chars) - matches iOS
        if (!isValidBuildingGeohash(normalized)) {
            Log.w(TAG, "LocationNotesManager: rejecting invalid geohash '$normalized' (expected 8 valid base32 chars)")
            return
        }
        
        Log.d(TAG, "Setting geohash: $normalized")
        
        // Cancel existing subscription
        cancel()
        
        // Set loading state before clearing to prevent empty state flicker (iOS pattern)
        _state.value = State.LOADING
        _initialLoadComplete.value = false
        _errorMessage.value = null
        
        // Clear notes
        _notes.value = emptyList()
        noteIDs.clear()
        _geohash.value = normalized
        
        // Compute target geohashes: center + neighbors (±1)
        val neighbors = try {
            com.bitchat.android.geohash.Geohash.neighborsSamePrecision(normalized)
        } catch (_: Exception) { emptySet() }
        subscribedGeohashes = (neighbors + normalized).toSet()

        // Start new subscriptions for all cells
        subscribeAll()
    }
    
    /**
     * Validate building-level geohash (precision 8) - matches iOS Geohash.isValidBuildingGeohash
     */
    private fun isValidBuildingGeohash(geohash: String): Boolean {
        if (geohash.length != 8) return false
        val base32Chars = "0123456789bcdefghjkmnpqrstuvwxyz"
        return geohash.all { it in base32Chars }
    }
    
    /**
     * Refresh notes for current geohash
     */
    fun refresh() {
        val currentGeohash = _geohash.value
        if (currentGeohash == null) {
            Log.w(TAG, "Cannot refresh - no geohash set")
            return
        }
        
        Log.d(TAG, "Refreshing notes for geohash: $currentGeohash")
        
        // Cancel and restart subscriptions for current ±1 set
        cancel()
        _notes.value = emptyList()
        noteIDs.clear()
        _initialLoadComplete.value = false
        // Rebuild subscribedGeohashes and resubscribe
        val neighbors = try {
            com.bitchat.android.geohash.Geohash.neighborsSamePrecision(currentGeohash)
        } catch (_: Exception) { emptySet() }
        subscribedGeohashes = (neighbors + currentGeohash).toSet()
        subscribeAll()
    }
    
    /**
     * Send a new location note
     */
    fun send(content: String, nickname: String?) {
        val currentGeohash = _geohash.value
        if (currentGeohash == null) {
            Log.w(TAG, "Cannot send note - no geohash set")
            _errorMessage.value = "No location set"
            return
        }
        
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return
        }
        
        // CRITICAL FIX: Get geo-specific relays for sending (matching iOS pattern)
        // iOS: let relays = dependencies.relayLookup(geohash, TransportConfig.nostrGeoRelayCount)
        val relays = try {
            com.bitchat.android.nostr.RelayDirectory.closestRelaysForGeohash(currentGeohash, 5)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lookup relays for geohash $currentGeohash: ${e.message}")
            emptyList()
        }
        
        // Check if we have relays (iOS pattern: guard !relays.isEmpty())
        if (relays.isEmpty()) {
            Log.w(TAG, "Send blocked - no geo relays for geohash: $currentGeohash")
            _state.value = State.NO_RELAYS
            _errorMessage.value = "No relays available"
            return
        }
        
        val deriveIdentity = deriveIdentityFunc
        if (deriveIdentity == null) {
            Log.e(TAG, "Cannot send note - deriveIdentity not initialized")
            _errorMessage.value = "Not initialized"
            return
        }
        
        Log.d(TAG, "Sending note to geohash: $currentGeohash via ${relays.size} geo relays")
        
        scope.launch {
            try {
                val identity = withContext(Dispatchers.IO) {
                    deriveIdentity(currentGeohash)
                }
                
                val event = withContext(Dispatchers.IO) {
                    NostrProtocol.createGeohashTextNote(
                        content = trimmed,
                        geohash = currentGeohash,
                        senderIdentity = identity,
                        nickname = nickname
                    )
                }
                
                // Optimistic local echo - add note immediately to UI
                val localNote = Note(
                    id = event.id,
                    pubkey = event.pubkey,
                    content = trimmed,
                    createdAt = event.createdAt,
                    nickname = nickname
                )
                
                if (!noteIDs.contains(event.id)) {
                    noteIDs.add(event.id)
                    val currentNotes = _notes.value ?: emptyList()
                    _notes.value = (currentNotes + localNote).sortedByDescending { it.createdAt }
                    
                    // Trim if exceeds max
                    if (noteIDs.size > MAX_NOTES_IN_MEMORY) {
                        trimOldestNotes()
                    }
                }
                
                // CRITICAL FIX: Send to geo-specific relays (matching iOS pattern)
                // iOS: dependencies.sendEvent(event, relays)
                withContext(Dispatchers.IO) {
                    sendEventFunc?.invoke(event, relays)
                }
                
                Log.d(TAG, "✅ Note sent successfully to ${relays.size} geo relays: ${event.id.take(16)}...")
                
                // Clear any error messages on successful send
                _errorMessage.value = null
                _state.value = State.READY
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send note: ${e.message}")
                _errorMessage.value = "Failed to send: ${e.message}"
            }
        }
    }
    
    /**
     * Subscribe to location notes for current geohash
     */
    private fun subscribeAll() {
        val currentGeohash = _geohash.value
        if (currentGeohash == null) {
            Log.w(TAG, "Cannot subscribe - no geohash set")
            _state.value = State.IDLE
            return
        }
        
        val subscribe = subscribeFunc
        if (subscribe == null) {
            Log.e(TAG, "Cannot subscribe - subscribe function not initialized; will retry shortly")
            _state.value = State.LOADING
            // Retry a few times in case initialization is racing the sheet open
            scope.launch {
                var attempts = 0
                while (attempts < 10 && subscribeFunc == null) {
                    delay(300)
                    attempts++
                }
                val subNow = subscribeFunc
                if (subNow != null) {
                    // Try again now that dependencies are ready
                    subscribeAll()
                } else {
                    // Give UI a chance to show empty state rather than spinner forever
                    if (!_initialLoadComplete.value!!) {
                        _initialLoadComplete.value = true
                        _state.value = State.READY
                    }
                }
            }
            return
        }

        _state.value = State.LOADING
        
        // Subscribe for each geohash in the ±1 set
        subscribedGeohashes.forEach { gh ->
            val filter = NostrFilter.geohashNotes(
                geohash = gh,
                since = null,
                limit = 200
            )
            val subId = "location-notes-$gh"
            Log.d(TAG, "📡 Subscribing to location notes: $subId")
            try {
                val id = subscribe(filter, subId) { event -> handleEvent(event) }
                subscriptionIDs[gh] = id
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe for $gh: ${e.message}")
            }
        }
        
        // Mark initial load complete after brief delay to allow relay responses
        scope.launch {
            delay(2000) // Wait 2 seconds for initial batch
            if (!_initialLoadComplete.value!!) {
                _initialLoadComplete.value = true
                _state.value = State.READY
                Log.d(TAG, "Initial load complete for geohash: $currentGeohash (${noteIDs.size} notes)")
            }
        }
    }
    
    /**
     * Handle incoming event from subscription
     */
    private fun handleEvent(event: NostrEvent) {
        // Validate event
        if (event.kind != NostrKind.TEXT_NOTE) {
            Log.v(TAG, "Ignoring non-text-note event: kind=${event.kind}")
            return
        }
        
        // Check for geohash tag
        val geohashTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "g" }
        if (geohashTag == null) {
            Log.v(TAG, "Ignoring event without geohash tag: ${event.id.take(16)}...")
            return
        }
        
        // Check if matches current geohash
        val eventGeohash = geohashTag[1]
        if (!subscribedGeohashes.contains(eventGeohash)) {
            Log.v(TAG, "Ignoring event for non-subscribed geohash: $eventGeohash")
            return
        }
        
        // Deduplicate
        if (noteIDs.contains(event.id)) {
            return
        }
        
        // Extract nickname from tags
        val nicknameTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "n" }
        val nickname = nicknameTag?.get(1)
        
        // Create note
        val note = Note(
            id = event.id,
            pubkey = event.pubkey,
            content = event.content,
            createdAt = event.createdAt,
            nickname = nickname
        )
        
        // Add to collection
        noteIDs.add(event.id)
        val currentNotes = _notes.value ?: emptyList()
        _notes.value = (currentNotes + note).sortedByDescending { it.createdAt }
        
        Log.d(TAG, "📥 Added note: ${note.displayName} - ${note.content.take(50)}")
        
        // Trim if exceeds max
        if (noteIDs.size > MAX_NOTES_IN_MEMORY) {
            trimOldestNotes()
        }
        
        // Update state
        if (!_initialLoadComplete.value!!) {
            _initialLoadComplete.value = true
        }
        _state.value = State.READY
    }
    
    /**
     * Trim oldest notes to stay within memory limit
     */
    private fun trimOldestNotes() {
        val currentNotes = _notes.value ?: return
        if (currentNotes.size <= MAX_NOTES_IN_MEMORY) return
        
        val trimmed = currentNotes.sortedByDescending { it.createdAt }.take(MAX_NOTES_IN_MEMORY)
        _notes.value = trimmed
        
        // Update note IDs set
        noteIDs.clear()
        noteIDs.addAll(trimmed.map { it.id })
        
        Log.d(TAG, "Trimmed notes to $MAX_NOTES_IN_MEMORY (removed ${currentNotes.size - trimmed.size})")
    }
    
    /**
     * Clear error message - matches iOS clearError()
     */
    fun clearError() {
        _errorMessage.value = null
    }
    
    /**
     * Cancel subscription and clear state
     */
    fun cancel() {
        if (subscriptionIDs.isNotEmpty()) {
            subscriptionIDs.values.forEach { subId ->
                try {
                    Log.d(TAG, "🚫 Canceling subscription: $subId")
                    unsubscribeFunc?.invoke(subId)
                } catch (_: Exception) { }
            }
            subscriptionIDs.clear()
        }
        subscribedGeohashes = emptySet()
        _state.value = State.IDLE
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        cancel()
        scope.cancel()
        _notes.value = emptyList()
        noteIDs.clear()
        _geohash.value = null
        _initialLoadComplete.value = false
        _errorMessage.value = null
    }
}
