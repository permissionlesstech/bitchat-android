package com.bitchat.android.wifiaware

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.net.*
import android.net.wifi.aware.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.annotation.RequiresPermission
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.toHexString
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
    }

    // Core crypto/services
    private val encryptionService = EncryptionService(context)

    // Peer ID must match BluetoothMeshService: first 16 hex chars of identity fingerprint (8 bytes)
    override val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val wifiTransport = WifiAwareTransport()
    private lateinit var meshCore: MeshCore

    // Service-level notification manager for background (no-UI) DMs
    private val serviceNotificationManager = com.bitchat.android.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext),
        com.bitchat.android.util.NotificationIntervalManager()
    )

    // Wi-Fi Aware transport
    private val awareManager = context.getSystemService(WifiAwareManager::class.java)
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val listenerExec = Executors.newCachedThreadPool()
    private var isActive = false

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

    // Timestamp dedupe
    private val lastTimestamps = ConcurrentHashMap<String, ULong>()

    init {
        // Ensure BluetoothMeshService is initialized so we share its GossipSyncManager
        // This avoids race conditions and ensures a single gossip source/delegate
        com.bitchat.android.service.MeshServiceHolder.getOrCreate(context)
        val shared = com.bitchat.android.service.MeshServiceHolder.sharedGossipSyncManager
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
        // Wi-Fi Aware uses full packets; no fragmentation
        val data = routed.packet.toBinaryData() ?: return
        serviceScope.launch { broadcastRaw(data) }
    }

    // Expose a public method so BLE can forward relays to Wi-Fi Aware
    fun broadcastRoutedPacket(routed: RoutedPacket) {
        broadcastPacket(routed)
    }

    /**
     * Send packet to connected peer.
     */
    private fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
        // Wi-Fi Aware uses full packets; no fragmentation
        val data = packet.toBinaryData() ?: return
        serviceScope.launch {
            val sock = connectionTracker.peerSockets[peerID]
            if (sock == null) {
                Log.w(TAG, "TX: no socket for ${peerID.take(8)}")
                return@launch
            }
            try {
                sock.write(data)
                Log.d(TAG, "TX: packet type=${packet.type} to ${peerID.take(8)} (bytes=${data.size})")
            } catch (e: IOException) {
                Log.e(TAG, "TX: write to ${peerID.take(8)} failed: ${e.message}")
            }
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
        isActive = true
        Log.i(TAG, "Starting Wi-Fi Aware mesh with peer ID: $myPeerID")

        awareManager?.attach(object : AttachCallback() {
            @SuppressLint("MissingPermission")
            @RequiresPermission(allOf = [
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ])
            override fun onAttached(session: WifiAwareSession) {
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
                            publishSession = pub
                            Log.d(TAG, "PUBLISH: onPublishStarted()")
                            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi-Fi Aware Publish Started")) } catch (_: Exception) {}
                        }
                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            val peerId = try { String(serviceSpecificInfo) } catch (_: Exception) { "" }
                            handleToPeerId[peerHandle] = peerId
                            if (peerId.isNotBlank()) {
                                discoveredTimestamps[peerId] = System.currentTimeMillis()
                                Log.i(TAG, "PUBLISH: Discovered subscriber '$peerId' via Aware")
                            }
                            Log.d(TAG, "PUBLISH: onServiceDiscovered ssi='${peerId.take(16)}' len=${serviceSpecificInfo.size}")
                        }

                        @RequiresApi(Build.VERSION_CODES.Q)
                        override fun onMessageReceived(
                            peerHandle: PeerHandle,
                            message: ByteArray
                        ) {
                            if (message.isEmpty()) return
                            val subscriberId = try { String(message) } catch (_: Exception) { "" }
                            if (subscriberId == myPeerID) return

                            handleToPeerId[peerHandle] = subscriberId
                            if (subscriberId.isNotBlank()) discoveredTimestamps[subscriberId] = System.currentTimeMillis()
                            Log.i(TAG, "PUBLISH: Received discovery ping from subscriber '$subscriberId'")
                            handleSubscriberPing(publishSession!!, peerHandle)
                        }

            override fun onSessionTerminated() {
                Log.e(TAG, "PUBLISH: onSessionTerminated()")
                publishSession = null
                if (isActive) {
                    Log.i(TAG, "PUBLISH: Attempting to restart publish session...")
                    // Delay and check if we need to restart services entirely
                    serviceScope.launch { delay(2000); if (isActive) startServices() }
                }
            }
                    },
                    Handler(Looper.getMainLooper())
                )

                // SUBSCRIBE (client role)
                session.subscribe(
                    SubscribeConfig.Builder()
                        .setServiceName(SERVICE_NAME)
                        .build(),
                    object : DiscoverySessionCallback() {
                        override fun onSubscribeStarted(sub: SubscribeDiscoverySession) {
                            subscribeSession = sub
                            Log.d(TAG, "SUBSCRIBE: onSubscribeStarted()")
                            try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().addDebugMessage(com.bitchat.android.ui.debug.DebugMessage.SystemMessage("Wi-Fi Aware Subscribe Started")) } catch (_: Exception) {}
                        }
                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            val peerId = try { String(serviceSpecificInfo) } catch (_: Exception) { "" }
                            handleToPeerId[peerHandle] = peerId
                            val msgId = (System.nanoTime() and 0x7fffffff).toInt()
                            subscribeSession?.sendMessage(peerHandle, msgId, myPeerID.toByteArray())
                            if (peerId.isNotBlank()) discoveredTimestamps[peerId] = System.currentTimeMillis()
                            Log.d(TAG, "SUBSCRIBE: sent ping to '${peerId.take(16)}' (msgId=$msgId)")
                        }

                        @RequiresApi(Build.VERSION_CODES.Q)
                        override fun onMessageReceived(
                            peerHandle: PeerHandle,
                            message: ByteArray
                        ) {
                            if (message.isEmpty()) return
                            val peerId = handleToPeerId[peerHandle] ?: return
                            if (peerId == myPeerID) return

                            Log.d(TAG, "SUBSCRIBE: onMessageReceived() \u2192 server-ready from ${peerId.take(8)} payload=${message.size}B")
                            handleServerReady(peerHandle, message)
                        }

                        override fun onSessionTerminated() {
                            Log.e(TAG, "SUBSCRIBE: onSessionTerminated()")
                            subscribeSession = null
                            if (isActive) {
                                Log.i(TAG, "SUBSCRIBE: Attempting to restart subscribe session...")
                                serviceScope.launch { delay(2000); if (isActive) startServices() }
                            }
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }
            override fun onAttachFailed() {
                Log.e(TAG, "Wi-Fi Aware attach failed")
            }

            override fun onAwareSessionTerminated() {
                Log.e(TAG, "Aware Session Terminated unexpectedly")
                wifiAwareSession = null
                isActive = false
                if (com.bitchat.android.wifiaware.WifiAwareController.enabled.value) { serviceScope.launch { delay(3000); com.bitchat.android.wifiaware.WifiAwareController.startIfPossible() } }
            }
        }, Handler(Looper.getMainLooper()))

        meshCore.startCore()
        startPeriodicConnectionMaintenance()
        connectionTracker.start()
        
        // Register with cross-layer transport bridge
        TransportBridgeService.register("WIFI", this)
    }

    /**
     * Stops the Wi-Fi Aware mesh services and cleans up sockets and sessions.
     */
    override fun stopServices() {
        if (!isActive) return
        isActive = false
        Log.i(TAG, "Stopping Wi-Fi Aware mesh")

        // Unregister from bridge
        TransportBridgeService.unregister("WIFI")

        meshCore.sendLeaveAnnouncement()

        serviceScope.launch {
            delay(200)

            meshCore.stopCore()
            connectionTracker.stop() // Handles socket closing and callback unregistration

            publishSession?.close();   publishSession   = null
            subscribeSession?.close(); subscribeSession = null
            wifiAwareSession?.close(); wifiAwareSession = null

            handleToPeerId.clear()

            meshCore.shutdown()

            serviceScope.cancel()
        }
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
                    // 1. Identify peers that are discovered (recently seen) but not currently connected
                    val recentDiscovered = discoveredTimestamps.filter { (id, ts) ->
                        (now - ts) < 5 * 60 * 1000 // Seen in last 5 minutes
                    }.keys

                    // 2. Filter out those who are already connected
                    val disconnectedPeers = recentDiscovered.filter { peerId ->
                        !connectionTracker.isConnected(peerId)
                    }

                    // 3. Attempt reconnection
                    for (peerId in disconnectedPeers) {
                        // Find the PeerHandle for this peerId
                        val handle = handleToPeerId.entries.find { it.value == peerId }?.key ?: continue

                        // Check tracker policy
                        if (!connectionTracker.isConnectionAttemptAllowed(peerId)) continue

                        Log.i(TAG, "🔄 Maintenance: attempting reconnect to ${peerId.take(8)}")
                        if (connectionTracker.addPendingConnection(peerId)) {
                            // Resend ping to trigger handshake
                            val msgId = (System.nanoTime() and 0x7fffffff).toInt()
                            try {
                                subscribeSession?.sendMessage(handle, msgId, myPeerID.toByteArray())
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to send maintenance ping to ${peerId.take(8)}: ${e.message}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in connection maintenance: ${e.message}")
                }
            }
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

        if (connectionTracker.serverSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already serving $peerId, skipping")
            return
        }

        val ss = ServerSocket()
        try {
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(0))
        } catch (e: Exception) { Log.e(TAG, "Failed to bind server socket", e) }

        connectionTracker.addServerSocket(peerId, ss)
        val port = ss.localPort

        Log.d(TAG, "SERVER: listening for ${peerId.take(8)} on port $port")

        val spec = WifiAwareNetworkSpecifier.Builder(pubSession, peerHandle)
            .setPskPassphrase(PSK)
            .setPort(port)
            .build()
        // Default capabilities include NET_CAPABILITY_NOT_VPN.
        // Keeping defaults for hardware interface handle acquisition compatibility with global VPNs.
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            private var activeSocket: SyncedSocket? = null

            override fun onAvailable(network: Network) {
                Log.i(TAG, "SERVER: onAvailable() - Aware network is ready for ${peerId.take(8)}")
                try {
                    val client = ss.accept()
                    Log.i(TAG, "SERVER: Accepted raw TCP connection from ${peerId.take(8)}")
                    try { network.bindSocket(client) } catch (e: Exception) { Log.w(TAG, "Server bindSocket EPERM: ${e.message}") }
                    client.keepAlive = true
                    Log.i(TAG, "SERVER: Bound and established TCP with ${peerId.take(8)} addr=${client.inetAddress?.hostAddress}")
                    val synced = SyncedSocket(client)
                    activeSocket = synced
                    connectionTracker.onClientConnected(peerId, synced)
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
                    Log.e(TAG, "SERVER: accept failed for ${peerId.take(8)}", ioe)
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
            cm.requestNetwork(req, cb, 30_000) 
        } catch (e: Exception) {
            Log.e(TAG, "SERVER: ConnectivityManager.requestNetwork threw exception", e)
            connectionTracker.disconnect(peerId)
        }

        val readyId = (System.nanoTime() and 0x7fffffff).toInt()
        val portBytes = ByteBuffer.allocate(4)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(port)
            .array()
        Handler(Looper.getMainLooper()).post {
            try {
                val sent = pubSession.sendMessage(peerHandle, readyId, portBytes)
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
                    try { client.write(ByteArray(0)) } catch (_: IOException) { break }
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

        val peerId = handleToPeerId[peerHandle] ?: return
        if (amIServerFor(peerId)) return
        if (connectionTracker.peerSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already client-connected to $peerId, skipping")
            return
        }

        val port = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
        Log.i(TAG, "CLIENT: Received server-ready from ${peerId.take(8)} on port $port. Requesting network...")

        val spec = WifiAwareNetworkSpecifier.Builder(subscribeSession!!, peerHandle)
            .setPskPassphrase(PSK)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            private var activeSocket: SyncedSocket? = null

            override fun onAvailable(network: Network) {
                Log.i(TAG, "CLIENT: onAvailable() - Aware network is ready for ${peerId.take(8)}")
                // Do not bind process for Aware; use per-socket binding instead
            }
            
            override fun onUnavailable() {
                Log.e(TAG, "CLIENT: onUnavailable() - Failed to acquire Aware network for ${peerId.take(8)}")
                handleNetworkFailure(peerId)
            }

            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                if (connectionTracker.peerSockets.containsKey(peerId)) return
                val info = (nc.transportInfo as? WifiAwareNetworkInfo) ?: return
                val addr = info.peerIpv6Addr as? Inet6Address ?: return
                Log.i(TAG, "CLIENT: onCapabilitiesChanged() - Peer IPv6 discovered: $addr")

                val lp = cm.getLinkProperties(network)
                val iface = lp?.interfaceName

                try {
                    val sock = Socket()
                    try { network.bindSocket(sock) } catch (e: Exception) { Log.w(TAG, "Client bindSocket EPERM: ${e.message}") }
                    sock.tcpNoDelay = true
                    sock.keepAlive = true

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

                    sock.connect(java.net.InetSocketAddress(scopedAddr, port), 7000)
                    Log.i(TAG, "CLIENT: TCP connected to ${peerId.take(8)} at $scopedAddr:$port")

                    val synced = SyncedSocket(sock)
                    activeSocket = synced
                    connectionTracker.onClientConnected(peerId, synced)
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
            cm.requestNetwork(req, cb, 30_000)
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
                    try { sock.write(ByteArray(0)) } catch (_: IOException) { break }
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
    private fun amIServerFor(peerId: String) = myPeerID < peerId

    /**
     * Listens for incoming packets from a connected peer and dispatches them through
     * the packet processor.
     *
     * @param socket Socket connected to the peer
     * @param initialLogicalPeerId Temporary identifier before peer ID resolution
     */
    private fun listenToPeer(socket: SyncedSocket, initialLogicalPeerId: String) {
        while (isActive) {
            val raw = socket.read() ?: break
            
            if (raw.isEmpty()) {
                // Keep-alive (0 length frame)
                continue
            }

            val pkt = BitchatPacket.fromBinaryData(raw) ?: continue

            val senderPeerHex = pkt.senderID?.toHexString()?.take(16) ?: continue
            
            // Deduplicate based on timestamp + sender (standard flood fill protection)
            val ts = pkt.timestamp
            if (lastTimestamps.put(senderPeerHex, ts) == ts) {
                continue
            }

            // Route the packet: 
            // - peerID = Originator (who signed it)
            // - relayAddress = Neighbor (who sent it to us over this socket)
            // Note: We do NOT update peerSockets mapping based on senderPeerHex. 
            // The socket belongs to initialLogicalPeerId effectively serving as the "MAC address" layer.
            Log.d(TAG, "RX: packet type=${pkt.type} from ${senderPeerHex.take(8)} via ${initialLogicalPeerId.take(8)} (bytes=${raw.size})")
            meshCore.processIncoming(pkt, senderPeerHex, initialLogicalPeerId)
        }
        
        // Breaking out of the loop means the socket is dead or service is stopping.
        Log.i(TAG, "Socket loop terminated for ${initialLogicalPeerId.take(8)} removing peer.")
        handlePeerDisconnection(initialLogicalPeerId, socket)
        socket.close()
    }

    private fun handleNetworkFailure(peerId: String) {
         serviceScope.launch {
            Log.d(TAG, "Network failure cleanup for: $peerId")
            // Specifically release the callback if it didn't happen automatically
            connectionTracker.releaseNetworkRequest(peerId)
            
            if (!connectionTracker.isConnected(peerId)) {
                meshCore.removePeer(peerId)
            } else {
                Log.d(TAG, "Network failure ignored for $peerId - another socket is active")
            }
        }
    }

    private fun handlePeerDisconnection(initialId: String, socket: SyncedSocket? = null) {
        serviceScope.launch {
            // Check if this socket is the current active one before nuking the session
            val currentSocket = connectionTracker.peerSockets[initialId]
            if (currentSocket === socket) {
                Log.d(TAG, "Cleaning up peer: $initialId (active socket)")
                connectionTracker.disconnect(initialId)
                meshCore.removePeer(initialId)
            } else if (socket == null && currentSocket == null) {
                // Fallback: If we don't have a specific socket context but we are already disconnected, ensure cleanup
                Log.d(TAG, "Cleaning up peer: $initialId (no active socket)")
                connectionTracker.disconnect(initialId)
                meshCore.removePeer(initialId)
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
        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
            this@WifiAwareMeshService.sendPacketToPeer(peerID, packet)
        }
        override fun getDeviceAddressForPeer(peerID: String): String? {
            return connectionTracker.peerSockets[peerID]?.let { resolveScopedAddress(it.rawSocket) }
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
