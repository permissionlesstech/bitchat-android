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
        // Discovery freshness window for reconnection maintenance
        private const val DISCOVERY_STALE_MS = 5L * 60 * 1000
        private const val DISCOVERY_IDLE_REFRESH_MS = 2L * 60 * 1000
        private const val DISCOVERY_SESSION_REFRESH_MIN_INTERVAL_MS = 90L * 1000
        private const val ROLE_REVERSAL_PREFIX = "ROLE_SERVER:"
    }

    // Core crypto/services
    private val encryptionService = EncryptionService(context)

    // Peer ID must match BluetoothMeshService: first 16 hex chars of identity fingerprint (8 bytes)
    override val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wifiTransport = WifiAwareTransport()
    private lateinit var meshCore: MeshCore
    private lateinit var fragmentingSender: FragmentingPacketSender

    // Service-level notification manager for background (no-UI) DMs
    private val serviceNotificationManager = com.bitchat.android.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext),
        com.bitchat.android.util.NotificationIntervalManager()
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
                    routed.peerID?.let { pid ->
                        try { meshCore.gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000) } catch (_: Exception) { }
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

    private fun handleMessageReceived(message: BitchatMessage) {
        try {
            when {
                message.isPrivate -> {
                    val peer = message.senderPeerID ?: ""
                    if (peer.isNotEmpty()) com.bitchat.android.services.AppStateStore.addPrivateMessage(peer, message)
                }
                message.channel != null -> {
                    com.bitchat.android.services.AppStateStore.addChannelMessage(message.channel!!, message)
                }
                else -> {
                    com.bitchat.android.services.AppStateStore.addPublicMessage(message)
                }
            }
        } catch (_: Exception) { }

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
    }

    /**
     * Broadcasts raw bytes to currently connected peer.
     */
    private fun broadcastRaw(bytes: ByteArray) {
        var sent = 0
        connectionTracker.peerSockets.forEach { (pid, sock) ->
            try {
                sock.write(bytes)
                sent++
            } catch (e: IOException) {
                Log.e(TAG, "TX: write failed to ${pid.take(8)}: ${e.message}")
            }
        }
        Log.i(TAG, "TX: broadcast via Wi-Fi Aware to $sent peers (bytes=${bytes.size})")
    }

    // TransportLayer implementation
    override fun send(packet: RoutedPacket) {
        // Received from bridge (e.g. BLE) -> Send via Wi-Fi
        // Direct injection prevents routing loops (bridge handles source check)
        meshCore.sendFromBridge(packet)
    }

    override fun sendToPeer(peerID: String, packet: BitchatPacket) {
        sendPacketToPeer(peerID, packet)
    }

    /**
     * Broadcasts routed packet to currently connected peers.
     */
    private fun broadcastPacket(routed: RoutedPacket) {
        Log.d(TAG, "TX: packet type=${routed.packet.type} broadcast (ttl=${routed.packet.ttl})")

        val packet = routed.packet
        if (packet.senderID.toHexString() == myPeerID && !packet.route.isNullOrEmpty()) {
            val firstHop = packet.route!![0].toHexString()
            if (sendRoutedPacketToPeer(firstHop, routed)) {
                Log.d(TAG, "TX: source-routed packet sent only to first Wi-Fi hop ${firstHop.take(8)}")
                return
            }
            Log.w(TAG, "TX: first Wi-Fi source-route hop ${firstHop.take(8)} unavailable; falling back to broadcast")
        }

        val recipientId = packet.recipientID?.toHexString()
        if (recipientId != null && !packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            if (sendRoutedPacketToPeer(recipientId, routed)) {
                Log.d(TAG, "TX: addressed packet sent directly to Wi-Fi peer ${recipientId.take(8)}")
                return
            }
        }

        fragmentingSender.send(routed, "Wi-Fi Aware broadcast") { single ->
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
            Log.w(TAG, "TX: no socket for ${peerID.take(8)}")
            return false
        }
        return fragmentingSender.send(routed, "Wi-Fi Aware peer ${peerID.take(8)}") { single ->
            sendSinglePacketToPeer(peerID, single.packet)
        }
    }

    private fun broadcastSinglePacket(routed: RoutedPacket): Boolean {
        val data = routed.packet.toBinaryData() ?: return false
        broadcastRaw(data)
        return true
    }

    private fun sendSinglePacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val data = packet.toBinaryData() ?: return false
        val sock = connectionTracker.getSocketForPeer(peerID)
        if (sock == null) {
            Log.w(TAG, "TX: no socket for ${peerID.take(8)}")
            return false
        }
        try {
            sock.write(data)
            Log.d(TAG, "TX: packet type=${packet.type} to ${peerID.take(8)} (bytes=${data.size})")
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
                Log.i(TAG, "Wi-Fi Aware attached; starting publish & subscribe (peerID=$myPeerID)")

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
                            Log.d(TAG, "PUBLISH: onPublishStarted()")
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
                                Log.i(TAG, "PUBLISH: Discovered subscriber '$peerId' via Aware")
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                    offerServerPathIfAppropriate(peerId, peerHandle, "publish discovery")
                                }
                            }
                            Log.d(TAG, "PUBLISH: onServiceDiscovered ssi='${peerId.take(16)}' len=${serviceSpecificInfo.size}")
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
                            Log.i(TAG, "PUBLISH: Received discovery ping from subscriber '$subscriberId'")
                            handleSubscriberPing(publishSession!!, peerHandle)
                        }

            override fun onSessionTerminated() {
                if (!isCurrentSession(generation)) return
                Log.e(TAG, "PUBLISH: onSessionTerminated()")
                publishSession = null
                val shouldRestart = isActive && com.bitchat.android.wifiaware.WifiAwareController.enabled.value
                handleUnexpectedStop(generation)
                if (shouldRestart) {
                    Log.i(TAG, "PUBLISH: Scheduling Wi-Fi Aware restart")
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
                            Log.d(TAG, "SUBSCRIBE: onSubscribeStarted()")
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
                            Log.e(TAG, "SUBSCRIBE: onSessionTerminated()")
                            subscribeSession = null
                            val shouldRestart = isActive && com.bitchat.android.wifiaware.WifiAwareController.enabled.value
                            handleUnexpectedStop(generation)
                            if (shouldRestart) {
                                Log.i(TAG, "SUBSCRIBE: Scheduling Wi-Fi Aware restart")
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
                Log.e(TAG, "Aware Session Terminated unexpectedly")
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
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun offerServerPathIfAppropriate(peerId: String, peerHandle: PeerHandle, reason: String) {
        val pubSession = publishSession ?: return
        if (peerId.isBlank() || peerId == myPeerID || !amIServerFor(peerId)) return
        if (!connectionTracker.isConnectionAttemptAllowed(peerId)) return

        Log.d(TAG, "PUBLISH: offering server path to ${peerId.take(8)} after $reason")
        handleSubscriberPing(pubSession, peerHandle)
    }

    private fun refreshDiscoverySessions(reason: String, now: Long = System.currentTimeMillis()): Boolean {
        if (!isActive || recoveryInProgress) return false
        if (!com.bitchat.android.wifiaware.WifiAwareController.enabled.value) return false

        val lastRefresh = lastDiscoveryRefreshAt.get()
        if ((now - lastRefresh) < DISCOVERY_SESSION_REFRESH_MIN_INTERVAL_MS) return false
        if (!lastDiscoveryRefreshAt.compareAndSet(lastRefresh, now)) return false

        Log.i(TAG, "Maintenance: refreshing Wi-Fi Aware discovery sessions ($reason)")
        handleUnexpectedStop(sessionGeneration.get())
        return true
    }

    /**
     * Periodic active maintenance: retries connections to discovered but unconnected peers.
     */
    private fun startPeriodicConnectionMaintenance() {
        serviceScope.launch {
            Log.d(TAG, "Starting periodic connection maintenance loop")
            while (isActive) {
                try {
                    delay(15_000) // Check every 15 seconds
                    if (!isActive) break

                    val now = System.currentTimeMillis()

                    // 0. Prune stale discovery entries. PeerHandles become invalid when the
                    // discovery sessions restart, so we must not keep pinging old handles forever.
                    val staleIds = discoveredTimestamps.filter { (id, ts) ->
                        (now - ts) >= DISCOVERY_STALE_MS && !connectionTracker.isConnected(id)
                    }.keys.toSet()
                    if (staleIds.isNotEmpty()) {
                        staleIds.forEach { discoveredTimestamps.remove(it) }
                        handleToPeerId.entries.removeIf { it.value in staleIds }
                        staleIds.forEach { subscribeHandles.remove(it) }
                        staleIds.forEach { publishHandles.remove(it) }
                        Log.d(TAG, "Maintenance: pruned ${staleIds.size} stale discovery entries")
                    }

                    // 1. Identify peers that are discovered (recently seen) but not currently connected
                    val recentDiscovered = discoveredTimestamps.filter { (id, ts) ->
                        (now - ts) < DISCOVERY_STALE_MS // Seen in last 5 minutes
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
                                Log.i(TAG, "Maintenance: offering Wi-Fi Aware server path to ${peerId.take(8)}")
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

                        Log.i(TAG, "Maintenance: attempting Wi-Fi Aware reconnect to ${peerId.take(8)}")
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
                            recentDiscovered.isEmpty() && idleFor >= DISCOVERY_IDLE_REFRESH_MS -> {
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
            Log.d(TAG, "SUBSCRIBE: sent $reason ping to '${peerId.take(16)}' (msgId=$msgId)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send $reason ping to ${peerId.take(8)}: ${e.message}")
        }
    }

    private fun requestRoleReversal(peerId: String, allowForcedClientOverride: Boolean = false) {
        if (peerId.isBlank()) return
        if (forcedClientPeers.contains(peerId) && !allowForcedClientOverride) return
        forcedServerPeers.add(peerId)
        forcedClientPeers.remove(peerId)

        val handle = subscribeHandles[peerId]
        if (handle == null) {
            Log.i(TAG, "CLIENT: role reversal queued for ${peerId.take(8)} until subscribe handle is available")
            return
        }

        val msgId = (System.nanoTime() and 0x7fffffff).toInt()
        val payload = "$ROLE_REVERSAL_PREFIX$myPeerID".toByteArray()
        try {
            subscribeSession?.sendMessage(handle, msgId, payload)
            Log.i(TAG, "CLIENT: requested Wi-Fi Aware role reversal with ${peerId.take(8)} (msgId=$msgId)")
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
            Log.i(TAG, "CLIENT: ${peerId.take(8)} failed $failures client socket attempts; requesting role reversal")
        } else {
            Log.d(TAG, "CLIENT: ${peerId.take(8)} failed client socket attempt $failures/$CLIENT_ROLE_REVERSAL_FAILURES; retrying same role")
        }
        return shouldReverse
    }

    private fun handleRoleReversalRequest(peerHandle: PeerHandle, requesterId: String) {
        if (requesterId.isBlank() || requesterId == myPeerID) return
        handleToPeerId[peerHandle] = requesterId
        discoveredTimestamps[requesterId] = System.currentTimeMillis()
        forcedClientPeers.add(requesterId)
        forcedServerPeers.remove(requesterId)
        Log.i(TAG, "PUBLISH: role reversal requested by ${requesterId.take(8)}; switching to client role")

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
            Log.v(TAG, "↪ already connected to $peerId, skipping serve")
            return
        }
        if (connectionTracker.hasOpenServerSocket(peerId)) {
            Log.v(TAG, "↪ already serving $peerId, skipping")
            return
        }
        if (connectionTracker.hasPendingDataPathRequest(peerId)) {
            val pending = connectionTracker.pendingDataPathPeerIds(peerId).joinToString(", ") { it.take(8) }
            Log.d(TAG, "SERVER: deferring serve for ${peerId.take(8)}; pending Aware data path(s): $pending")
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

        Log.d(TAG, "SERVER: listening for ${peerId.take(8)} on ${ss.localSocketAddress}")

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
                Log.i(TAG, "SERVER: onAvailable() - Aware network is ready for ${peerId.take(8)}")
                // Only accept once per network request
                if (!acceptStarted.compareAndSet(false, true)) return
                // Offload the blocking accept() off the callback thread so we never stall
                // the (main-thread) ConnectivityManager callback dispatcher.
                listenerExec.execute {
                    try {
                        try { ss.soTimeout = ACCEPT_TIMEOUT_MS } catch (_: Exception) {}
                        val client = ss.accept()
                        Log.i(TAG, "SERVER: Accepted raw TCP connection from ${peerId.take(8)}")
                        try { network.bindSocket(client) } catch (e: Exception) { Log.w(TAG, "Server bindSocket EPERM: ${e.message}") }
                        client.keepAlive = true
                        Log.i(TAG, "SERVER: Bound and established TCP with ${peerId.take(8)} addr=${client.inetAddress?.hostAddress}")
                        val synced = SyncedSocket(client)
                        activeSocket = synced
                        connectionTracker.onClientConnected(peerId, synced)
                        // We only ever accept a single data socket per server request. Close the
                        // listening ServerSocket now so it can't block a future re-serve (its
                        // presence makes hasOpenServerSocket() true for the life of the process)
                        // and so we free the fd/port promptly.
                        connectionTracker.closeServerSocket(peerId)
                        try { meshCore.setDirectConnection(peerId, true) } catch (_: Exception) {}
                        try { meshCore.addOrUpdatePeer(peerId, peerId) } catch (_: Exception) {}
                        listenerExec.execute { listenToPeer(synced, peerId) }
                        handleSubscriberKeepAlive(synced, peerId, pubSession, peerHandle)

                        // Kick off Noise handshake for this logical peer
                        if (myPeerID < peerId) {
                            meshCore.initiateNoiseHandshake(peerId)
                            Log.i(TAG, "SERVER: Initiating Noise handshake to ${peerId.take(8)}")
                        }
                        // Ensure fast presence even before handshake settles
                        serviceScope.launch { delay(150); sendBroadcastAnnounce() }
                    } catch (ioe: IOException) {
                        if (ss.isClosed || !isActive) {
                            Log.d(TAG, "SERVER: accept stopped for ${peerId.take(8)} after socket cleanup")
                        } else {
                            Log.e(TAG, "SERVER: accept failed for ${peerId.take(8)}", ioe)
                            handleNetworkFailure(peerId)
                        }
                    }
                }
            }

            override fun onUnavailable() {
                Log.e(TAG, "SERVER: onUnavailable() - Failed to acquire Aware network for ${peerId.take(8)} (timeout or refused)")
                handleNetworkFailure(peerId)
            }

            override fun onLost(network: Network) {
                handlePeerDisconnection(peerId, activeSocket)
                Log.i(TAG, "SERVER: WiFi Aware network lost for ${peerId.take(8)}")
            }
        }

        connectionTracker.addNetworkCallback(peerId, cb)
        Log.i(TAG, "SERVER: [Calling requestNetwork] for ${peerId.take(8)} with port $port")
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
                val sent = pubSession.sendMessage(peerHandle, readyId, readyPayload)
                Log.d(TAG, "PUBLISH: server-ready sent=$sent (msgId=$readyId, port=$port)")
            } catch (e: Exception) {
                Log.e(TAG, "PUBLISH: Exception sending server-ready to $peerHandle", e)
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
        // TCP keep-alive pings
        serviceScope.launch {
            try {
                while (connectionTracker.isConnected(peerId)) {
                    // write empty byte array effectively sends [4 bytes length=0] which is our ping
                    try {
                        client.write(ByteArray(0))
                    } catch (_: IOException) {
                        // The write side is dead. Don't just stop pinging: actively tear down so the
                        // half-open socket stops counting as "connected" and maintenance can retry.
                        handlePeerDisconnection(peerId, client)
                        break
                    }
                    delay(2_000)
                }
            } catch (_: Exception) {}
        }
        // Discovery keep-alive
        serviceScope.launch {
            var msgId = 0
            while (connectionTracker.isConnected(peerId)) {
                try { pubSession.sendMessage(peerHandle, msgId++, ByteArray(0)) } catch (_: Exception) { break }
                delay(20_000)
            }
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
                if (attempt > 1) {
                    Log.i(TAG, "CLIENT: socket connect succeeded for ${peerId.take(8)} on attempt $attempt")
                }
                return sock
            } catch (e: IOException) {
                lastFailure = e
                try { sock?.close() } catch (_: Exception) { }
                if (attempt < CLIENT_SOCKET_ATTEMPTS) {
                    Log.w(TAG, "CLIENT: socket attempt $attempt/$CLIENT_SOCKET_ATTEMPTS failed for ${peerId.take(8)}: ${e.message}; retrying")
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
            Log.w(TAG, "SUBSCRIBE: dropped server-ready with no peer mapping and no peer ID payload (payload=${payload.size}B)")
            return null
        }

        handleToPeerId[peerHandle] = peerId
        subscribeHandles[peerId] = peerHandle
        rememberDiscoveredPeer(peerId)
        if (advertisedPeerId != null && mappedPeerId != null && advertisedPeerId != mappedPeerId) {
            Log.d(TAG, "SUBSCRIBE: server-ready remapped handle ${mappedPeerId.take(8)} -> ${advertisedPeerId.take(8)}")
        }
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
            Log.w(TAG, "handleServerReady called with invalid payload size=${payload.size}, dropping")
            return
        }

        val peerId = resolveServerReadyPeerId(peerHandle, payload) ?: return
        if (peerId == myPeerID) return
        if (amIServerFor(peerId)) return
        if (connectionTracker.peerSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already client-connected to $peerId, skipping")
            return
        }
        val cancelledServerOffers = connectionTracker.cancelPendingServerDataPaths(peerId)
        if (cancelledServerOffers.isNotEmpty()) {
            val cancelled = cancelledServerOffers.joinToString(", ") { it.take(8) }
            Log.i(TAG, "CLIENT: preempted pending server offer(s) for $cancelled to connect ${peerId.take(8)}")
        }
        if (connectionTracker.hasPendingDataPathRequest(peerId)) {
            val pending = connectionTracker.pendingDataPathPeerIds(peerId).joinToString(", ") { it.take(8) }
            Log.d(TAG, "CLIENT: deferring server-ready for ${peerId.take(8)}; pending Aware data path(s): $pending")
            return
        }
        if (!connectionTracker.addPendingConnection(peerId)) {
            return
        }

        val port = ByteBuffer.wrap(payload, 0, Int.SIZE_BYTES).order(ByteOrder.BIG_ENDIAN).int
        Log.i(TAG, "CLIENT: Received server-ready from ${peerId.take(8)} on port $port (payload=${payload.size}B). Requesting network...")

        val subSession = subscribeSession ?: run {
            Log.w(TAG, "CLIENT: subscribe session missing for server-ready from ${peerId.take(8)}")
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
                Log.i(TAG, "CLIENT: onAvailable() - Aware network is ready for ${peerId.take(8)}")
                // Do not bind process for Aware; use per-socket binding instead
            }
            
            override fun onUnavailable() {
                Log.e(TAG, "CLIENT: onUnavailable() - Failed to acquire Aware network for ${peerId.take(8)}")
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
                Log.i(TAG, "CLIENT: onCapabilitiesChanged() - Peer IPv6 discovered: $addr port=$connectPort")

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
                        Log.i(TAG, "CLIENT: TCP connected to ${peerId.take(8)} at $scopedAddr:$connectPort")

                        val synced = SyncedSocket(sock)
                        activeSocket = synced
                        connectionTracker.onClientConnected(peerId, synced)
                        clientSocketFailures.remove(peerId)
                        try { meshCore.setDirectConnection(peerId, true) } catch (_: Exception) {}
                        try { meshCore.addOrUpdatePeer(peerId, peerId) } catch (_: Exception) {}
                        listenerExec.execute { listenToPeer(synced, peerId) }
                        handleServerKeepAlive(synced, peerId, peerHandle)

                        // Kick off Noise handshake for this logical peer
                        if (myPeerID < peerId) {
                            meshCore.initiateNoiseHandshake(peerId)
                            Log.i(TAG, "CLIENT: Initiating Noise handshake to ${peerId.take(8)}")
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
                Log.i(TAG, "CLIENT: WiFi Aware network lost for ${peerId.take(8)}")
            }
        }

        connectionTracker.addNetworkCallback(peerId, cb)
        Log.i(TAG, "CLIENT: [Calling requestNetwork] for ${peerId.take(8)}")
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
        // TCP keep-alive
        serviceScope.launch {
            try {
                while (connectionTracker.isConnected(peerId)) {
                    try {
                        sock.write(ByteArray(0))
                    } catch (_: IOException) {
                        // The write side is dead. Tear down so the half-open socket stops counting
                        // as "connected" and maintenance can retry instead of silently stalling.
                        handlePeerDisconnection(peerId, sock)
                        break
                    }
                    delay(2_000)
                }
            } catch (_: Exception) {}
        }
        // Discovery keep-alive
        serviceScope.launch {
            var msgId = 0
            while (connectionTracker.isConnected(peerId)) {
                try { subscribeSession?.sendMessage(peerHandle, msgId++, ByteArray(0)) } catch (_: Exception) { break }
                delay(20_000)
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
     * Listens for incoming packets from a connected peer and dispatches them through
     * the packet processor.
     *
     * @param socket Socket connected to the peer
     * @param initialLogicalPeerId Temporary identifier before peer ID resolution
     */
    private fun listenToPeer(socket: SyncedSocket, initialLogicalPeerId: String) {
        var logicalPeerId = initialLogicalPeerId
        while (isActive) {
            val raw = socket.read() ?: break
            
            if (raw.isEmpty()) {
                // Keep-alive (0 length frame)
                continue
            }

            val pkt = BitchatPacket.fromBinaryData(raw) ?: continue

            val senderPeerHex = pkt.senderID?.toHexString()?.take(16) ?: continue

            if (pkt.type == MessageType.ANNOUNCE.value && pkt.ttl >= MAX_TTL && senderPeerHex != logicalPeerId) {
                val previousPeerId = logicalPeerId
                logicalPeerId = connectionTracker.rebindPeerId(previousPeerId, senderPeerHex, socket)
                handleToPeerId.forEach { (handle, peerId) ->
                    if (peerId == previousPeerId) {
                        handleToPeerId[handle] = senderPeerHex
                    }
                }
                subscribeHandles.remove(previousPeerId)?.let { subscribeHandles[senderPeerHex] = it }
                discoveredTimestamps.remove(previousPeerId)
                discoveredTimestamps[senderPeerHex] = System.currentTimeMillis()
                try { meshCore.setDirectConnection(previousPeerId, false) } catch (_: Exception) { }
                try { meshCore.removePeer(previousPeerId) } catch (_: Exception) { }
                try { meshCore.setDirectConnection(senderPeerHex, true) } catch (_: Exception) { }
                publishHandles.remove(previousPeerId)?.let { publishHandles[senderPeerHex] = it }
                Log.i(TAG, "RX: rebound Wi-Fi direct peer ${previousPeerId.take(8)} -> ${senderPeerHex.take(8)}")
            }
            
            // Route the packet: 
            // - peerID = Originator (who signed it)
            // - relayAddress = Neighbor (who sent it to us over this socket)
            Log.d(TAG, "RX: packet type=${pkt.type} from ${senderPeerHex.take(8)} via ${logicalPeerId.take(8)} (bytes=${raw.size})")
            meshCore.processIncoming(pkt, senderPeerHex, logicalPeerId)
        }
        
        // Breaking out of the loop means the socket is dead or service is stopping.
        Log.i(TAG, "Socket loop terminated for ${logicalPeerId.take(8)} removing peer.")
        handlePeerDisconnection(logicalPeerId, socket)
        socket.close()
    }

    private fun handleNetworkFailure(peerId: String) {
         serviceScope.launch {
            Log.d(TAG, "Network failure cleanup for: $peerId")
            if (!connectionTracker.isConnected(peerId)) {
                val canonicalPeerId = connectionTracker.canonicalPeerId(peerId)
                connectionTracker.disconnect(peerId)
                meshCore.removePeer(canonicalPeerId)
                if (canonicalPeerId != peerId) {
                    meshCore.removePeer(peerId)
                }
            } else {
                Log.d(TAG, "Network failure ignored for $peerId - another socket is active")
            }
        }
    }

    private fun handlePeerDisconnection(initialId: String, socket: SyncedSocket? = null) {
        serviceScope.launch {
            // Check if this socket is the current active one before nuking the session
            val currentSocket = connectionTracker.getSocketForPeer(initialId)
            val canonicalPeerId = connectionTracker.canonicalPeerId(initialId)
            if (currentSocket === socket) {
                Log.d(TAG, "Cleaning up peer: $canonicalPeerId (active socket)")
                connectionTracker.disconnect(initialId)
                meshCore.removePeer(canonicalPeerId)
                if (canonicalPeerId != initialId) {
                    meshCore.removePeer(initialId)
                }
            } else if (socket == null && currentSocket == null) {
                // Fallback: If we don't have a specific socket context but we are already disconnected, ensure cleanup
                Log.d(TAG, "Cleaning up peer: $initialId (no active socket)")
                connectionTracker.disconnect(initialId)
                meshCore.removePeer(canonicalPeerId)
                if (canonicalPeerId != initialId) {
                    meshCore.removePeer(initialId)
                }
            } else {
                Log.d(TAG, "Ignored disconnection for $initialId - socket replaced or inactive")
                // Do not remove peer/session, as a new socket has likely taken over
            }
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

        override fun broadcastPacket(routed: RoutedPacket) {
            this@WifiAwareMeshService.broadcastPacket(routed)
        }
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
            return this@WifiAwareMeshService.sendPacketToPeer(peerID, packet)
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
