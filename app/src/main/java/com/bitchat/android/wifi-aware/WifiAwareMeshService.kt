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
import com.bitchat.android.model.*
import com.bitchat.android.protocol.*
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.toHexString
// Mesh-layer components are reused from the existing Bluetooth stack
import com.bitchat.android.mesh.PeerManager
import com.bitchat.android.mesh.PeerManagerDelegate
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.FragmentManager
import com.bitchat.android.mesh.SecurityManager
import com.bitchat.android.mesh.SecurityManagerDelegate
import com.bitchat.android.mesh.StoreForwardManager
import com.bitchat.android.mesh.StoreForwardManagerDelegate
import com.bitchat.android.mesh.MessageHandler
import com.bitchat.android.mesh.MessageHandlerDelegate
import com.bitchat.android.mesh.PacketProcessor
import com.bitchat.android.mesh.PacketProcessorDelegate
import kotlinx.coroutines.*
import java.io.IOException
import java.net.Inet6Address
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
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
class WifiAwareMeshService(private val context: Context) {

    companion object {
        private const val TAG = "WifiAwareMeshService"
        private const val MAX_TTL: UByte = 7u
        private const val SERVICE_NAME = "bitchat"
        private const val PSK = "bitchat_secret"
    }

    // Core crypto/services
    private val encryptionService = EncryptionService(context)

