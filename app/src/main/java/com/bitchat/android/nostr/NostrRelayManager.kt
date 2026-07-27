package com.bitchat.android.nostr

import android.util.Log
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages WebSocket connections to Nostr relays
 * Compatible with iOS implementation with Android-specific optimizations
 */
class NostrRelayManager private constructor() {
    private data class QueuedEvent(
        val event: NostrEvent,
        val pendingRelays: MutableSet<String>,
        val queuedAtMs: Long,
        val liveLocationToken: Long? = null
    )
    
    companion object {
        @JvmStatic
        val shared = NostrRelayManager()
        
        private const val TAG = "NostrRelayManager"
        private const val PUBLISH_ACK_TIMEOUT_MS = 10_000L
        private const val MESSAGE_QUEUE_RETENTION_MS = 24L * 60 * 60 * 1000
        private const val MAX_MESSAGE_QUEUE_SIZE = 500
        
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
    private val _relays = MutableStateFlow<List<Relay>>(emptyList())
    val relays: StateFlow<List<Relay>> = _relays.asStateFlow()
    
    private val _isConnected = MutableStateFlow<Boolean>(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
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
        val liveLocationToken: Long? = null
    )
    
    // Event deduplication system
    private val eventDeduplicator = NostrEventDeduplicator.getInstance()
    
    // Message queue for reliability
    private val messageQueue = mutableListOf<QueuedEvent>()
    private val messageQueueLock = Any()
    private val publishTracker = NostrPublishTracker()
    
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
    private val liveGeohashTokens = ConcurrentHashMap<String, Long>()
    private val liveLocationRelayTokens = ConcurrentHashMap<String, Long>()
    private val nonLiveRelayUrls = ConcurrentHashMap.newKeySet<String>()
    private val liveLocationConnectionJobs = ConcurrentHashMap.newKeySet<Job>()

    // --- Public API for geohash-specific operation ---

    /**
     * Compute and connect to relays for a given geohash (nearest + optional defaults), cache the mapping.
     */
    fun ensureGeohashRelaysConnected(
        geohash: String,
        nRelays: Int = 5,
        includeDefaults: Boolean = false,
        liveLocationToken: Long? = null
    ) {
        if (!isNetworkActionAllowed(liveLocationToken)) return
        try {
            val nearest = RelayDirectory.closestRelaysForGeohash(geohash, nRelays)
            val selected = if (includeDefaults) {
                (nearest + Companion.defaultRelays()).toSet()
            } else nearest.toSet()
            if (selected.isEmpty()) {
                Log.w(TAG, "No relays selected for a geohash")
                return
            }
            runNetworkAction(liveLocationToken) {
                geohashToRelays[geohash] = selected
                if (liveLocationToken == null) {
                    liveGeohashTokens.remove(geohash)
                    nonLiveRelayUrls.addAll(selected)
                } else {
                    liveGeohashTokens[geohash] = liveLocationToken
                    selected.forEach { relayUrl ->
                        if (relayUrl !in nonLiveRelayUrls) {
                            liveLocationRelayTokens[relayUrl] = liveLocationToken
                        }
                    }
                }
                ensureConnectionsFor(selected, liveLocationToken)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to ensure geohash relays")
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
        liveLocationToken: Long? = null
    ): String {
        if (!isNetworkActionAllowed(liveLocationToken)) return id
        ensureGeohashRelaysConnected(
            geohash,
            nRelays,
            includeDefaults,
            liveLocationToken
        )
        if (!isNetworkActionAllowed(liveLocationToken)) return id
        val relayUrls = getRelaysForGeohash(geohash)
        return subscribe(
            filter = filter,
            id = id,
            handler = handler,
            targetRelayUrls = relayUrls,
            liveLocationToken = liveLocationToken
        )
    }

    /**
     * Send an event specifically to a geohash's relays (+ optional defaults).
     */
    fun sendEventToGeohash(
        event: NostrEvent,
        geohash: String,
        includeDefaults: Boolean = false,
        nRelays: Int = 5,
        liveLocationToken: Long? = null
    ) {
        if (!isNetworkActionAllowed(liveLocationToken)) return
        ensureGeohashRelaysConnected(
            geohash,
            nRelays,
            includeDefaults,
            liveLocationToken
        )
        if (!isNetworkActionAllowed(liveLocationToken)) return
        val relayUrls = getRelaysForGeohash(geohash)
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No target relays for geohash event; falling back to defaults")
            sendEvent(event, Companion.defaultRelays(), liveLocationToken)
            return
        }
        sendEvent(event, relayUrls, liveLocationToken)
    }

    // --- Internal helpers ---

    private fun isNetworkActionAllowed(liveLocationToken: Long?): Boolean =
        liveLocationToken == null || LiveLocationPrivacyGate.accepts(liveLocationToken)

    private fun runNetworkAction(
        liveLocationToken: Long?,
        action: () -> Unit
    ): Boolean = if (liveLocationToken == null) {
        action()
        true
    } else {
        LiveLocationPrivacyGate.runIfAllowed(liveLocationToken, action)
    }

    /**
     * Privacy teardown is allowed to bypass an already-revoked token solely to stop
     * server-side delivery. Live subscription IDs are opaque, so CLOSE carries no
     * geohash. If a CLOSE cannot be queued, fail closed by dropping that socket.
     */
    private fun closeSubscriptionsOnConnectedRelays(subscriptionIds: Set<String>) {
        if (subscriptionIds.isEmpty()) return

        val closeTargets = NostrLiveSubscriptionPrivacy.closeTargets(
            liveSubscriptionIds = subscriptionIds,
            subscriptionsByRelay = subscriptions,
        )
        closeTargets.forEach { (relayUrl, relaySubscriptionIds) ->
            val webSocket = connections[relayUrl] ?: return@forEach
            relaySubscriptionIds.forEach { subscriptionId ->
                val request = NostrRequest.Close(subscriptionId)
                val message = gson.toJson(request, NostrRequest::class.java)
                val closeQueued = runCatching { webSocket.send(message) }
                    .getOrDefault(false)
                if (!closeQueued) {
                    connections.remove(relayUrl, webSocket)
                    webSocket.cancel()
                }
            }
        }
    }

    private fun revokeLiveLocationAccess() {
        liveLocationConnectionJobs.forEach(Job::cancel)
        liveLocationConnectionJobs.clear()

        val liveSubscriptionIds = activeSubscriptions.values
            .filter { it.liveLocationToken != null }
            .mapTo(mutableSetOf()) { it.id }
        closeSubscriptionsOnConnectedRelays(liveSubscriptionIds)
        liveSubscriptionIds.forEach { id ->
            activeSubscriptions.remove(id)
            messageHandlers.remove(id)
        }
        subscriptions.replaceAll { _, ids -> ids - liveSubscriptionIds }

        synchronized(messageQueueLock) {
            messageQueue.removeAll { it.liveLocationToken != null }
        }

        liveGeohashTokens.keys.forEach(geohashToRelays::remove)
        liveGeohashTokens.clear()

        val liveOnlyRelayUrls = liveLocationRelayTokens.keys
            .filterNotTo(mutableSetOf()) { it in nonLiveRelayUrls }
        liveOnlyRelayUrls.forEach { relayUrl ->
            connections.remove(relayUrl)?.cancel()
        }
        synchronized(relaysList) {
            relaysList.removeAll { it.url in liveOnlyRelayUrls }
        }
        liveLocationRelayTokens.clear()
        updateRelaysList()
        updateConnectionStatus()
    }

    private fun ensureConnectionsFor(
        relayUrls: Set<String>,
        liveLocationToken: Long? = null
    ) {
        if (!isNetworkActionAllowed(liveLocationToken)) return
        // Ensure relays are tracked for UI/status
        relayUrls.forEach { url ->
            if (relaysList.none { it.url == url }) {
                relaysList.add(Relay(url))
            }
        }
        updateRelaysList()

        val job = scope.launch {
            if (!isNetworkActionAllowed(liveLocationToken)) return@launch
            relayUrls.forEach { relayUrl ->
                launch {
                    if (!connections.containsKey(relayUrl) &&
                        isNetworkActionAllowed(liveLocationToken)
                    ) {
                        connectToRelay(relayUrl, liveLocationToken)
                    }
                }
            }
        }
        if (liveLocationToken != null) {
            liveLocationConnectionJobs.add(job)
            job.invokeOnCompletion { liveLocationConnectionJobs.remove(job) }
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
            nonLiveRelayUrls.addAll(defaultRelayUrls)
            _relays.value = relaysList.map { it.copy() }
            updateConnectionStatus()
            LiveLocationPrivacyGate.addRevocationListener(::revokeLiveLocationAccess)
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
        scope.launch {
            relaysList.forEach { relay ->
                launch {
                    val liveToken = liveLocationRelayTokens[relay.url]
                        ?.takeIf { relay.url !in nonLiveRelayUrls }
                    if (liveToken == null || LiveLocationPrivacyGate.accepts(liveToken)) {
                        connectToRelay(relay.url, liveToken)
                    }
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
    fun sendEvent(
        event: NostrEvent,
        relayUrls: List<String>? = null,
        liveLocationToken: Long? = null
    ) {
        val targetRelays = (relayUrls ?: relaysList.map { it.url }).distinct()
        if (targetRelays.isEmpty()) return

        val queued = runNetworkAction(liveLocationToken) {
            synchronized(messageQueueLock) {
                val now = System.currentTimeMillis()
                messageQueue.removeAll {
                    now - it.queuedAtMs > MESSAGE_QUEUE_RETENTION_MS || it.event.id == event.id
                }
                messageQueue += QueuedEvent(
                    event = event,
                    pendingRelays = targetRelays.toMutableSet(),
                    queuedAtMs = now,
                    liveLocationToken = liveLocationToken
                )
                while (messageQueue.size > MAX_MESSAGE_QUEUE_SIZE) {
                    messageQueue.removeAt(0)
                }
            }
            scope.launch {
                if (!isNetworkActionAllowed(liveLocationToken)) return@launch
                targetRelays.forEach { relayUrl ->
                    val webSocket = connections[relayUrl]
                    if (webSocket != null) {
                        sendToRelay(event, webSocket, relayUrl, liveLocationToken)
                    }
                }
            }
        }
        if (!queued) return
    }

    /**
     * Publish and wait until at least one target relay accepts the event.
     *
     * A timeout is not success: callers that persist delivery dedup state must
     * retain their own retryable payload until [NostrPublishResult.Accepted].
     */
    suspend fun sendEventAndAwaitAcceptance(
        event: NostrEvent,
        relayUrls: List<String>? = null,
        timeoutMs: Long = PUBLISH_ACK_TIMEOUT_MS
    ): NostrPublishResult {
        val targets = (relayUrls ?: relaysList.map { it.url })
            .distinct()
            .mapNotNull { relayUrl ->
                connections[relayUrl]?.let { relayUrl to it }
            }
        if (targets.isEmpty()) return NostrPublishResult.Rejected(emptyMap())
        val result = publishTracker.begin(event.id, targets.mapTo(mutableSetOf()) { it.first })
        targets.forEach { (relayUrl, webSocket) ->
            if (!sendToRelay(event, webSocket, relayUrl)) {
                publishTracker.record(
                    eventId = event.id,
                    relayUrl = relayUrl,
                    accepted = false,
                    message = "WebSocket send failed"
                )
            }
        }
        return try {
            withTimeoutOrNull(timeoutMs) { result.await() } ?: NostrPublishResult.TimedOut
        } finally {
            publishTracker.cancel(event.id, result)
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
        liveLocationToken: Long? = null
    ): String {
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet(),
            liveLocationToken = liveLocationToken
        )
        
        runNetworkAction(liveLocationToken) {
            activeSubscriptions[id] = subscriptionInfo
            messageHandlers[id] = handler
            sendSubscriptionToRelays(subscriptionInfo)
        }
        
        return id
    }
    
    /**
     * Send a subscription to the appropriate relays
     */
    private fun sendSubscriptionToRelays(subscriptionInfo: SubscriptionInfo) {
        if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) return
        val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
        val message = gson.toJson(request, NostrRequest::class.java)

        scope.launch {
            if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) return@launch
            val targetRelays = subscriptionInfo.targetRelayUrls?.toList() ?: connections.keys.toList()
            
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    try {
                        var success = false
                        runNetworkAction(subscriptionInfo.liveLocationToken) {
                            success = webSocket.send(message)
                            if (success) {
                                val currentSubs = subscriptions[relayUrl] ?: emptySet()
                                subscriptions[relayUrl] =
                                    currentSubs + subscriptionInfo.id
                            }
                        }
                        if (!success) {
                            Log.w(TAG, "Failed to send subscription: WebSocket send failed")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to send subscription")
                    }
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
            return
        }

        if (subscriptionInfo.liveLocationToken != null &&
            !isNetworkActionAllowed(subscriptionInfo.liveLocationToken)
        ) {
            closeSubscriptionsOnConnectedRelays(setOf(id))
            subscriptions.replaceAll { _, ids -> ids - id }
            return
        }

        val request = NostrRequest.Close(id)
        val message = gson.toJson(request, NostrRequest::class.java)
        
        scope.launch {
            if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) {
                closeSubscriptionsOnConnectedRelays(setOf(id))
                subscriptions.replaceAll { _, ids -> ids - id }
                return@launch
            }
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        runNetworkAction(subscriptionInfo.liveLocationToken) {
                            webSocket.send(message)
                        }
                        subscriptions[relayUrl] = currentSubs - id
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unsubscribe from relay")
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
        val liveToken = liveLocationRelayTokens[relayUrl]
            ?.takeIf { relayUrl !in nonLiveRelayUrls }
        if (!isNetworkActionAllowed(liveToken)) return
        
        // Reset reconnection attempts
        relay.reconnectAttempts = 0
        relay.nextReconnectTime = null
        
        // Disconnect if connected
        connections[relayUrl]?.close(1000, "Manual retry")
        connections.remove(relayUrl)
        
        // Attempt immediate reconnection
        scope.launch {
            connectToRelay(relayUrl, liveToken)
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
            while (isActive) {
                delay(SUBSCRIPTION_VALIDATION_INTERVAL)
                
                try {
                    val report = validateSubscriptionConsistency()
                    if (!report.isConsistent && report.connectedRelayCount > 0) {
                        Log.w(TAG, "Nostr subscription inconsistencies detected")
                        
                        // Auto-repair: re-establish subscriptions for relays with missing ones
                        connections.forEach { (relayUrl, webSocket) ->
                            val currentSubs = subscriptions[relayUrl] ?: emptySet()
                            val expectedSubs = activeSubscriptions.keys.filter { subId ->
                                val subInfo = activeSubscriptions[subId]
                                subInfo?.targetRelayUrls == null || subInfo.targetRelayUrls.contains(relayUrl)
                            }.toSet()
                            
                            val missingSubs = expectedSubs - currentSubs
                            if (missingSubs.isNotEmpty()) {
                                Log.i(TAG, "Auto-repairing ${missingSubs.size} missing subscriptions")
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
    
    private suspend fun connectToRelay(
        urlString: String,
        liveLocationToken: Long? = null
    ) {
        val connectionToken = liveLocationToken
            ?.takeIf { urlString !in nonLiveRelayUrls }
        if (!isNetworkActionAllowed(connectionToken)) return
        // Skip if we already have a connection
        if (connections.containsKey(urlString)) {
            return
        }

        try {
            val request = Request.Builder()
                .url(urlString)
                .build()
            
            runNetworkAction(connectionToken) {
                val webSocket = httpClient.newWebSocket(
                    request,
                    RelayWebSocketListener(urlString, connectionToken)
                )
                connections[urlString] = webSocket
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection")
            handleDisconnection(urlString, e, liveLocationToken)
        }
    }
    
    private fun sendToRelay(
        event: NostrEvent,
        webSocket: WebSocket,
        relayUrl: String,
        liveLocationToken: Long? = null
    ): Boolean {
        if (!isNetworkActionAllowed(liveLocationToken)) return false
        return try {
            val request = NostrRequest.Event(event)
            val message = gson.toJson(request, NostrRequest::class.java)

            var success = false
            runNetworkAction(liveLocationToken) {
                success = webSocket.send(message)
            }
            if (success) {
                // Update relay stats
                val relay = relaysList.find { it.url == relayUrl }
                relay?.messagesSent = (relay?.messagesSent ?: 0) + 1
                updateRelaysList()
            } else {
                Log.e(TAG, "Failed to send event: WebSocket send failed")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send event")
            false
        }
    }
    
    private fun handleMessage(message: String, relayUrl: String) {
        try {
            val jsonElement = JsonParser.parseString(message)
            if (!jsonElement.isJsonArray) {
                Log.w(TAG, "Received non-array message from relay")
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
                    val subscriptionInfo = activeSubscriptions[response.subscriptionId]
                        ?: return
                    if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) return
                    subscriptionInfo.let { subInfo ->
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
                                if (isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) {
                                    handler(event)
                                }
                            }
                        } else {
                            Log.w(TAG, "⚠️ No handler for Nostr subscription")
                        }
                    }

                }

                is NostrResponse.EndOfStoredEvents -> {
                    // No action needed
                }

                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    publishTracker.record(
                        eventId = response.eventId,
                        relayUrl = relayUrl,
                        accepted = response.accepted,
                        message = response.message
                    )
                    acknowledgeQueuedEvent(response.eventId, relayUrl, response.accepted)
                    if (!response.accepted) {
                        val level = if (wasGiftWrap) Log.WARN else Log.ERROR
                        Log.println(level, TAG, "Event rejected by relay: ${response.message ?: "no reason"}")
                    }
                }

                is NostrResponse.Notice -> {
                    // No action needed
                }

                is NostrResponse.Unknown -> {
                    // No action needed
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse relay message")
        }
    }
    
    private fun handleDisconnection(
        relayUrl: String,
        error: Throwable,
        liveLocationToken: Long? = null
    ) {
        val connectionToken = liveLocationToken
            ?.takeIf { relayUrl !in nonLiveRelayUrls }
        connections.remove(relayUrl)
        // NOTE: Don't remove subscriptions here - keep them for restoration on reconnection
        // subscriptions.remove(relayUrl)  // REMOVED - this was causing subscription loss
        
        updateRelayStatus(relayUrl, false, error)
        if (!isNetworkActionAllowed(connectionToken)) return
        
        // Check if this is a DNS error
        val errorMessage = error.message?.lowercase() ?: ""
        if (errorMessage.contains("hostname could not be found") || 
            errorMessage.contains("dns") ||
            errorMessage.contains("unable to resolve host")) {
            
            val relay = relaysList.find { it.url == relayUrl }
            if (relay?.lastError == null) {
                Log.w(TAG, "Nostr relay DNS failure; not retrying")
            }
            return
        }
        
        // Implement exponential backoff for non-DNS errors
        val relay = relaysList.find { it.url == relayUrl } ?: return
        relay.reconnectAttempts++
        
        // Stop attempting after max attempts
        if (relay.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max Nostr relay reconnection attempts reached")
            return
        }
        
        // Calculate backoff interval
        val backoffInterval = min(
            INITIAL_BACKOFF_INTERVAL * BACKOFF_MULTIPLIER.pow(relay.reconnectAttempts - 1.0),
            MAX_BACKOFF_INTERVAL.toDouble()
        ).toLong()
        
        relay.nextReconnectTime = System.currentTimeMillis() + backoffInterval
        
        Log.d(TAG, "Scheduling Nostr relay reconnection")
        
        // Schedule reconnection
        scope.launch {
            delay(backoffInterval)
            if (isNetworkActionAllowed(connectionToken)) {
                connectToRelay(relayUrl, connectionToken)
            }
        }
    }

    private fun acknowledgeQueuedEvent(eventId: String, relayUrl: String, accepted: Boolean) {
        synchronized(messageQueueLock) {
            val iterator = messageQueue.iterator()
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.event.id != eventId) continue
                if (accepted) {
                    iterator.remove()
                } else {
                    queued.pendingRelays.remove(relayUrl)
                    if (queued.pendingRelays.isEmpty()) iterator.remove()
                }
            }
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
        _relays.value = relaysList.map { it.copy() }
    }
    
    private fun updateConnectionStatus() {
        val connected = relaysList.any { it.isConnected }
        _isConnected.value = connected
    }
    
    private fun generateSubscriptionId(): String {
        return "sub-${UUID.randomUUID()}"
    }
    
    /**
     * Restore all active subscriptions for a specific relay that just reconnected
     */
    private fun restoreSubscriptionsForRelay(relayUrl: String, webSocket: WebSocket) {
        val subscriptionsToRestore = activeSubscriptions.values.filter { subscriptionInfo ->
            // Include subscription if it targets all relays or specifically targets this relay
            isNetworkActionAllowed(subscriptionInfo.liveLocationToken) &&
                (subscriptionInfo.targetRelayUrls == null ||
                    subscriptionInfo.targetRelayUrls.contains(relayUrl))
        }
        
        if (subscriptionsToRestore.isEmpty()) {
            return
        }

        subscriptionsToRestore.forEach { subscriptionInfo ->
            try {
                val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
                val message = gson.toJson(request, NostrRequest::class.java)

                var success = false
                runNetworkAction(subscriptionInfo.liveLocationToken) {
                    success = webSocket.send(message)
                    if (success) {
                        val currentSubs = subscriptions[relayUrl] ?: emptySet()
                        subscriptions[relayUrl] =
                            currentSubs + subscriptionInfo.id
                    }
                }
                if (!success) {
                    Log.w(TAG, "Failed to restore subscription: WebSocket send failed")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore subscription")
            }
        }
    }
    
    /**
     * WebSocket listener for relay connections
     */
    private inner class RelayWebSocketListener(
        private val relayUrl: String,
        private val liveLocationToken: Long?
    ) : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!isNetworkActionAllowed(liveLocationToken)) {
                connections.remove(relayUrl)
                webSocket.cancel()
                return
            }
            updateRelayStatus(relayUrl, true)
            
            // Restore all active subscriptions for this relay
            restoreSubscriptionsForRelay(relayUrl, webSocket)
            
            // Process any queued messages for this relay
            synchronized(messageQueueLock) {
                val iterator = messageQueue.iterator()
                while (iterator.hasNext()) {
                    val queued = iterator.next()
                    if (relayUrl in queued.pendingRelays &&
                        isNetworkActionAllowed(queued.liveLocationToken)
                    ) {
                        sendToRelay(
                            queued.event,
                            webSocket,
                            relayUrl,
                            queued.liveLocationToken
                        )
                    }
                }
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text, relayUrl)
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server-initiated close; onClosed will follow
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(relayUrl, error, liveLocationToken)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Nostr WebSocket failure")
            handleDisconnection(relayUrl, t, liveLocationToken)
        }
    }

}
