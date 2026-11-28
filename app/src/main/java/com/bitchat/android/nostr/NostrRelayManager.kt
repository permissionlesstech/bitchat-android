package com.bitchat.android.nostr

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.net.ArtiTorManager
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
    private val webSocketFactory: () -> WebSocket.Factory,
    private val scope: CoroutineScope,
    private val monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    private val awaitTorRoute: suspend (Long) -> Boolean = { timeoutMs ->
        val tor = ArtiTorManager.getInstance()
        if (tor.currentSocksAddress() != null && !tor.isProxyEnabled()) {
            tor.awaitSelectedRoute(timeoutMs)
        } else {
            true
        }
    },
    private val backgroundStateFlow: StateFlow<Boolean>? = null
) {

    private constructor() : this(
        webSocketFactory = { com.bitchat.android.net.OkHttpProvider.webSocketClient() },
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    )

    companion object {
        @JvmStatic
        val shared = NostrRelayManager()
        
        private const val TAG = "NostrRelayManager"
        private const val MAX_QUEUED_EVENTS = 500
        // Only reset exponential reconnect backoff if a relay stayed connected at least this long.
        // Prevents flapping relays that accept WebSocket upgrade and immediately close from
        // spinning at INITIAL_BACKOFF_INTERVAL_MS (1s) forever through Tor.
        private const val STABLE_CONNECTION_RESET_MS = 30_000L
        // When the app is in the background and at least one other relay is already healthy,
        // throttle persistently failing relays so dead endpoints stop waking Tor/TLS every 5m.
        private const val BACKGROUND_DEAD_RELAY_BACKOFF_MS = 15 * 60_000L
        private const val BACKGROUND_DEAD_RELAY_MIN_ATTEMPTS = 4
        const val OWNER_LEGACY = "legacy"
        const val OWNER_BACKGROUND = "background"
        
        /**
         * Get instance for Android compatibility (context-aware calls)
         */
        fun getInstance(context: android.content.Context): NostrRelayManager {
            shared.appContext = context.applicationContext
            shared.ensureNetworkCallbackRegistered()
            shared.ensureBackgroundObserverRegistered()
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
        var lastConnectedMonoMs: Long? = null,
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
    private val backgroundThrottledRelays = ConcurrentHashMap.newKeySet<String>()
    private val desiredConnected = AtomicBoolean(false)
    private val panicReconnectWithheld = AtomicBoolean(false)
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
        /**
         * Deliver every matching event straight to [handler] on the socket thread, bypassing the
         * shared deduplicator. For observers that must not consume events other subscriptions
         * still need (the globe activity sweep overlaps the open chat's subscription).
         */
        val observeOnly: Boolean = false
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

    // Network connectivity awareness: pause reconnect churn when offline and heal immediately on return.
    private val networkAvailable = AtomicBoolean(true)
    private val networkCallbackRegistered = AtomicBoolean(false)
    private val backgroundObserverStarted = AtomicBoolean(false)
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    private fun isAppBackgrounded(): Boolean =
        backgroundStateFlow?.value ?: (powerManager?.profile?.value?.isBackground == true)

    private fun ensureBackgroundObserverRegistered() {
        val bgFlow = backgroundStateFlow
            ?: powerManager?.profile?.map { it.isBackground }?.distinctUntilChanged()
            ?: return
        if (!backgroundObserverStarted.compareAndSet(false, true)) return
        scope.launch {
            var wasBackground = isAppBackgrounded()
            bgFlow.collect { nowBackground ->
                if (wasBackground && !nowBackground && desiredConnected.get() && networkAvailable.get()) {
                    // Cancel long background backoff timers and recover disconnected relays immediately on foregrounding.
                    backgroundThrottledRelays.clear()
                    reconnectJobs.values.forEach(Job::cancel)
                    reconnectJobs.clear()
                    reconnectDisconnectedRelays(resetBackoff = true)
                }
                wasBackground = nowBackground
            }
        }
    }

    private fun ensureNetworkCallbackRegistered() {
        val ctx = appContext ?: return
        if (!networkCallbackRegistered.compareAndSet(false, true)) return
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run {
                networkCallbackRegistered.set(false)
                return
            }
            val activeCaps = cm.getNetworkCapabilities(cm.activeNetwork)
            networkAvailable.set(
                activeCaps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: true
            )
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val wasOffline = !networkAvailable.getAndSet(true)
                    if (desiredConnected.get() && wasOffline) {
                        Log.i(TAG, "Network restored; reconnecting disconnected Nostr relays")
                        reconnectDisconnectedRelays(resetBackoff = true)
                    }
                }

                override fun onLost(network: Network) {
                    val caps = try { cm.getNetworkCapabilities(cm.activeNetwork) } catch (_: Exception) { null }
                    val stillOnline = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
                    if (!stillOnline) {
                        networkAvailable.set(false)
                        // Pause reconnect timers while completely offline to avoid burning Tor/CPU.
                        backgroundThrottledRelays.clear()
                        reconnectJobs.values.forEach(Job::cancel)
                        reconnectJobs.clear()
                    }
                }
            }
            cm.registerDefaultNetworkCallback(callback)
            connectivityCallback = callback
        } catch (e: Exception) {
            networkCallbackRegistered.set(false)
            networkAvailable.set(true)
        }
    }

    internal fun reconnectDisconnectedRelays(resetBackoff: Boolean) {
        if (!desiredConnected.get()) return
        val snapshot = synchronized(relaysList) {
            if (resetBackoff) {
                relaysList.forEach { relay ->
                    if (!relay.isConnected) {
                        relay.reconnectAttempts = 0
                        relay.nextReconnectTime = null
                    }
                }
            }
            relaysList.map { it.url }
        }
        scope.launch {
            snapshot.forEach { url ->
                if (!desiredConnected.get()) return@launch
                if (!connections.containsKey(url)) {
                    val (allowed, liveToken) = authorizeRelayConnection(url)
                    if (allowed) {
                        val job = launch {
                            connectToRelay(url, liveToken)
                        }
                        if (liveToken != null) {
                            liveLocationConnectionJobs.add(job)
                            job.invokeOnCompletion { liveLocationConnectionJobs.remove(job) }
                        }
                    }
                }
            }
        }
    }
    
    // WebSocket factory, resolved per connection (via provider to honor Tor)
    private val httpClient: WebSocket.Factory
        get() = webSocketFactory()

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
     * Connect to an explicit relay set that carries no location meaning, such as the globe
     * picker's worldwide activity sweep. Pair with [releaseRelays].
     */
    fun connectToRelays(relayUrls: Collection<String>) {
        val urls = relayUrls.filter { it.isNotBlank() }.toSet()
        if (urls.isEmpty()) return
        // A relay held only for a live-location channel keeps its live gating.
        val connectable = urls.filterTo(mutableSetOf()) {
            !liveLocationRelayTokens.containsKey(it) || it in nonLiveRelayUrls
        }
        nonLiveRelayUrls.addAll(connectable)
        ensureConnectionsFor(connectable)
    }

    /**
     * Disconnect relays from [connectToRelays] that nothing else needs: not a default relay,
     * not routed for a geohash, and not targeted by any remaining subscription.
     */
    fun releaseRelays(relayUrls: Collection<String>) {
        val routed = buildSet { geohashToRelays.values.forEach { addAll(it) } }
        val targeted = buildSet {
            activeSubscriptions.values.forEach { info -> info.targetRelayUrls?.let { addAll(it) } }
        }
        val releasable = relayUrls.toSet() - DEFAULT_RELAYS.toSet() - routed - targeted
        if (releasable.isEmpty()) return
        releasable.forEach { url ->
            backgroundThrottledRelays.remove(url)
            reconnectJobs.remove(url)?.cancel()
            connections.remove(url)?.close(1000, "Released")
            subscriptions.remove(url)
            nonLiveRelayUrls.remove(url)
        }
        synchronized(relaysList) {
            relaysList.removeAll { it.url in releasable }
        }
        updateRelaysList()
        updateConnectionStatus()
        wakeDegradedBackgroundRelays()
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
            liveLocationToken = liveLocationToken
        ).also { subscriptionId ->
            activeSubscriptions[subscriptionId]?.let { subscription ->
                activeSubscriptions[subscriptionId] =
                    subscription.copy(originGeohash = geohash)
            }
        }
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

    /**
     * Fail-closed relay connection authorization:
     * - Relays in [nonLiveRelayUrls] (defaults, teleported geohashes, globe sweeps) connect
     *   without a live-location token.
     * - Any other relay (live-location-only) MUST carry an active, currently-tracked token in
     *   [liveLocationRelayTokens] accepted by [LiveLocationPrivacyGate]; a missing token is
     *   treated as revoked, never unrestricted.
     */
    private fun authorizeRelayConnection(
        relayUrl: String,
        explicitToken: Long? = null
    ): Pair<Boolean, Long?> {
        if (relayUrl in nonLiveRelayUrls) return true to null
        val trackedToken = liveLocationRelayTokens[relayUrl] ?: return false to null
        val token = explicitToken ?: trackedToken
        return if (token == trackedToken && LiveLocationPrivacyGate.accepts(token)) {
            true to token
        } else {
            false to null
        }
    }

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
        }
        subscriptions.replaceAll { _, ids -> ids - liveSubscriptionIds }

        messageQueue.removeLiveLocationEvents()

        liveGeohashTokens.keys.forEach(geohashToRelays::remove)
        liveGeohashTokens.clear()

        val liveOnlyRelayUrls = liveLocationRelayTokens.keys
            .filterNotTo(mutableSetOf()) { it in nonLiveRelayUrls }
        liveOnlyRelayUrls.forEach { relayUrl ->
            backgroundThrottledRelays.remove(relayUrl)
            connections.remove(relayUrl)?.cancel()
            subscriptions.remove(relayUrl)
            reconnectJobs.remove(relayUrl)?.cancel()
        }
        synchronized(relaysList) {
            relaysList.removeAll { it.url in liveOnlyRelayUrls }
        }
        liveLocationRelayTokens.clear()
        updateRelaysList()
        updateConnectionStatus()
        wakeDegradedBackgroundRelays()
    }

    private fun ensureConnectionsFor(
        relayUrls: Set<String>,
        liveLocationToken: Long? = null
    ) {
        if (!isNetworkActionAllowed(liveLocationToken)) return
        // Ensure relays are tracked for UI/status
        var addedAny = false
        synchronized(relaysList) {
            relayUrls.forEach { url ->
                if (relaysList.none { it.url == url }) {
                    relaysList.add(Relay(url))
                    addedAny = true
                }
            }
        }
        if (addedAny) updateRelaysList()

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
            if (backgroundStateFlow != null) {
                ensureBackgroundObserverRegistered()
            }
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
        ensureNetworkCallbackRegistered()
        val relayUrls = synchronized(relaysList) { relaysList.map { it.url } }
        Log.i(TAG, "Connecting to ${relayUrls.size} Nostr relays")
        scope.launch {
            relayUrls.forEach { url ->
                val (allowed, liveToken) = authorizeRelayConnection(url)
                if (allowed) {
                    val job = launch {
                        connectToRelay(url, liveToken)
                    }
                    if (liveToken != null) {
                        liveLocationConnectionJobs.add(job)
                        job.invokeOnCompletion { liveLocationConnectionJobs.remove(job) }
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
        backgroundThrottledRelays.clear()
        reconnectJobs.values.forEach(Job::cancel)
        reconnectJobs.clear()
        liveLocationConnectionJobs.forEach(Job::cancel)
        liveLocationConnectionJobs.clear()

        val sockets = buildList { connections.values.forEach { add(it) } }
        connections.clear()
        sockets.forEach { webSocket ->
            webSocket.close(1000, "Manual disconnect")
        }

        // Preserve logical subscriptions for controlled resets, but forget per-socket state.
        subscriptions.clear()
        synchronized(relaysList) {
            relaysList.forEach {
                it.isConnected = false
                it.nextReconnectTime = null
            }
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
        liveLocationToken: Long? = null
    ) {
        val targetRelays = (relayUrls ?: relaysList.map { it.url })
            .filter { it.isNotBlank() }
            .distinct()
        if (targetRelays.isEmpty()) return

        val queued = runNetworkAction(liveLocationToken) {
            val queueId = messageQueue.enqueue(
                event = event,
                relayUrls = targetRelays,
                liveLocationToken = liveLocationToken
            ) ?: return@runNetworkAction
            scope.launch {
                if (!isNetworkActionAllowed(liveLocationToken)) return@launch
                targetRelays.forEach { relayUrl ->
                    val webSocket = connections[relayUrl]
                    if (webSocket != null) {
                        if (sendToRelay(event, webSocket, relayUrl, liveLocationToken)) {
                            messageQueue.markDelivered(queueId, relayUrl)
                        }
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
        observeOnly: Boolean = false
    ): String {
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet(),
            owner = owner,
            liveLocationToken = liveLocationToken,
            observeOnly = observeOnly
        )
        
        runNetworkAction(liveLocationToken) {
            activeSubscriptions[id] = subscriptionInfo
            messageHandlers[id] = handler
            wakeDegradedBackgroundRelays()
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
            // A concurrent view can shrink between Collection.size and iterator.next.
            // Build by iteration instead of Kotlin's size-based toList fast paths.
            val targetRelays = subscriptionInfo.targetRelayUrls?.toList()
                ?: buildList { connections.keys.forEach { add(it) } }
            
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
        val relay = synchronized(relaysList) { relaysList.find { it.url == relayUrl } } ?: return
        desiredConnected.set(true)
        val (allowed, liveToken) = authorizeRelayConnection(relayUrl)
        if (!allowed) return

        // Reset reconnection attempts
        synchronized(relaysList) {
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
        }

        // Disconnect if connected
        backgroundThrottledRelays.remove(relayUrl)
        reconnectJobs.remove(relayUrl)?.cancel()
        connections.remove(relayUrl)?.close(1000, "Manual retry")

        // Attempt immediate reconnection
        val job = scope.launch {
            connectToRelay(relayUrl, liveToken)
        }
        if (liveToken != null) {
            liveLocationConnectionJobs.add(job)
            job.invokeOnCompletion { liveLocationConnectionJobs.remove(job) }
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

            Log.i(TAG, "Cleared all Nostr subscriptions and routing caches")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear subscriptions: ${e.message}")
        }
    }

    /**
     * Clear all subscription tracking, deduplication cache, message queue, and connections for panic mode.
     *
     * When [reconnect] is true and connectivity was desired, comes back online from scratch on the
     * default relays. Pass false to stay offline when the wipe could not be confirmed.
     */
    fun clearAllOnPanic(reconnect: Boolean) {
        try {
            val wantedConnected = desiredConnected.get() || panicReconnectWithheld.get()
            clearAllSubscriptions()
            clearDeduplicationCache()
            disconnect()
            forgetSessionRelays()
            // Remembered so a retried wipe that is confirmed can still come back online.
            panicReconnectWithheld.set(wantedConnected && !reconnect)
            if (wantedConnected && reconnect) {
                connect()
            }
            Log.w(TAG, "🚨 Cleared NostrRelayManager subscriptions, cache, and connections for panic mode")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear NostrRelayManager on panic: ${e.message}")
        }
    }

    /**
     * Drop every relay the wiped session added. Geohash-selected relays are chosen by proximity,
     * so reconnecting to them would tie the fresh identity to the previous session's locations.
     */
    private fun forgetSessionRelays() {
        liveGeohashTokens.clear()
        liveLocationRelayTokens.clear()
        nonLiveRelayUrls.retainAll(DEFAULT_RELAYS.toSet())
        synchronized(relaysList) {
            relaysList.clear()
            relaysList.addAll(DEFAULT_RELAYS.map { Relay(it) })
        }
        updateRelaysList()
        updateConnectionStatus()
    }

    /**
     * Get detailed status for all relays (immutable snapshots with live message counters).
     */
    fun getRelayStatuses(): List<Relay> {
        return synchronized(relaysList) { relaysList.map { it.copy() } }
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
            if (activeSubscriptions.isEmpty() || connections.isEmpty()) return
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
        if (!networkAvailable.get()) return
        val (allowed, connectionToken) = authorizeRelayConnection(urlString, liveLocationToken)
        if (!allowed) return
        // Skip if we already have a connection
        if (connections.containsKey(urlString)) {
            return
        }

        // When Tor mode is enabled but Arti is still bootstrapping, wait for the SOCKS route
        // instead of hammering an unbound localhost port and triggering failing reconnect storms.
        val ready = awaitTorRoute(30_000L)
        if (!desiredConnected.get() || !networkAvailable.get() || connections.containsKey(urlString)) {
            return
        }
        val (stillAllowed, currentToken) = authorizeRelayConnection(urlString, connectionToken)
        if (!stillAllowed) return
        if (!ready) {
            // Schedule normal backoff retry so slow Tor recovery never leaves relays permanently offline.
            handleConnectionCreationFailure(
                urlString,
                IllegalStateException("Tor SOCKS route not ready within 30s"),
                currentToken
            )
            return
        }

        try {
            val request = Request.Builder()
                .url(urlString)
                .build()

            val started = runNetworkAction(currentToken) {
                if (!authorizeRelayConnection(urlString, currentToken).first) return@runNetworkAction
                val webSocket = httpClient.newWebSocket(
                    request,
                    RelayWebSocketListener(urlString, currentToken)
                )
                val existing = connections.putIfAbsent(urlString, webSocket)
                when {
                    existing != null -> webSocket.close(1000, "Duplicate connection")
                    !desiredConnected.get() || !authorizeRelayConnection(urlString, currentToken).first -> {
                        connections.remove(urlString, webSocket)
                        webSocket.cancel()
                    }
                }
            }
            if (!started) return

        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection")
            handleConnectionCreationFailure(urlString, e, currentToken)
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
                // Update relay stats without copying and re-emitting _relays on every event
                synchronized(relaysList) {
                    relaysList.find { it.url == relayUrl }?.let { relay ->
                        relay.messagesSent += 1
                    }
                }
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
                    // Update relay stats without copying and re-emitting _relays on every event
                    synchronized(relaysList) {
                        relaysList.find { it.url == relayUrl }?.let { relay ->
                            relay.messagesReceived += 1
                        }
                    }
                    
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

                    if (subscriptionInfo.observeOnly) {
                        messageHandlers[response.subscriptionId]?.invoke(response.event)
                        return
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
        webSocket: WebSocket,
        error: Throwable,
        liveLocationToken: Long? = null
    ) {
        // Ignore callbacks from intentionally closed or replaced sockets. They must not remove a
        // newer socket or schedule a reconnect after a controlled disconnect/privacy revocation.
        if (!connections.remove(relayUrl, webSocket)) return
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
        val nowWall = System.currentTimeMillis()
        val nowMono = monotonicClockMs()
        // Only treat a connection as stable if it was ACTUALLY open right before this disconnect
        // AND stayed open for at least STABLE_CONNECTION_RESET_MS on a monotonic clock. A relay
        // that never opened (or closed quickly) must continue backing off instead of resetting.
        val wasStable = synchronized(relaysList) {
            val relay = relaysList.find { it.url == relayUrl }
            relay != null &&
                relay.isConnected &&
                relay.lastConnectedMonoMs != null &&
                (nowMono - relay.lastConnectedMonoMs!!) >= STABLE_CONNECTION_RESET_MS
        }

        updateRelayStatus(relayUrl, false, error)
        // If this disconnect removed the last healthy alternative for a background-throttled relay,
        // wake that relay immediately instead of leaving it sitting on a 15-minute backoff timer.
        wakeDegradedBackgroundRelays()

        val (allowed, connectionToken) = authorizeRelayConnection(relayUrl, liveLocationToken)
        if (!desiredConnected.get() ||
            !networkAvailable.get() ||
            !allowed
        ) {
            backgroundThrottledRelays.remove(relayUrl)
            return
        }

        // Every failure backs off and retries, including a name-resolution
        // failure: "unable to resolve host" is what this device reports when it
        // simply has no network, so treating it as permanent turns a walk
        // through a tunnel into a dead relay layer for the rest of the process.
        // Decide backoff AND register both throttle marker and reconnect job atomically under
        // synchronized(relaysList) so a concurrent disconnect/release/revoke cannot interleave
        // and miss waking this relay.
        var backoffInterval = 0L
        synchronized(relaysList) {
            val relay = relaysList.find { it.url == relayUrl } ?: return
            val previousAttempts = if (wasStable) 0 else relay.reconnectAttempts
            relay.reconnectAttempts = RelayReconnectPolicy.nextAttempt(previousAttempts)
            var delayMs = RelayReconnectPolicy.backoffMs(relay.reconnectAttempts)
            val isBackground = isAppBackgrounded()
            var extendedBackgroundThrottle = false
            if (isBackground &&
                relay.reconnectAttempts >= BACKGROUND_DEAD_RELAY_MIN_ATTEMPTS &&
                hasHealthyAlternativeForRelayLocked(relayUrl)
            ) {
                delayMs = maxOf(delayMs, BACKGROUND_DEAD_RELAY_BACKOFF_MS)
                extendedBackgroundThrottle = true
            }
            relay.nextReconnectTime = nowWall + delayMs
            backoffInterval = delayMs

            if (extendedBackgroundThrottle) {
                backgroundThrottledRelays.add(relayUrl)
            } else {
                backgroundThrottledRelays.remove(relayUrl)
            }

            reconnectJobs.remove(relayUrl)?.cancel()
            val reconnectJob = scope.launch {
                delay(delayMs)
                backgroundThrottledRelays.remove(relayUrl)
                val (stillAllowed, currentToken) = authorizeRelayConnection(relayUrl, connectionToken)
                if (desiredConnected.get() &&
                    networkAvailable.get() &&
                    stillAllowed
                ) {
                    connectToRelay(relayUrl, currentToken)
                }
            }
            reconnectJobs[relayUrl] = reconnectJob
            if (connectionToken != null) {
                liveLocationConnectionJobs.add(reconnectJob)
            }
            reconnectJob.invokeOnCompletion {
                reconnectJobs.remove(relayUrl, reconnectJob)
                if (connectionToken != null) {
                    liveLocationConnectionJobs.remove(reconnectJob)
                }
            }
        }

        Log.d(TAG, "Scheduling Nostr relay reconnection in ${backoffInterval}ms")
    }

    /**
     * Cancel extended 15-minute background backoff timers for any throttled relay that no longer
     * has a healthy connected alternative (for example after the healthy relay disconnects,
     * is released/revoked, or a new subscription targets the throttled relay exclusively).
     */
    private fun wakeDegradedBackgroundRelays() {
        if (!desiredConnected.get() || !networkAvailable.get()) return

        val relaysToWake = synchronized(relaysList) {
            if (backgroundThrottledRelays.isEmpty()) return
            val toWake = mutableListOf<String>()
            for (url in backgroundThrottledRelays.toList()) {
                if (!hasHealthyAlternativeForRelayLocked(url)) {
                    toWake.add(url)
                    backgroundThrottledRelays.remove(url)
                    reconnectJobs.remove(url)?.cancel()
                    relaysList.find { it.url == url }?.nextReconnectTime = null
                }
            }
            toWake
        }
        if (relaysToWake.isEmpty()) return

        updateRelaysList()

        scope.launch {
            relaysToWake.forEach { url ->
                if (!desiredConnected.get() || !networkAvailable.get()) return@launch
                if (!connections.containsKey(url)) {
                    val (allowed, liveToken) = authorizeRelayConnection(url)
                    if (allowed) {
                        val job = launch { connectToRelay(url, liveToken) }
                        if (liveToken != null) {
                            liveLocationConnectionJobs.add(job)
                            job.invokeOnCompletion { liveLocationConnectionJobs.remove(job) }
                        }
                    }
                }
            }
        }
    }

    private fun updateRelayStatus(url: String, isConnected: Boolean, error: Throwable? = null) {
        if (isConnected) {
            backgroundThrottledRelays.remove(url)
        }
        synchronized(relaysList) {
            val relay = relaysList.find { it.url == url } ?: return

            relay.isConnected = isConnected
            relay.lastError = error

            if (isConnected) {
                relay.lastConnectedAt = System.currentTimeMillis()
                relay.lastConnectedMonoMs = monotonicClockMs()
                relay.nextReconnectTime = null
            } else {
                relay.lastDisconnectedAt = System.currentTimeMillis()
            }
        }

        updateRelaysList()
        updateConnectionStatus()
    }
    
    /**
     * Caller holds `synchronized(relaysList)`. Returns true only when another connected relay
     * already serves every active subscription that targets [relayUrl], so background dead-relay
     * throttling never defers a subscription's sole relay for 15 minutes.
     */
    private fun hasHealthyAlternativeForRelayLocked(relayUrl: String): Boolean {
        val connectedUrls = relaysList.filter { it.isConnected && it.url != relayUrl }
            .mapTo(HashSet()) { it.url }
        if (connectedUrls.isEmpty()) return false
        for (sub in activeSubscriptions.values) {
            val targets = sub.targetRelayUrls
            if (targets != null && relayUrl in targets) {
                if (targets.none { it != relayUrl && it in connectedUrls }) {
                    return false
                }
            }
        }
        return true
    }

    private fun updateRelaysList() {
        _relays.value = synchronized(relaysList) { relaysList.map { it.copy() } }
    }
    
    private fun updateConnectionStatus() {
        val connected = synchronized(relaysList) { relaysList.any { it.isConnected } }
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
                if (sendToRelay(
                        delivery.event,
                        webSocket,
                        relayUrl,
                        delivery.liveLocationToken
                    )
                ) {
                    messageQueue.markDelivered(delivery.queueId, relayUrl)
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
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(relayUrl, webSocket, error, liveLocationToken)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Nostr WebSocket failure")
            handleDisconnection(relayUrl, webSocket, t, liveLocationToken)
        }
    }
}
