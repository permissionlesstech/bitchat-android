package com.bitchat.android.nostr

import android.util.Log
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages WebSocket connections to Nostr relays
 * Compatible with iOS implementation with Android-specific optimizations
 */
class NostrRelayManager internal constructor(
    private val initialRelayUrls: List<String> = defaultRelays(),
    private val identityProvider: (() -> NostrIdentity?)? = null,
    private val clientProvider: () -> WebSocket.Factory = {
        com.bitchat.android.net.OkHttpProvider.webSocketClient()
    },
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {
    
    companion object {
        @JvmStatic
        val shared by lazy { NostrRelayManager() }
        
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
        
        // Reconnect backoff lives in RelayReconnectPolicy.

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
    private val authentication = ConcurrentHashMap<WebSocket, NostrRelayAuthenticationSession>()
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
        val originGeohash: String? = null,
        val owner: String = OWNER_LEGACY,
        val liveLocationToken: Long? = null,
        val authenticationIdentity: (() -> NostrIdentity?)? = null
    )
    
    // Event deduplication system
    private val eventDeduplicator = NostrEventDeduplicator.getInstance()
    
    // Bounded per-relay delivery queue for reconnect reliability.
    private val messageQueue = NostrPendingEventQueue(MAX_QUEUED_EVENTS)
    
    // Subscription validation timer
    private var subscriptionValidationJob: Job? = null
    private val powerManager: com.bitchat.android.mesh.PowerManager?
        get() = appContext?.let { com.bitchat.android.mesh.PowerManager.getInstance(it) }
    @Volatile private var appContext: android.content.Context? = null
    
    // OkHttp client for WebSocket connections (via provider to honor Tor)
    private val httpClient: WebSocket.Factory
        get() = clientProvider()
    
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
        owner: String = OWNER_LEGACY,
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
            owner = owner,
            liveLocationToken = liveLocationToken,
            authenticationIdentity = { appContext?.let { NostrIdentityBridge.deriveIdentity(geohash, it) } },
            originGeohash = geohash
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
            sendEvent(
                event, Companion.defaultRelays(), liveLocationToken,
                authenticationIdentity = { appContext?.let { NostrIdentityBridge.deriveIdentity(geohash, it) } }
            )
            return
        }
        sendEvent(
            event, relayUrls, liveLocationToken,
            authenticationIdentity = { appContext?.let { NostrIdentityBridge.deriveIdentity(geohash, it) } }
        )
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
            for (subscriptionId in relaySubscriptionIds) {
                val request = NostrRequest.Close(subscriptionId)
                val message = gson.toJson(request, NostrRequest::class.java)
                val closeQueued = runCatching { webSocket.send(message) }
                    .getOrDefault(false)
                if (!closeQueued) {
                    connections.remove(relayUrl, webSocket)
                    authentication.remove(webSocket)
                    subscriptions.remove(relayUrl)
                    webSocket.cancel()
                    updateRelayStatus(
                        relayUrl,
                        isConnected = false,
                        error = IllegalStateException("Failed to close revoked subscription")
                    )
                    if (desiredConnected.get() && relayUrl in nonLiveRelayUrls) {
                        scope.launch { connectToRelay(relayUrl, liveLocationToken = null) }
                    }
                    break
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
            authentication.values.forEach { it.forgetSubscription(id) }
        }
        subscriptions.replaceAll { _, ids -> ids - liveSubscriptionIds }

        val revokedQueueIds = connections.keys.flatMap { messageQueue.pendingForRelay(it) }
            .filter { it.liveLocationToken != null }.map { it.queueId }.toSet()
        authentication.values.forEach { session -> revokedQueueIds.forEach(session::forgetEvent) }
        messageQueue.removeLiveLocationEvents()

        liveGeohashTokens.keys.forEach(geohashToRelays::remove)
        liveGeohashTokens.clear()

        val liveOnlyRelayUrls = liveLocationRelayTokens.keys
            .filterNotTo(mutableSetOf()) { it in nonLiveRelayUrls }
        liveOnlyRelayUrls.forEach { relayUrl ->
            connections.remove(relayUrl)?.let { socket ->
                authentication.remove(socket)
                socket.cancel()
            }
            subscriptions.remove(relayUrl)
            reconnectJobs.remove(relayUrl)?.cancel()
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

        if (!desiredConnected.get()) return
        val job = scope.launch {
            if (!desiredConnected.get() ||
                !isNetworkActionAllowed(liveLocationToken)
            ) return@launch
            relayUrls.forEach { relayUrl ->
                launch {
                    if (desiredConnected.get() &&
                        !connections.containsKey(relayUrl) &&
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
            val defaultRelayUrls = initialRelayUrls
            relaysList.addAll(defaultRelayUrls.map { Relay(it) })
            nonLiveRelayUrls.addAll(defaultRelayUrls)
            _relays.value = relaysList.toList()
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
        desiredConnected.set(true)
        Log.i(TAG, "Connecting to ${relaysList.size} Nostr relays")
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
        Log.i(TAG, "Disconnecting from all Nostr relays")
        desiredConnected.set(false)

        // Stop subscription validation
        stopSubscriptionValidation()
        reconnectJobs.values.forEach(Job::cancel)
        reconnectJobs.clear()
        liveLocationConnectionJobs.forEach(Job::cancel)
        liveLocationConnectionJobs.clear()

        val sockets = connections.values.toList()
        connections.clear()
        authentication.clear()
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
    fun sendEvent(
        event: NostrEvent,
        relayUrls: List<String>? = null,
        liveLocationToken: Long? = null,
        authenticationIdentity: (() -> NostrIdentity?)? = null
    ) {
        val targetRelays = (relayUrls ?: relaysList.map { it.url })
            .filter { it.isNotBlank() }
            .distinct()
        if (targetRelays.isEmpty()) return

        val queued = runNetworkAction(liveLocationToken) {
            messageQueue.enqueue(
                event = event,
                relayUrls = targetRelays,
                liveLocationToken = liveLocationToken,
                authenticationIdentity = authenticationIdentity
            ) ?: return@runNetworkAction
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
     * Subscribe to events matching a filter
     * The subscription will be automatically re-established on reconnection
     */
    fun subscribe(
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit,
        targetRelayUrls: List<String>? = null,
        owner: String = OWNER_LEGACY,
        liveLocationToken: Long? = null,
        authenticationIdentity: (() -> NostrIdentity?)? = null,
        originGeohash: String? = null
    ): String {
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet(),
            originGeohash = originGeohash,
            owner = owner,
            liveLocationToken = liveLocationToken,
            authenticationIdentity = authenticationIdentity
        )
        
        runNetworkAction(liveLocationToken) {
            authentication.values.forEach { it.forgetSubscription(id) }
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
        authentication.values.forEach { it.forgetSubscription(id) }
        
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
        val liveToken = liveLocationRelayTokens[relayUrl]
            ?.takeIf { relayUrl !in nonLiveRelayUrls }
        if (!isNetworkActionAllowed(liveToken)) return
        
        // Reset reconnection attempts
        relay.reconnectAttempts = 0
        relay.nextReconnectTime = null
        
        // Disconnect if connected
        reconnectJobs.remove(relayUrl)?.cancel()
        connections.remove(relayUrl)?.let { socket ->
            authentication.remove(socket)
            socket.close(1000, "Manual retry")
        }
        
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
            messageQueue.clear()
            authentication.values.forEach { it.clearRequests() }

            Log.i(TAG, "Cleared all Nostr subscriptions and routing caches")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear subscriptions: ${e.message}")
        }
    }

    /**
     * Clear all subscription tracking, deduplication cache, message queue, and connections for panic mode.
     */
    fun clearAllOnPanic() {
        try {
            val wasConnected = desiredConnected.get()
            clearAllSubscriptions()
            clearDeduplicationCache()
            disconnect()
            if (wasConnected) {
                desiredConnected.set(true)
            }
            Log.w(TAG, "🚨 Cleared NostrRelayManager subscriptions, cache, and connections for panic mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear NostrRelayManager on panic: ${e.message}")
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
            val manager = powerManager
            if (manager == null) {
                runSubscriptionValidationLoop(
                    com.bitchat.android.util.AppConstants.Nostr
                        .SUBSCRIPTION_VALIDATION_INTERVAL_MS
                )
                return@launch
            }

            manager.profile
                .map { it.nostr.subscriptionValidationMs }
                .distinctUntilChanged()
                .collectLatest(::runSubscriptionValidationLoop)
        }
    }

    private suspend fun runSubscriptionValidationLoop(intervalMs: Long) {
        while (currentCoroutineContext().isActive && desiredConnected.get()) {
            delay(intervalMs)
            if (!desiredConnected.get()) break
            validateAndRepairSubscriptions()
        }
    }

    private fun validateAndRepairSubscriptions() {
        try {
            val report = validateSubscriptionConsistency()
            if (report.isConsistent || report.connectedRelayCount == 0) return

            Log.w(TAG, "Nostr subscription inconsistencies detected")
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl] ?: emptySet()
                val expectedSubs = activeSubscriptions.keys.filter { subId ->
                    val subInfo = activeSubscriptions[subId]
                    subInfo?.targetRelayUrls == null ||
                        subInfo.targetRelayUrls.contains(relayUrl)
                }.toSet()

                if ((expectedSubs - currentSubs).isNotEmpty()) {
                    Log.i(TAG, "Auto-repairing missing subscriptions")
                    restoreSubscriptionsForRelay(relayUrl, webSocket)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during subscription validation: ${e.message}")
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
        if (!desiredConnected.get()) return
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
            
            val started = runNetworkAction(connectionToken) {
                val webSocket = httpClient.newWebSocket(
                    request,
                    RelayWebSocketListener(urlString, connectionToken)
                )
                authentication[webSocket] = NostrRelayAuthenticationSession()
                val existing = connections.putIfAbsent(urlString, webSocket)
                when {
                    existing != null -> {
                        authentication.remove(webSocket)
                        webSocket.close(1000, "Duplicate connection")
                    }
                    !desiredConnected.get() -> {
                        connections.remove(urlString, webSocket)
                        authentication.remove(webSocket)
                        webSocket.close(1000, "Connection no longer desired")
                    }
                }
            }
            if (!started) return
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection")
            handleConnectionCreationFailure(urlString, e, connectionToken)
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
                relaysList.find { it.url == relayUrl }?.let { relay ->
                    relay.messagesSent += 1
                }
                updateRelaysList()
                true
            } else {
                Log.e(TAG, "Failed to send event: WebSocket send failed")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send event")
            false
        }
    }

    private fun handleMessage(message: String, relayUrl: String, webSocket: WebSocket) {
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
                    relaysList.find { it.url == relayUrl }?.let { relay ->
                        relay.messagesReceived += 1
                    }
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
                    val session = authentication[webSocket] ?: return
                    val authResult = session.onOk(response.eventId, response.accepted)
                    if (authResult != null) {
                        if (authResult.actions.rejected) {
                            Log.w(TAG, "Relay authentication rejected")
                        }
                        applyAuthenticationActions(authResult, relayUrl, webSocket, session)
                        return
                    }
                    val pending = messageQueue.pendingForRelay(relayUrl)
                        .filter { it.event.id == response.eventId && isNetworkActionAllowed(it.liveLocationToken) }
                    if (pending.isEmpty()) return
                    if (!response.accepted && NostrRelayAuthentication.isAuthRequired(response.message)) {
                        pending.forEach { delivery ->
                            runNetworkAction(delivery.liveLocationToken) {
                                val identity = resolveAuthenticationIdentity(delivery.authenticationIdentity)
                                    ?: return@runNetworkAction
                                applyAuthenticationActions(
                                    session.onEventRejected(delivery.queueId, identity), relayUrl, webSocket, session
                                )
                            }
                        }
                        return
                    }
                    messageQueue.acknowledge(response.eventId, relayUrl)
                    pending.forEach { session.forgetEvent(it.queueId) }
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    if (!response.accepted) {
                        val level = if (wasGiftWrap) Log.WARN else Log.ERROR
                        Log.println(level, TAG, "Event rejected by relay")
                    }
                }

                is NostrResponse.Auth -> {
                    val session = authentication[webSocket] ?: return
                    session.onChallenge(response.challenge).forEach { actions ->
                        applyAuthenticationActions(actions, relayUrl, webSocket, session)
                    }
                }

                is NostrResponse.Closed -> {
                    val info = activeSubscriptions[response.subscriptionId] ?: return
                    if (!isNetworkActionAllowed(info.liveLocationToken) ||
                        subscriptions[relayUrl]?.contains(info.id) != true ||
                        (info.targetRelayUrls != null && relayUrl !in info.targetRelayUrls)
                    ) return
                    if (NostrRelayAuthentication.isAuthRequired(response.message)) {
                        val session = authentication[webSocket] ?: return
                        runNetworkAction(info.liveLocationToken) {
                            val identity = resolveAuthenticationIdentity(info.authenticationIdentity)
                                ?: return@runNetworkAction
                            applyAuthenticationActions(
                                session.onSubscriptionRejected(info.id, identity), relayUrl, webSocket, session
                            )
                        }
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

    private fun resolveAuthenticationIdentity(provider: (() -> NostrIdentity?)?): NostrIdentity? = try {
        // An explicit channel signer returning null must fail closed, without account fallback.
        if (provider != null) provider.invoke()
        else if (identityProvider != null) identityProvider.invoke()
        else appContext?.let(NostrIdentityBridge::getCurrentNostrIdentity)
    } catch (e: Exception) {
        Log.w(TAG, "Unable to load relay authentication identity")
        null
    }

    private fun applyAuthenticationActions(
        scopedActions: NostrRelayAuthenticationSession.Actions?,
        relayUrl: String,
        webSocket: WebSocket,
        session: NostrRelayAuthenticationSession
    ) {
        if (scopedActions == null) return
        val actions = scopedActions.actions
        val identity = scopedActions.identity
        if (connections[relayUrl] !== webSocket || authentication[webSocket] !== session ||
            !desiredConnected.get()
        ) return
        val connectionToken = liveLocationRelayTokens[relayUrl]
            ?.takeIf { relayUrl !in nonLiveRelayUrls }
        if (!isNetworkActionAllowed(connectionToken)) return

        actions.challenge?.let { challenge ->
            fun sendProof() {
                if (connections[relayUrl] !== webSocket || authentication[webSocket] !== session) return
                try {
                    val event = NostrEvent.createRelayAuthentication(relayUrl, challenge, identity)
                    if (!session.recordProof(identity, challenge, event.id)) return
                    runNetworkAction(connectionToken) {
                        if (connections[relayUrl] !== webSocket || authentication[webSocket] !== session) {
                            return@runNetworkAction
                        }
                        val request = NostrRequest.Auth(event)
                        if (!webSocket.send(gson.toJson(request, NostrRequest::class.java))) {
                            Log.w(TAG, "Failed to send relay authentication")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to sign relay authentication")
                }
            }
            // Bind the proof itself to an originating operation's consent, even on shared sockets.
            actions.proofSubscriptionIds.forEach { id ->
                val info = activeSubscriptions[id] ?: return@forEach
                if (info.targetRelayUrls != null && relayUrl !in info.targetRelayUrls) return@forEach
                var attempted = false
                runNetworkAction(info.liveLocationToken) {
                    if (activeSubscriptions[id] === info &&
                        resolveAuthenticationIdentity(info.authenticationIdentity)?.publicKeyHex == identity.publicKeyHex
                    ) {
                        attempted = true
                        sendProof()
                    }
                }
                if (attempted) return
            }
            messageQueue.pendingForRelay(relayUrl)
                .filter { it.queueId.toString() in actions.proofEventIds }
                .forEach { delivery ->
                    var attempted = false
                    runNetworkAction(delivery.liveLocationToken) {
                        if (messageQueue.pendingForRelay(relayUrl).any { it.queueId == delivery.queueId } &&
                            resolveAuthenticationIdentity(delivery.authenticationIdentity)?.publicKeyHex == identity.publicKeyHex
                        ) {
                            attempted = true
                            sendProof()
                        }
                    }
                    if (attempted) return
                }
            return
        }

        actions.subscriptionIds.forEach { id ->
            val info = activeSubscriptions[id] ?: return@forEach
            if (info.targetRelayUrls != null && relayUrl !in info.targetRelayUrls) return@forEach
            runNetworkAction(info.liveLocationToken) {
                if (connections[relayUrl] === webSocket && activeSubscriptions[id] === info) {
                    if (resolveAuthenticationIdentity(info.authenticationIdentity)?.publicKeyHex != identity.publicKeyHex) {
                        return@runNetworkAction
                    }
                    val request = NostrRequest.Subscribe(id, listOf(info.filter))
                    webSocket.send(gson.toJson(request, NostrRequest::class.java))
                }
            }
        }
        messageQueue.pendingForRelay(relayUrl)
            .filter { it.queueId.toString() in actions.eventIds }
            .forEach { delivery ->
                if (connections[relayUrl] === webSocket) {
                    sendToRelay(delivery.event, webSocket, relayUrl, delivery.liveLocationToken)
                }
            }
    }
    
    private fun handleDisconnection(
        relayUrl: String,
        webSocket: WebSocket,
        error: Throwable,
        liveLocationToken: Long? = null
    ) {
        // Ignore callbacks from intentionally closed or replaced sockets. They must not remove a
        // newer socket or schedule a reconnect after a controlled disconnect/privacy revocation.
        if (!connections.remove(relayUrl, webSocket)) return
        authentication.remove(webSocket)
        subscriptions.remove(relayUrl)
        handleCurrentDisconnection(relayUrl, error, liveLocationToken)
    }

    private fun handleConnectionCreationFailure(
        relayUrl: String,
        error: Throwable,
        liveLocationToken: Long?
    ) {
        if (!desiredConnected.get()) return
        handleCurrentDisconnection(relayUrl, error, liveLocationToken)
    }

    private fun handleCurrentDisconnection(
        relayUrl: String,
        error: Throwable,
        liveLocationToken: Long?
    ) {
        val connectionToken = liveLocationToken
            ?.takeIf { relayUrl !in nonLiveRelayUrls }

        updateRelayStatus(relayUrl, false, error)
        if (!desiredConnected.get() ||
            !isNetworkActionAllowed(connectionToken)
        ) return

        // Every failure backs off and retries, including a name-resolution
        // failure: "unable to resolve host" is what this device reports when it
        // simply has no network, so treating it as permanent turns a walk
        // through a tunnel into a dead relay layer for the rest of the process.
        val relay = relaysList.find { it.url == relayUrl } ?: return
        relay.reconnectAttempts = RelayReconnectPolicy.nextAttempt(relay.reconnectAttempts)
        val backoffInterval = RelayReconnectPolicy.backoffMs(relay.reconnectAttempts)

        relay.nextReconnectTime = System.currentTimeMillis() + backoffInterval
        
        Log.d(TAG, "Scheduling Nostr relay reconnection")
        
        reconnectJobs.remove(relayUrl)?.cancel()
        val reconnectJob = scope.launch {
            delay(backoffInterval)
            if (desiredConnected.get() &&
                isNetworkActionAllowed(connectionToken)
            ) {
                connectToRelay(relayUrl, connectionToken)
            }
        }
        reconnectJobs[relayUrl] = reconnectJob
        reconnectJob.invokeOnCompletion {
            reconnectJobs.remove(relayUrl, reconnectJob)
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
        _relays.value = relaysList.toList()
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
            if (!desiredConnected.get() ||
                connections[relayUrl] !== webSocket ||
                !isNetworkActionAllowed(liveLocationToken)
            ) {
                connections.remove(relayUrl, webSocket)
                authentication.remove(webSocket)
                webSocket.close(1000, "Stale connection")
                return
            }
            reconnectJobs.remove(relayUrl)?.cancel()
            updateRelayStatus(relayUrl, true)
            
            // Restore all active subscriptions for this relay
            restoreSubscriptionsForRelay(relayUrl, webSocket)
            
            // Process only events still pending for this relay, outside the queue lock.
            val queuedForRelay = messageQueue.pendingForRelay(relayUrl)
                .filter { isNetworkActionAllowed(it.liveLocationToken) }
            queuedForRelay.forEach { delivery ->
                sendToRelay(
                    delivery.event,
                    webSocket,
                    relayUrl,
                    delivery.liveLocationToken
                )
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (connections[relayUrl] !== webSocket) return
            if (!isNetworkActionAllowed(liveLocationToken)) return
            handleMessage(text, relayUrl, webSocket)
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server-initiated close; onClosed will follow
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(relayUrl, webSocket, error, liveLocationToken)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Nostr WebSocket failure")
            handleDisconnection(relayUrl, webSocket, t, liveLocationToken)
        }
    }
}
