package com.bitchat.android.nostr

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
        
        /**
         * Get instance for Android compatibility (context-aware calls)
         */
        fun getInstance(context: android.content.Context): NostrRelayManager {
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
    private val _relays = MutableLiveData<List<Relay>>()
    val relays: LiveData<List<Relay>> = _relays
    
    private val _isConnected = MutableLiveData<Boolean>()
    val isConnected: LiveData<Boolean> = _isConnected
    
    // Internal state
    private val relaysList = mutableListOf<Relay>()
    private val connections = ConcurrentHashMap<String, WebSocket>()
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
        val originGeohash: String? = null // used for logging and grouping
    )
    
    // Event deduplication system
    private val eventDeduplicator = NostrEventDeduplicator.getInstance()
    
    // Message queue for reliability
    private val messageQueue = mutableListOf<Pair<NostrEvent, List<String>>>()
    private val messageQueueLock = Any()
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Subscription validation timer
    private var subscriptionValidationJob: Job? = null
    private val SUBSCRIPTION_VALIDATION_INTERVAL = com.bitchat.android.util.AppConstants.Nostr.SUBSCRIPTION_VALIDATION_INTERVAL_MS // 30 seconds
    
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
            Log.i(TAG, "🌐 Geohash $geohash using ${selected.size} relays: ${selected.joinToString()}")
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
        nRelays: Int = 5
    ): String {
        ensureGeohashRelaysConnected(geohash, nRelays, includeDefaults)
        val relayUrls = getRelaysForGeohash(geohash)
        Log.d(TAG, "📡 Subscribing id=$id for geohash=$geohash on ${relayUrls.size} relays")
        return subscribe(
            filter = filter,
            id = id,
            handler = handler,
            targetRelayUrls = relayUrls
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
        Log.v(TAG, "📤 Sending event kind=${event.kind} to ${relayUrls.size} relays for geohash=$geohash")
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
            _relays.postValue(relaysList.toList())
            updateConnectionStatus()
            Log.d(TAG, "✅ NostrRelayManager initialized with ${relaysList.size} default relays")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NostrRelayManager: ${e.message}", e)
            // Initialize with empty list as fallback
            _relays.postValue(emptyList())
            _isConnected.postValue(false)
        }
    }
    
    /**
     * Connect to all configured relays
     */
    fun connect() {
        Log.d(TAG, "🌐 Connecting to ${relaysList.size} Nostr relays")
        
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
        Log.d(TAG, "Disconnecting from all relays")
        
        // Stop subscription validation
        stopSubscriptionValidation()
        
        connections.values.forEach { webSocket ->
            webSocket.close(1000, "Manual disconnect")
        }
        connections.clear()
        
        // Clear subscriptions
        subscriptions.clear()
        
        updateConnectionStatus()
    }
    
    /**
     * Send an event to specified relays (or all if none specified)
     */
    fun sendEvent(event: NostrEvent, relayUrls: List<String>? = null) {
        val targetRelays = relayUrls ?: relaysList.map { it.url }
        
        // Add to queue for reliability
        synchronized(messageQueueLock) {
            messageQueue.add(Pair(event, targetRelays))
        }
        
        // Attempt immediate send
        scope.launch {
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    sendToRelay(event, webSocket, relayUrl)
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
        targetRelayUrls: List<String>? = null
    ): String {
        // Store subscription info for persistent tracking
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet()
        )
        
        activeSubscriptions[id] = subscriptionInfo
        messageHandlers[id] = handler
        
        Log.d(TAG, "📡 Subscribing to Nostr filter id=$id ${filter.getDebugDescription()}")
        
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
        
        // DEBUG: Log the actual serialized message format
        Log.v(TAG, "🔍 DEBUG: Serialized subscription message: $message")
        
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
                            
                            Log.v(TAG, "✅ Subscription '${subscriptionInfo.id}' sent to relay: $relayUrl")
                        } else {
                            Log.w(TAG, "❌ Failed to send subscription to $relayUrl: WebSocket send failed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Failed to send subscription to $relayUrl: ${e.message}")
                    }
                } else {
                    Log.v(TAG, "⏳ Relay $relayUrl not connected, subscription will be sent on reconnection")
                }
            }
            
            if (connections.isEmpty()) {
                Log.w(TAG, "⚠️ No relay connections available for subscription, will retry on reconnection")
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
            Log.w(TAG, "⚠️ Attempted to unsubscribe from unknown subscription: $id")
            return
        }
        
        Log.d(TAG, "🚫 Unsubscribing from subscription: $id")
        
        val request = NostrRequest.Close(id)
        val message = gson.toJson(request, NostrRequest::class.java)
        
        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        webSocket.send(message)
                        subscriptions[relayUrl] = currentSubs - id
                        Log.v(TAG, "Unsubscribed '$id' from relay: $relayUrl")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unsubscribe from $relayUrl: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Manually retry connection to a specific relay
     */
    fun retryConnection(relayUrl: String) {
        val relay = relaysList.find { it.url == relayUrl } ?: return
        
        // Reset reconnection attempts
        relay.reconnectAttempts = 0
        relay.nextReconnectTime = null
        
        // Disconnect if connected
        connections[relayUrl]?.close(1000, "Manual retry")
        connections.remove(relayUrl)
        
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
        disconnect()
        
        // Reset all relay states
        relaysList.forEach { relay ->
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
            relay.lastError = null
        }
        
        // Reconnect - subscriptions will be automatically restored in onOpen
        connect()
    }
    
    /**
     * Force re-establishment of all subscriptions on currently connected relays
     * Useful for ensuring subscription consistency after network issues
     */
    fun reestablishAllSubscriptions() {
        Log.d(TAG, "🔄 Force re-establishing all ${activeSubscriptions.size} active subscriptions")
        
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

            Log.i(TAG, "🧹 Cleared all Nostr subscriptions and routing caches")
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
        Log.i(TAG, "🧹 Cleared event deduplication cache")
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
            while (isActive) {
                delay(SUBSCRIPTION_VALIDATION_INTERVAL)
                
                try {
                    val report = validateSubscriptionConsistency()
                    if (!report.isConsistent && report.connectedRelayCount > 0) {
                        Log.w(TAG, "⚠️ Subscription inconsistencies detected: ${report.inconsistencies}")
                        
                        // Auto-repair: re-establish subscriptions for relays with missing ones
                        connections.forEach { (relayUrl, webSocket) ->
                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            val expectedSubs = activeSubscriptions.keys.filter { subId ->
                                val subInfo = activeSubscriptions[subId]
                                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
                            }.toSet()
                            
                            val missingSubs = expectedSubs - currentSubs
                            if (missingSubs.isNotEmpty()) {
                                Log.i(TAG, "🔧 Auto-repairing ${missingSubs.size} missing subscriptions for $relayUrl")
                                restoreSubscriptionsForRelay(relayUrl, webSocket)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during subscription validation: ${e.message}")
                }
            }
        }
        
        Log.d(TAG, "🔄 Started periodic subscription validation (${SUBSCRIPTION_VALIDATION_INTERVAL / 1000}s interval)")
    }
    
    /**
     * Stop periodic subscription validation
     */
    private fun stopSubscriptionValidation() {
        subscriptionValidationJob?.cancel()
        subscriptionValidationJob = null
        Log.v(TAG, "⏹️ Stopped subscription validation")
    }
    
    // MARK: - Private Methods
    
    private suspend fun connectToRelay(urlString: String) {
        // Skip if we already have a connection
        if (connections.containsKey(urlString)) {
            return
        }
        
        Log.v(TAG, "Attempting to connect to Nostr relay: $urlString")
        
        try {
            val request = Request.Builder()
                .url(urlString)
                .build()
            
            val webSocket = httpClient.newWebSocket(request, RelayWebSocketListener(urlString))
            connections[urlString] = webSocket
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create WebSocket connection to $urlString: ${e.message}")
            handleDisconnection(urlString, e)
        }
    }
    
    private fun sendToRelay(event: NostrEvent, webSocket: WebSocket, relayUrl: String) {
        try {
            val request = NostrRequest.Event(event)
            val message = gson.toJson(request, NostrRequest::class.java)
            
            Log.v(TAG, "📤 Sending Nostr event (kind: ${event.kind}) to relay: $relayUrl")
            
            val success = webSocket.send(message)
            if (success) {
                // Update relay stats
                val relay = relaysList.find { it.url == relayUrl }
                relay?.messagesSent = (relay?.messagesSent ?: 0) + 1
                updateRelaysList()
            } else {
                Log.e(TAG, "❌ Failed to send event to $relayUrl: WebSocket send failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send event to $relayUrl: ${e.message}")
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
                            Log.v(TAG, "🚫 Dropping event ${response.event.id.take(16)}... not matching filter for sub=${response.subscriptionId}")
                            // Do NOT call deduplicator here to allow the correct subscription to process it later
                            return
                        }
                    }
                    
                    // DEDUPLICATION: Check if we've already processed this event
                    val wasProcessed = eventDeduplicator.processEvent(response.event) { event ->
                        // Only log non-gift-wrap events to reduce noise
                        if (event.kind != NostrKind.GIFT_WRAP) {
                            val originGeo = activeSubscriptions[response.subscriptionId]?.originGeohash
                            if (originGeo != null) {
                                Log.v(TAG, "📥 Processing event (kind=${event.kind}) from relay=$relayUrl geo=$originGeo sub=${response.subscriptionId}")
                            } else {
                                Log.v(TAG, "📥 Processing event (kind=${event.kind}) from relay=$relayUrl sub=${response.subscriptionId}")
                            }
                        }
                        
                        // Call handler for new events only
                        val handler = messageHandlers[response.subscriptionId]
                        if (handler != null) {
                            scope.launch(Dispatchers.Main) {
                                handler(event)
                            }
                        } else {
                            Log.w(TAG, "⚠️ No handler for subscription ${response.subscriptionId}")
                        }
                    }
                    
                    if (!wasProcessed) {
                        //Log.v(TAG, "🔄 Duplicate event ${response.event.id.take(16)}... from relay: $relayUrl")
                    }
                }
                
                is NostrResponse.EndOfStoredEvents -> {
                    Log.v(TAG, "End of stored events for subscription: ${response.subscriptionId}")
                }
                
                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    if (response.accepted) {
                        Log.d(TAG, "✅ Event accepted id=${response.eventId.take(16)}... by relay: $relayUrl")
                    } else {
                        val level = if (wasGiftWrap) Log.WARN else Log.ERROR
                        Log.println(level, TAG, "📮 Event ${response.eventId.take(16)}... rejected by relay: ${response.message ?: "no reason"}")
                    }
                }
                
                is NostrResponse.Notice -> {
                    Log.i(TAG, "📢 Notice from $relayUrl: ${response.message}")
                }
                
                is NostrResponse.Unknown -> {
                    Log.v(TAG, "Unknown message type from $relayUrl: ${response.raw}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from $relayUrl: ${e.message}")
        }
    }
    
    private fun handleDisconnection(relayUrl: String, error: Throwable) {
        connections.remove(relayUrl)
        // NOTE: Don't remove subscriptions here - keep them for restoration on reconnection
        // subscriptions.remove(relayUrl)  // REMOVED - this was causing subscription loss
        
        updateRelayStatus(relayUrl, false, error)
        
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
        
        // Schedule reconnection
        scope.launch {
            delay(backoffInterval)
            connectToRelay(relayUrl)
        }
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
        _relays.postValue(relaysList.toList())
    }
    
    private fun updateConnectionStatus() {
        val connected = relaysList.any { it.isConnected }
        _isConnected.postValue(connected)
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
            Log.v(TAG, "🔄 No subscriptions to restore for relay: $relayUrl")
            return
        }
        
        Log.d(TAG, "🔄 Restoring ${subscriptionsToRestore.size} subscriptions for relay: $relayUrl")
        
        subscriptionsToRestore.forEach { subscriptionInfo ->
            try {
                val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
                val message = gson.toJson(request, NostrRequest::class.java)
                
                val success = webSocket.send(message)
                if (success) {
                    // Track subscription for this relay
                    val currentSubs = subscriptions[relayUrl] ?: emptySet()
                    subscriptions[relayUrl] = currentSubs + subscriptionInfo.id
                    
                    Log.v(TAG, "✅ Restored subscription '${subscriptionInfo.id}' to relay: $relayUrl")
                } else {
                    Log.w(TAG, "❌ Failed to restore subscription '${subscriptionInfo.id}' to $relayUrl: WebSocket send failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to restore subscription '${subscriptionInfo.id}' to $relayUrl: ${e.message}")
            }
        }
    }
    
    /**
     * WebSocket listener for relay connections
     */
    private inner class RelayWebSocketListener(private val relayUrl: String) : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "✅ Connected to Nostr relay: $relayUrl")
            updateRelayStatus(relayUrl, true)
            
            // Restore all active subscriptions for this relay
            restoreSubscriptionsForRelay(relayUrl, webSocket)
            
            // Process any queued messages for this relay
            synchronized(messageQueueLock) {
                val iterator = messageQueue.iterator()
                while (iterator.hasNext()) {
                    val (event, targetRelays) = iterator.next()
                    if (relayUrl in targetRelays) {
                        sendToRelay(event, webSocket, relayUrl)
                    }
                }
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text, relayUrl)
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing for $relayUrl: $code $reason")
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed for $relayUrl: $code $reason")
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(relayUrl, error)
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "❌ WebSocket failure for $relayUrl: ${t.message}")
            handleDisconnection(relayUrl, t)
        }
    }
}
