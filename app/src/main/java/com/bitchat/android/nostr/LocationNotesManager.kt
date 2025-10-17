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
         * Display name for the note (nickname or truncated pubkey)
         */
        val displayName: String
            get() = nickname ?: "@${pubkey.take(8)}"
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
    private var subscriptionID: String? = null
    private val noteIDs = mutableSetOf<String>() // For deduplication
    
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
     */
    fun setGeohash(newGeohash: String) {
        if (_geohash.value == newGeohash) {
            Log.d(TAG, "Geohash unchanged, skipping: $newGeohash")
            return
        }
        
        Log.d(TAG, "Setting geohash: $newGeohash")
        
        // Cancel existing subscription
        cancel()
        
        // Clear state
        _notes.value = emptyList()
        noteIDs.clear()
        _initialLoadComplete.value = false
        _errorMessage.value = null
        _geohash.value = newGeohash
        
        // Start new subscription
        subscribe()
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
        
        // Cancel and restart subscription
        cancel()
        _notes.value = emptyList()
        noteIDs.clear()
        _initialLoadComplete.value = false
        subscribe()
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
    private fun subscribe() {
        val currentGeohash = _geohash.value
        if (currentGeohash == null) {
            Log.w(TAG, "Cannot subscribe - no geohash set")
            _state.value = State.IDLE
            return
        }
        
        val subscribe = subscribeFunc
        if (subscribe == null) {
            Log.e(TAG, "Cannot subscribe - subscribe function not initialized")
            _state.value = State.NO_RELAYS
            return
        }
        
        // CRITICAL FIX: Get geo-specific relays for this geohash (matching iOS pattern)
        // iOS: let relays = dependencies.relayLookup(geohash, TransportConfig.nostrGeoRelayCount)
        val relays = try {
            com.bitchat.android.nostr.RelayDirectory.closestRelaysForGeohash(currentGeohash, 5)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lookup relays for geohash $currentGeohash: ${e.message}")
            emptyList()
        }
        
        // Check if we have any relays (iOS pattern: guard !relays.isEmpty())
        if (relays.isEmpty()) {
            Log.w(TAG, "No geo relays available for geohash: $currentGeohash")
            _state.value = State.NO_RELAYS
            _initialLoadComplete.value = true
            _errorMessage.value = "No relays available"
            return
        }
        
        Log.d(TAG, "📡 Found ${relays.size} geo relays for geohash $currentGeohash: ${relays.joinToString()}")
        
        _state.value = State.LOADING
        
        val filter = NostrFilter.geohashNotes(
            geohash = currentGeohash,
            since = null, // Get all notes (relays will limit)
            limit = 200
        )
        
        val subId = "location-notes-$currentGeohash"
        
        Log.d(TAG, "📡 Subscribing to location notes: $subId")
        
        subscriptionID = subscribe(filter, subId) { event ->
            handleEvent(event)
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
        if (eventGeohash != _geohash.value) {
            Log.v(TAG, "Ignoring event for different geohash: $eventGeohash")
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
        subscriptionID?.let { subId ->
            Log.d(TAG, "🚫 Canceling subscription: $subId")
            unsubscribeFunc?.invoke(subId)
            subscriptionID = null
        }
        
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
