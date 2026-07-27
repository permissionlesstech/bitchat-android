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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

internal fun isNip20ConfirmedSuccess(accepted: Boolean, message: String?): Boolean =
    accepted || message?.startsWith("duplicate:") == true

/**
 * Manages WebSocket connections to Nostr relays
 * Compatible with iOS implementation with Android-specific optimizations
 */
class NostrRelayManager internal constructor(
    private val scope: CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val eventDeduplicator: NostrEventDeduplicator =
        NostrEventDeduplicator.getInstance()
) {
    
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
        private const val CONFIRMED_PUBLISH_TIMEOUT_MS = 15_000L
        
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
    private val commitAwareMessageHandlers =
        ConcurrentHashMap<String, (NostrEvent) -> Boolean>()
    private val pendingGiftWrapIDs = ConcurrentHashMap.newKeySet<String>()
    
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
    
    // Message queue for reliability
    private data class QueuedEvent(
        val event: NostrEvent,
        val targetRelays: List<String>,
        val liveLocationToken: Long? = null,
        val accountGeneration: Long
    )

    private val messageQueue = mutableListOf<QueuedEvent>()
    private val messageQueueLock = Any()
    private val accountGeneration = AtomicLong(0L)
    private val accountGenerationLock = Any()
    private val accountResetOperationLock = Any()
    @Volatile
    private var accountResetBlocked = false

    private data class ConfirmedPublish(
        val awaitingRelayUrls: MutableSet<String>,
        val completion: (Boolean) -> Unit,
        val accountGeneration: Long,
        @Volatile var timeoutJob: Job? = null
    )

    private data class EventDispatch(
        val subscriptionInfo: SubscriptionInfo,
        val commitAwareHandler: ((NostrEvent) -> Boolean)?,
        val ordinaryHandler: ((NostrEvent) -> Unit)?
    )

    private data class DisconnectionOutcome(
        val confirmedCompletions: List<Pair<String, ConfirmedPublish>>,
        val reconnectDelayMs: Long?,
        val connectionToken: Long?
    )

    private val confirmedPublishes = ConcurrentHashMap<String, ConfirmedPublish>()
    @Volatile
    private var ndrConnectionAvailableHandler: (() -> Unit)? = null
    
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
        liveLocationToken: Long? = null,
        expectedAccountGeneration: Long = accountGeneration.get()
    ) {
        val generation = expectedAccountGeneration
        if (!isCurrentAccountGeneration(generation)) return
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
                val registered = synchronized(accountGenerationLock) {
                    if (!isCurrentAccountGeneration(generation)) {
                        false
                    } else {
                        geohashToRelays[geohash] = selected
                        if (liveLocationToken == null) {
                            liveGeohashTokens.remove(geohash)
                            nonLiveRelayUrls.addAll(selected)
                        } else {
                            liveGeohashTokens[geohash] = liveLocationToken
                            selected.forEach { relayUrl ->
                                if (relayUrl !in nonLiveRelayUrls) {
                                    liveLocationRelayTokens[relayUrl] =
                                        liveLocationToken
                                }
                            }
                        }
                        true
                    }
                }
                if (registered) {
                    ensureConnectionsFor(
                        selected,
                        liveLocationToken,
                        generation
                    )
                }
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
        liveLocationToken: Long? = null,
        expectedAccountGeneration: Long = accountGeneration.get()
    ): String {
        val generation = expectedAccountGeneration
        if (!isCurrentAccountGeneration(generation)) return id
        if (!isNetworkActionAllowed(liveLocationToken)) return id
        ensureGeohashRelaysConnected(
            geohash,
            nRelays,
            includeDefaults,
            liveLocationToken,
            generation
        )
        if (!isNetworkActionAllowed(liveLocationToken)) return id
        val relayUrls = getRelaysForGeohash(geohash)
        return subscribe(
            filter = filter,
            id = id,
            handler = handler,
            targetRelayUrls = relayUrls,
            liveLocationToken = liveLocationToken,
            expectedAccountGeneration = generation
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
        liveLocationToken: Long? = null,
        expectedAccountGeneration: Long = accountGeneration.get()
    ) {
        val generation = expectedAccountGeneration
        if (!isCurrentAccountGeneration(generation)) return
        if (!isNetworkActionAllowed(liveLocationToken)) return
        ensureGeohashRelaysConnected(
            geohash,
            nRelays,
            includeDefaults,
            liveLocationToken,
            generation
        )
        if (!isNetworkActionAllowed(liveLocationToken)) return
        val relayUrls = getRelaysForGeohash(geohash)
        if (relayUrls.isEmpty()) {
            Log.w(TAG, "No target relays for geohash event; falling back to defaults")
            sendEvent(
                event,
                Companion.defaultRelays(),
                liveLocationToken,
                generation
            )
            return
        }
        sendEvent(event, relayUrls, liveLocationToken, generation)
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
    private fun closeSubscriptionsOnConnectedRelays(
        subscriptionIds: Set<String>,
        generation: Long
    ) {
        if (subscriptionIds.isEmpty()) return
        if (!isCurrentAccountGeneration(generation)) return

        val closeTargets = NostrLiveSubscriptionPrivacy.closeTargets(
            liveSubscriptionIds = subscriptionIds,
            subscriptionsByRelay = subscriptions,
        )
        closeTargets.forEach { (relayUrl, relaySubscriptionIds) ->
            val webSocket = connections[relayUrl] ?: return@forEach
            relaySubscriptionIds.forEach { subscriptionId ->
                val request = NostrRequest.Close(subscriptionId)
                val message = gson.toJson(request, NostrRequest::class.java)
                var closeQueued = false
                synchronized(accountGenerationLock) {
                    if (!isCurrentAccountGeneration(generation)) return
                    closeQueued = runCatching { webSocket.send(message) }
                        .getOrDefault(false)
                }
                if (!closeQueued) {
                    connections.remove(relayUrl, webSocket)
                    webSocket.cancel()
                }
            }
        }
    }

    private fun revokeLiveLocationAccess() {
        val generation = accountGeneration.get()
        liveLocationConnectionJobs.forEach(Job::cancel)
        liveLocationConnectionJobs.clear()

        val liveSubscriptionIds = activeSubscriptions.values
            .filter { it.liveLocationToken != null }
            .mapTo(mutableSetOf()) { it.id }
        closeSubscriptionsOnConnectedRelays(liveSubscriptionIds, generation)
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
        liveLocationToken: Long? = null,
        expectedAccountGeneration: Long = accountGeneration.get()
    ) {
        val generation = expectedAccountGeneration
        if (!isCurrentAccountGeneration(generation)) return
        if (!isNetworkActionAllowed(liveLocationToken)) return
        // Ensure relays are tracked for UI/status
        relayUrls.forEach { url ->
            if (relaysList.none { it.url == url }) {
                relaysList.add(Relay(url))
            }
        }
        updateRelaysList()

        val job = scope.launch {
            if (!isCurrentAccountGeneration(generation) ||
                !isNetworkActionAllowed(liveLocationToken)
            ) return@launch
            relayUrls.forEach { relayUrl ->
                launch {
                    if (!connections.containsKey(relayUrl) &&
                        isCurrentAccountGeneration(generation) &&
                        isNetworkActionAllowed(liveLocationToken)
                    ) {
                        connectToRelay(relayUrl, liveLocationToken, generation)
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
        val generation = accountGeneration.get()
        scope.launch {
            if (!isCurrentAccountGeneration(generation)) return@launch
            relaysList.forEach { relay ->
                launch {
                    val liveToken = liveLocationRelayTokens[relay.url]
                        ?.takeIf { relay.url !in nonLiveRelayUrls }
                    if (isCurrentAccountGeneration(generation) &&
                        (liveToken == null || LiveLocationPrivacyGate.accepts(liveToken))
                    ) {
                        connectToRelay(relay.url, liveToken, generation)
                    }
                }
            }
        }
        
        // Start periodic subscription validation
        startSubscriptionValidation(generation)
    }
    
    /**
     * Disconnect from all relays
     */
    fun disconnect() {
        // Stop subscription validation
        stopSubscriptionValidation()

        confirmedPublishes.entries.toList().forEach { (eventId, tracker) ->
            completeConfirmedPublish(eventId, tracker, accepted = false)
        }
        
        connections.values.forEach { webSocket ->
            webSocket.close(1000, "Manual disconnect")
        }
        connections.clear()
        val disconnectedAt = System.currentTimeMillis()
        relaysList.forEach { relay ->
            if (relay.isConnected) {
                relay.lastDisconnectedAt = disconnectedAt
            }
            relay.isConnected = false
        }
        
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
        liveLocationToken: Long? = null,
        expectedAccountGeneration: Long = accountGeneration.get()
    ): Boolean {
        val generation = expectedAccountGeneration
        if (!isCurrentAccountGeneration(generation)) return false
        val targetRelays = relayUrls ?: relaysList.map { it.url }

        var accepted = false
        val queued = runNetworkAction(liveLocationToken) {
            synchronized(messageQueueLock) {
                if (!isCurrentAccountGeneration(generation)) return@runNetworkAction
                messageQueue.add(
                    QueuedEvent(
                        event = event,
                        targetRelays = targetRelays,
                        liveLocationToken = liveLocationToken,
                        accountGeneration = generation
                    )
                )
                accepted = true
            }
            if (!accepted) return@runNetworkAction
            scope.launch {
                if (!isCurrentAccountGeneration(generation) ||
                    !isNetworkActionAllowed(liveLocationToken)
                ) return@launch
                targetRelays.forEach { relayUrl ->
                    val webSocket = connections[relayUrl]
                    if (webSocket != null) {
                        sendToRelay(
                            event,
                            webSocket,
                            relayUrl,
                            liveLocationToken,
                            generation
                        )
                    }
                }
            }
        }
        return queued && accepted
    }

    /**
     * Sends without using the process-local retry queue and completes only
     * after at least one relay returns an accepted NIP-01 OK.
     */
    fun sendEventConfirmed(
        event: NostrEvent,
        relayUrls: List<String>? = null,
        completion: (Boolean) -> Unit
    ) {
        val generation = accountGeneration.get()
        if (!isCurrentAccountGeneration(generation)) {
            completion(false)
            return
        }
        val requestedRelays = (relayUrls ?: relaysList.map { it.url }).toSet()
        val connectedTargets = requestedRelays.filterTo(linkedSetOf()) {
            connections.containsKey(it)
        }
        if (connectedTargets.isEmpty()) {
            completion(false)
            return
        }

        val tracker = ConfirmedPublish(
            awaitingRelayUrls = ConcurrentHashMap.newKeySet<String>().apply {
                addAll(connectedTargets)
            },
            completion = completion,
            accountGeneration = generation
        )
        if (confirmedPublishes.putIfAbsent(event.id, tracker) != null) {
            completion(false)
            return
        }
        tracker.timeoutJob = scope.launch {
            delay(CONFIRMED_PUBLISH_TIMEOUT_MS)
            if (!isCurrentAccountGeneration(generation)) {
                completeConfirmedPublish(event.id, tracker, accepted = false)
                return@launch
            }
            completeConfirmedPublish(event.id, tracker, accepted = false)
        }

        connectedTargets.forEach { relayUrl ->
            val webSocket = connections[relayUrl]
            if (webSocket == null ||
                !sendToRelay(
                    event,
                    webSocket,
                    relayUrl,
                    accountGeneration = generation
                )
            ) {
                tracker.awaitingRelayUrls.remove(relayUrl)
            }
        }
        if (tracker.awaitingRelayUrls.isEmpty()) {
            completeConfirmedPublish(event.id, tracker, accepted = false)
            return
        }
    }

    fun cancelConfirmedEvent(eventId: String) {
        val tracker = confirmedPublishes.remove(eventId) ?: return
        tracker.timeoutJob?.cancel()
        runCatching { tracker.completion(false) }
            .onFailure { Log.w(TAG, "Confirmed publish cancellation callback failed") }
    }

    fun setNdrConnectionAvailableHandler(handler: () -> Unit) {
        ndrConnectionAvailableHandler = handler
    }

    private fun completeConfirmedPublish(
        eventId: String,
        tracker: ConfirmedPublish,
        accepted: Boolean
    ) {
        if (!confirmedPublishes.remove(eventId, tracker)) return
        tracker.timeoutJob?.cancel()
        val acceptedForCurrentAccount =
            accepted && isCurrentAccountGeneration(tracker.accountGeneration)
        runCatching { tracker.completion(acceptedForCurrentAccount) }
            .onFailure { Log.w(TAG, "Confirmed publish callback failed") }
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
        liveLocationToken: Long? = null,
        expectedAccountGeneration: Long = accountGeneration.get()
    ): String {
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet(),
            liveLocationToken = liveLocationToken
        )
        registerSubscription(
            subscriptionInfo = subscriptionInfo,
            ordinaryHandler = handler,
            expectedAccountGeneration = expectedAccountGeneration
        )
        return id
    }

    /**
     * NDR relay copies are considered seen only after the durable runtime
     * commits them. A transient storage failure must leave another relay copy
     * eligible for processing.
     */
    fun subscribeAfterSuccessfulProcessing(
        filter: NostrFilter,
        id: String,
        targetRelayUrls: List<String>? = null,
        expectedAccountGeneration: Long = accountGeneration.get(),
        handler: (NostrEvent) -> Boolean
    ): Boolean {
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = {},
            targetRelayUrls = targetRelayUrls?.toSet()
        )
        return registerSubscription(
            subscriptionInfo = subscriptionInfo,
            commitAwareHandler = handler,
            expectedAccountGeneration = expectedAccountGeneration
        )
    }

    /**
     * Installs the complete handler mode before any relay can observe the REQ.
     * Some WebSocket implementations can synchronously deliver a cached EVENT
     * from inside send(), so handler replacement after send is already too late.
     */
    private fun registerSubscription(
        subscriptionInfo: SubscriptionInfo,
        ordinaryHandler: ((NostrEvent) -> Unit)? = null,
        commitAwareHandler: ((NostrEvent) -> Boolean)? = null,
        expectedAccountGeneration: Long
    ): Boolean {
        require((ordinaryHandler == null) != (commitAwareHandler == null))
        val generation = expectedAccountGeneration
        var registered = false
        runNetworkAction(subscriptionInfo.liveLocationToken) {
            synchronized(accountGenerationLock) {
                if (!isCurrentAccountGeneration(generation)) return@synchronized
                activeSubscriptions[subscriptionInfo.id] = subscriptionInfo
                if (commitAwareHandler != null) {
                    commitAwareMessageHandlers[subscriptionInfo.id] = commitAwareHandler
                    messageHandlers.remove(subscriptionInfo.id)
                } else {
                    messageHandlers[subscriptionInfo.id] = requireNotNull(ordinaryHandler)
                    commitAwareMessageHandlers.remove(subscriptionInfo.id)
                }
                registered = true
            }
            if (registered) {
                sendSubscriptionToRelays(subscriptionInfo, generation)
            }
        }
        return registered
    }
    
    /**
     * Send a subscription to the appropriate relays
     */
    private fun sendSubscriptionToRelays(
        subscriptionInfo: SubscriptionInfo,
        generation: Long
    ) {
        if (!isCurrentAccountGeneration(generation) ||
            !isNetworkActionAllowed(subscriptionInfo.liveLocationToken)
        ) return
        val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
        val message = gson.toJson(request, NostrRequest::class.java)

        scope.launch {
            if (!isCurrentAccountGeneration(generation) ||
                !isNetworkActionAllowed(subscriptionInfo.liveLocationToken)
            ) return@launch
            val targetRelays = subscriptionInfo.targetRelayUrls?.toList() ?: connections.keys.toList()
            
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    try {
                        var success = false
                        runNetworkAction(subscriptionInfo.liveLocationToken) {
                            synchronized(accountGenerationLock) {
                                if (isCurrentAccountGeneration(generation)) {
                                    success = webSocket.send(message)
                                    if (success) {
                                        val currentSubs = subscriptions[relayUrl] ?: emptySet()
                                        subscriptions[relayUrl] =
                                            currentSubs + subscriptionInfo.id
                                    }
                                }
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
        val generation = accountGeneration.get()
        // Remove from persistent tracking
        val subscriptionInfo = synchronized(accountGenerationLock) {
            if (!isCurrentAccountGeneration(generation)) {
                null
            } else {
                activeSubscriptions.remove(id).also {
                    messageHandlers.remove(id)
                    commitAwareMessageHandlers.remove(id)
                }
            }
        }
        
        if (subscriptionInfo == null) {
            return
        }

        if (subscriptionInfo.liveLocationToken != null &&
            !isNetworkActionAllowed(subscriptionInfo.liveLocationToken)
        ) {
            closeSubscriptionsOnConnectedRelays(setOf(id), generation)
            subscriptions.replaceAll { _, ids -> ids - id }
            return
        }

        val request = NostrRequest.Close(id)
        val message = gson.toJson(request, NostrRequest::class.java)
        
        scope.launch {
            if (!isCurrentAccountGeneration(generation)) return@launch
            if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) {
                closeSubscriptionsOnConnectedRelays(setOf(id), generation)
                subscriptions.replaceAll { _, ids -> ids - id }
                return@launch
            }
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        runNetworkAction(subscriptionInfo.liveLocationToken) {
                            synchronized(accountGenerationLock) {
                                if (isCurrentAccountGeneration(generation)) {
                                    webSocket.send(message)
                                }
                            }
                        }
                        if (isCurrentAccountGeneration(generation)) {
                            subscriptions[relayUrl] = currentSubs - id
                        }
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
        val generation = accountGeneration.get()
        if (!isCurrentAccountGeneration(generation)) return
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
            if (isCurrentAccountGeneration(generation)) {
                connectToRelay(relayUrl, liveToken, generation)
            }
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
        val generation = accountGeneration.get()
        scope.launch {
            if (!isCurrentAccountGeneration(generation)) return@launch
            connections.forEach { (relayUrl, webSocket) ->
                if (isCurrentAccountGeneration(generation)) {
                    restoreSubscriptionsForRelay(relayUrl, webSocket, generation)
                }
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
            commitAwareMessageHandlers.clear()
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
     * Discard every process-local relay artifact that belongs to the old
     * account. Unlike a normal disconnect, panic/quit must never replay queued
     * kind-1059 gift wraps after identity replacement.
     */
    fun discardForAccountReset(): Long {
        val resetToken = beginAccountReset()
        discardForAccountReset(resetToken)
        return resetToken
    }

    /**
     * Clear old-account state only while this caller still owns the reset.
     * Reset operations are serialized so a stale reset cannot clear state after
     * a newer reset has already reopened the replacement account.
     */
    fun discardForAccountReset(resetToken: Long): Boolean =
        synchronized(accountResetOperationLock) resetOperation@{
            synchronized(accountGenerationLock) {
                if (!accountResetBlocked ||
                    accountGeneration.get() != resetToken
                ) return@resetOperation false
            }

            // Invalidate every already-launched send/connect/listener/reconnect job
            // before clearing state or closing sockets. Account-scoped handler
            // jobs use their own cancel-and-mutation barrier.
            synchronized(messageQueueLock) {
                messageQueue.clear()
            }
            liveLocationConnectionJobs.forEach(Job::cancel)
            liveLocationConnectionJobs.clear()
            pendingGiftWrapIDs.clear()
            disconnect()
            clearAllSubscriptions()
            liveGeohashTokens.clear()
            liveLocationRelayTokens.clear()
            nonLiveRelayUrls.clear()
            true
        }

    /** Refuse new account work and invalidate all previously captured generations. */
    fun beginAccountReset(): Long =
        synchronized(accountGenerationLock) {
            accountResetBlocked = true
            accountGeneration.incrementAndGet()
        }

    /** Permit fresh relay work only when this caller owns the latest reset. */
    fun completeAccountReset(resetToken: Long): Boolean =
        synchronized(accountResetOperationLock) resetOperation@{
            synchronized(accountGenerationLock) {
                if (!accountResetBlocked ||
                    accountGeneration.get() != resetToken
                ) return@resetOperation false
                // Tokens captured while the reset was blocked must never become
                // valid work for the replacement account.
                accountGeneration.incrementAndGet()
                accountResetBlocked = false
                true
            }
        }

    internal fun queuedEventCountForTesting(): Int =
        synchronized(messageQueueLock) { messageQueue.size }

    internal fun pendingGiftWrapCountForTesting(): Int = pendingGiftWrapIDs.size

    internal fun accountGenerationForTesting(): Long = accountGeneration.get()

    internal fun captureAccountGeneration(): Long = accountGeneration.get()

    internal fun registerPendingGiftWrap(
        id: String,
        expectedAccountGeneration: Long
    ): Boolean = synchronized(accountGenerationLock) {
        if (!isCurrentAccountGeneration(expectedAccountGeneration)) {
            false
        } else {
            pendingGiftWrapIDs.add(id)
            true
        }
    }

    internal fun isAccountGenerationCurrent(generation: Long): Boolean =
        isCurrentAccountGeneration(generation)

    private fun isCurrentAccountGeneration(generation: Long): Boolean =
        !accountResetBlocked && accountGeneration.get() == generation

    private inline fun <T> withAccountCallback(
        generation: Long,
        rejected: T,
        callback: () -> T
    ): T {
        if (!isCurrentAccountGeneration(generation)) return rejected
        return callback()
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
    private fun startSubscriptionValidation(generation: Long) {
        synchronized(accountGenerationLock) {
            if (!isCurrentAccountGeneration(generation)) return
            stopSubscriptionValidation() // Stop any existing validation

            subscriptionValidationJob = scope.launch {
            while (isActive) {
                delay(SUBSCRIPTION_VALIDATION_INTERVAL)
                if (!isCurrentAccountGeneration(generation)) return@launch
                
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
                                restoreSubscriptionsForRelay(
                                    relayUrl,
                                    webSocket,
                                    generation
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error during subscription validation: ${e.message}")
                }
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
        liveLocationToken: Long? = null,
        generation: Long = accountGeneration.get()
    ) {
        val connectionToken = liveLocationToken
            ?.takeIf { urlString !in nonLiveRelayUrls }
        if (!isCurrentAccountGeneration(generation) ||
            !isNetworkActionAllowed(connectionToken)
        ) return
        // Skip if we already have a connection
        if (connections.containsKey(urlString)) {
            return
        }

        try {
            val request = Request.Builder()
                .url(urlString)
                .build()
            
            runNetworkAction(connectionToken) {
                synchronized(accountGenerationLock) {
                    if (!isCurrentAccountGeneration(generation)) {
                        return@synchronized
                    }
                    val webSocket = httpClient.newWebSocket(
                        request,
                        RelayWebSocketListener(urlString, connectionToken, generation)
                    )
                    if (isCurrentAccountGeneration(generation)) {
                        connections[urlString] = webSocket
                    } else {
                        webSocket.cancel()
                    }
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection")
            handleDisconnection(urlString, e, liveLocationToken, generation)
        }
    }
    
    private fun sendToRelay(
        event: NostrEvent,
        webSocket: WebSocket,
        relayUrl: String,
        liveLocationToken: Long? = null,
        accountGeneration: Long = this.accountGeneration.get()
    ): Boolean {
        if (!isCurrentAccountGeneration(accountGeneration) ||
            !isNetworkActionAllowed(liveLocationToken)
        ) return false
        return try {
            val request = NostrRequest.Event(event)
            val message = gson.toJson(request, NostrRequest::class.java)

            var success = false
            runNetworkAction(liveLocationToken) {
                synchronized(accountGenerationLock) {
                    if (isCurrentAccountGeneration(accountGeneration)) {
                        success = webSocket.send(message)
                    }
                }
            }
            if (success) {
                // Update relay stats
                val relay = relaysList.find { it.url == relayUrl }
                relay?.let { it.messagesSent += 1 }
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
    
    private fun handleMessage(
        message: String,
        relayUrl: String,
        generation: Long
    ) {
        if (!isCurrentAccountGeneration(generation)) return
        try {
            val jsonElement = JsonParser.parseString(message)
            if (!jsonElement.isJsonArray) {
                Log.w(TAG, "Received non-array message from relay")
                return
            }
            
            val response = NostrResponse.fromJsonArray(jsonElement.asJsonArray)
            
            when (response) {
                is NostrResponse.Event -> {
                    val dispatch = synchronized(accountGenerationLock) {
                        if (!isCurrentAccountGeneration(generation)) return
                        val relay = relaysList.find { it.url == relayUrl }
                        relay?.let { it.messagesReceived += 1 }
                        updateRelaysList()
                        val subscriptionInfo =
                            activeSubscriptions[response.subscriptionId]
                                ?: return
                        EventDispatch(
                            subscriptionInfo = subscriptionInfo,
                            commitAwareHandler =
                                commitAwareMessageHandlers[response.subscriptionId],
                            ordinaryHandler =
                                messageHandlers[response.subscriptionId]
                        )
                    }
                    val subscriptionInfo = dispatch.subscriptionInfo
                    if (!isNetworkActionAllowed(subscriptionInfo.liveLocationToken)) return
                    val matches = try {
                        subscriptionInfo.filter.matches(response.event)
                    } catch (_: Exception) {
                        true
                    }
                    if (!matches) {
                        // Do not deduplicate an event that another subscription may accept.
                        return
                    }

                    val commitAwareHandler = dispatch.commitAwareHandler
                    if (commitAwareHandler != null) {
                        scope.launch {
                            withAccountCallback(generation, false) {
                                eventDeduplicator.processEventAfterSuccess(response.event) { event ->
                                    isCurrentAccountGeneration(generation) &&
                                        commitAwareHandler(event)
                                }
                            }
                        }
                        return
                    }

                    val handler = dispatch.ordinaryHandler
                    if (handler != null) {
                        withAccountCallback(generation, Unit) {
                            eventDeduplicator.processEvent(response.event) { event ->
                                scope.launch(Dispatchers.Main) {
                                    withAccountCallback(generation, Unit) {
                                        if (isNetworkActionAllowed(
                                                subscriptionInfo.liveLocationToken
                                            )) {
                                            handler(event)
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Log.w(TAG, "⚠️ No handler for Nostr subscription")
                    }
                }

                is NostrResponse.EndOfStoredEvents -> {
                    // No action needed
                }

                is NostrResponse.Ok -> {
                    var completion: Pair<ConfirmedPublish, Boolean>? = null
                    val wasGiftWrap = synchronized(accountGenerationLock) {
                        if (!isCurrentAccountGeneration(generation)) return
                        val pending = pendingGiftWrapIDs.remove(response.eventId)
                        confirmedPublishes[response.eventId]?.let { tracker ->
                            if (isNip20ConfirmedSuccess(
                                    response.accepted,
                                    response.message
                                )
                            ) {
                                completion = tracker to true
                            } else if (tracker.awaitingRelayUrls.remove(relayUrl) &&
                                tracker.awaitingRelayUrls.isEmpty()
                            ) {
                                completion = tracker to false
                            }
                        }
                        pending
                    }
                    completion?.let { (tracker, accepted) ->
                        completeConfirmedPublish(
                            response.eventId,
                            tracker,
                            accepted
                        )
                    }
                    if (!isNip20ConfirmedSuccess(response.accepted, response.message)) {
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
        liveLocationToken: Long? = null,
        generation: Long = accountGeneration.get(),
        webSocket: WebSocket? = null
    ) {
        val outcome = synchronized(accountGenerationLock) {
            if (!isCurrentAccountGeneration(generation)) return
            if (webSocket != null && !connections.remove(relayUrl, webSocket)) {
                return
            }
            val connectionToken = liveLocationToken
                ?.takeIf { relayUrl !in nonLiveRelayUrls }
            val confirmedCompletions = confirmedPublishes.entries
                .toList()
                .mapNotNull { (eventId, tracker) ->
                    if (tracker.awaitingRelayUrls.remove(relayUrl) &&
                        tracker.awaitingRelayUrls.isEmpty()
                    ) {
                        eventId to tracker
                    } else {
                        null
                    }
                }

            // Keep subscriptions for restoration on reconnection.
            updateRelayStatus(relayUrl, false, error)

            var reconnectDelayMs: Long? = null
            if (isNetworkActionAllowed(connectionToken)) {
                val errorMessage = error.message?.lowercase() ?: ""
                val dnsFailure =
                    errorMessage.contains("hostname could not be found") ||
                        errorMessage.contains("dns") ||
                        errorMessage.contains("unable to resolve host")
                if (dnsFailure) {
                    Log.w(TAG, "Nostr relay DNS failure; not retrying")
                } else {
                    val relay = relaysList.find { it.url == relayUrl }
                    if (relay != null) {
                        relay.reconnectAttempts++
                        if (relay.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                            Log.w(TAG, "Max Nostr relay reconnection attempts reached")
                        } else {
                            val delayMs = min(
                                INITIAL_BACKOFF_INTERVAL *
                                    BACKOFF_MULTIPLIER.pow(
                                        relay.reconnectAttempts - 1.0
                                    ),
                                MAX_BACKOFF_INTERVAL.toDouble()
                            ).toLong()
                            reconnectDelayMs = delayMs
                            relay.nextReconnectTime =
                                System.currentTimeMillis() + delayMs
                            Log.d(TAG, "Scheduling Nostr relay reconnection")
                        }
                    }
                }
            }
            DisconnectionOutcome(
                confirmedCompletions = confirmedCompletions,
                reconnectDelayMs = reconnectDelayMs,
                connectionToken = connectionToken
            )
        }

        outcome.confirmedCompletions.forEach { (eventId, tracker) ->
            completeConfirmedPublish(eventId, tracker, accepted = false)
        }
        outcome.reconnectDelayMs?.let { delayMs ->
            scope.launch {
                delay(delayMs)
                if (isCurrentAccountGeneration(generation) &&
                    isNetworkActionAllowed(outcome.connectionToken)
                ) {
                    connectToRelay(
                        relayUrl,
                        outcome.connectionToken,
                        generation
                    )
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
    private fun restoreSubscriptionsForRelay(
        relayUrl: String,
        webSocket: WebSocket,
        generation: Long
    ) {
        if (!isCurrentAccountGeneration(generation)) return
        val subscriptionsToRestore = activeSubscriptions.values.filter { subscriptionInfo ->
            // Include subscription if it targets all relays or specifically targets this relay
            isCurrentAccountGeneration(generation) &&
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
                    synchronized(accountGenerationLock) {
                        if (isCurrentAccountGeneration(generation)) {
                            success = webSocket.send(message)
                            if (success) {
                                val currentSubs = subscriptions[relayUrl] ?: emptySet()
                                subscriptions[relayUrl] =
                                    currentSubs + subscriptionInfo.id
                            }
                        }
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
        private val liveLocationToken: Long?,
        private val generation: Long
    ) : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(accountGenerationLock) {
                if (!isCurrentAccountGeneration(generation) ||
                    !isNetworkActionAllowed(liveLocationToken) ||
                    connections[relayUrl] !== webSocket
                ) {
                    connections.remove(relayUrl, webSocket)
                    webSocket.cancel()
                    return
                }
                updateRelayStatus(relayUrl, true)
            }
            val callbackAccepted = withAccountCallback(generation, false) {
                runCatching { ndrConnectionAvailableHandler?.invoke() }
                    .onFailure { Log.w(TAG, "NDR reconnect callback failed") }
                true
            }
            if (!callbackAccepted) return
            synchronized(accountGenerationLock) {
                if (!isCurrentAccountGeneration(generation)) return
                restoreSubscriptionsForRelay(relayUrl, webSocket, generation)

                // Process any queued messages for this relay
                synchronized(messageQueueLock) {
                    val iterator = messageQueue.iterator()
                    while (iterator.hasNext()) {
                        val queued = iterator.next()
                        if (queued.accountGeneration == generation &&
                            isCurrentAccountGeneration(generation) &&
                            relayUrl in queued.targetRelays &&
                            isNetworkActionAllowed(queued.liveLocationToken)
                        ) {
                            sendToRelay(
                                queued.event,
                                webSocket,
                                relayUrl,
                                queued.liveLocationToken,
                                generation
                            )
                        }
                    }
                }
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text, relayUrl, generation)
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server-initiated close; onClosed will follow
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!isCurrentAccountGeneration(generation)) {
                connections.remove(relayUrl, webSocket)
                return
            }
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(
                relayUrl,
                error,
                liveLocationToken,
                generation,
                webSocket
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (!isCurrentAccountGeneration(generation)) {
                connections.remove(relayUrl, webSocket)
                return
            }
            Log.e(TAG, "Nostr WebSocket failure")
            handleDisconnection(
                relayUrl,
                t,
                liveLocationToken,
                generation,
                webSocket
            )
        }
    }
}
