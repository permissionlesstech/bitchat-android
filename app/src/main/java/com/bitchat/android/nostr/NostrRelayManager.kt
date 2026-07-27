package com.bitchat.android.nostr

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages WebSocket connections to Nostr relays
 * Compatible with iOS implementation with Android-specific optimizations
 */
class NostrRelayManager private constructor() {
    
    companion object {
        @JvmStatic
        val shared = NostrRelayManager()
        
        private const val TAG = "NostrRelayManager"
        private const val MAX_QUEUED_EVENTS = 500
        const val OWNER_LEGACY = "legacy"
        const val OWNER_BACKGROUND = "background"
        
        /**
         * Get instance for Android compatibility (context-aware calls)
         */
        fun getInstance(context: android.content.Context): NostrRelayManager {
            shared.appContext = context.applicationContext
            return shared
        }

        // Default relay list (same as iOS)
        private val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr21.com"
        )
        
        // Exponential backoff configuration (same as iOS)
        private const val INITIAL_BACKOFF_INTERVAL = com.bitchat.android.util.AppConstants.Nostr.INITIAL_BACKOFF_INTERVAL_MS  // 1 second
        private const val MAX_BACKOFF_INTERVAL = com.bitchat.android.util.AppConstants.Nostr.MAX_BACKOFF_INTERVAL_MS    // 5 minutes
        private const val BACKOFF_MULTIPLIER = com.bitchat.android.util.AppConstants.Nostr.BACKOFF_MULTIPLIER
        private const val MAX_RECONNECT_ATTEMPTS = com.bitchat.android.util.AppConstants.Nostr.MAX_RECONNECT_ATTEMPTS
        
        // Track gift-wraps we initiated for logging
        private val pendingGiftWrapIDs = ConcurrentHashMap.newKeySet<String>()
        
        fun registerPendingGiftWrap(id: String) {
            pendingGiftWrapIDs.add(id)
        }

