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
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

internal fun isNip20ConfirmedSuccess(accepted: Boolean, message: String?): Boolean =
    accepted || message?.startsWith("duplicate:") == true

internal class RelayAccountResetToken internal constructor(
    internal val generation: Long
)

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
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val desiredConnected = AtomicBoolean(false)
    private val subscriptions = ConcurrentHashMap<String, Set<String>>() // relay URL -> subscription IDs
    private val messageHandlers = ConcurrentHashMap<String, (NostrEvent) -> Unit>()
    private val commitAwareMessageHandlers =
        ConcurrentHashMap<String, (NostrEvent) -> Boolean>()
    private val pendingGiftWrapGenerations = ConcurrentHashMap<String, Long>()
    
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
        val accountGeneration: Long
    )

    // Bounded per-relay delivery queue for reconnect reliability.
    private val messageQueue = NostrPendingEventQueue(MAX_QUEUED_EVENTS)

    private val accountGeneration = AtomicLong(0L)
    private val accountGenerationLock = Any()
    private val accountResetOperationLock = Any()
    @Volatile private var accountResetBlocked = false
    private var preparedResetGeneration = Long.MIN_VALUE
    private var discardedResetGeneration = Long.MIN_VALUE

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

    private val confirmedPublishes = ConcurrentHashMap<String, ConfirmedPublish>()

    private data class AccountResetCleanup(
        val jobs: List<Job>,
        val sockets: List<WebSocket>,
        val confirmedPublishes: List<ConfirmedPublish>
    )

    private data class NdrConnectionHandler(
        val accountGeneration: Long,
        val callback: () -> Unit
    )

    @Volatile private var ndrConnectionAvailableHandler: NdrConnectionHandler? = null
    
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
                    ensureConnectionsFor(selected, liveLocationToken, generation)
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
        owner: String = OWNER_LEGACY,
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
        if (!isCurrentAccountGeneration(generation) ||
            !isNetworkActionAllowed(liveLocationToken)
        ) return id
        val relayUrls = getRelaysForGeohash(geohash)
        return subscribe(
            filter = filter,
            id = id,
            handler = handler,
            targetRelayUrls = relayUrls,
            owner = owner,
            liveLocationToken = liveLocationToken,
            expectedAccountGeneration = generation,
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
        if (!isCurrentAccountGeneration(generation) ||
            !isNetworkActionAllowed(liveLocationToken)
        ) return
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
            for (subscriptionId in relaySubscriptionIds) {
                if (!isCurrentAccountGeneration(generation) ||
                    connections[relayUrl] !== webSocket
                ) return
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
                    if (desiredConnected.get() &&
                        relayUrl in nonLiveRelayUrls &&
                        isCurrentAccountGeneration(generation)
                    ) {
                        scope.launch {
                            connectToRelay(
                                relayUrl,
                                liveLocationToken = null,
                                generation = generation
                            )
                        }
                    }
                    break
                }
            }
        }
    }

    private fun revokeLiveLocationAccess() {
        val generation = accountGeneration.get()
        if (!isCurrentAccountGeneration(generation)) return

        val liveSubscriptionIds = activeSubscriptions.values
            .filter { it.liveLocationToken != null }
            .mapTo(mutableSetOf()) { it.id }
        closeSubscriptionsOnConnectedRelays(liveSubscriptionIds, generation)
        val (socketsToCancel, reconnectsToCancel, connectionJobsToCancel) =
            synchronized(accountGenerationLock) {
                if (!isCurrentAccountGeneration(generation)) return
                liveSubscriptionIds.forEach { id ->
                    val subscription = activeSubscriptions[id]
                    if (subscription?.accountGeneration == generation) {
                        activeSubscriptions.remove(id, subscription)
                        messageHandlers.remove(id)
                        commitAwareMessageHandlers.remove(id)
                    }
                }
                subscriptions.replaceAll { _, ids -> ids - liveSubscriptionIds }

                messageQueue.removeLiveLocationEvents(generation)

                liveGeohashTokens.keys.forEach(geohashToRelays::remove)
                liveGeohashTokens.clear()

                val liveOnlyRelayUrls = liveLocationRelayTokens.keys
                    .filterNotTo(mutableSetOf()) { it in nonLiveRelayUrls }
                val sockets = liveOnlyRelayUrls.mapNotNull { relayUrl ->
                    subscriptions.remove(relayUrl)
                    connections.remove(relayUrl)
                }
                val reconnects = liveOnlyRelayUrls.mapNotNull { relayUrl ->
                    reconnectJobs.remove(relayUrl)
                }
                synchronized(relaysList) {
                    relaysList.removeAll { it.url in liveOnlyRelayUrls }
                }
                liveLocationRelayTokens.clear()
                val connectionJobs = liveLocationConnectionJobs.toList()
                liveLocationConnectionJobs.clear()
                Triple(sockets, reconnects, connectionJobs)
            }
        connectionJobsToCancel.forEach(Job::cancel)
        reconnectsToCancel.forEach(Job::cancel)
        socketsToCancel.forEach { it.cancel() }
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
        // Ensure relays are tracked for UI/status.
        val tracked = synchronized(accountGenerationLock) {
            if (!isCurrentAccountGeneration(generation)) {
                false
            } else {
                synchronized(relaysList) {
                    relayUrls.forEach { url ->
                        if (relaysList.none { it.url == url }) {
                            relaysList.add(Relay(url))
                        }
                    }
                }
                true
            }
        }
        if (!tracked) return
        updateRelaysList()

        if (!desiredConnected.get()) return
        val job = scope.launch {
            if (!desiredConnected.get() ||
                !isCurrentAccountGeneration(generation) ||
                !isNetworkActionAllowed(liveLocationToken)
            ) return@launch
            relayUrls.forEach { relayUrl ->
                launch {
                    if (desiredConnected.get() &&
                        isCurrentAccountGeneration(generation) &&
                        !connections.containsKey(relayUrl) &&
                        isNetworkActionAllowed(liveLocationToken)
                    ) {
                        connectToRelay(relayUrl, liveLocationToken, generation)
                    }
                }
            }
        }
        if (liveLocationToken != null) {
            val registered = synchronized(accountGenerationLock) {
                isCurrentAccountGeneration(generation) &&
                    liveLocationConnectionJobs.add(job)
            }
            if (!registered) {
                job.cancel()
                return
            }
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
        val (generation, relayUrls) = synchronized(accountGenerationLock) {
            val current = accountGeneration.get()
            if (!isCurrentAccountGeneration(current)) return
            desiredConnected.set(true)
            current to synchronized(relaysList) {
                relaysList.map { it.url }
            }
        }
        Log.i(TAG, "Connecting to ${relayUrls.size} Nostr relays")
        scope.launch {
            if (!desiredConnected.get() ||
                !isCurrentAccountGeneration(generation)
            ) return@launch
            relayUrls.forEach { relayUrl ->
                launch {
                    val liveToken = liveLocationRelayTokens[relayUrl]
                        ?.takeIf { relayUrl !in nonLiveRelayUrls }
                    if (desiredConnected.get() &&
                        isCurrentAccountGeneration(generation) &&
                        (liveToken == null || LiveLocationPrivacyGate.accepts(liveToken))
                    ) {
                        connectToRelay(relayUrl, liveToken, generation)
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
        sockets.forEach { webSocket ->
            webSocket.close(1000, "Manual disconnect")
        }

        confirmedPublishes.entries.toList().forEach { (eventId, tracker) ->
            completeConfirmedPublish(eventId, tracker, accepted = false)
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
        expectedAccountGeneration: Long = accountGeneration.get()
    ): Boolean {
        val generation = expectedAccountGeneration
        if (!isCurrentAccountGeneration(generation)) return false
        val configuredRelays = relayUrls ?: synchronized(relaysList) {
            relaysList.map { it.url }
        }
        val targetRelays = configuredRelays
            .filter { it.isNotBlank() }
            .distinct()
        if (targetRelays.isEmpty()) return false

        var queueId: Long? = null
        val allowed = runNetworkAction(liveLocationToken) {
            queueId = synchronized(accountGenerationLock) {
                if (!isCurrentAccountGeneration(generation)) {
                    null
                } else {
                    messageQueue.enqueue(
                        event = event,
                        relayUrls = targetRelays,
                        liveLocationToken = liveLocationToken,
                        accountGeneration = generation
                    )
                }
            }
            val admittedQueueId = queueId ?: return@runNetworkAction
            scope.launch {
                if (!isCurrentAccountGeneration(generation) ||
                    !isNetworkActionAllowed(liveLocationToken)
                ) return@launch
                targetRelays.forEach { relayUrl ->
                    val webSocket = connections[relayUrl]
                    if (webSocket != null) {
                        if (sendToRelay(
                                event = event,
                                webSocket = webSocket,
                                relayUrl = relayUrl,
                                liveLocationToken = liveLocationToken,
                                generation = generation
                            )
                        ) {
                            messageQueue.markDelivered(admittedQueueId, relayUrl)
                        }
                    }
                }
            }
        }
        return allowed && queueId != null
    }

    /**
     * Publish without the retry queue and complete after one relay confirms the
     * event, or after every connected target rejects/disconnects.
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

        val configuredRelays = relayUrls ?: synchronized(relaysList) {
            relaysList.map { it.url }
        }
        val requestedRelays = configuredRelays
            .filter { it.isNotBlank() }
            .toSet()
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
        val registered = synchronized(accountGenerationLock) {
            isCurrentAccountGeneration(generation) &&
                confirmedPublishes.putIfAbsent(event.id, tracker) == null
        }
        if (!registered) {
            completion(false)
            return
        }

        tracker.timeoutJob = scope.launch {
            delay(CONFIRMED_PUBLISH_TIMEOUT_MS)
            completeConfirmedPublish(event.id, tracker, accepted = false)
        }

        connectedTargets.forEach { relayUrl ->
            val webSocket = connections[relayUrl]
            if (webSocket == null ||
                !sendToRelay(
                    event = event,
                    webSocket = webSocket,
                    relayUrl = relayUrl,
                    generation = generation
                )
            ) {
                tracker.awaitingRelayUrls.remove(relayUrl)
            }
        }
        if (tracker.awaitingRelayUrls.isEmpty()) {
            completeConfirmedPublish(event.id, tracker, accepted = false)
        }
    }

    fun cancelConfirmedEvent(eventId: String) {
        val tracker = confirmedPublishes.remove(eventId) ?: return
        tracker.timeoutJob?.cancel()
        runCatching { tracker.completion(false) }
            .onFailure { Log.w(TAG, "Confirmed publish cancellation callback failed") }
    }

    fun setNdrConnectionAvailableHandler(handler: () -> Unit) {
        val generation = accountGeneration.get()
        synchronized(accountGenerationLock) {
            if (isCurrentAccountGeneration(generation)) {
                ndrConnectionAvailableHandler =
                    NdrConnectionHandler(generation, handler)
            }
        }
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
        owner: String = OWNER_LEGACY,
        liveLocationToken: Long? = null,
        expectedAccountGeneration: Long = accountGeneration.get(),
        originGeohash: String? = null
    ): String {
        val generation = expectedAccountGeneration
        val subscriptionInfo = SubscriptionInfo(
            id = id,
            filter = filter,
            handler = handler,
            targetRelayUrls = targetRelayUrls?.toSet(),
            originGeohash = originGeohash,
            owner = owner,
            liveLocationToken = liveLocationToken,
            accountGeneration = generation
        )
        registerSubscription(
            subscriptionInfo = subscriptionInfo,
            ordinaryHandler = handler,
            expectedAccountGeneration = generation
        )
        return id
    }

    /**
     * NDR relay copies become deduplicated only after durable processing. A
     * rejected registration is reported so the native action remains pending.
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
            targetRelayUrls = targetRelayUrls?.toSet(),
            accountGeneration = expectedAccountGeneration
        )
        return registerSubscription(
            subscriptionInfo = subscriptionInfo,
            commitAwareHandler = handler,
            expectedAccountGeneration = expectedAccountGeneration
        )
    }

    /**
     * Install the complete handler mode before any REQ can synchronously deliver
     * a cached event from a test or WebSocket implementation.
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
                    commitAwareMessageHandlers[subscriptionInfo.id] =
                        commitAwareHandler
                    messageHandlers.remove(subscriptionInfo.id)
                } else {
                    messageHandlers[subscriptionInfo.id] =
                        requireNotNull(ordinaryHandler)
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
            subscriptionInfo.accountGeneration != generation ||
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
                if (!isCurrentAccountGeneration(generation)) return@launch
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    try {
                        var success = false
                        runNetworkAction(subscriptionInfo.liveLocationToken) {
                            if (isCurrentAccountGeneration(generation) &&
                                connections[relayUrl] === webSocket
                            ) {
                                success = webSocket.send(message)
                            }
                            if (success && isCurrentAccountGeneration(generation)) {
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
    fun unsubscribe(
        id: String,
        expectedAccountGeneration: Long = accountGeneration.get()
    ) {
        val generation = expectedAccountGeneration
        // Remove from persistent tracking
        val subscriptionInfo = synchronized(accountGenerationLock) {
            if (!isCurrentAccountGeneration(generation)) {
                null
            } else {
                activeSubscriptions[id]
                    ?.takeIf { it.accountGeneration == generation }
                    ?.also {
                        activeSubscriptions.remove(id, it)
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
                if (!isCurrentAccountGeneration(generation)) return@launch
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        runNetworkAction(subscriptionInfo.liveLocationToken) {
                            if (isCurrentAccountGeneration(generation) &&
                                connections[relayUrl] === webSocket
                            ) {
                                webSocket.send(message)
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

    fun unsubscribeOwner(
        owner: String,
        expectedAccountGeneration: Long = accountGeneration.get()
    ) {
        if (!isCurrentAccountGeneration(expectedAccountGeneration)) return
        activeSubscriptions.values
            .filter {
                it.owner == owner &&
                    it.accountGeneration == expectedAccountGeneration
            }
            .map { it.id }
            .forEach { unsubscribe(it, expectedAccountGeneration) }
    }
    
    /**
     * Manually retry connection to a specific relay
     */
    fun retryConnection(relayUrl: String) {
        val generation = accountGeneration.get()
        if (!isCurrentAccountGeneration(generation)) return
        val relay = relaysList.find { it.url == relayUrl } ?: return
        synchronized(accountGenerationLock) {
            if (!isCurrentAccountGeneration(generation)) return
            desiredConnected.set(true)
        }
        val liveToken = liveLocationRelayTokens[relayUrl]
            ?.takeIf { relayUrl !in nonLiveRelayUrls }
        if (!isNetworkActionAllowed(liveToken)) return
        
        // Reset reconnection attempts
        relay.reconnectAttempts = 0
        relay.nextReconnectTime = null
        
        // Disconnect if connected
        reconnectJobs.remove(relayUrl)?.cancel()
        connections.remove(relayUrl)?.close(1000, "Manual retry")
        
        // Attempt immediate reconnection
        scope.launch {
            if (desiredConnected.get() &&
                isCurrentAccountGeneration(generation)
            ) {
                connectToRelay(relayUrl, liveToken, generation)
            }
        }
    }
    
    /**
     * Reset all relay connections
     * This will automatically restore all subscriptions when reconnected
     */
    fun resetAllConnections() {
        val generation = accountGeneration.get()
        if (!isCurrentAccountGeneration(generation)) return
        val shouldReconnect = desiredConnected.get()
        disconnect()
        
        // Reset all relay states
        relaysList.forEach { relay ->
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
            relay.lastError = null
        }
        
        // Reconnect only when connectivity was desired before the controlled reset.
        if (shouldReconnect && isCurrentAccountGeneration(generation)) connect()
    }
    
    /**
     * Force re-establishment of all subscriptions on currently connected relays
     * Useful for ensuring subscription consistency after network issues
     */
    fun reestablishAllSubscriptions() {
        val generation = accountGeneration.get()
        if (!isCurrentAccountGeneration(generation)) return
        scope.launch {
            if (!isCurrentAccountGeneration(generation)) return@launch
            connections.forEach { (relayUrl, webSocket) ->
                if (isCurrentAccountGeneration(generation) &&
                    connections[relayUrl] === webSocket
                ) {
                    restoreSubscriptionsForRelay(
                        relayUrl,
                        webSocket,
                        generation
                    )
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
            messageQueue.clear()

            Log.i(TAG, "Cleared all Nostr subscriptions and routing caches")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear subscriptions: ${e.message}")
        }
    }

    /**
     * Refuse fresh work, then discard every process-local relay artifact owned
     * by the old account. The opaque token prevents one reset from reopening a
     * newer reset.
     */
    internal fun discardForAccountReset(): RelayAccountResetToken {
        val resetToken = beginAccountReset()
        discardForAccountReset(resetToken)
        return resetToken
    }

    internal fun discardForAccountReset(
        resetToken: RelayAccountResetToken
    ): Boolean {
        val cleanup = prepareAccountResetCleanup(resetToken) ?: return false

        cleanup.jobs.forEach(Job::cancel)
        cleanup.sockets.forEach { socket ->
            runCatching { socket.close(1000, "Account reset") }
                .onFailure { socket.cancel() }
        }
        cleanup.confirmedPublishes.forEach { tracker ->
            tracker.timeoutJob?.cancel()
            runCatching { tracker.completion(false) }
                .onFailure {
                    Log.w(TAG, "Confirmed publish reset callback failed")
                }
        }

        return synchronized(accountResetOperationLock) {
            synchronized(accountGenerationLock) {
                val generation = resetToken.generation
                if (!accountResetBlocked ||
                    accountGeneration.get() != generation ||
                    preparedResetGeneration != generation
                ) {
                    false
                } else {
                    discardedResetGeneration = generation
                    true
                }
            }
        }
    }

    private fun prepareAccountResetCleanup(
        resetToken: RelayAccountResetToken
    ): AccountResetCleanup? = synchronized(accountResetOperationLock) {
        synchronized(accountGenerationLock) generationCheck@{
            val generation = resetToken.generation
            if (!accountResetBlocked ||
                accountGeneration.get() != generation
            ) return@generationCheck null

            desiredConnected.set(false)
            val jobs = buildList {
                subscriptionValidationJob?.let(::add)
                addAll(reconnectJobs.values)
                addAll(liveLocationConnectionJobs)
            }
            subscriptionValidationJob = null
            reconnectJobs.clear()
            liveLocationConnectionJobs.clear()

            val sockets = connections.values.toList()
            connections.clear()
            val pendingConfirmations =
                confirmedPublishes.values.toList()
            confirmedPublishes.clear()

            activeSubscriptions.clear()
            messageHandlers.clear()
            commitAwareMessageHandlers.clear()
            subscriptions.clear()
            messageQueue.clear()
            pendingGiftWrapGenerations.clear()
            ndrConnectionAvailableHandler = null
            eventDeduplicator.clear()

            geohashToRelays.clear()
            liveGeohashTokens.clear()
            liveLocationRelayTokens.clear()
            nonLiveRelayUrls.clear()
            nonLiveRelayUrls.addAll(DEFAULT_RELAYS)
            synchronized(relaysList) {
                relaysList.removeAll { it.url !in DEFAULT_RELAYS }
                DEFAULT_RELAYS.forEach { url ->
                    val relay = relaysList.find { it.url == url }
                    if (relay == null) {
                        relaysList.add(Relay(url))
                    } else {
                        relay.isConnected = false
                        relay.lastError = null
                        relay.nextReconnectTime = null
                        relay.reconnectAttempts = 0
                    }
                }
            }
            preparedResetGeneration = generation
            discardedResetGeneration = Long.MIN_VALUE
            updateRelaysList()
            updateConnectionStatus()

            AccountResetCleanup(
                jobs = jobs,
                sockets = sockets,
                confirmedPublishes = pendingConfirmations
            )
        }
    }

    internal fun beginAccountReset(): RelayAccountResetToken =
        synchronized(accountGenerationLock) {
            accountResetBlocked = true
            preparedResetGeneration = Long.MIN_VALUE
            discardedResetGeneration = Long.MIN_VALUE
            RelayAccountResetToken(accountGeneration.incrementAndGet())
        }

    internal fun completeAccountReset(
        resetToken: RelayAccountResetToken
    ): Boolean =
        synchronized(accountResetOperationLock) {
            synchronized(accountGenerationLock) {
                val generation = resetToken.generation
                if (!accountResetBlocked ||
                    accountGeneration.get() != generation ||
                    discardedResetGeneration != generation
                ) {
                    false
                } else {
                    // Work captured while admission was blocked must not become
                    // valid when the replacement account is opened.
                    accountGeneration.incrementAndGet()
                    accountResetBlocked = false
                    preparedResetGeneration = Long.MIN_VALUE
                    discardedResetGeneration = Long.MIN_VALUE
                    true
                }
            }
        }

    internal fun queuedEventCountForTesting(): Int = messageQueue.size()

    internal fun pendingGiftWrapCountForTesting(): Int =
        pendingGiftWrapGenerations.size

    internal fun accountGenerationForTesting(): Long = accountGeneration.get()

    internal fun captureAccountGeneration(): Long = accountGeneration.get()

    internal fun registerPendingGiftWrap(
        id: String,
        expectedAccountGeneration: Long
    ): Boolean = synchronized(accountGenerationLock) {
        if (!isCurrentAccountGeneration(expectedAccountGeneration)) {
            false
        } else {
            pendingGiftWrapGenerations[id] = expectedAccountGeneration
            true
        }
    }

    internal fun isAccountGenerationCurrent(generation: Long): Boolean =
        isCurrentAccountGeneration(generation)

    private fun isCurrentAccountGeneration(generation: Long): Boolean =
        !accountResetBlocked && accountGeneration.get() == generation
    
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
        if (!isCurrentAccountGeneration(generation)) return
        stopSubscriptionValidation() // Stop any existing validation

        subscriptionValidationJob = scope.launch {
            if (!isCurrentAccountGeneration(generation)) return@launch
            val manager = powerManager
            if (manager == null) {
                runSubscriptionValidationLoop(
                    intervalMs = com.bitchat.android.util.AppConstants.Nostr
                        .SUBSCRIPTION_VALIDATION_INTERVAL_MS,
                    generation = generation
                )
                return@launch
            }

            manager.profile
                .map { it.nostr.subscriptionValidationMs }
                .distinctUntilChanged()
                .collectLatest { intervalMs ->
                    runSubscriptionValidationLoop(intervalMs, generation)
                }
        }
    }

    private suspend fun runSubscriptionValidationLoop(
        intervalMs: Long,
        generation: Long
    ) {
        while (currentCoroutineContext().isActive &&
            desiredConnected.get() &&
            isCurrentAccountGeneration(generation)
        ) {
            delay(intervalMs)
            if (!desiredConnected.get() ||
                !isCurrentAccountGeneration(generation)
            ) break
            validateAndRepairSubscriptions(generation)
        }
    }

    private fun validateAndRepairSubscriptions(generation: Long) {
        if (!isCurrentAccountGeneration(generation)) return
        try {
            val report = validateSubscriptionConsistency()
            if (report.isConsistent || report.connectedRelayCount == 0) return

            Log.w(TAG, "Nostr subscription inconsistencies detected")
            connections.forEach { (relayUrl, webSocket) ->
                if (!isCurrentAccountGeneration(generation)) return
                val currentSubs = subscriptions[relayUrl] ?: emptySet()
                val expectedSubs = activeSubscriptions.keys.filter { subId ->
                    val subInfo = activeSubscriptions[subId]
                    subInfo?.accountGeneration == generation &&
                        (subInfo.targetRelayUrls == null ||
                            subInfo.targetRelayUrls.contains(relayUrl))
                }.toSet()

                if ((expectedSubs - currentSubs).isNotEmpty() &&
                    connections[relayUrl] === webSocket
                ) {
                    Log.i(TAG, "Auto-repairing missing subscriptions")
                    restoreSubscriptionsForRelay(
                        relayUrl,
                        webSocket,
                        generation
                    )
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
        liveLocationToken: Long? = null,
        generation: Long = accountGeneration.get()
    ) {
        val connectionToken = liveLocationToken
            ?.takeIf { urlString !in nonLiveRelayUrls }
        if (!desiredConnected.get() ||
            !isCurrentAccountGeneration(generation) ||
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
            
            val started = runNetworkAction(connectionToken) {
                val webSocket = httpClient.newWebSocket(
                    request,
                    RelayWebSocketListener(
                        relayUrl = urlString,
                        liveLocationToken = connectionToken,
                        generation = generation
                    )
                )
                val existing = connections.putIfAbsent(urlString, webSocket)
                when {
                    existing != null -> webSocket.close(1000, "Duplicate connection")
                    !desiredConnected.get() ||
                        !isCurrentAccountGeneration(generation) ||
                        !isNetworkActionAllowed(connectionToken) -> {
                        connections.remove(urlString, webSocket)
                        webSocket.close(1000, "Connection no longer desired")
                    }
                }
            }
            if (!started) return
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection")
            handleConnectionCreationFailure(
                relayUrl = urlString,
                error = e,
                liveLocationToken = connectionToken,
                generation = generation
            )
        }
    }
    
    private fun sendToRelay(
        event: NostrEvent,
        webSocket: WebSocket,
        relayUrl: String,
        liveLocationToken: Long? = null,
        generation: Long = accountGeneration.get()
    ): Boolean {
        if (!isCurrentAccountGeneration(generation) ||
            !isNetworkActionAllowed(liveLocationToken) ||
            connections[relayUrl] !== webSocket
        ) return false
        return try {
            val request = NostrRequest.Event(event)
            val message = gson.toJson(request, NostrRequest::class.java)

            var success = false
            runNetworkAction(liveLocationToken) {
                if (isCurrentAccountGeneration(generation) &&
                    connections[relayUrl] === webSocket
                ) {
                    success = webSocket.send(message)
                }
            }
            if (success &&
                isCurrentAccountGeneration(generation) &&
                connections[relayUrl] === webSocket
            ) {
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
                    // Update relay stats
                    relaysList.find { it.url == relayUrl }?.let { relay ->
                        relay.messagesReceived += 1
                    }
                    updateRelaysList()

                    // CLIENT-SIDE FILTER ENFORCEMENT: Ensure this event matches the subscription's filter
                    val subscriptionInfo = activeSubscriptions[response.subscriptionId]
                        ?: return
                    if (subscriptionInfo.accountGeneration != generation ||
                        !isCurrentAccountGeneration(generation) ||
                        !isNetworkActionAllowed(subscriptionInfo.liveLocationToken)
                    ) return
                    subscriptionInfo.let { subInfo ->
                        val matches = try { subInfo.filter.matches(response.event) } catch (e: Exception) { true }
                        if (!matches) {
                            // Do NOT call deduplicator here to allow the correct subscription to process it later
                            return
                        }
                    }

                    val dispatch = EventDispatch(
                        subscriptionInfo = subscriptionInfo,
                        commitAwareHandler =
                            commitAwareMessageHandlers[response.subscriptionId],
                        ordinaryHandler =
                            messageHandlers[response.subscriptionId]
                    )
                    val commitAwareHandler = dispatch.commitAwareHandler
                    if (commitAwareHandler != null) {
                        scope.launch {
                            if (!isCurrentAccountGeneration(generation)) {
                                return@launch
                            }
                            eventDeduplicator.processEventAfterSuccess(
                                response.event
                            ) { event ->
                                isCurrentAccountGeneration(generation) &&
                                    commitAwareHandler(event)
                            }
                        }
                        return
                    }

                    val handler = dispatch.ordinaryHandler
                    if (handler == null) {
                        Log.w(TAG, "⚠️ No handler for Nostr subscription")
                        return
                    }
                    eventDeduplicator.processEvent(response.event) { event ->
                        scope.launch(Dispatchers.Main) {
                            if (isCurrentAccountGeneration(generation) &&
                                isNetworkActionAllowed(
                                    subscriptionInfo.liveLocationToken
                                )
                            ) {
                                handler(event)
                            }
                        }
                    }

                }

                is NostrResponse.EndOfStoredEvents -> {
                    // No action needed
                }

                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapGenerations.remove(
                        response.eventId,
                        generation
                    )
                    val tracker = confirmedPublishes[response.eventId]
                        ?.takeIf { it.accountGeneration == generation }
                    if (tracker != null) {
                        when {
                            isNip20ConfirmedSuccess(
                                response.accepted,
                                response.message
                            ) -> completeConfirmedPublish(
                                response.eventId,
                                tracker,
                                accepted = true
                            )

                            tracker.awaitingRelayUrls.remove(relayUrl) &&
                                tracker.awaitingRelayUrls.isEmpty() ->
                                completeConfirmedPublish(
                                    response.eventId,
                                    tracker,
                                    accepted = false
                                )
                        }
                    }
                    if (!isNip20ConfirmedSuccess(
                            response.accepted,
                            response.message
                        )
                    ) {
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
        liveLocationToken: Long? = null,
        generation: Long
    ) {
        if (!isCurrentAccountGeneration(generation)) {
            connections.remove(relayUrl, webSocket)
            return
        }
        // Ignore callbacks from intentionally closed or replaced sockets. They must not remove a
        // newer socket or schedule a reconnect after a controlled disconnect/privacy revocation.
        if (!connections.remove(relayUrl, webSocket)) return
        subscriptions.remove(relayUrl)
        handleCurrentDisconnection(
            relayUrl,
            error,
            liveLocationToken,
            generation
        )
    }

    private fun handleConnectionCreationFailure(
        relayUrl: String,
        error: Throwable,
        liveLocationToken: Long?,
        generation: Long
    ) {
        if (!desiredConnected.get() ||
            !isCurrentAccountGeneration(generation) ||
            connections.containsKey(relayUrl)
        ) return
        handleCurrentDisconnection(
            relayUrl,
            error,
            liveLocationToken,
            generation
        )
    }

    private fun handleCurrentDisconnection(
        relayUrl: String,
        error: Throwable,
        liveLocationToken: Long?,
        generation: Long
    ) {
        if (!isCurrentAccountGeneration(generation)) return
        val connectionToken = liveLocationToken
            ?.takeIf { relayUrl !in nonLiveRelayUrls }

        updateRelayStatus(relayUrl, false, error)
        confirmedPublishes.entries.toList().forEach { (eventId, tracker) ->
            if (tracker.accountGeneration == generation &&
                tracker.awaitingRelayUrls.remove(relayUrl) &&
                tracker.awaitingRelayUrls.isEmpty()
            ) {
                completeConfirmedPublish(
                    eventId,
                    tracker,
                    accepted = false
                )
            }
        }
        if (!desiredConnected.get() ||
            !isCurrentAccountGeneration(generation) ||
            !isNetworkActionAllowed(connectionToken)
        ) return

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

        reconnectJobs.remove(relayUrl)?.cancel()
        val reconnectJob = scope.launch {
            delay(backoffInterval)
            if (desiredConnected.get() &&
                isCurrentAccountGeneration(generation) &&
                isNetworkActionAllowed(connectionToken)
            ) {
                connectToRelay(relayUrl, connectionToken, generation)
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
    private fun restoreSubscriptionsForRelay(
        relayUrl: String,
        webSocket: WebSocket,
        generation: Long
    ) {
        if (!isCurrentAccountGeneration(generation) ||
            connections[relayUrl] !== webSocket
        ) return
        val subscriptionsToRestore = activeSubscriptions.values.filter { subscriptionInfo ->
            // Include subscription if it targets all relays or specifically targets this relay
            subscriptionInfo.accountGeneration == generation &&
                isNetworkActionAllowed(subscriptionInfo.liveLocationToken) &&
                (subscriptionInfo.targetRelayUrls == null ||
                    subscriptionInfo.targetRelayUrls.contains(relayUrl))
        }
        
        if (subscriptionsToRestore.isEmpty()) {
            return
        }

        subscriptionsToRestore.forEach { subscriptionInfo ->
            if (!isCurrentAccountGeneration(generation) ||
                connections[relayUrl] !== webSocket
            ) return
            try {
                val request = NostrRequest.Subscribe(subscriptionInfo.id, listOf(subscriptionInfo.filter))
                val message = gson.toJson(request, NostrRequest::class.java)

                var success = false
                runNetworkAction(subscriptionInfo.liveLocationToken) {
                    if (isCurrentAccountGeneration(generation) &&
                        connections[relayUrl] === webSocket
                    ) {
                        success = webSocket.send(message)
                    }
                    if (success && isCurrentAccountGeneration(generation)) {
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
        private val liveLocationToken: Long?,
        private val generation: Long
    ) : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!desiredConnected.get() ||
                !isCurrentAccountGeneration(generation) ||
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
            restoreSubscriptionsForRelay(relayUrl, webSocket, generation)

            // Process only events still pending for this relay, outside the queue lock.
            val queuedForRelay = messageQueue.pendingForRelay(
                relayUrl = relayUrl,
                accountGeneration = generation
            ).filter { isNetworkActionAllowed(it.liveLocationToken) }
            queuedForRelay.forEach { delivery ->
                if (delivery.accountGeneration == generation &&
                    isCurrentAccountGeneration(generation) &&
                    connections[relayUrl] === webSocket &&
                    sendToRelay(
                        delivery.event,
                        webSocket,
                        relayUrl,
                        delivery.liveLocationToken,
                        generation
                    )
                ) {
                    messageQueue.markDelivered(delivery.queueId, relayUrl)
                }
            }

            val connectionHandler = ndrConnectionAvailableHandler
            if (connectionHandler?.accountGeneration == generation &&
                isCurrentAccountGeneration(generation) &&
                connections[relayUrl] === webSocket
            ) {
                runCatching { connectionHandler.callback() }
                    .onFailure {
                        Log.w(TAG, "NDR reconnect callback failed")
                    }
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (!isCurrentAccountGeneration(generation) ||
                connections[relayUrl] !== webSocket
            ) return
            handleMessage(text, relayUrl, generation)
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            // Server-initiated close; onClosed will follow
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(
                relayUrl,
                webSocket,
                error,
                liveLocationToken,
                generation
            )
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "Nostr WebSocket failure")
            handleDisconnection(
                relayUrl,
                webSocket,
                t,
                liveLocationToken,
                generation
            )
        }
    }
}