    // Peer ID must match BluetoothMeshService: first 16 hex chars of identity fingerprint (8 bytes)
    val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)

    // Core components
    private val peerManager = PeerManager()
    private val fragmentManager = FragmentManager()
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID, context.applicationContext)
    private val packetProcessor = PacketProcessor(myPeerID)

    // Gossip sync
    private val gossipSyncManager: GossipSyncManager

    // Wi-Fi Aware transport
    private val awareManager = context.getSystemService(WifiAwareManager::class.java)
    private var wifiAwareSession: WifiAwareSession? = null
    private var publishSession: PublishDiscoverySession? = null
    private var subscribeSession: SubscribeDiscoverySession? = null
    private val listenerExec = Executors.newCachedThreadPool()
    private var isActive = false

    // Delegate
    var delegate: WifiAwareMeshDelegate? = null

    // Transport state
    private val peerSockets = ConcurrentHashMap<String, Socket>()
    private val serverSockets = ConcurrentHashMap<String, ServerSocket>()
    private val networkCallbacks = ConcurrentHashMap<String, ConnectivityManager.NetworkCallback>()
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val handleToPeerId = ConcurrentHashMap<PeerHandle, String>() // discovery mapping
    private val discoveredTimestamps = ConcurrentHashMap<String, Long>() // peerID -> last seen time

    // Timestamp dedupe
    private val lastTimestamps = ConcurrentHashMap<String, ULong>()

    // Coroutines
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        setupDelegates()
        messageHandler.packetProcessor = packetProcessor

        // Use shared GossipSyncManager from MeshServiceHolder if available (minimal refactor)
        val shared = com.bitchat.android.service.MeshServiceHolder.sharedGossipSyncManager
        if (shared != null) {
            gossipSyncManager = shared
        } else {
            gossipSyncManager = GossipSyncManager(
                myPeerID = myPeerID,
                scope = serviceScope,
                configProvider = object : GossipSyncManager.ConfigProvider {
                    override fun seenCapacity(): Int = 500
                    override fun gcsMaxBytes(): Int = 400
                    override fun gcsTargetFpr(): Double = 0.01
                }
            )
            gossipSyncManager.delegate = object : GossipSyncManager.Delegate {
                override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                    this@WifiAwareMeshService.sendPacketToPeer(peerID, packet)
                }
                override fun sendPacket(packet: BitchatPacket) {
                    broadcastPacket(RoutedPacket(packet))
                }
                override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                    return signPacketBeforeBroadcast(packet)
                }
            }
        }
    }

    /**
     * Helper method hexToBa.
     */
    private fun hexStringToByteArray(hex: String): ByteArray {
        val out = ByteArray(8)
        var idx = 0
        var s = hex
        while (s.length >= 2 && idx < 8) {
            val b = s.substring(0, 2).toIntOrNull(16)?.toByte() ?: 0
            out[idx++] = b
            s = s.drop(2)
        }
        return out
    }

    /**
     * Sign packet before broadcasting.
     */
    private fun signPacketBeforeBroadcast(packet: BitchatPacket): BitchatPacket {
        val data = packet.toBinaryDataForSigning() ?: return packet
        val sig = encryptionService.signData(data) ?: return packet
        return packet.copy(signature = sig)
    }

    /**
     * Broadcasts raw bytes to currently connected peer.
     */
    private fun broadcastRaw(bytes: ByteArray) {
        var sent = 0
        peerSockets.forEach { (pid, sock) ->
            try {
                sock.getOutputStream().write(bytes)
                sent++
            } catch (e: IOException) {
                Log.e(TAG, "TX: write failed to ${pid.take(8)}: ${e.message}")
            }
        }
        Log.i(TAG, "TX: broadcast via Wi-Fi Aware to $sent peers (bytes=${bytes.size})")
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
            val sock = peerSockets[peerID]
            if (sock == null) {
                Log.w(TAG, "TX: no socket for ${peerID.take(8)}")
                return@launch
            }
            try {
                sock.getOutputStream().write(data)
                Log.d(TAG, "TX: packet type=${packet.type} to ${peerID.take(8)} (bytes=${data.size})")
            } catch (e: IOException) {
                Log.e(TAG, "TX: write to ${peerID.take(8)} failed: ${e.message}")
            }
        }
    }

    /**
     * Configures delegates for internal components so that events are routed back
     * through this service and ultimately to the {@link WifiAwareMeshDelegate}.
     */
    private fun setupDelegates() {
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerListUpdated(peerIDs: List<String>) {
                delegate?.didUpdatePeerList(peerIDs)
            }
            override fun onPeerRemoved(peerID: String) {
                try { gossipSyncManager.removeAnnouncementForPeer(peerID) } catch (_: Exception) { }
                try { encryptionService.removePeer(peerID) } catch (_: Exception) { }
            }
        }

        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {
                serviceScope.launch {
                    delay(100)
                    sendAnnouncementToPeer(peerID)
                    delay(1000)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    ttl = MAX_TTL
                )
                broadcastPacket(RoutedPacket(signPacketBeforeBroadcast(packet)))
            }
            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }
        }

        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String) = delegate?.isFavorite(peerID) ?: false
            override fun isPeerOnline(peerID: String) = peerManager.isPeerActive(peerID)
            override fun sendPacket(packet: BitchatPacket) {
                broadcastPacket(RoutedPacket(packet))
            }
        }

        messageHandler.delegate = object : MessageHandlerDelegate {
            override fun addOrUpdatePeer(peerID: String, nickname: String) =
                peerManager.addOrUpdatePeer(peerID, nickname)
            override fun removePeer(peerID: String) = peerManager.removePeer(peerID)
            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }
            override fun getPeerNickname(peerID: String) =
                peerManager.getPeerNickname(peerID)
            override fun getNetworkSize() = peerManager.getActivePeerCount()
            override fun getMyNickname() = delegate?.getNickname()
            override fun getPeerInfo(peerID: String): PeerInfo? = peerManager.getPeerInfo(peerID)
            override fun updatePeerInfo(
                peerID: String,
                nickname: String,
                noisePublicKey: ByteArray,
                signingPublicKey: ByteArray,
                isVerified: Boolean
            ): Boolean = peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)

            override fun sendPacket(packet: BitchatPacket) {
                broadcastPacket(RoutedPacket(signPacketBeforeBroadcast(packet)))
            }
            override fun relayPacket(routed: RoutedPacket) { broadcastPacket(routed) }
            override fun getBroadcastRecipient() = SpecialRecipients.BROADCAST

            override fun verifySignature(packet: BitchatPacket, peerID: String) =
                securityManager.verifySignature(packet, peerID)
            override fun encryptForPeer(data: ByteArray, recipientPeerID: String) =
                securityManager.encryptForPeer(data, recipientPeerID)
            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String) =
                securityManager.decryptFromPeer(encryptedData, senderPeerID)
            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean =
                encryptionService.verifyEd25519Signature(signature, data, publicKey)

            override fun hasNoiseSession(peerID: String) =
                encryptionService.hasEstablishedSession(peerID)
            override fun initiateNoiseHandshake(peerID: String) {
                serviceScope.launch {
                    val hs = encryptionService.initiateHandshake(peerID) ?: return@launch
                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_HANDSHAKE.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = hexStringToByteArray(peerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = hs,
                        ttl = MAX_TTL
                    )
                    broadcastPacket(RoutedPacket(signPacketBeforeBroadcast(packet)))
                }
            }
            override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? =
                try { encryptionService.processHandshakeMessage(payload, peerID) } catch (_: Exception) { null }

            override fun updatePeerIDBinding(newPeerID: String, nickname: String, publicKey: ByteArray, previousPeerID: String?) {
                peerManager.addOrUpdatePeer(newPeerID, nickname)
                val fingerprint = peerManager.storeFingerprintForPeer(newPeerID, publicKey)
                previousPeerID?.let { peerManager.removePeer(it) }
                Log.d(TAG, "Updated peer binding to $newPeerID, fp=${fingerprint.take(16)}")
            }

            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String) =
                delegate?.decryptChannelMessage(encryptedContent, channel)

            override fun onMessageReceived(message: BitchatMessage) {
                delegate?.didReceiveMessage(message)
            }
            override fun onChannelLeave(channel: String, fromPeer: String) {
                delegate?.didReceiveChannelLeave(channel, fromPeer)
            }
            override fun onDeliveryAckReceived(messageID: String, peerID: String) {
                delegate?.didReceiveDeliveryAck(messageID, peerID)
            }
            override fun onReadReceiptReceived(messageID: String, peerID: String) {
                delegate?.didReceiveReadReceipt(messageID, peerID)
            }
        }

        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) =
                securityManager.validatePacket(packet, peerID)
            override fun updatePeerLastSeen(peerID: String) = peerManager.updatePeerLastSeen(peerID)
            override fun getPeerNickname(peerID: String): String? = peerManager.getPeerNickname(peerID)
            override fun getNetworkSize(): Int = peerManager.getActivePeerCount()
            override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST

            override fun handleNoiseHandshake(routed: RoutedPacket): Boolean =
                runBlocking { securityManager.handleNoiseHandshake(routed) }

            override fun handleNoiseEncrypted(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleNoiseEncrypted(routed) }
            }

            override fun handleAnnounce(routed: RoutedPacket) {
                serviceScope.launch {
                    val isFirst = messageHandler.handleAnnounce(routed)
                    routed.peerID?.let { pid ->
                        try { gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000) } catch (_: Exception) { }
                    }
                    try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                }
            }

            override fun handleMessage(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleMessage(routed) }
                try {
                    val pkt = routed.packet
                    val isBroadcast = (pkt.recipientID == null || pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && pkt.type == MessageType.MESSAGE.value) {
                        gossipSyncManager.onPublicPacketSeen(pkt)
                    }
                } catch (_: Exception) { }
            }

            override fun handleLeave(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleLeave(routed) }
            }

            override fun handleFragment(packet: BitchatPacket): BitchatPacket? {
                try {
                    val isBroadcast = (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && packet.type == MessageType.FRAGMENT.value) {
                        gossipSyncManager.onPublicPacketSeen(packet)
                    }
                } catch (_: Exception) { }
                return fragmentManager.handleFragment(packet)
            }

            override fun sendAnnouncementToPeer(peerID: String) = this@WifiAwareMeshService.sendAnnouncementToPeer(peerID)
            override fun sendCachedMessages(peerID: String) = storeForwardManager.sendCachedMessages(peerID)
            override fun relayPacket(routed: RoutedPacket) = broadcastPacket(routed)

            override fun handleRequestSync(routed: RoutedPacket) {
                val fromPeer = routed.peerID ?: return
                val req = RequestSyncPacket.decode(routed.packet.payload) ?: return
                gossipSyncManager.handleRequestSync(fromPeer, req)
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
    fun startServices() {
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
                        }
                        override fun onServiceDiscovered(
                            peerHandle: PeerHandle,
                            serviceSpecificInfo: ByteArray,
                            matchFilter: List<ByteArray>
                        ) {
                            val peerId = try { String(serviceSpecificInfo) } catch (_: Exception) { "" }
                            handleToPeerId[peerHandle] = peerId
                            if (peerId.isNotBlank()) discoveredTimestamps[peerId] = System.currentTimeMillis()
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
                            Log.d(TAG, "PUBLISH: got ping from $subscriberId; spinning up server")
                            handleSubscriberPing(publishSession!!, peerHandle)
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

                            Log.d(TAG, "SUBSCRIBE: onMessageReceived() → server-ready from ${peerId.take(8)} payload=${message.size}B")
                            handleServerReady(peerHandle, message)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }
            override fun onAttachFailed() {
                Log.e(TAG, "Wi-Fi Aware attach failed")
            }
        }, Handler(Looper.getMainLooper()))

        sendPeriodicBroadcastAnnounce()
        gossipSyncManager.start()
    }

    /**
     * Stops the Wi-Fi Aware mesh services and cleans up sockets and sessions.
     */
    fun stopServices() {
        if (!isActive) return
        isActive = false
        Log.i(TAG, "Stopping Wi-Fi Aware mesh")

        sendLeaveAnnouncement()

        serviceScope.launch {
            delay(200)

            gossipSyncManager.stop()

            networkCallbacks.values.forEach { runCatching { cm.unregisterNetworkCallback(it) } }
            networkCallbacks.clear()
            publishSession?.close();   publishSession   = null
            subscribeSession?.close(); subscribeSession = null
            wifiAwareSession?.close(); wifiAwareSession = null

            serverSockets.values.forEach { it.closeQuietly() }
            peerSockets.values.forEach { it.closeQuietly() }
            handleToPeerId.clear()
            serverSockets.clear()
            peerSockets.clear()

            peerManager.shutdown()
            fragmentManager.shutdown()
            securityManager.shutdown()
            storeForwardManager.shutdown()
            messageHandler.shutdown()
            packetProcessor.shutdown()

            serviceScope.cancel()
        }
    }

    /**
     * Periodically broadcasts an ANNOUNCE packet (every ~30s) while the service is active,
     * so new/idle peers can discover us without user action.
     */
    private fun sendPeriodicBroadcastAnnounce() {
        serviceScope.launch {
            while (isActive) {
                try { delay(30_000); sendBroadcastAnnounce() } catch (_: Exception) { }
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

        if (serverSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already serving $peerId, skipping")
            return
        }

        val ss = ServerSocket(0)
        serverSockets[peerId] = ss
        val port = ss.localPort
        
        // Ensure port is set to reuse if connection was recently closed (TIME_WAIT)
        try {
            ss.reuseAddress = true
        } catch (_: Exception) {}

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
            override fun onAvailable(network: Network) {
                try {
                    val client = ss.accept()
                    try { network.bindSocket(client) } catch (e: Exception) { Log.w(TAG, "Server bindSocket EPERM: ${e.message}") }
                    client.keepAlive = true
                    Log.d(TAG, "SERVER: accepted TCP from ${peerId.take(8)} addr=${client.inetAddress?.hostAddress}")
                    peerSockets[peerId] = client
                    try { peerManager.setDirectConnection(peerId, true) } catch (_: Exception) {}
                    try { peerManager.addOrUpdatePeer(peerId, peerId) } catch (_: Exception) {}
                    listenerExec.execute { listenToPeer(client, peerId) }
                    handleSubscriberKeepAlive(client, peerId, pubSession, peerHandle)
                    // Kick off Noise handshake for this logical peer
                    if (myPeerID < peerId) {
                        messageHandler.delegate?.initiateNoiseHandshake(peerId)
                        Log.d(TAG, "SERVER: initiating Noise handshake to ${peerId.take(8)} (lower ID)")
                    }
                    // Ensure fast presence even before handshake settles
                    serviceScope.launch { delay(150); sendBroadcastAnnounce() }
                } catch (ioe: IOException) {
                    Log.e(TAG, "SERVER: accept failed for ${peerId.take(8)}", ioe)
                }
            }
            override fun onLost(network: Network) {
                networkCallbacks.remove(peerId)
                Log.d(TAG, "SERVER: network lost for ${peerId.take(8)}")
            }
        }

        networkCallbacks[peerId] = cb
        Log.d(TAG, "SERVER: requesting Aware network for ${peerId.take(8)}")
        cm.requestNetwork(req, cb)

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
        client: Socket,
        peerId: String,
        pubSession: PublishDiscoverySession,
        peerHandle: PeerHandle
    ) {
        // TCP keep-alive pings
        serviceScope.launch {
            try {
                val os = client.getOutputStream()
                while (peerSockets.containsKey(peerId)) {
                    try { os.write(0) } catch (_: IOException) { break }
                    delay(2_000)
                }
            } catch (_: Exception) {}
        }
        // Discovery keep-alive
        serviceScope.launch {
            var msgId = 0
            while (peerSockets.containsKey(peerId)) {
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
        if (peerSockets.containsKey(peerId)) {
            Log.v(TAG, "↪ already client-connected to $peerId, skipping")
            return
        }

        val port = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN).int
        Log.d(TAG, "CLIENT: connecting to ${peerId.take(8)} port=$port")

        val spec = WifiAwareNetworkSpecifier.Builder(subscribeSession!!, peerHandle)
            .setPskPassphrase(PSK)
            .build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(spec)
            .build()

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // Do not bind process for Aware; use per-socket binding instead
            }
            override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
                if (peerSockets.containsKey(peerId)) return
                val info = (nc.transportInfo as? WifiAwareNetworkInfo) ?: return
                val addr = info.peerIpv6Addr as? Inet6Address ?: return

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
                    Log.d(TAG, "CLIENT: TCP connected to ${peerId.take(8)} addr=$scopedAddr:$port (iface=$iface)")

                    peerSockets[peerId] = sock
                    try { peerManager.setDirectConnection(peerId, true) } catch (_: Exception) {}
                    try { peerManager.addOrUpdatePeer(peerId, peerId) } catch (_: Exception) {}
                    listenerExec.execute { listenToPeer(sock, peerId) }
                    handleServerKeepAlive(sock, peerId, peerHandle)
                    // Kick off Noise handshake for this logical peer
                    if (myPeerID < peerId) {
                        messageHandler.delegate?.initiateNoiseHandshake(peerId)
                        Log.d(TAG, "CLIENT: initiating Noise handshake to ${peerId.take(8)} (lower ID)")
                    }
                    // Ensure fast presence even before handshake settles
                    serviceScope.launch { delay(150); sendBroadcastAnnounce() }
                } catch (ioe: IOException) {
                    Log.e(TAG, "CLIENT: socket connect failed to ${peerId.take(8)}", ioe)
                }
            }
            override fun onLost(network: Network) {
                networkCallbacks.remove(peerId)
                Log.d(TAG, "CLIENT: network lost for ${peerId.take(8)}")
            }
        }

        networkCallbacks[peerId] = cb
        Log.d(TAG, "CLIENT: requesting Aware network for ${peerId.take(8)}")
        cm.requestNetwork(req, cb)
    }

    /**
     * Sends periodic TCP and discovery keep-alive messages for server connections.
     */
    private fun handleServerKeepAlive(
        sock: Socket,
        peerId: String,
        peerHandle: PeerHandle
    ) {
        // TCP keep-alive
        serviceScope.launch {
            try {
                val os = sock.getOutputStream()
                while (peerSockets.containsKey(peerId)) {
                    try { os.write(0) } catch (_: IOException) { break }
                    delay(2_000)
                }
            } catch (_: Exception) {}
        }
        // Discovery keep-alive
        serviceScope.launch {
            var msgId = 0
            while (peerSockets.containsKey(peerId)) {
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
    private fun listenToPeer(socket: Socket, initialLogicalPeerId: String) {
        val inStream = socket.getInputStream()
        val buf = ByteArray(64 * 1024)
        var routedPeerId: String? = null

        while (isActive) {
            val len = try { inStream.read(buf) } catch (_: IOException) { break }
            if (len <= 0) break

            val raw = buf.copyOf(len)
            val pkt = BitchatPacket.fromBinaryData(raw) ?: continue

            val senderPeerHex = pkt.senderID?.toHexString()?.take(16) ?: continue
            if (senderPeerHex == myPeerID) continue

            val ts = pkt.timestamp
            if (lastTimestamps.put(senderPeerHex, ts) == ts) {
                continue
            }

            if (routedPeerId == null) {
                routedPeerId = senderPeerHex
                peerSockets[routedPeerId] = socket
            }

            Log.d(TAG, "RX: packet type=${pkt.type} from ${senderPeerHex.take(8)} (bytes=${raw.size})")
            packetProcessor.processPacket(RoutedPacket(pkt, routedPeerId))
        }
        
        // Breaking out of the loop means the socket is dead or service is stopping.
        // We MUST notify the mesh layer so it removes the logical peer immediately to allow reconnection.
        Log.i(TAG, "Socket loop terminated for ${initialLogicalPeerId.take(8)} removing peer.")
        handlePeerDisconnection(initialLogicalPeerId, routedPeerId)
        socket.closeQuietly()
    }

    private fun handlePeerDisconnection(initialId: String, routedId: String?) {
        serviceScope.launch {
            Log.d(TAG, "Cleaning up peer: $initialId / $routedId")
            
            peerSockets.remove(initialId)?.closeQuietly()
            serverSockets.remove(initialId)?.closeQuietly()
            networkCallbacks.remove(initialId)?.let { runCatching { cm.unregisterNetworkCallback(it) } }
            peerManager.removePeer(initialId)
            
            routedId?.let { id ->
                if (id != initialId) {
                    peerSockets.remove(id)?.closeQuietly()
                    serverSockets.remove(id)?.closeQuietly()
                    networkCallbacks.remove(id)?.let { runCatching { cm.unregisterNetworkCallback(it) } }
                    peerManager.removePeer(id)
                }
            }
        }
    }

    /**
     * Sends a broadcast message to all peers.
     * @param content   Text content of the message
     * @param mentions  Optional list of mentioned peer IDs
     * @param channel   Optional channel name
     */
    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        if (content.isEmpty()) return

        serviceScope.launch {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = content.toByteArray(Charsets.UTF_8),
                signature = null,
                ttl = MAX_TTL
            )
            val signed = signPacketBeforeBroadcast(packet)
            broadcastPacket(RoutedPacket(signed))
            try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
        }
    }

    /**
     * Sends a private encrypted message to a specific peer.
     *
     * @param content            The message text
     * @param recipientPeerID    Destination peer ID
     * @param recipientNickname  Recipient nickname
     * @param messageID          Optional message ID (UUID if null)
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty()) return

        serviceScope.launch {
            val finalId = messageID ?: UUID.randomUUID().toString()

            if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                try {
                    val pm = PrivateMessagePacket(messageID = finalId, content = content)
                    val tlv = pm.encode() ?: return@launch
                    val payload = NoisePayload(type = NoisePayloadType.PRIVATE_MESSAGE, data = tlv).encode()
                    val enc = encryptionService.encrypt(payload, recipientPeerID)

                    val pkt = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = hexStringToByteArray(recipientPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = enc,
                        signature = null,
                        ttl = MAX_TTL
                    )
                    broadcastPacket(RoutedPacket(signPacketBeforeBroadcast(pkt)))
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt private message: ${e.message}")
                }
            } else {
                messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)
            }
        }
    }

    /**
     * Sends a read receipt for a specific message to the given peer over an established
     * Noise session. If no session exists, this will log an error.
     *
     * @param messageID        The ID of the message that was read.
     * @param recipientPeerID  The peer to notify.
     * @param readerNickname   Nickname of the reader (may be shown by the receiver).
     */
    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        serviceScope.launch {
            try {
                val payload = NoisePayload(
                    type = NoisePayloadType.READ_RECEIPT,
                    data = messageID.toByteArray(Charsets.UTF_8)
                ).encode()
                val enc = encryptionService.encrypt(payload, recipientPeerID)
                val pkt = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = enc,
                    signature = null,
                    ttl = MAX_TTL
                )
                broadcastPacket(RoutedPacket(signPacketBeforeBroadcast(pkt)))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt: ${e.message}")
            }
        }
    }

    /**
     * Broadcasts a file (TLV payload) to all peers. Uses protocol version 2 to support
     * large payloads and generates a deterministic transferId (sha256 of payload) for UI/state.
     *
     * @param file Encoded metadata and chunks descriptor of the file to send.
     */
    fun sendFileBroadcast(file: BitchatFilePacket) {
        try {
            val payload = file.encode() ?: run { Log.e(TAG, "file TLV encode failed"); return }
            serviceScope.launch {
                val pkt = BitchatPacket(
                    version = 2u, // FILE_TRANSFER big length
                    type = MessageType.FILE_TRANSFER.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = payload,
                    signature = null,
                    ttl = MAX_TTL
                )
                val signed = signPacketBeforeBroadcast(pkt)
                val transferId = sha256Hex(payload)
                broadcastPacket(RoutedPacket(signed, transferId = transferId))
                try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendFileBroadcast failed: ${e.message}", e)
        }
    }

    /**
     * Sends a file privately to a specific peer. If no Noise session is established,
     * a handshake will be initiated and the send is deferred/aborted for now.
     *
     * @param recipientPeerID Target peer.
     * @param file            Encoded metadata and chunks descriptor of the file to send.
     */
    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        try {
            serviceScope.launch {
                if (!encryptionService.hasEstablishedSession(recipientPeerID)) {
                    messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)
                    return@launch
                }
                val tlv = file.encode() ?: return@launch
                val np = NoisePayload(type = NoisePayloadType.FILE_TRANSFER, data = tlv).encode()
                val enc = encryptionService.encrypt(np, recipientPeerID)
                val pkt = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = enc,
                    signature = null,
                    ttl = MAX_TTL
                )
                val signed = signPacketBeforeBroadcast(pkt)
                val transferId = sha256Hex(tlv)
                broadcastPacket(RoutedPacket(signed, transferId = transferId))
            }
        } catch (e: Exception) {
            Log.e(TAG, "sendFilePrivate failed: ${e.message}", e)
        }
    }

    /**
     * Attempts to cancel an in-flight file transfer identified by its transferId.
     *
     * @param transferId Deterministic id (usually sha256 of the file TLV).
     * @return true if a transfer with this id was found and cancellation was scheduled, false otherwise.
     */
    fun cancelFileTransfer(transferId: String): Boolean {
        return false
    }

    /**
     * Computes the SHA-256 of the given bytes and returns a lowercase hex string.
     * Falls back to the byte-length in hex if MessageDigest is unavailable.
     */
    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes); md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { bytes.size.toString(16) }

    /**
     * Broadcasts an ANNOUNCE packet to the entire mesh.
     */
    fun sendBroadcastAnnounce() {
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            val staticKey = encryptionService.getStaticPublicKey() ?: run {
                Log.e(TAG, "No static public key available for announcement"); return@launch
            }
            val signingKey = encryptionService.getSigningPublicKey() ?: run {
                Log.e(TAG, "No signing public key available for announcement"); return@launch
            }

            val tlvPayload = IdentityAnnouncement(nickname, staticKey, signingKey).encode() ?: return@launch

            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = MAX_TTL,
                senderID = myPeerID,
                payload = tlvPayload
            )
            val signed = signPacketBeforeBroadcast(announcePacket)

            broadcastPacket(RoutedPacket(signed))
            try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
        }
    }

    /**
     * Sends an ANNOUNCE packet to a specific peer.
     */
    fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return

        val nickname = delegate?.getNickname() ?: myPeerID
        val staticKey = encryptionService.getStaticPublicKey() ?: return
        val signingKey = encryptionService.getSigningPublicKey() ?: return

        val tlvPayload = IdentityAnnouncement(nickname, staticKey, signingKey).encode() ?: return

        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = tlvPayload
        )
        val signed = signPacketBeforeBroadcast(packet)

        broadcastPacket(RoutedPacket(signed))
        peerManager.markPeerAsAnnouncedTo(peerID)
        try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
    }

    /**
     * Sends a LEAVE announcement to all peers before disconnecting.
     */
    private fun sendLeaveAnnouncement() {
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        broadcastPacket(RoutedPacket(signPacketBeforeBroadcast(packet)))
    }

    /** @return Mapping of peer IDs to nicknames. */
    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()

    /** @return Mapping of peer IDs to RSSI values. */
    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()

    /**
     * @return true if a Noise session with the peer is fully established.
     */
    fun hasEstablishedSession(peerID: String) = encryptionService.hasEstablishedSession(peerID)

    /**
     * @return a human-readable Noise session state for the given peer (implementation-defined).
     */
    fun getSessionState(peerID: String) = encryptionService.getSessionState(peerID)

    /**
     * Triggers a Noise handshake with the given peer. Safe to call repeatedly; no-op if already handshaking/established.
     */
    fun initiateNoiseHandshake(peerID: String) = messageHandler.delegate?.initiateNoiseHandshake(peerID)

    /**
     * @return the stored public-key fingerprint (hex) for a peer, if known.
     */
    fun getPeerFingerprint(peerID: String): String? = peerManager.getFingerprintForPeer(peerID)

    /**
     * Retrieves the full profile for a peer, including keys and verification state, if available.
     */
    fun getPeerInfo(peerID: String): PeerInfo? = peerManager.getPeerInfo(peerID)

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
    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean = peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)

    /**
     * @return the local device’s long-term identity fingerprint (hex).
     */
    fun getIdentityFingerprint(): String = encryptionService.getIdentityFingerprint()

    /**
     * @return true if the UI should show an “encrypted” indicator for this peer.
     */
    fun shouldShowEncryptionIcon(peerID: String) = encryptionService.hasEstablishedSession(peerID)

    /**
     * @return a snapshot list of peers with established Noise sessions.
     */
    fun getEncryptedPeers(): List<String> = emptyList()

    /**
     * @return the current IPv4/IPv6 address of a connected peer, if any.
     * Prefers the scoped IPv6 address format.
     */
    fun getDeviceAddressForPeer(peerID: String): String? =
        peerSockets[peerID]?.let { resolveScopedAddress(it) }

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
    fun getDeviceAddressToPeerMapping(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        peerSockets.forEach { (pid, sock) ->
            map[pid] = resolveScopedAddress(sock) ?: "unknown"
        }
        return map
    }

    /**
     * @return map of peer ID to nickname, bridged for UI warning fix.
     */
    fun getPeerNicknamesMap(): Map<String, String?> = peerManager.getAllPeerNicknames()

    /** Returns recently discovered peer IDs via Aware discovery (may not be connected). */
    fun getDiscoveredPeerIds(): Set<String> =
        (handleToPeerId.values + discoveredTimestamps.keys).filter { it.isNotBlank() }.toSet()

    /**
     * Utility for logs/UI: pretty-prints one peer-to-address mapping per line.
     */
    fun printDeviceAddressesForPeers(): String =
        getDeviceAddressToPeerMapping().entries.joinToString("\n") { "${it.key} -> ${it.value}" }

    /**
     * @return A detailed string containing the debug status of all mesh components.
     */
    fun getDebugStatus(): String = buildString {
        appendLine("=== Wi-Fi Aware Mesh Debug Status ===")
        appendLine("My Peer ID: $myPeerID")
        appendLine("Peers: ${peerSockets.keys}")
        appendLine(peerManager.getDebugInfo(getDeviceAddressToPeerMapping()))
        appendLine(fragmentManager.getDebugInfo())
        appendLine(securityManager.getDebugInfo())
        appendLine(storeForwardManager.getDebugInfo())
        appendLine(messageHandler.getDebugInfo())
        appendLine(packetProcessor.getDebugInfo())
    }

    /** Utility extension to safely close sockets. */
    private fun Socket.closeQuietly() = try { close() } catch (_: Exception) {}

    /** Utility extension to safely close server sockets. */
    private fun ServerSocket.closeQuietly() = try { close() } catch (_: Exception) {}
}


/**
 * Delegate interface for mesh service callbacks (maintains exact same interface)
 */
interface WifiAwareMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String)
    fun didReceiveReadReceipt(messageID: String, recipientPeerID: String)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
}