        fun defaultRelays(): List<String> = DEFAULT_RELAYS
    }
    
    /**
     * Relay status information
     */
    data class Relay(
        val url: String,
        var isConnected: Boolean = false,
        var lastError: Throwable? = null,
        var lastConnectedAt: Long? = null,
        var messagesSent: Int = 0,
        var messagesReceived: Int = 0,
        var reconnectAttempts: Int = 0,
        var lastDisconnectedAt: Long? = null,
        var nextReconnectTime: Long? = null
    )
    
    // Published state
    private val _relays = MutableStateFlow<List<Relay>>(emptyList())
    val relays: StateFlow<List<Relay>> = _relays.asStateFlow()
    
    private val _isConnected = MutableStateFlow<Boolean>(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // Internal state
    private val relaysList = mutableListOf<Relay>()
    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val desiredConnected = AtomicBoolean(false)
    private val subscriptions = ConcurrentHashMap<String, Set<String>>() // relay URL -> subscription IDs
    private val messageHandlers = ConcurrentHashMap<String, (NostrEvent) -> Unit>()
    
    // Persistent subscription tracking for robust reconnection
    private val activeSubscriptions = ConcurrentHashMap<String, SubscriptionInfo>() // subscription ID -> info
    
    /**
     * Information about an active subscription that needs to be maintained across reconnections
     */
    data class SubscriptionInfo(
        val id: String,
        val filter: NostrFilter,
        val handler: (NostrEvent) -> Unit,
        val targetRelayUrls: Set<String>? = null, // null means all relays
        val createdAt: Long = System.currentTimeMillis(),
        val originGeohash: String? = null, // used for logging and grouping
        val owner: String = OWNER_LEGACY
    )
    
    // Event deduplication system
    private val eventDeduplicator = NostrEventDeduplicator.getInstance()
    
    // Message queue for reliability
    private data class QueuedEvent(
        val event: NostrEvent,
        val pendingRelayUrls: MutableSet<String>
    )
    private val messageQueue = mutableListOf<QueuedEvent>()
    private val messageQueueLock = Any()
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Subscription validation timer
    private var subscriptionValidationJob: Job? = null
    private val powerManager: com.bitchat.android.mesh.PowerManager?
        get() = appContext?.let { com.bitchat.android.mesh.PowerManager.getInstance(it) }
    @Volatile private var appContext: android.content.Context? = null
    
    // OkHttp client for WebSocket connections (via provider to honor Tor)
    private val httpClient: OkHttpClient
        get() = com.bitchat.android.net.OkHttpProvider.webSocketClient()
    
    private val gson by lazy { NostrRequest.createGson() }
    
    // Per-geohash relay selection
    private val geohashToRelays = ConcurrentHashMap<String, Set<String>>() // geohash -> relay URLs

    // --- Public API for geohash-specific operation ---

    /**
     * Compute and connect to relays for a given geohash (nearest + optional defaults), cache the mapping.
     */
    fun ensureGeohashRelaysConnected(geohash: String, nRelays: Int = 5, includeDefaults: Boolean = false) {
        try {
            val nearest = RelayDirectory.closestRelaysForGeohash(geohash, nRelays)
            val selected = if (includeDefaults) {
                (nearest + Companion.defaultRelays()).toSet()
            } else nearest.toSet()
            if (selected.isEmpty()) {
                Log.w(TAG, "No relays selected for geohash=$geohash")
                return
            }
            geohashToRelays[geohash] = selected
            Log.d(TAG, "Geohash $geohash using ${selected.size} relays")
            ensureConnectionsFor(selected)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure relays for $geohash: ${e.message}")
        }
    }

    /**
     * Get relays mapped to a geohash (empty list if none configured).
     */
    fun getRelaysForGeohash(geohash: String): List<String> {
        return geohashToRelays[geohash]?.toList() ?: emptyList()
    }

    /**
     * Subscribe with explicit geohash routing; ensures connections exist, then targets only those relays.
     */
    fun subscribeForGeohash(
        geohash: String,
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        includeDefaults: Boolean = false,
        nRelays: Int = 5,
        owner: String = OWNER_LEGACY
    ): String {
        ensureGeohashRelaysConnected(geohash, nRelays, includeDefaults)
        val relayUrls = getRelaysForGeohash(geohash)
        return subscribe(
            filter = filter,
            id = id,
            handler = handler,
            targetRelayUrls = relayUrls,
            owner = owner
        ).also {
            // update origin geohash for this subscription
            activeSubscriptions[it]?.let { sub ->
                activeSubscriptions[it] = sub.copy(originGeohash = geohash)
            }
        }
    }

    /**
     * Send an event specifically to a geohash's relays (+ optional defaults).
     */
    fun sendEventToGeohash(event: NostrEvent, geohash: String, includeDefaults: Boolean = false, nRelays: Int = 5) {
        ensureGeohashRelaysConnected(geohash, nRelays, includeDefaults)
        val relayUrls = getRelaysForGeohash(geohash)
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No target relays to send event for geohash=$geohash; falling back to defaults")
            sendEvent(event, Companion.defaultRelays())
            return
        }
        sendEvent(event, relayUrls)
    }

    // --- Internal helpers ---

    private fun ensureConnectionsFor(relayUrls: Set<String>) {
        // Ensure relays are tracked for UI/status
        relayUrls.forEach { url ->
            if (relaysList.none { it.url == url }) {
                relaysList.add(Relay(url))
            }
        }
        updateRelaysList()

        if (!desiredConnected.get()) return
        scope.launch {
            relayUrls.forEach { relayUrl ->
                launch {
                    if (!connections.containsKey(relayUrl)) {
                        connectToRelay(relayUrl)
                    }
                }
            }
        }
    }

    init {
        // Initialize with default relays - avoid static initialization order issues
        try {
            val defaultRelayUrls = listOf(
                "wss://relay.damus.io",
                "wss://relay.primal.net",
                "wss://offchain.pub",
                "wss://nostr21.com"
            )
            relaysList.addAll(defaultRelayUrls.map { Relay(it) })
            _relays.value = relaysList.toList()
            updateConnectionStatus()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NostrRelayManager: ${e.message}", e)
            // Initialize with empty list as fallback
            _relays.value = emptyList()
            _isConnected.value = false
        }
    }
    
    /**
     * Connect to all configured relays
     */
    fun connect() {
        desiredConnected.set(true)
        Log.i(TAG, "Connecting to ${relaysList.size} Nostr relays")
        
        scope.launch {
            relaysList.forEach { relay ->
                launch {
                    connectToRelay(relay.url)
                }
            }
        }
        
        // Start periodic subscription validation
        startSubscriptionValidation()
    }
    
    /**
     * Disconnect from all relays
     */
    fun disconnect() {
        Log.i(TAG, "Disconnecting from all Nostr relays")
        desiredConnected.set(false)
        
        // Stop subscription validation
        stopSubscriptionValidation()
        reconnectJobs.values.forEach { it.cancel() }
        reconnectJobs.clear()
        
        val sockets = connections.values.toList()
        connections.clear()
        sockets.forEach { webSocket ->
            webSocket.close(1000, "Manual disconnect")
        }
        
        // Preserve logical subscriptions for controlled resets, but forget per-socket state.
        subscriptions.clear()
        relaysList.forEach {
            it.isConnected = false
            it.nextReconnectTime = null
        }
        updateRelaysList()
        updateConnectionStatus()
    }
    
    /**
     * Send an event to specified relays (or all if none specified)
     */
    fun sendEvent(event: NostrEvent, relayUrls: List<String>? = null) {
        val targetRelays = relayUrls ?: relaysList.map { it.url }
        
        // Add to queue for reliability
        synchronized(messageQueueLock) {
            if (messageQueue.size >= MAX_QUEUED_EVENTS) messageQueue.removeAt(0)
            messageQueue.add(QueuedEvent(event, targetRelays.toMutableSet()))
        }
        
        // Attempt immediate send
        scope.launch {
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    if (sendToRelay(event, webSocket, relayUrl)) {
                        markQueuedRelayDelivered(event.id, relayUrl)
                    }
                }
            }
        }
    }
    
    /**
     * Subscribe to events matching a filter
     * The subscription will be automatically re-established on reconnection
     */
    fun subscribe(
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: List<String>? = null,
        owner: String = OWNER_LEGACY
    ): String {
        // Store subscription info for persistent tracking
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet(),
            owner = owner
        )
        
        activeSubscriptions[id] = subscriptionInfo
        messageHandlers[id] = handler

        // Send subscription to appropriate relays
        sendSubscriptionToRelays(subscriptionInfo)
        
        return id
    }
    
    /**
     * Send a subscription to the appropriate relays
     */
    private fun sendSubscriptionToRelays(subscriptionInfo: SubscriptionInfo) {
        val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
        val message = gson.toJson(request, NostrRequest::class.java)

        scope.launch {
            val targetRelays = subscriptionInfo.targetRelayUrls?.toList() ?: connections.keys.toList()
            
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    try {
                        val success = webSocket.send(message)
                        if (success) {
                            // Track subscription for this relay
                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            subscriptions[relayUrl] = currentSubs + subscriptionInfo.id
                        } else {
                            Log.w(TAG, "Failed to send subscription to $relayUrl: WebSocket send failed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send subscription to $relayUrl: ${e.message}")
                    }
                }
            }

            if (connections.isEmpty()) {
                Log.w(TAG, "No relay connections available for subscription, will retry on reconnection")
            }
        }
    }
    
    /**
     * Unsubscribe from a subscription
     */
    fun unsubscribe(id: String) {
        // Remove from persistent tracking
        val subscriptionInfo = activeSubscriptions.remove(id)
        messageHandlers.remove(id)
        
        if (subscriptionInfo == null) {
            Log.w(TAG, "Attempted to unsubscribe from unknown subscription: $id")
            return
        }

        val request = NostrRequest.Close(id)
        val message = gson.toJson(request, NostrRequest::class.java)
        
        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        webSocket.send(message)
                        subscriptions[relayUrl] = currentSubs - id
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unsubscribe from $relayUrl: ${e.message}")
                    }
                }
            }
        }
    }

    fun unsubscribeOwner(owner: String) {
        activeSubscriptions.values
            .filter { it.owner == owner }
            .map { it.id }
            .forEach(::unsubscribe)
    }
    
    /**
     * Manually retry connection to a specific relay
     */
    fun retryConnection(relayUrl: String) {
        val relay = relaysList.find { it.url == relayUrl } ?: return
        desiredConnected.set(true)
        
        // Reset reconnection attempts
        relay.reconnectAttempts = 0
        relay.nextReconnectTime = null
        
        // Disconnect if connected
        reconnectJobs.remove(relayUrl)?.cancel()
        connections.remove(relayUrl)?.close(1000, "Manual retry")
        
        // Attempt immediate reconnection
        scope.launch {
            connectToRelay(relayUrl)
        }
    }
    
    /**
     * Reset all relay connections
     * This will automatically restore all subscriptions when reconnected
     */
    fun resetAllConnections() {
        val shouldReconnect = desiredConnected.get()
        disconnect()
        
        // Reset all relay states
        relaysList.forEach { relay ->
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
            relay.lastError = null
        }
        
        // Reconnect only when connectivity was desired before the controlled reset.
        if (shouldReconnect) connect()
    }
    
    /**
     * Force re-establishment of all subscriptions on currently connected relays
     * Useful for ensuring subscription consistency after network issues
     */
    fun reestablishAllSubscriptions() {
        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                restoreSubscriptionsForRelay(relayUrl, webSocket)
            }
        }
    }
    
    /**
     * Clear all subscription tracking, message handlers, routing caches, and queued messages.
     * Intended for panic/reset flows prior to reconnecting and re-subscribing from scratch.
     */
    fun clearAllSubscriptions() {
        try {
            // Clear persistent subscription tracking
            activeSubscriptions.clear()
            messageHandlers.clear()
            subscriptions.clear()

            // Clear routing caches (per-geohash relay selections)
            geohashToRelays.clear()

            // Clear any queued messages waiting to be sent
            synchronized(messageQueueLock) {
                messageQueue.clear()
            }

            Log.i(TAG, "Cleared all Nostr subscriptions and routing caches")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear subscriptions: ${e.message}")
        }
    }
    
    /**
     * Get detailed status for all relays
     */
    fun getRelayStatuses(): List<Relay> {
        return relaysList.toList()
    }
    
    /**
     * Get event deduplication statistics
     */
    fun getDeduplicationStats(): DeduplicationStats {
        return eventDeduplicator.getStats()
    }
    
    /**
     * Clear the event deduplication cache (useful for testing or debugging)
     */
    fun clearDeduplicationCache() {
        eventDeduplicator.clear()
    }
    
    /**
     * Get the count of active subscriptions
     */
    fun getActiveSubscriptionCount(): Int {
        return activeSubscriptions.size
    }
    
    /**
     * Get information about all active subscriptions (for debugging)
     */
    fun getActiveSubscriptions(): Map<String, SubscriptionInfo> {
        return activeSubscriptions.toMap()
    }
    
    /**
     * Validate subscription consistency across all relays
     * Returns a report of any inconsistencies found
     */
    fun validateSubscriptionConsistency(): SubscriptionConsistencyReport {
        val expectedSubs = activeSubscriptions.keys
        val actualSubsByRelay = subscriptions.toMap()
        val inconsistencies = mutableListOf<String>()
        
        connections.keys.forEach { relayUrl ->
            val actualSubs = actualSubsByRelay[relayUrl] ?: emptySet()
            val expectedForRelay = expectedSubs.filter { subId ->
                val subInfo = activeSubscriptions[subId]
                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
            }.toSet()
            
            val missing = expectedForRelay - actualSubs
            val extra = actualSubs - expectedForRelay
            
            if (missing.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl missing subscriptions: $missing")
            }
            if (extra.isNotEmpty()) {
                inconsistencies.add("Relay $relayUrl has extra subscriptions: $extra")
            }
        }
        
        return SubscriptionConsistencyReport(
            isConsistent = inconsistencies.isEmpty(),
            inconsistencies = inconsistencies,
            totalActiveSubscriptions = activeSubscriptions.size,
            connectedRelayCount = connections.size
        )
    }
    
    data class SubscriptionConsistencyReport(
        val isConsistent: Boolean,
        val inconsistencies: List<String>,
        val totalActiveSubscriptions: Int,
        val connectedRelayCount: Int
    )
    
    /**
     * Start periodic subscription validation to ensure robustness
     */
    private fun startSubscriptionValidation() {
        stopSubscriptionValidation() // Stop any existing validation
        
        subscriptionValidationJob = scope.launch {
            while (isActive && desiredConnected.get()) {
                val validationInterval = powerManager?.profile?.value
                    ?.nostr?.subscriptionValidationMs
                    ?: com.bitchat.android.util.AppConstants.Nostr.SUBSCRIPTION_VALIDATION_INTERVAL_MS
                delay(validationInterval)
                if (!desiredConnected.get()) break
                
                try {
                    val report = validateSubscriptionConsistency()
                    if (!report.isConsistent && report.connectedRelayCount > 0) {
                        Log.w(TAG, "Subscription inconsistencies detected: ${report.inconsistencies}")
                        
                        // Auto-repair: re-establish subscriptions for relays with missing ones
                        connections.forEach { (relayUrl, webSocket) ->
                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            val expectedSubs = activeSubscriptions.keys.filter { subId ->
                                val subInfo = activeSubscriptions[subId]
                                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
                            }.toSet()
                            
                            val missingSubs = expectedSubs - currentSubs
                            if (missingSubs.isNotEmpty()) {
                                Log.i(TAG, "Auto-repairing ${missingSubs.size} missing subscriptions for $relayUrl")
                                restoreSubscriptionsForRelay(relayUrl, webSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during subscription validation: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Stop periodic subscription validation
     */
    private fun stopSubscriptionValidation() {
        subscriptionValidationJob?.cancel()
        subscriptionValidationJob = null
    }
    
    // MARK: - Private Methods
    
    private suspend fun connectToRelay(urlString: String) {
        if (!desiredConnected.get()) return
        // Skip if we already have a connection
        if (connections.containsKey(urlString)) {
            return
        }

        try {
            val request = Request.Builder()
                .url(urlString)
                .build()
            
            val webSocket = httpClient.newWebSocket(request, RelayWebSocketListener(urlString))
            val existing = connections.putIfAbsent(urlString, webSocket)
            if (existing != null) {
                webSocket.close(1000, "Duplicate connection")
            } else if (!desiredConnected.get()) {
                connections.remove(urlString, webSocket)
                webSocket.close(1000, "Connection no longer desired")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection to $urlString: ${e.message}")
            handleConnectionCreationFailure(urlString, e)
        }
    }
    
    private fun sendToRelay(event: NostrEvent, webSocket: WebSocket, relayUrl: String): Boolean {
        return try {
            val request = NostrRequest.Event(event)
            val message = gson.toJson(request, NostrRequest::class.java)

            val success = webSocket.send(message)
            if (success) {
                // Update relay stats
                val relay = relaysList.find { it.url == relayUrl }
                relay?.messagesSent = (relay?.messagesSent ?: 0) + 1
                updateRelaysList()
                true
            } else {
                Log.e(TAG, "Failed to send event to $relayUrl: WebSocket send failed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send event to $relayUrl: ${e.message}")
            false
        }
    }

    private fun markQueuedRelayDelivered(eventId: String, relayUrl: String) {
        synchronized(messageQueueLock) {
            val iterator = messageQueue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.event.id != eventId) continue
                queued.pendingRelayUrls.remove(relayUrl)
                if (queued.pendingRelayUrls.isEmpty()) iterator.remove()
            }
        }
    }
    
    private fun handleMessage(message: String, relayUrl: String) {
        try {
            val jsonElement = JsonParser.parseString(message)
            if (!jsonElement.isJsonArray) {
                Log.w(TAG, "Received non-array message from $relayUrl")
                return
            }
            
            val response = NostrResponse.fromJsonArray(jsonElement.asJsonArray)
            
            when (response) {
                is NostrResponse.Event -> {
                    // Update relay stats
                    val relay = relaysList.find { it.url == relayUrl }
                    relay?.messagesReceived = (relay?.messagesReceived ?: 0) + 1
                    updateRelaysList()
                    
                    // CLIENT-SIDE FILTER ENFORCEMENT: Ensure this event matches the subscription's filter
                    activeSubscriptions[response.subscriptionId]?.let { subInfo ->
                        val matches = try { subInfo.filter.matches(response.event) } catch (e: Exception) { true }
                        if (!matches) {
                            // Do NOT call deduplicator here to allow the correct subscription to process it later
                            return
                        }
                    }

                    // DEDUPLICATION: Check if we've already processed this event
                    eventDeduplicator.processEvent(response.event) { event ->
                        // Call handler for new events only
                        val handler = messageHandlers[response.subscriptionId]
                        if (handler != null) {
                            scope.launch(Dispatchers.Main) {
                                handler(event)
                            }
                        } else {
                            Log.d(TAG, "No handler for subscription ${response.subscriptionId}")
                        }
                    }
                }

                is NostrResponse.EndOfStoredEvents -> {
                    // No action needed
                }

                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    if (!response.accepted) {
                        val level = if (wasGiftWrap) Log.WARN else Log.ERROR
                        Log.println(level, TAG, "Event rejected by relay $relayUrl: ${response.message ?: "no reason"}")
                    }
                }

                is NostrResponse.Notice -> {
                    Log.d(TAG, "Notice from $relayUrl: ${response.message}")
                }

                is NostrResponse.Unknown -> {
                    Log.d(TAG, "Unknown message type from $relayUrl")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from $relayUrl: ${e.message}")
        }
    }
    
    private fun handleDisconnection(relayUrl: String, webSocket: WebSocket, error: Throwable) {
        // A close/failure from an intentionally closed or replaced socket is stale. In particular,
        // it must not remove a newer socket or schedule a reconnect after manual disconnect.
        if (!connections.remove(relayUrl, webSocket)) return
        subscriptions.remove(relayUrl)
        handleCurrentDisconnection(relayUrl, error)
    }

    private fun handleConnectionCreationFailure(relayUrl: String, error: Throwable) {
        if (!desiredConnected.get()) return
        handleCurrentDisconnection(relayUrl, error)
    }

    private fun handleCurrentDisconnection(relayUrl: String, error: Throwable) {
        updateRelayStatus(relayUrl, false, error)
        if (!desiredConnected.get()) return
        
        // Check if this is a DNS error
        val errorMessage = error.message?.lowercase() ?: ""
        if (errorMessage.contains("hostname could not be found") || 
            errorMessage.contains("dns") ||
            errorMessage.contains("unable to resolve host")) {
            
            val relay = relaysList.find { it.url == relayUrl }
            if (relay?.lastError == null) {
                Log.w(TAG, "Nostr relay DNS failure for $relayUrl - not retrying")
            }
            return
        }
        
        // Implement exponential backoff for non-DNS errors
        val relay = relaysList.find { it.url == relayUrl } ?: return
        relay.reconnectAttempts++
        
        // Stop attempting after max attempts
        if (relay.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached for $relayUrl")
            return
        }
        
        // Calculate backoff interval
        val backoffInterval = min(
            INITIAL_BACKOFF_INTERVAL * BACKOFF_MULTIPLIER.pow(relay.reconnectAttempts - 1.0),
            MAX_BACKOFF_INTERVAL.toDouble()
        ).toLong()
        
        relay.nextReconnectTime = System.currentTimeMillis() + backoffInterval
        
        Log.d(TAG, "Scheduling reconnection to $relayUrl in ${backoffInterval / 1000}s (attempt ${relay.reconnectAttempts})")
        
        reconnectJobs.remove(relayUrl)?.cancel()
        val reconnectJob = scope.launch {
            delay(backoffInterval)
            if (desiredConnected.get()) connectToRelay(relayUrl)
        }
        reconnectJobs[relayUrl] = reconnectJob
        reconnectJob.invokeOnCompletion { reconnectJobs.remove(relayUrl, reconnectJob) }
    }
    
    private fun updateRelayStatus(url: String, isConnected: Boolean, error: Throwable? = null) {
        val relay = relaysList.find { it.url == url } ?: return
        
        relay.isConnected = isConnected
        relay.lastError = error
        
        if (isConnected) {
            relay.lastConnectedAt = System.currentTimeMillis()
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
        } else {
            relay.lastDisconnectedAt = System.currentTimeMillis()
        }
        
        updateRelaysList()
        updateConnectionStatus()
    }
    
    private fun updateRelaysList() {
        _relays.value = relaysList.toList()
    }
    
    private fun updateConnectionStatus() {
        val connected = relaysList.any { it.isConnected }
        _isConnected.value = connected
    }
    
    private fun generateSubscriptionId(): String {
        return "sub-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}"
    }
    
    /**
     * Restore all active subscriptions for a specific relay that just reconnected
     */
    private fun restoreSubscriptionsForRelay(relayUrl: String, webSocket: WebSocket) {
        val subscriptionsToRestore = activeSubscriptions.values.filter { subscriptionInfo ->
            // Include subscription if it targets all relays or specifically targets this relay
            subscriptionInfo.targetRelayUrls == null || subscriptionInfo.targetRelayUrls.contains(relayUrl)
        }
        
        if (subscriptionsToRestore.isEmpty()) {
            return
        }

        Log.d(TAG, "Restoring ${subscriptionsToRestore.size} subscriptions for relay: $relayUrl")

        subscriptionsToRestore.forEach { subscriptionInfo ->
            try {
                val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
                val message = gson.toJson(request, NostrRequest::class.java)

                val success = webSocket.send(message)
                if (success) {
                    // Track subscription for this relay
                    val currentSubs = subscriptions[relayUrl] ?: emptySet()
                    subscriptions[relayUrl] = currentSubs + subscriptionInfo.id
                } else {
                    Log.w(TAG, "Failed to restore subscription '${subscriptionInfo.id}' to $relayUrl: WebSocket send failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore subscription '${subscriptionInfo.id}' to $relayUrl: ${e.message}")
            }
        }
    }
    
    /**
     * WebSocket listener for relay connections
     */
    private inner class RelayWebSocketListener(private val relayUrl: String) : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!desiredConnected.get() || connections[relayUrl] !== webSocket) {
                webSocket.close(1000, "Stale connection")
                return
            }
            reconnectJobs.remove(relayUrl)?.cancel()
            Log.i(TAG, "Connected to Nostr relay: $relayUrl")
            updateRelayStatus(relayUrl, true)
            
            // Restore all active subscriptions for this relay
            restoreSubscriptionsForRelay(relayUrl, webSocket)
            
            // Process only events still pending for this relay, outside the queue lock.
            val queuedForRelay = synchronized(messageQueueLock) {
                messageQueue
                    .filter { relayUrl in it.pendingRelayUrls }
                    .map { it.event }
            }
            queuedForRelay.forEach { event ->
                if (sendToRelay(event, webSocket, relayUrl)) {
                    markQueuedRelayDelivered(event.id, relayUrl)
                }
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (connections[relayUrl] !== webSocket) return
            handleMessage(text, relayUrl)
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server-initiated close; onClosed will follow
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "Disconnected from Nostr relay $relayUrl: $code $reason")
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(relayUrl, webSocket, error)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "WebSocket failure for $relayUrl: ${t.message}")
            handleDisconnection(relayUrl, webSocket, t)
        }
    }
}
