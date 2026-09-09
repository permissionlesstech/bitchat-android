package com.bitchat.android.wifiaware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.net.wifi.aware.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.system.OsConstants
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.DirectLinkAnnouncementPolicy
import com.bitchat.android.mesh.FragmentingPacketSender
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.toHexString
import java.io.InterruptedIOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * WifiAware mesh service - LATEST
 *
 * This is now a coordinator that orchestrates the following components:
 * - PeerManager: Peer lifecycle management
 * - FragmentManager: Message fragmentation and reassembly
 * - SecurityManager: Security, duplicate detection, encryption
 * - StoreForwardManager: Offline message caching
 * - MessageHandler: Message type processing and relay logic
 * - PacketProcessor: Incoming packet routing
 */
class WifiAwareMeshService(private val context: Context) : MeshService, TransportBridgeService.TransportLayer {

    companion object {
        private const val TAG = "WifiAwareMeshService"
        private const val MAX_TTL: UByte = 7u
        private const val SERVICE_NAME = "bitchat"
        private const val PSK = "bitchat_secret"
        // Network request / socket timeouts
        private const val NETWORK_REQUEST_TIMEOUT_MS = 30_000
        private const val ACCEPT_TIMEOUT_MS = 30_000
        private const val CLIENT_CONNECT_TIMEOUT_MS = 7_000
        private const val CLIENT_SOCKET_READY_DELAY_MS = 750L
        private const val CLIENT_SOCKET_RETRY_DELAY_MS = 750L
        private const val CLIENT_SOCKET_ATTEMPTS = 3
        private const val CLIENT_ROLE_REVERSAL_FAILURES = 3
        private const val ROLE_REVERSAL_PREFIX = "ROLE_SERVER:"
    }

    // Core crypto/services
    private val encryptionService = EncryptionService(context)

    // Peer ID must match BluetoothMeshService: first 16 hex chars of identity fingerprint (8 bytes)
    override val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val powerManager = com.bitchat.android.mesh.PowerManager.getInstance(context.applicationContext)
    private val wifiTransport = WifiAwareTransport()
    private lateinit var meshCore: MeshCore
    private lateinit var fragmentingSender: FragmentingPacketSender

