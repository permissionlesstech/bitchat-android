package com.bitchat.android.nostr

import android.util.Log
import androidx.annotation.MainThread
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Manages location notes (kind=1 text notes with geohash tags)
 * iOS-compatible implementation with StateFlow for Android UI binding
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
    
    // Published state (StateFlow for Android)
    private val _notes = MutableStateFlow<List<Note>>(emptyList())
    val notes: StateFlow<List<Note>> = _notes.asStateFlow()
    
    private val _geohash = MutableStateFlow<String?>(null)
    val geohash: StateFlow<String?> = _geohash.asStateFlow()
    
    private val _initialLoadComplete = MutableStateFlow(false)
    val initialLoadComplete: StateFlow<Boolean> = _initialLoadComplete.asStateFlow()
    
    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()
    
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    
    // Private state
    private var subscriptionIDs: MutableMap<String, String> = mutableMapOf()
    private val noteIDs = mutableSetOf<String>() // For deduplication
    private var subscribedGeohashes: Set<String> = emptySet()
    
    // Dependencies (injected via setters for flexibility)
    private var relayLookup: (() -> NostrRelayManager)? = null
    private var subscribeFunc: ((NostrFilter, String, (NostrEvent) -> Unit) -> String)? = null
    private var unsubscribeFunc: ((String) -> Unit)? = null
    private var sendEventFunc: ((NostrEvent, List<String>?, Long) -> Unit)? = null
    private var deriveIdentityFunc: ((String) -> NostrIdentity)? = null
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var liveLocationToken: Long? = null
    private var subscribeRetryJob: Job? = null
    private var initialLoadJob: Job? = null

    init {
        LiveLocationPrivacyGate.addRevocationListener(::stop)
    }
    
    /**
     * Initialize dependencies
     */
    fun initialize(
        relayManager: () -> NostrRelayManager,
        subscribe: (NostrFilter, String, (NostrEvent) -> Unit) -> String,
        unsubscribe: (String) -> Unit,
        sendEvent: (NostrEvent, List<String>?, Long) -> Unit,
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
        val token = LiveLocationPrivacyGate.captureToken() ?: run {
            stop()
            return
        }
        val normalized = newGeohash.lowercase()
        
        if (_geohash.value == normalized &&
            liveLocationToken?.let(LiveLocationPrivacyGate::accepts) == true
        ) {
            return
        }
        
        // Validate geohash (building-level precision: 8 chars) - matches iOS
        if (!isValidBuildingGeohash(normalized)) {
            Log.w(TAG, "LocationNotesManager rejected an invalid building geohash")
            return
        }

        // Cancel existing subscription
        cancel()
        if (!LiveLocationPrivacyGate.accepts(token)) return
        liveLocationToken = token
        
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
        subscribeAll(token)
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
        val token = LiveLocationPrivacyGate.captureToken() ?: run {
            stop()
            return
        }
        val currentGeohash = _geohash.value
        if (currentGeohash == null) {
            Log.w(TAG, "Cannot refresh - no geohash set")
            return
        }
        
        // Cancel and restart subscriptions for current ±1 set
        cancel()
        if (!LiveLocationPrivacyGate.accepts(token)) return
        liveLocationToken = token
        _notes.value = emptyList()
        noteIDs.clear()
        _initialLoadComplete.value = false
        // Rebuild subscribedGeohashes and resubscribe
        val neighbors = try {
            com.bitchat.android.geohash.Geohash.neighborsSamePrecision(currentGeohash)
        } catch (_: Exception) { emptySet() }
        subscribedGeohashes = (neighbors + currentGeohash).toSet()
        subscribeAll(token)
    }
    
    /**
     * Send a new location note
     */
    fun send(content: String, nickname: String?) {
        val token = LiveLocationPrivacyGate.captureToken() ?: run {
            stop()
            return
        }
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
        var relays: List<String> = emptyList()
        try {
            LiveLocationPrivacyGate.runIfAllowed(token) {
                relays = RelayDirectory.closestRelaysForGeohash(currentGeohash, 5)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to look up location-note relays")
        }
        if (!LiveLocationPrivacyGate.accepts(token)) {
            stop()
            return
        }
        
        // Check if we have relays (iOS pattern: guard !relays.isEmpty())
        if (relays.isEmpty()) {
            Log.w(TAG, "Location-note send blocked because no relays are available")
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
        
        scope.launch {
            try {
                var identity: NostrIdentity? = null
                val identityPrepared = withContext(Dispatchers.IO) {
                    LiveLocationPrivacyGate.runIfAllowed(token) {
                        identity = deriveIdentity(currentGeohash)
                    }
                }
                val preparedIdentity = identity
                if (!identityPrepared || preparedIdentity == null ||
                    !LiveLocationPrivacyGate.accepts(token)
                ) return@launch

                val preparedEvent = withContext(Dispatchers.IO) {
                    NostrProtocol.createGeohashTextNote(
                            content = trimmed,
                            geohash = currentGeohash,
                            senderIdentity = preparedIdentity,
                            nickname = nickname
                        )
                }
                if (!LiveLocationPrivacyGate.accepts(token)) return@launch

                // Optimistic local echo - add note immediately to UI
                val localNote = Note(
                    id = preparedEvent.id,
                    pubkey = preparedEvent.pubkey,
                    content = trimmed,
                    createdAt = preparedEvent.createdAt,
                    nickname = nickname
                )
                
                if (!noteIDs.contains(preparedEvent.id)) {
                    noteIDs.add(preparedEvent.id)
                    val currentNotes = _notes.value ?: emptyList()
                    _notes.value = (currentNotes + localNote).sortedByDescending { it.createdAt }
                    
                    // Trim if exceeds max
                    if (noteIDs.size > MAX_NOTES_IN_MEMORY) {
                        trimOldestNotes()
                    }
                }
                
                // CRITICAL FIX: Send to geo-specific relays (matching iOS pattern)
                // iOS: dependencies.sendEvent(event, relays)
                val sent = withContext(Dispatchers.IO) {
                    LiveLocationPrivacyGate.runIfAllowed(token) {
                        sendEventFunc?.invoke(preparedEvent, relays, token)
                    }
                }
                if (!sent) return@launch
                
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
    private fun subscribeAll(token: Long) {
        subscribeRetryJob?.cancel()
        subscribeRetryJob = null
        initialLoadJob?.cancel()
        initialLoadJob = null

        if (!LiveLocationPrivacyGate.accepts(token)) {
            stop()
            return
        }
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
            subscribeRetryJob = scope.launch {
                var attempts = 0
                while (attempts < 10 &&
                    subscribeFunc == null &&
                    LiveLocationPrivacyGate.accepts(token)
                ) {
                    delay(300)
                    attempts++
                }
                val subNow = subscribeFunc
                if (subNow != null && LiveLocationPrivacyGate.accepts(token)) {
                    // Try again now that dependencies are ready
                    subscribeAll(token)
                } else {
                    // Give UI a chance to show empty state rather than spinner forever
                    if (!_initialLoadComplete.value) {
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
            if (!LiveLocationPrivacyGate.accepts(token)) return
            val filter = NostrFilter.geohashNotes(
                geohash = gh,
                since = null,
                limit = 200
            )
            val subId = "location-notes-${UUID.randomUUID()}"
            try {
                var id: String? = null
                LiveLocationPrivacyGate.runIfAllowed(token) {
                    id = subscribe(filter, subId) { event -> handleEvent(event) }
                }
                id?.let { subscriptionIDs[gh] = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to subscribe to location notes")
            }
        }
        
        // Mark initial load complete after brief delay to allow relay responses
        initialLoadJob = scope.launch {
            delay(2000) // Wait 2 seconds for initial batch
            if (_geohash.value == currentGeohash &&
                LiveLocationPrivacyGate.accepts(token) &&
                !_initialLoadComplete.value
            ) {
                _initialLoadComplete.value = true
                _state.value = State.READY
            }
        }
    }
    
    /**
     * Handle incoming event from subscription
     */
    private fun handleEvent(event: NostrEvent) {
        val token = liveLocationToken
        if (token == null || !LiveLocationPrivacyGate.accepts(token)) return

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
        subscribeRetryJob?.cancel()
        subscribeRetryJob = null
        initialLoadJob?.cancel()
        initialLoadJob = null

        if (subscriptionIDs.isNotEmpty()) {
            subscriptionIDs.values.forEach { subId ->
                try {
                    unsubscribeFunc?.invoke(subId)
                } catch (_: Exception) { }
            }
            subscriptionIDs.clear()
        }
        subscribedGeohashes = emptySet()
        _state.value = State.IDLE
    }

    /**
     * End the nearby-notes session and discard location-correlated UI state.
     * Unlike [cancel], this also clears the target so a later activation can
     * safely subscribe to the same building geohash again.
     */
    fun stop() {
        cancel()
        liveLocationToken = null
        _geohash.value = null
        _notes.value = emptyList()
        noteIDs.clear()
        _initialLoadComplete.value = false
        _errorMessage.value = null
    }

    /**
     * Cleanup resources
     */
    fun cleanup() {
        stop()
        scope.cancel()
    }
}