    // Service-level notification manager for background (no-UI) DMs
    private val serviceNotificationManager = com.bitchat.android.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext)
    )

    // Wi-Fi Aware transport
    private val awareManager = context.getSystemService(WifiAwareManager::class.java)
    @Volatile private var wifiAwareSession: WifiAwareSession? = null
    @Volatile private var publishSession: PublishDiscoverySession? = null
    @Volatile private var subscribeSession: SubscribeDiscoverySession? = null
    private val listenerExec = Executors.newCachedThreadPool()
    @Volatile private var isActive = false
    @Volatile private var recoveryInProgress = false
    private val sessionGeneration = AtomicInteger(0)

    // Delegate
    override var delegate: WifiAwareMeshDelegate? = null
        set(value) {
            field = value
            if (::meshCore.isInitialized) {
                meshCore.delegate = value
                meshCore.refreshPeerList()
            }
        }
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    // Transport state
    private val connectionTracker = WifiAwareConnectionTracker(serviceScope, cm)
    private val ingressLinks = ConcurrentHashMap<
        String,
        IngressLinkPolicy.Link<SyncedSocket>
    >()
    private val handleToPeerId = ConcurrentHashMap<PeerHandle, String>() // discovery mapping
    private val discoveredTimestamps = ConcurrentHashMap<String, Long>() // peerID -> last seen time
    // Subscribe-session-scoped handles only. PeerHandles are session-scoped, so a handle obtained
    // from the publish session is NOT valid for subscribeSession.sendMessage(). Maintenance re-pings
    // (subscriber -> publisher) must use a handle that originated from the subscribe session.
    private val subscribeHandles = ConcurrentHashMap<String, PeerHandle>() // peerID -> latest subscribe handle
    private val publishHandles = ConcurrentHashMap<String, PeerHandle>() // peerID -> latest publish handle
    private val forcedServerPeers = ConcurrentHashMap.newKeySet<String>()
    private val forcedClientPeers = ConcurrentHashMap.newKeySet<String>()
    private val clientSocketFailures = ConcurrentHashMap<String, AtomicInteger>()
    private val lastDiscoveryActivityAt = AtomicLong(0L)
    private val lastDiscoveryRefreshAt = AtomicLong(0L)

    fun isRunning(): Boolean = isActive

    init {
        // Ensure BluetoothMeshService is initialized so we share its GossipSyncManager
        // This avoids race conditions and ensures a single gossip source/delegate
        com.bitchat.android.service.MeshServiceHolder.getOrCreate(context)
        val shared = com.bitchat.android.service.MeshServiceHolder.sharedGossipSyncManager
        encryptionService.onSessionEstablished = { peerID ->
            Log.d(TAG, "Wi-Fi Aware Noise session established with ${peerID.take(8)}")
            try {
                com.bitchat.android.services.MessageRouter
                    .tryGetInstance()
                    ?.onSessionEstablished(peerID)
            } catch (_: Exception) { }
        }
        meshCore = MeshCore(
            context = context.applicationContext,
            scope = serviceScope,
            transport = wifiTransport,
            encryptionService = encryptionService,
            myPeerID = myPeerID,
            maxTtl = MAX_TTL,
            sharedGossipManager = shared,
            gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = 500
                override fun gcsMaxBytes(): Int = 400
                override fun gcsTargetFpr(): Double = 0.01
            },
            hooks = MeshCore.Hooks(
                onMessageReceived = { message -> handleMessageReceived(message) },
                onAnnounceProcessed = { routed, _ ->
                    publishControllerDebugSnapshot()
                    routed.peerID?.let { pid ->
                        DirectLinkAnnouncementPolicy.observationFor(routed, MAX_TTL)
                            ?.let(::observeDirectIngressLink)
                        try {
                            meshCore.gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000)
                        } catch (_: Exception) { }
                    }
                },
                announcementNicknameProvider = {
                    try { com.bitchat.android.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { null }
                },
                leavePayloadProvider = {
                    (delegate?.getNickname() ?: myPeerID).toByteArray(Charsets.UTF_8)
                }
            )
        )
        fragmentingSender = FragmentingPacketSender(serviceScope, meshCore.fragmentManager, TAG)
    }

    private fun handleMessageReceived(message: BitchatMessage): Boolean {
        // Match BLE admission semantics: a private message rejected during panic or as a
        // duplicate must not create a notification after the conversation state was cleared.
        if (
            !com.bitchat.android.services.IncomingMessageAdmission
                .admitToAppState(message)
        ) return false

        if (delegate == null && message.isPrivate) {
            try {
                val senderPeerID = message.senderPeerID
                if (senderPeerID != null) {
                    val nick = try { meshCore.getPeerNickname(senderPeerID) } catch (_: Exception) { null } ?: senderPeerID
                    val preview = com.bitchat.android.ui.NotificationTextUtils.buildPrivateMessagePreview(message)
                    serviceNotificationManager.setAppBackgroundState(true)
                    serviceNotificationManager.showPrivateMessageNotification(senderPeerID, nick, preview)
                }
            } catch (_: Exception) { }
        }
        return true
    }

    /**
     * Broadcasts raw bytes to currently connected peer.
     */
    private fun broadcastRaw(bytes: ByteArray): Boolean {
        var accepted = false
        connectionTracker.peerSockets.forEach { (pid, sock) ->
            try {
                sock.write(bytes)
                accepted = true
            } catch (e: IOException) {
                Log.e(TAG, "TX: write failed to ${pid.take(8)}: ${e.message}")
            }
        }
        return accepted
    }

    // TransportLayer implementation
    override fun send(packet: RoutedPacket) {
        // Received from bridge (e.g. BLE) -> Send via Wi-Fi
        // Direct injection prevents routing loops (bridge handles source check)
        meshCore.sendFromBridge(packet)
    }

    override suspend fun sendAndReport(packet: RoutedPacket): Boolean {
        return meshCore.sendFromBridgeAndReport(packet)
    }

    override fun sendToPeer(peerID: String, packet: BitchatPacket) {
        sendPacketToPeer(peerID, packet)
    }

    /**
     * Broadcasts routed packet to currently connected peers.
     */
    private fun broadcastPacket(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        if (packet.senderID.toHexString() == myPeerID && !packet.route.isNullOrEmpty()) {
            val firstHop = packet.route!![0].toHexString()
            if (sendRoutedPacketToPeer(firstHop, routed)) {
                return true
            }
        }

        val recipientId = packet.recipientID?.toHexString()
        if (recipientId != null && !packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            if (sendRoutedPacketToPeer(recipientId, routed)) {
                return true
            }
        }

        return fragmentingSender.send(routed, "Wi-Fi Aware broadcast") { single ->
            broadcastSinglePacket(single)
        }
    }

    // Expose a public method so BLE can forward relays to Wi-Fi Aware
    fun broadcastRoutedPacket(routed: RoutedPacket) {
        broadcastPacket(routed)
    }

    /**
     * Send packet to connected peer.
     */
    private fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        return sendRoutedPacketToPeer(peerID, RoutedPacket(packet))
    }

    private fun sendRoutedPacketToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (connectionTracker.getSocketForPeer(peerID) == null) {
            return false
        }
        return fragmentingSender.send(routed, "Wi-Fi Aware peer ${peerID.take(8)}") { single ->
            sendSinglePacketToPeer(peerID, single.packet)
        }
    }

    private fun broadcastSinglePacket(routed: RoutedPacket): Boolean {
        val data = routed.packet.toBinaryData() ?: return false
        return broadcastRaw(data)
    }

    private fun sendSinglePacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val data = packet.toBinaryData() ?: return false
        val sock = connectionTracker.getSocketForPeer(peerID)
        if (sock == null) {
            Log.d(TAG, "TX: no socket for ${peerID.take(8)}")
            return false
        }
        try {
            sock.write(data)
            return true
        } catch (e: IOException) {
            Log.e(TAG, "TX: write to ${peerID.take(8)} failed: ${e.message}")
            return false
        }
    }

    

    /**
     * Starts Wi-Fi Aware services (publish + subscribe).
     *
     * Requires Wi-Fi state and location permissions. This method attaches to the
     * Aware session and initializes both the publisher (server role) and subscriber
     * (client role).
     */
    @SuppressLint("MissingPermission")
    @RequiresPermission(allOf = [
        Manifest.permission.ACCESS_WIFI_STATE,
        Manifest.permission.CHANGE_WIFI_STATE
    ])
    override fun startServices() {
        if (isActive) return
        if (!com.bitchat.android.wifiaware.WifiAwareController.enabled.value) {
            Log.i(TAG, "Wi-Fi Aware transport disabled by debug settings; not starting")
            return
        }
        val supportStatus = com.bitchat.android.wifiaware.WifiAwareSupport.evaluate(context)
        if (!supportStatus.supported) {
            Log.i(TAG, "Wi-Fi Aware unsupported on this device; not starting (${supportStatus.reason})")
            return
        }
        if (!supportStatus.available) {
            Log.i(TAG, "Wi-Fi Aware unavailable right now; not starting (${supportStatus.reason})")
            return
        }
        if (recoveryInProgress) {
            Log.i(TAG, "Wi-Fi Aware recovery cleanup still in progress; deferring start")
            return
        }
        val manager = awareManager
        if (manager == null || !manager.isAvailable) {
            Log.w(TAG, "Wi-Fi Aware manager unavailable; not starting")
            return
        }
        isActive = true
        val startTime = System.currentTimeMillis()
        lastDiscoveryActivityAt.set(startTime)
        lastDiscoveryRefreshAt.set(startTime)
        val generation = sessionGeneration.incrementAndGet()
        Log.i(TAG, "Starting Wi-Fi Aware mesh with peer ID: $myPeerID")

        manager.attach(object : AttachCallback() {
            @SuppressLint("MissingPermission")
            @RequiresPermission(allOf = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ])
            override fun onAttached(session: WifiAwareSession) {
                if (!isCurrentSession(generation)) {
                    session.close()
                    return
                }
                wifiAwareSession = session
                Log.i(TAG, "Wi-Fi Aware attached; starting publish & subscribe")

                // PUBLISH (server role)
                session.publish(
                    PublishConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .setServiceSpecificInfo(myPeerID.toByteArray())
                        .build(),
                    object : DiscoverySessionCallback() {
                        override fun onPublishStarted(pub: PublishDiscoverySession) {
                            if (!isCurrentSession(generation)) {
                                pub.close()
                                return
                            }
                            publishSession = pub
                            Log.d(TAG, "Wi-Fi Aware publish started")
                            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi-Fi Aware Publish Started")) } catch (_: Exception) {}
                        }
                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            if (!isCurrentSession(generation)) return
                            val peerId = try { String(serviceSpecificInfo) } catch (_: Exception) { "" }
                            handleToPeerId[peerHandle] = peerId
                            if (peerId.isNotBlank()) {
                                rememberDiscoveredPeer(peerId)
                                publishHandles[peerId] = peerHandle
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    offerServerPathIfAppropriate(peerId, peerHandle, "publish discovery")
                                }
                            }
                        }

                        @RequiresApi(Build.VERSION_CODES.Q)
                        override fun onMessageReceived(
                            peerHandle: PeerHandle,
                            message: ByteArray
                        ) {
                            if (!isCurrentSession(generation)) return
                            if (message.isEmpty()) return
                            val subscriberId = try { String(message) } catch (_: Exception) { "" }
                            if (subscriberId.startsWith(ROLE_REVERSAL_PREFIX)) {
                                val requesterId = subscriberId.removePrefix(ROLE_REVERSAL_PREFIX)
                                handleRoleReversalRequest(peerHandle, requesterId)
                                return
                            }
                            if (subscriberId == myPeerID) return

                            handleToPeerId[peerHandle] = subscriberId
                            if (subscriberId.isNotBlank()) {
                                rememberDiscoveredPeer(subscriberId)
                                publishHandles[subscriberId] = peerHandle
                            }
                            handleSubscriberPing(publishSession!!, peerHandle)
                        }

            override fun onSessionTerminated() {
                if (!isCurrentSession(generation)) return
                publishSession = null
                val shouldRestart = isActive && com.bitchat.android.wifiaware.WifiAwareController.enabled.value
                Log.w(TAG, "Wi-Fi Aware publish session terminated (restart=$shouldRestart)")
                handleUnexpectedStop(generation)
                if (shouldRestart) {
                    com.bitchat.android.wifiaware.WifiAwareController.restartIfStillEnabled(2000)
                }
            }
                    },
                    Handler(Looper.getMainLooper())
                )

                // SUBSCRIBE (client role)
                session.subscribe(
                    SubscribeConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .setServiceSpecificInfo(myPeerID.toByteArray(Charsets.UTF_8))
                        .build(),
                    object : DiscoverySessionCallback() {
                        override fun onSubscribeStarted(sub: SubscribeDiscoverySession) {
                            if (!isCurrentSession(generation)) {
                                sub.close()
                                return
                            }
                            subscribeSession = sub
                            Log.d(TAG, "Wi-Fi Aware subscribe started")
                            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi-Fi Aware Subscribe Started")) } catch (_: Exception) {}
                        }
                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            if (!isCurrentSession(generation)) return
                            val peerId = try { String(serviceSpecificInfo) } catch (_: Exception) { "" }
                            handleToPeerId[peerHandle] = peerId
                            // This handle came from the subscribe session, so it is valid for
                            // subscribeSession.sendMessage() (used by maintenance reconnection).
                            if (peerId.isNotBlank()) subscribeHandles[peerId] = peerHandle
                            sendSubscribePing(peerId, peerHandle, "discovery")
                            if (peerId.isNotBlank()) rememberDiscoveredPeer(peerId)
                        }

                        @RequiresApi(Build.VERSION_CODES.Q)
                        override fun onMessageReceived(
                            peerHandle: PeerHandle,
                            message: ByteArray
                        ) {
                            if (!isCurrentSession(generation)) return
                            if (message.isEmpty()) return
                            handleServerReady(peerHandle, message)
                        }

                        override fun onSessionTerminated() {
                            if (!isCurrentSession(generation)) return
                            subscribeSession = null
                            val shouldRestart = isActive && com.bitchat.android.wifiaware.WifiAwareController.enabled.value
                            Log.w(TAG, "Wi-Fi Aware subscribe session terminated (restart=$shouldRestart)")
                            handleUnexpectedStop(generation)
                            if (shouldRestart) {
                                com.bitchat.android.wifiaware.WifiAwareController.restartIfStillEnabled(2000)
                            }
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }
            override fun onAttachFailed() {
                if (!isCurrentSession(generation)) return
                Log.e(TAG, "Wi-Fi Aware attach failed")
                handleUnexpectedStop(generation)
                if (com.bitchat.android.wifiaware.WifiAwareController.enabled.value) {
                    com.bitchat.android.wifiaware.WifiAwareController.restartIfStillEnabled(3000)
                }
            }

            override fun onAwareSessionTerminated() {
                if (!isCurrentSession(generation)) return
                Log.e(TAG, "Wi-Fi Aware session terminated unexpectedly")
                wifiAwareSession = null
                val shouldRestart = com.bitchat.android.wifiaware.WifiAwareController.enabled.value
                handleUnexpectedStop(generation)
                if (shouldRestart) {
                    com.bitchat.android.wifiaware.WifiAwareController.restartIfStillEnabled(3000)
                }
            }
        }, Handler(Looper.getMainLooper()))

        // Register with cross-layer transport bridge
        TransportBridgeService.register("WIFI", this)

        meshCore.startCore()
        com.bitchat.android.service.MeshServiceHolder.startSharedGossip("WIFI")
        startPeriodicConnectionMaintenance()
        connectionTracker.start()
    }

    /**
     * Stops the Wi-Fi Aware mesh services and cleans up sockets and sessions.
     */
    override fun stopServices() {
        val wasActive = isActive
        isActive = false
        sessionGeneration.incrementAndGet()
        Log.i(TAG, "Stopping Wi-Fi Aware mesh")

        // Unregister from bridge
        TransportBridgeService.unregister("WIFI")
        com.bitchat.android.service.MeshServiceHolder.stopSharedGossip("WIFI")
        try { com.bitchat.android.services.AppStateStore.clearTransportPeers("WIFI") } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportDirectPeers("WIFI") } catch (_: Exception) { }

        if (wasActive) {
            meshCore.sendLeaveAnnouncement()
        }

        serviceScope.launch {
            delay(200)

            meshCore.stopCore()
            connectionTracker.stop() // Handles socket closing and callback unregistration

            publishSession?.close();   publishSession   = null
            subscribeSession?.close(); subscribeSession = null
            wifiAwareSession?.close(); wifiAwareSession = null

            handleToPeerId.clear()
            subscribeHandles.clear()
            publishHandles.clear()
            discoveredTimestamps.clear()
            ingressLinks.clear()

            meshCore.shutdown()

            // Tear down listener threads; this instance is discarded after a full stop.
            try { listenerExec.shutdownNow() } catch (_: Exception) { }

            com.bitchat.android.wifiaware.WifiAwareController.onServiceStopped(this@WifiAwareMeshService)
            serviceScope.cancel()
        }
    }

    private fun isCurrentSession(generation: Int): Boolean {
        return generation == sessionGeneration.get() && isActive
    }

    private fun handleUnexpectedStop(generation: Int) {
        if (generation != sessionGeneration.get()) return
        if (!isActive) {
            return
        }
        recoveryInProgress = true
        isActive = false
        TransportBridgeService.unregister("WIFI")
        com.bitchat.android.service.MeshServiceHolder.stopSharedGossip("WIFI")
        try { com.bitchat.android.services.AppStateStore.clearTransportPeers("WIFI") } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportDirectPeers("WIFI") } catch (_: Exception) { }
        val oldPublishSession = publishSession
        val oldSubscribeSession = subscribeSession
        val oldWifiAwareSession = wifiAwareSession
        serviceScope.launch {
            try {
                try { meshCore.stopCore() } catch (_: Exception) { }
                try { connectionTracker.stop() } catch (_: Exception) { }
                try { oldPublishSession?.close() } catch (_: Exception) { }
                try { oldSubscribeSession?.close() } catch (_: Exception) { }
                try { oldWifiAwareSession?.close() } catch (_: Exception) { }
                if (generation == sessionGeneration.get() && !isActive) {
                    if (publishSession === oldPublishSession) publishSession = null
                    if (subscribeSession === oldSubscribeSession) subscribeSession = null
                    if (wifiAwareSession === oldWifiAwareSession) wifiAwareSession = null
                    handleToPeerId.clear()
                    subscribeHandles.clear()
                    publishHandles.clear()
                    discoveredTimestamps.clear()
                    ingressLinks.clear()
                }
            } finally {
                recoveryInProgress = false
                // Recovery cleanup is done; nudge a restart now that startServices() will no
                // longer be deferred by recoveryInProgress. The controller coalesces requests.
                if (com.bitchat.android.wifiaware.WifiAwareController.enabled.value) {
                    com.bitchat.android.wifiaware.WifiAwareController.restartIfStillEnabled(500)
                }
            }
        }
    }

    private fun rememberDiscoveredPeer(peerId: String) {
        if (peerId.isBlank() || peerId == myPeerID) return
        val now = System.currentTimeMillis()
        discoveredTimestamps[peerId] = now
        lastDiscoveryActivityAt.set(now)
        publishControllerDebugSnapshot()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun offerServerPathIfAppropriate(peerId: String, peerHandle: PeerHandle, reason: String) {
        val pubSession = publishSession ?: return
        if (peerId.isBlank() || peerId == myPeerID || !amIServerFor(peerId)) return
        if (!connectionTracker.isConnectionAttemptAllowed(peerId)) return

        handleSubscriberPing(pubSession, peerHandle)
    }

    private fun refreshDiscoverySessions(reason: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!isActive || recoveryInProgress) return false
        if (!com.bitchat.android.wifiaware.WifiAwareController.enabled.value) return false

        val lastRefresh = lastDiscoveryRefreshAt.get()
        val minRefreshMs = powerManager.profile.value.wifiAware.discoverySessionRefreshMinMs
        if ((now - lastRefresh) < minRefreshMs) return false
        if (!lastDiscoveryRefreshAt.compareAndSet(lastRefresh, now)) return false

        Log.i(TAG, "Refreshing Wi-Fi Aware discovery sessions ($reason)")
        handleUnexpectedStop(sessionGeneration.get())
        return true
    }

    /**
     * Periodic active maintenance: retries connections to discovered but unconnected peers.
     */
    private fun startPeriodicConnectionMaintenance() {
        serviceScope.launch {
            while (isActive) {
                try {
                    val schedule = powerManager.profile.value.wifiAware
                    delay(schedule.connectionMaintenanceMs)
                    if (!isActive) break

                    val now = System.currentTimeMillis()

                    // 0. Prune stale discovery entries. PeerHandles become invalid when the
                    // discovery sessions restart, so we must not keep pinging old handles forever.
                    val staleIds = discoveredTimestamps.filter { (id, ts) ->
                        (now - ts) >= schedule.discoveryStaleMs && !connectionTracker.isConnected(id)
                    }.keys.toSet()
                    if (staleIds.isNotEmpty()) {
                        staleIds.forEach { discoveredTimestamps.remove(it) }
                        handleToPeerId.entries.removeIf { it.value in staleIds }
                        staleIds.forEach { subscribeHandles.remove(it) }
                        staleIds.forEach { publishHandles.remove(it) }
                        publishControllerDebugSnapshot()
                    }

                    // 1. Identify peers that are discovered (recently seen) but not currently connected
                    val recentDiscovered = discoveredTimestamps.filter { (id, ts) ->
                        (now - ts) < schedule.discoveryStaleMs
                    }.keys

                    // 2. Filter out those who are already connected
                    val disconnectedPeers = recentDiscovered.filter { peerId ->
                        !connectionTracker.isConnected(peerId)
                    }

                    // 3. Attempt reconnection. Aware discovery is not always symmetrical:
                    // subscribe handles can disappear while publish handles still see the peer.
                    var attemptedReconnect = false
                    var missingUsableHandle = false
                    for (peerId in disconnectedPeers) {
                        if (amIServerFor(peerId)) {
                            val handle = publishHandles[peerId]
                            if (handle == null) {
                                missingUsableHandle = true
                                continue
                            }
                            if (!connectionTracker.isConnectionAttemptAllowed(peerId)) continue
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                offerServerPathIfAppropriate(peerId, handle, "maintenance")
                                attemptedReconnect = true
                            }
                            continue
                        }

                        // Use a subscribe-session-scoped handle. A publish-scoped handle would be
                        // invalid for subscribeSession.sendMessage() and silently fail.
                        val handle = subscribeHandles[peerId]
                        if (handle == null) {
                            missingUsableHandle = true
                            continue
                        }

                        // Check tracker policy
                        if (!connectionTracker.isConnectionAttemptAllowed(peerId)) continue

                        sendSubscribePing(peerId, handle, "maintenance")
                        attemptedReconnect = true
                    }

                    val noActiveDataPath = connectionTracker.getConnectionCount() == 0 &&
                        !connectionTracker.hasPendingDataPathRequest()
                    if (noActiveDataPath) {
                        val idleFor = now - lastDiscoveryActivityAt.get()
                        when {
                            disconnectedPeers.isNotEmpty() && missingUsableHandle && !attemptedReconnect -> {
                                refreshDiscoverySessions("missing peer handle", now)
                            }
                            recentDiscovered.isEmpty() && idleFor >= schedule.discoveryIdleRefreshMs -> {
                                refreshDiscoverySessions("idle discovery", now)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error in connection maintenance: ${e.message}")
                }
            }
        }
    }

    private fun sendSubscribePing(peerId: String, peerHandle: PeerHandle, reason: String) {
        if (peerId.isBlank()) return
        val msgId = (System.nanoTime() and 0x7fffffff).toInt()
        try {
            subscribeSession?.sendMessage(peerHandle, msgId, myPeerID.toByteArray())
        } catch (e: Exception) {
            Log.d(TAG, "Failed to send $reason ping to ${peerId.take(8)}: ${e.message}")
        }
    }

    private fun requestRoleReversal(peerId: String, allowForcedClientOverride: Boolean = false) {
        if (peerId.isBlank()) return
        if (forcedClientPeers.contains(peerId) && !allowForcedClientOverride) return
        forcedServerPeers.add(peerId)
        forcedClientPeers.remove(peerId)

        val handle = subscribeHandles[peerId]
        if (handle == null) {
            Log.d(TAG, "CLIENT: role reversal queued for ${peerId.take(8)} until subscribe handle is available")
            return
        }

        val msgId = (System.nanoTime() and 0x7fffffff).toInt()
        val payload = "$ROLE_REVERSAL_PREFIX$myPeerID".toByteArray()
        try {
            subscribeSession?.sendMessage(handle, msgId, payload)
            Log.d(TAG, "CLIENT: requested role reversal with ${peerId.take(8)}")
        } catch (e: Exception) {
            Log.w(TAG, "CLIENT: failed to request role reversal with ${peerId.take(8)}: ${e.message}")
        }
    }

    private fun shouldRequestRoleReversalAfterClientFailure(peerId: String): Boolean {
        val failures = clientSocketFailures
            .computeIfAbsent(peerId) { AtomicInteger(0) }
            .incrementAndGet()
        val shouldReverse = failures >= CLIENT_ROLE_REVERSAL_FAILURES
        if (shouldReverse) {
            clientSocketFailures.remove(peerId)
            Log.d(TAG, "CLIENT: ${peerId.take(8)} failed $failures client socket attempts; requesting role reversal")
        }
        return shouldReverse
    }

    private fun handleRoleReversalRequest(peerHandle: PeerHandle, requesterId: String) {
        if (requesterId.isBlank() || requesterId == myPeerID) return
        handleToPeerId[peerHandle] = requesterId
        discoveredTimestamps[requesterId] = System.currentTimeMillis()
        forcedClientPeers.add(requesterId)
        forcedServerPeers.remove(requesterId)
        Log.i(TAG, "Role reversal requested by ${requesterId.take(8)}; switching to client role")

        subscribeHandles[requesterId]?.let { handle ->
            sendSubscribePing(requesterId, handle, "role-reversal")
        }
    }

    /**
     * Handles subscriber ping: spawns a server socket and responds with connection info.
     *
     * @param pubSession The current publish discovery session
     * @param peerHandle The handle for the peer that pinged us
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleSubscriberPing(
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        val peerId = handleToPeerId[peerHandle] ?: return
        if (!amIServerFor(peerId)) return

        if (connectionTracker.isConnected(peerId)) {
            return
        }
        if (connectionTracker.hasOpenServerSocket(peerId)) {
            return
        }
        if (connectionTracker.hasPendingDataPathRequest(peerId)) {
            return
        }
        if (!connectionTracker.addPendingConnection(peerId)) {
            return
        }

        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            val anyIpv6 = Inet6Address.getByAddress(ByteArray(16))
            ss.bind(java.net.InetSocketAddress(anyIpv6, 0))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind server socket", e)
            handleNetworkFailure(peerId)
            return
        }

        connectionTracker.addServerSocket(peerId, ss)
        val port = ss.localPort

        val spec = WifiAwareNetworkSpecifier.Builder(pubSession, peerHandle)
            .setPskPassphrase(PSK)
            .setPort(port)
            .setTransportProtocol(OsConstants.IPPROTO_TCP)
            .build()
        // Default capabilities include NET_CAPABILITY_NOT_VPN.
        // Keeping defaults for hardware interface handle acquisition compatibility with global VPNs.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var activeSocket: SyncedSocket? = null
            private val acceptStarted = AtomicBoolean(false)

            override fun onAvailable(network: Network) {
                // Only accept once per network request
                if (!acceptStarted.compareAndSet(false, true)) return
                // Offload the blocking accept() off the callback thread so we never stall
                // the (main-thread) ConnectivityManager callback dispatcher.
                listenerExec.execute {
                    try {
                        try { ss.soTimeout = ACCEPT_TIMEOUT_MS } catch (_: Exception) {}
                        val client = ss.accept()
                        try { network.bindSocket(client) } catch (e: Exception) { Log.w(TAG, "Server bindSocket EPERM: ${e.message}") }
                        client.keepAlive = true
                        Log.i(TAG, "Connected to ${peerId.take(8)} (server)")
                        val synced = SyncedSocket(client)
                        activeSocket = synced
                        connectionTracker.onClientConnected(peerId, synced)
                        publishControllerDebugSnapshot()
                        // We only ever accept a single data socket per server request. Close the
                        // listening ServerSocket now so it can't block a future re-serve (its
                        // presence makes hasOpenServerSocket() true for the life of the process)
                        // and so we free the fd/port promptly.
                        connectionTracker.closeServerSocket(peerId)
                        try { meshCore.addOrUpdatePeer(peerId, peerId) } catch (_: Exception) {}
                        listenerExec.execute { listenToPeer(synced, peerId) }
                        handleSubscriberKeepAlive(synced, peerId, pubSession, peerHandle)

                        // Kick off Noise handshake for this logical peer
                        if (myPeerID < peerId) {
                            meshCore.initiateNoiseHandshake(peerId)
                        }
                        // Ensure fast presence even before handshake settles
                        serviceScope.launch { delay(150); sendBroadcastAnnounce() }
                    } catch (ioe: IOException) {
                        if (!ss.isClosed && isActive) {
                            Log.e(TAG, "SERVER: accept failed for ${peerId.take(8)}", ioe)
                            handleNetworkFailure(peerId)
                        }
                    }
                }
            }

            override fun onUnavailable() {
                Log.e(TAG, "SERVER: failed to acquire Aware network for ${peerId.take(8)}")
                handleNetworkFailure(peerId)
            }

            override fun onLost(network: Network) {
                handlePeerDisconnection(peerId, activeSocket)
                Log.i(TAG, "Disconnected from ${peerId.take(8)} (server: network lost)")
            }
        }

        connectionTracker.addNetworkCallback(peerId, cb)
        try {
            // use requestNetwork with a timeout to trigger onUnavailable if it fails
            cm.requestNetwork(req, cb, NETWORK_REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "SERVER: ConnectivityManager.requestNetwork threw exception", e)
            connectionTracker.disconnect(peerId)
        }

        val readyId = (System.nanoTime() and 0x7fffffff).toInt()
        val readyPayload = buildServerReadyPayload(port)
        Handler(Looper.getMainLooper()).post {
            try {
                pubSession.sendMessage(peerHandle, readyId, readyPayload)
            } catch (e: Exception) {
                Log.e(TAG, "PUBLISH: failed to send server-ready to ${peerId.take(8)}", e)
            }
        }
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages to maintain a subscriber connection.
     *
     * @param client Connected client socket
     * @param peerId ID of the connected peer
     */
    private fun handleSubscriberKeepAlive(
        client: SyncedSocket,
        peerId: String,
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        startProfiledKeepAlive(client, peerId) { msgId ->
            pubSession.sendMessage(peerHandle, msgId, ByteArray(0))
        }
    }

    private fun connectAwareClientSocket(
        network: Network,
        scopedAddr: Inet6Address,
        port: Int,
        peerId: String
    ): Socket {
        var lastFailure: IOException? = null
        for (attempt in 1..CLIENT_SOCKET_ATTEMPTS) {
            val delayMs = if (attempt == 1) CLIENT_SOCKET_READY_DELAY_MS else CLIENT_SOCKET_RETRY_DELAY_MS
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException("Interrupted before Wi-Fi Aware socket connect")
                }
            }

            var sock: Socket? = null
            try {
                sock = network.socketFactory.createSocket()
                sock.tcpNoDelay = true
                sock.keepAlive = true
                sock.connect(java.net.InetSocketAddress(scopedAddr, port), CLIENT_CONNECT_TIMEOUT_MS)
                return sock
            } catch (e: IOException) {
                lastFailure = e
                try { sock?.close() } catch (_: Exception) { }
                if (attempt < CLIENT_SOCKET_ATTEMPTS) {
                    Log.d(TAG, "CLIENT: socket attempt $attempt/$CLIENT_SOCKET_ATTEMPTS failed for ${peerId.take(8)}: ${e.message}")
                }
            }
        }

        throw lastFailure ?: IOException("Wi-Fi Aware socket connect failed without an exception")
    }

    private fun buildServerReadyPayload(port: Int): ByteArray {
        val peerIdBytes = myPeerID.toByteArray(Charsets.UTF_8)
        return ByteBuffer.allocate(Int.SIZE_BYTES + peerIdBytes.size)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(port)
            .put(peerIdBytes)
            .array()
    }

    private fun peerIdFromServerReadyPayload(payload: ByteArray): String? {
        if (payload.size <= Int.SIZE_BYTES) return null
        val peerId = try {
            String(payload.copyOfRange(Int.SIZE_BYTES, payload.size), Charsets.UTF_8).trim()
        } catch (_: Exception) {
            return null
        }
        return peerId.takeIf { id ->
            id.length == 16 && id.all { ch -> ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F' }
        }?.lowercase()
    }

    private fun resolveServerReadyPeerId(peerHandle: PeerHandle, payload: ByteArray): String? {
        val advertisedPeerId = peerIdFromServerReadyPayload(payload)
        val mappedPeerId = handleToPeerId[peerHandle]?.takeIf { it.isNotBlank() }
        val peerId = advertisedPeerId ?: mappedPeerId
        if (peerId == null) {
            return null
        }

        handleToPeerId[peerHandle] = peerId
        subscribeHandles[peerId] = peerHandle
        rememberDiscoveredPeer(peerId)
        return peerId
    }

    /**
     * Handles a "server ready" message from a publishing peer and initiates a client connection.
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun handleServerReady(
        peerHandle: PeerHandle,
        payload: ByteArray
    ) {
        if (payload.size < Int.SIZE_BYTES) {
            return
        }

        val peerId = resolveServerReadyPeerId(peerHandle, payload) ?: return
        if (peerId == myPeerID) return
        if (amIServerFor(peerId)) return
        if (connectionTracker.peerSockets.containsKey(peerId)) {
            return
        }
        val cancelledServerOffers = connectionTracker.cancelPendingServerDataPaths(peerId)
        if (cancelledServerOffers.isNotEmpty()) {
            val cancelled = cancelledServerOffers.joinToString(", ") { it.take(8) }
            Log.d(TAG, "CLIENT: preempted pending server offer(s) for $cancelled to connect ${peerId.take(8)}")
        }
        if (connectionTracker.hasPendingDataPathRequest(peerId)) {
            return
        }
        if (!connectionTracker.addPendingConnection(peerId)) {
            return
        }

        val port = ByteBuffer.wrap(payload, 0, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int

        val subSession = subscribeSession ?: run {
            Log.d(TAG, "CLIENT: subscribe session missing for server-ready from ${peerId.take(8)}")
            connectionTracker.removePendingConnection(peerId)
            return
        }
        val spec = WifiAwareNetworkSpecifier.Builder(subSession, peerHandle)
            .setPskPassphrase(PSK)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            @Volatile private var activeSocket: SyncedSocket? = null
            private val connectStarted = AtomicBoolean(false)

            override fun onAvailable(network: Network) {
                // Do not bind process for Aware; use per-socket binding instead
            }

            override fun onUnavailable() {
                Log.e(TAG, "CLIENT: failed to acquire Aware network for ${peerId.take(8)}")
                if (shouldRequestRoleReversalAfterClientFailure(peerId)) {
                    requestRoleReversal(peerId, allowForcedClientOverride = true)
                }
                handleNetworkFailure(peerId)
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                if (connectionTracker.peerSockets.containsKey(peerId)) return
                val info = (nc.transportInfo as? WifiAwareNetworkInfo) ?: return
                val addr = info.peerIpv6Addr as? Inet6Address ?: return
                val connectPort = if (info.port > 0) info.port else port
                // onCapabilitiesChanged can fire multiple times; only connect once
                if (!connectStarted.compareAndSet(false, true)) return

                val lp = cm.getLinkProperties(network)
                val iface = lp?.interfaceName

                // Offload the blocking connect() off the callback thread.
                listenerExec.execute {
                    try {
                        // Use scoped IPv6 if interface name is available
                        val scopedAddr = if (iface != null && addr.scopeId == 0) {
                            try {
                                Inet6Address.getByAddress(null, addr.address, java.net.NetworkInterface.getByName(iface))
                            } catch (e: Exception) {
                                addr
                            }
                        } else {
                            addr
                        }

                        val sock = connectAwareClientSocket(network, scopedAddr, connectPort, peerId)
                        Log.i(TAG, "Connected to ${peerId.take(8)} (client)")

                        val synced = SyncedSocket(sock)
                        activeSocket = synced
                        connectionTracker.onClientConnected(peerId, synced)
                        publishControllerDebugSnapshot()
                        clientSocketFailures.remove(peerId)
                        try { meshCore.addOrUpdatePeer(peerId, peerId) } catch (_: Exception) {}
                        listenerExec.execute { listenToPeer(synced, peerId) }
                        handleServerKeepAlive(synced, peerId, peerHandle)

                        // Kick off Noise handshake for this logical peer
                        if (myPeerID < peerId) {
                            meshCore.initiateNoiseHandshake(peerId)
                        }
                        // Ensure fast presence even before handshake settles
                        serviceScope.launch { delay(150); sendBroadcastAnnounce() }
                    } catch (ioe: IOException) {
                        Log.e(TAG, "CLIENT: socket connect failed to ${peerId.take(8)}", ioe)
                        if (shouldRequestRoleReversalAfterClientFailure(peerId)) {
                            requestRoleReversal(peerId, allowForcedClientOverride = true)
                        }
                        handleNetworkFailure(peerId)
                    }
                }
            }
            override fun onLost(network: Network) {
                handlePeerDisconnection(peerId, activeSocket)
                Log.i(TAG, "Disconnected from ${peerId.take(8)} (client: network lost)")
            }
        }

        connectionTracker.addNetworkCallback(peerId, cb)
        try {
            cm.requestNetwork(req, cb, NETWORK_REQUEST_TIMEOUT_MS)
        } catch (e: Exception) {
            Log.e(TAG, "CLIENT: ConnectivityManager.requestNetwork threw exception", e)
            connectionTracker.disconnect(peerId)
        }
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages for server connections.
     */
    private fun handleServerKeepAlive(
        sock: SyncedSocket,
        peerId: String,
        peerHandle: PeerHandle
    ) {
        startProfiledKeepAlive(sock, peerId) { msgId ->
            subscribeSession?.sendMessage(peerHandle, msgId, ByteArray(0))
        }
    }

    /**
     * One coroutine per peer schedules both TCP and discovery deadlines. This avoids two
     * independently waking jobs while still adapting after every send to the latest profile.
     */
    private fun startProfiledKeepAlive(
        socket: SyncedSocket,
        peerId: String,
        sendDiscovery: (Int) -> Unit
    ) {
        serviceScope.launch {
            var discoveryMessageId = 0
            var nextTcpAt = 0L
            var nextDiscoveryAt = 0L
            while (connectionTracker.isConnected(peerId)) {
                val now = android.os.SystemClock.elapsedRealtime()
                val schedule = powerManager.profile.value.wifiAware

                if (now >= nextTcpAt) {
                    try {
                        socket.write(ByteArray(0))
                    } catch (_: IOException) {
                        handlePeerDisconnection(peerId, socket)
                        break
                    }
                    nextTcpAt = now + schedule.tcpKeepAliveMs
                }

                if (now >= nextDiscoveryAt) {
                    try {
                        sendDiscovery(discoveryMessageId++)
                    } catch (_: Exception) {
                        // The TCP data path remains usable; discovery maintenance will recover its
                        // session independently without dropping this idle connection.
                    }
                    nextDiscoveryAt = now + schedule.discoveryKeepAliveMs
                }

                val nextWakeAt = minOf(nextTcpAt, nextDiscoveryAt)
                delay((nextWakeAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(250L))
            }
        }
    }

    /**
     * Determines whether this device should act as the server in a given peer relationship.
     */
    private fun amIServerFor(peerId: String): Boolean = when {
        forcedClientPeers.contains(peerId) -> false
        forcedServerPeers.contains(peerId) -> true
        else -> myPeerID < peerId
    }

    /**
     * Records a validated, non-relayed ANNOUNCE as a direct route. The exact-link check keeps stale
     * socket readers from rebinding a replacement connection, but Noise remains peer-scoped and is
     * not restarted or coupled to this routing observation.
     */
    private fun observeDirectIngressLink(
        observation: DirectLinkAnnouncementPolicy.Observation
    ) {
        val link = IngressLinkPolicy.resolve(
            ingressLinkID = observation.ingressLinkID,
            relayAddress = observation.relayAddress,
            links = ingressLinks,
            currentTransportForRelay = connectionTracker::getSocketForPeer
        ) ?: run {
            Log.d(
                TAG,
                "Ignoring direct ANNOUNCE for ${observation.peerID.take(8)}: ingress link is stale"
            )
            return
        }

        val provisionalPeerId = link.relayAddress
        val existingCanonical = connectionTracker.canonicalPeerId(provisionalPeerId)
        if (existingCanonical == observation.peerID) {
            try { meshCore.setDirectConnection(observation.peerID, true) } catch (_: Exception) { }
            return
        }
        if (existingCanonical != provisionalPeerId) {
            Log.w(
                TAG,
                "Refusing Wi-Fi route change ${existingCanonical.take(8)} -> ${observation.peerID.take(8)} on existing alias"
            )
            return
        }

        if (!connectionTracker.rebindPeerIdIfCurrent(
                provisionalPeerId,
                observation.peerID,
                link.transport
            )
        ) {
            Log.d(
                TAG,
                "Ignoring direct ANNOUNCE for ${observation.peerID.take(8)}: provisional socket changed"
            )
            return
        }
        handleToPeerId.forEach { (handle, peerId) ->
            if (peerId == provisionalPeerId) handleToPeerId[handle] = observation.peerID
        }
        subscribeHandles.remove(provisionalPeerId)?.let { subscribeHandles[observation.peerID] = it }
        publishHandles.remove(provisionalPeerId)?.let { publishHandles[observation.peerID] = it }
        val discoveredAt = discoveredTimestamps.remove(provisionalPeerId) ?: System.currentTimeMillis()
        discoveredTimestamps[observation.peerID] = discoveredAt

        try { meshCore.setDirectConnection(provisionalPeerId, false) } catch (_: Exception) { }
        try { meshCore.removePeer(provisionalPeerId) } catch (_: Exception) { }
        try {
            meshCore.addOrUpdatePeer(
                observation.peerID,
                meshCore.getPeerNickname(observation.peerID) ?: observation.peerID
            )
        } catch (_: Exception) { }
        try { meshCore.setDirectConnection(observation.peerID, true) } catch (_: Exception) { }
        try {
            meshCore.gossipSyncManager.scheduleInitialSyncToPeer(observation.peerID, 1_000)
        } catch (_: Exception) { }
        publishControllerDebugSnapshot()

        Log.i(
            TAG,
            "Observed direct Wi-Fi route ${provisionalPeerId.take(8)} -> ${observation.peerID.take(8)}"
        )
    }

    /**
     * Listens for incoming packets from a connected peer and dispatches them through
     * the packet processor.
     *
     * @param socket Socket connected to the peer
     * @param initialLogicalPeerId Temporary identifier before peer ID resolution
     */
    private fun listenToPeer(socket: SyncedSocket, initialLogicalPeerId: String) {
        val logicalPeerId = initialLogicalPeerId
        val ingressLinkID = UUID.randomUUID().toString()
        val ingressLink = IngressLinkPolicy.Link(logicalPeerId, socket)
        ingressLinks[ingressLinkID] = ingressLink
        while (isActive) {
            val raw = socket.read() ?: break
            
            if (raw.isEmpty()) {
                // Keep-alive (0 length frame)
                continue
            }

            val pkt = BitchatPacket.fromBinaryData(raw) ?: continue

            val senderPeerHex = pkt.senderID?.toHexString()?.take(16) ?: continue

            if (pkt.type == MessageType.ANNOUNCE.value && pkt.ttl >= MAX_TTL && senderPeerHex != logicalPeerId) {
                // Rebinding happens only after MeshCore validates and accepts this ANNOUNCE.
                Log.d(
                    TAG,
                    "RX: Wi-Fi peer observation ${logicalPeerId.take(8)} -> ${senderPeerHex.take(8)} pending ANNOUNCE validation"
                )
            }

            // Route the packet:
            // - peerID = Originator (who signed it)
            // - relayAddress = Neighbor (who sent it to us over this socket)
            meshCore.processIncoming(pkt, senderPeerHex, logicalPeerId, ingressLinkID)
        }

        ingressLinks.remove(ingressLinkID, ingressLink)
        
        // Breaking out of the loop means the socket is dead or service is stopping.
        Log.i(TAG, "Disconnected from ${logicalPeerId.take(8)} (socket closed)")
        handlePeerDisconnection(logicalPeerId, socket)
        socket.close()
    }

    private fun handleNetworkFailure(peerId: String) {
         serviceScope.launch {
            if (!connectionTracker.isConnected(peerId)) {
                val canonicalPeerId = connectionTracker.canonicalPeerId(peerId)
                connectionTracker.disconnect(peerId)
                meshCore.removePeer(canonicalPeerId)
                if (canonicalPeerId != peerId) {
                    meshCore.removePeer(peerId)
                }
            }
        }
    }

    private fun handlePeerDisconnection(initialId: String, socket: SyncedSocket? = null) {
        serviceScope.launch {
            // Check if this socket is the current active one before nuking the session
            val currentSocket = connectionTracker.getSocketForPeer(initialId)
            val canonicalPeerId = connectionTracker.canonicalPeerId(initialId)
            if (currentSocket === socket) {
                connectionTracker.disconnect(initialId)
                meshCore.removePeer(canonicalPeerId)
                if (canonicalPeerId != initialId) {
                    meshCore.removePeer(initialId)
                }
            } else if (socket == null && currentSocket == null) {
                // Fallback: If we don't have a specific socket context but we are already disconnected, ensure cleanup
                connectionTracker.disconnect(initialId)
                meshCore.removePeer(canonicalPeerId)
                if (canonicalPeerId != initialId) {
                    meshCore.removePeer(initialId)
                }
            }
            // Else: socket replaced or inactive; do not remove peer/session, as a new socket has likely taken over
            publishControllerDebugSnapshot()
        }
    }

    /**
     * Sends a broadcast message to all peers.
     * @param content   Text content of the message
     * @param mentions  Optional list of mentioned peer IDs
     * @param channel   Optional channel name
     */
    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        meshCore.sendMessage(content, mentions, channel)
    }

    /**
     * Sends a private encrypted message to a specific peer.
     *
     * @param content            The message text
     * @param recipientPeerID    Destination peer ID
     * @param recipientNickname  Recipient nickname
     * @param messageID          Optional message ID (UUID if null)
     */
    override fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String?) {
        meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    }

    /**
     * Sends a read receipt for a specific message to the given peer over an established
     * Noise session. If no session exists, this will log an error.
     *
     * @param messageID        The ID of the message that was read.
     * @param recipientPeerID  The peer to notify.
     * @param readerNickname   Nickname of the reader (may be shown by the receiver).
     */
    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        meshCore.sendReadReceipt(messageID, recipientPeerID, readerNickname)
    }

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        meshCore.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
    }

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        meshCore.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
    }

    /**
     * Broadcasts a file (TLV payload) to all peers. Uses protocol version 2 to support
     * large payloads and generates a deterministic transferId (sha256 of payload) for UI/state.
     *
     * @param file Encoded metadata and chunks descriptor of the file to send.
     */
    override fun sendFileBroadcast(file: BitchatFilePacket) {
        meshCore.sendFileBroadcast(file)
    }

    /**
     * Sends a file privately to a specific peer. If no Noise session is established,
     * a handshake will be initiated and the send is deferred/aborted for now.
     *
     * @param recipientPeerID Target peer.
     * @param file            Encoded metadata and chunks descriptor of the file to send.
     */
    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        meshCore.sendFilePrivate(recipientPeerID, file)
    }

    override fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        meshCore.sendVoiceFrame(recipientPeerID, payload)
    }

    override fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): com.bitchat.android.mesh.PrivateMediaPreparation = meshCore.prepareFilePrivate(
        recipientPeerID,
        file,
        transferId,
        allowLegacyFallback
    )

    /**
     * Attempts to cancel an in-flight file transfer identified by its transferId.
     *
     * @param transferId Deterministic id (usually sha256 of the file TLV).
     * @return true if a transfer with this id was found and cancellation was scheduled, false otherwise.
     */
    override fun cancelFileTransfer(transferId: String): Boolean {
        return meshCore.cancelFileTransfer(transferId)
    }

    /**
     * Broadcasts an ANNOUNCE packet to the entire mesh.
     */
    override fun sendBroadcastAnnounce() {
        meshCore.sendBroadcastAnnounce()
    }

    /**
     * Sends an ANNOUNCE packet to a specific peer.
     */
    override fun sendAnnouncementToPeer(peerID: String) {
        meshCore.sendAnnouncementToPeer(peerID)
    }

    /** @return Mapping of peer IDs to nicknames. */
    override fun getPeerNicknames(): Map<String, String> = meshCore.getPeerNicknames()

    /** @return Mapping of peer IDs to RSSI values. */
    override fun getPeerRSSI(): Map<String, Int> = meshCore.getPeerRSSI()

    /** @return current active peer count for status surfaces. */
    override fun getActivePeerCount(): Int = meshCore.getActivePeerCount()

    /**
     * @return true if a Noise session with the peer is fully established.
     */
    override fun hasEstablishedSession(peerID: String) = meshCore.hasEstablishedSession(peerID)

    /**
     * @return a human-readable Noise session state for the given peer (implementation-defined).
     */
    override fun getSessionState(peerID: String) = meshCore.getSessionState(peerID)

    /**
     * Triggers a Noise handshake with the given peer. Safe to call repeatedly; no-op if already handshaking/established.
     */
    override fun initiateNoiseHandshake(peerID: String) = meshCore.initiateNoiseHandshake(peerID)

    /**
     * @return the stored public-key fingerprint (hex) for a peer, if known.
     */
    override fun getPeerFingerprint(peerID: String): String? = meshCore.getPeerFingerprint(peerID)

    /**
     * Retrieves the full profile for a peer, including keys and verification state, if available.
     */
    override fun getPeerInfo(peerID: String): PeerInfo? = meshCore.getPeerInfo(peerID)

    /**
     * Updates local metadata for a peer and returns whether the change was applied.
     *
     * @param peerID           Target peer id.
     * @param nickname         Display name.
     * @param noisePublicKey   Peer’s Noise static public key.
     * @param signingPublicKey Peer’s Ed25519 signing public key.
     * @param isVerified       Whether this identity is verified by the user.
     * @return true if the record was updated or created, false otherwise.
     */
    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean = meshCore.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)

    /**
     * @return the local device’s long-term identity fingerprint (hex).
     */
    override fun getIdentityFingerprint(): String = meshCore.getIdentityFingerprint()

    override fun getStaticNoisePublicKey(): ByteArray? = meshCore.getStaticNoisePublicKey()

    /**
     * @return true if the UI should show an “encrypted” indicator for this peer.
     */
    override fun shouldShowEncryptionIcon(peerID: String) = meshCore.shouldShowEncryptionIcon(peerID)

    /**
     * @return a snapshot list of peers with established Noise sessions.
     */
    override fun getEncryptedPeers(): List<String> = meshCore.getEncryptedPeers()

    /**
     * @return the current IPv4/IPv6 address of a connected peer, if any.
     * Prefers the scoped IPv6 address format.
     */
    override fun getDeviceAddressForPeer(peerID: String): String? =
        meshCore.getDeviceAddressForPeer(peerID)

    /**
     * Helper to resolve a scoped IPv6 address from a socket for UI display.
     */
    private fun resolveScopedAddress(sock: Socket): String? {
        val addr = sock.inetAddress as? Inet6Address ?: return sock.inetAddress?.hostAddress
        if (addr.scopeId != 0 || addr.isLoopbackAddress) return addr.hostAddress
        
        // If address has no scope but we are on Aware (Link-Local fe80), attempt interface resolution
        val iface = try {
            val lp = cm.getLinkProperties(cm.activeNetwork)
            lp?.interfaceName ?: "aware0"
        } catch (_: Exception) { "aware0" }
        
        return "${addr.hostAddress}%$iface"
    }

    /**
     * @return a mapping of peerID → connected device IP address for all active sockets.
     * Results are formatted as scoped addresses if applicable.
     */
    override fun getDeviceAddressToPeerMapping(): Map<String, String> =
        meshCore.getDeviceAddressToPeerMapping()

    /**
     * @return map of peer ID to nickname, bridged for UI warning fix.
     */
    fun getPeerNicknamesMap(): Map<String, String?> = meshCore.getPeerNicknames()

    /** Returns recently discovered peer IDs via Aware discovery (may not be connected). */
    fun getDiscoveredPeerIds(): Set<String> =
        (handleToPeerId.values + discoveredTimestamps.keys).filter { it.isNotBlank() }.toSet()

    private fun publishControllerDebugSnapshot() {
        try {
            com.bitchat.android.wifiaware.WifiAwareController.publishDebugSnapshot(
                connected = getDeviceAddressToPeerMapping(),
                known = getPeerNicknames(),
                discovered = getDiscoveredPeerIds()
            )
        } catch (_: Exception) { }
    }

    /**
     * Utility for logs/UI: pretty-prints one peer-to-address mapping per line.
     */
    override fun printDeviceAddressesForPeers(): String =
        getDeviceAddressToPeerMapping().entries.joinToString("\n") { "${it.key} -> ${it.value}" }

    /**
     * @return A detailed string containing the debug status of all mesh components.
     */
    override fun getDebugStatus(): String {
        return meshCore.getDebugStatus(
            transportInfo = connectionTracker.getDebugInfo(),
            deviceMap = getDeviceAddressToPeerMapping(),
            extraLines = listOf("Peers: ${connectionTracker.peerSockets.keys}"),
            title = "Wi-Fi Aware Mesh Debug Status"
        )
    }

    override fun clearAllInternalData() {
        meshCore.clearAllInternalData()
    }

    override fun clearAllEncryptionData() {
        meshCore.clearAllEncryptionData()
    }

    /** Utility extension to safely close server sockets. */
    private fun ServerSocket.closeQuietly() = try { close() } catch (_: Exception) {}


    private inner class WifiAwareTransport : MeshTransport {
        override val id: String = "WIFI"

        override fun broadcastPacket(routed: RoutedPacket): Boolean =
            this@WifiAwareMeshService.broadcastPacket(routed)
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
            return this@WifiAwareMeshService.sendPacketToPeer(peerID, packet)
        }
        override fun sendPacketToLink(
            relayAddress: String,
            ingressLinkID: String,
            packet: BitchatPacket
        ): Boolean {
            val link = IngressLinkPolicy.resolve(
                ingressLinkID = ingressLinkID,
                relayAddress = relayAddress,
                links = ingressLinks,
                currentTransportForRelay = connectionTracker::getSocketForPeer
            ) ?: return false
            val data = packet.toBinaryData() ?: return false
            return try {
                link.transport.write(data)
                true
            } catch (e: IOException) {
                Log.e(
                    TAG,
                    "TX: exact-link write to ${relayAddress.take(8)} failed: ${e.message}"
                )
                false
            }
        }
        override fun cancelTransfer(transferId: String): Boolean {
            return fragmentingSender.cancelTransfer(transferId)
        }
        override fun getDeviceAddressForPeer(peerID: String): String? {
            return connectionTracker.getSocketForPeer(peerID)?.let { resolveScopedAddress(it.rawSocket) }
        }

        override fun getDeviceAddressToPeerMapping(): Map<String, String> {
            val map = mutableMapOf<String, String>()
            connectionTracker.peerSockets.forEach { (pid, sock) ->
                map[pid] = resolveScopedAddress(sock.rawSocket) ?: "unknown"
            }
            return map
        }
        override fun getTransportDebugInfo(): String {
            return connectionTracker.getDebugInfo()
        }
    }
}
