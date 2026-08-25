package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.protocol.MessagePadding
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.toHexString
import com.bitchat.android.services.VerificationService
import com.bitchat.android.service.TransportBridgeService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.*
import kotlin.math.sign
import kotlin.random.Random

/**
 * Bluetooth mesh service - REFACTORED to use component-based architecture
 * 100% compatible with iOS version and maintains exact same UUIDs, packet format, and protocol logic
 * 
 * This is now a coordinator that orchestrates the following components:
 * - PeerManager: Peer lifecycle management
 * - FragmentManager: Message fragmentation and reassembly  
 * - SecurityManager: Security, duplicate detection, encryption
 * - StoreForwardManager: Offline message caching
 * - MessageHandler: Message type processing and relay logic
 * - BluetoothConnectionManager: BLE connections and GATT operations
 * - PacketProcessor: Incoming packet routing
 */
class BluetoothMeshService(private val context: Context) : TransportBridgeService.TransportLayer {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    
    companion object {
        private const val TAG = "BluetoothMeshService"
        private val MAX_TTL: UByte = com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS
        private const val PEER_DISCONNECT_GRACE_MS = com.bitchat.android.util.AppConstants.Mesh.PEER_DISCONNECT_GRACE_MS
    }
    
    // Core components - each handling specific responsibilities
    private val encryptionService = EncryptionService(context)

    // My peer identification - derived from persisted Noise identity fingerprint (first 16 hex chars)
    val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)
    private val peerManager = PeerManager()
    private val fragmentManager = FragmentManager()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val readReceiptRetrySender = RetryingControlPacketSender(serviceScope)
    private val authenticatedPeerStateStore = SecureAuthenticatedPeerStateStore(context)
    private val authenticatedPeerState by lazy {
        AuthenticatedPeerStateCoordinator(
            scope = serviceScope,
            authenticatedSessionProvider = encryptionService::getAuthenticatedSession,
            withAuthenticatedSession = encryptionService::withAuthenticatedSession,
            store = authenticatedPeerStateStore,
            localStateProvider = {
                AuthenticatedPeerState(
                    PeerCapabilities.LOCAL_SUPPORTED,
                    requireNotNull(encryptionService.getSigningPublicKey())
                )
            },
            applyAuthenticatedState = peerManager::applyAuthenticatedPeerState,
            sendState = ::sendAuthenticatedPeerState,
            onResolution = { peerID -> delegate?.didResolvePrivateMediaPolicy(peerID) }
        )
    }
    private val privateMediaSecurity by lazy { PrivateMediaSecurityController(
        authenticatedSessionProvider = encryptionService::getAuthenticatedSession,
        peerStateStatusProvider = authenticatedPeerState::status,
        isPrivateMediaPinned = authenticatedPeerState::isPrivateMediaPinned
    ) }
    private val privateMediaPreparer by lazy {
        PrivateMediaTransferPreparer(
            senderID = hexStringToByteArray(myPeerID),
            ttl = MAX_TTL,
            policyProvider = privateMediaSecurity::sendPolicy,
            encrypt = { plaintext, peerID, authenticatedSession ->
                try {
                    PrivateMediaEncryptionResult.Success(
                        encryptionService.encryptForSession(
                            plaintext,
                            peerID,
                            authenticatedSession
                        )
                    )
                } catch (_: com.bitchat.android.noise.NoiseSessionError.SessionGenerationChanged) {
                    PrivateMediaEncryptionResult.GenerationChanged
                } catch (_: com.bitchat.android.noise.NoiseSessionError.SessionNotFound) {
                    PrivateMediaEncryptionResult.GenerationChanged
                } catch (_: com.bitchat.android.noise.NoiseSessionError.SessionNotEstablished) {
                    PrivateMediaEncryptionResult.GenerationChanged
                } catch (_: Exception) {
                    PrivateMediaEncryptionResult.Failed
                }
            },
            finalizeRoutedAndSigned = ::routeAndSignPrivateMediaStrict,
            fragment = fragmentManager::createFragments
        )
    }
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID, context.applicationContext)
    internal val connectionManager = BluetoothConnectionManager(context, myPeerID, fragmentManager) // Made internal for access
    private val packetProcessor = PacketProcessor(myPeerID)
    private data class VoiceFrameRequest(val recipientPeerID: String?, val payload: ByteArray)
    private val voiceFrameQueue = Channel<VoiceFrameRequest>(capacity = 128)
    private lateinit var gossipSyncManager: GossipSyncManager
    // Service-level notification manager for background (no-UI) DMs
    private val serviceNotificationManager = com.bitchat.android.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext)
    )
    
    // Service state management
    private var isActive = false
    
    // Delegate for message callbacks (maintains same interface)
    var delegate: BluetoothMeshDelegate? = null
    
    // Coroutines
    // Tracks whether this instance has been terminated via stopServices()
    private var terminated = false
    
    init {
        serviceScope.launch {
            for (request in voiceFrameQueue) dispatchVoiceFrame(request)
        }
        Log.i(TAG, "Initializing BluetoothMeshService for peer=$myPeerID")
        VerificationService.configure(encryptionService)
        setupDelegates()
        messageHandler.packetProcessor = packetProcessor
        //startPeriodicDebugLogging()

        // Flush queued private messages as soon as a BLE Noise session authenticates,
        // instead of relying on the foreground-only UI poll.
        encryptionService.onSessionEstablished = { peerID ->
            Log.d(TAG, "BLE Noise session established with ${peerID.take(8)}")
            try {
                com.bitchat.android.services.MessageRouter
                    .tryGetInstance()
                    ?.onSessionEstablished(peerID)
            } catch (_: Exception) { }
        }

        // Initialize sync manager (needs serviceScope)
        gossipSyncManager = GossipSyncManager(
            myPeerID = myPeerID,
            scope = serviceScope,
            configProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = try {
                    com.bitchat.android.ui.debug.DebugPreferenceManager.getSeenPacketCapacity(500)
                } catch (_: Exception) { 500 }

                override fun gcsMaxBytes(): Int = try {
                    com.bitchat.android.ui.debug.DebugPreferenceManager.getGcsMaxFilterBytes(400)
                } catch (_: Exception) { 400 }

                override fun gcsTargetFpr(): Double = try {
                    com.bitchat.android.ui.debug.DebugPreferenceManager.getGcsFprPercent(1.0) / 100.0
                } catch (_: Exception) { 0.01 }
            }
        )

        com.bitchat.android.service.MeshServiceHolder.setGossipManager(gossipSyncManager) { packet ->
            signPacketBeforeBroadcast(packet)
        }
        if (isBleTransportEnabled()) {
            TransportBridgeService.register("BLE", this)
        }
        
        // Inject dynamic direct connection check into PeerManager
        // Matches iOS logic: checks if we have an active hardware mapping for this peer
        peerManager.isPeerDirectlyConnected = { peerID ->
            connectionManager.addressPeerMap.containsValue(peerID)
        }
    }

    override fun send(packet: RoutedPacket) {
        if (!isBleTransportEnabled()) return
        connectionManager.broadcastPacket(packet)
    }

    override suspend fun sendAndReport(packet: RoutedPacket): Boolean {
        if (!isBleTransportEnabled()) return false
        return connectionManager.broadcastControlPacketAndAwaitAcceptance(packet)
    }

    override fun sendToPeer(peerID: String, packet: BitchatPacket) {
        if (!isBleTransportEnabled()) return
        connectionManager.sendPacketToPeer(peerID, packet)
    }

    private fun broadcastRoutedPacket(routed: RoutedPacket): Boolean {
        if (!isBleTransportEnabled()) return false
        val queued = connectionManager.broadcastPacket(routed)
        if (!queued) return false
        TransportBridgeService.broadcast("BLE", routed)
        return true
    }

    private suspend fun broadcastRoutedPacketAndReport(routed: RoutedPacket): Boolean {
        if (!isBleTransportEnabled()) return false
        val acceptedByBle =
            connectionManager.broadcastControlPacketAndAwaitAcceptance(routed)
        val acceptedByBridgedTransport =
            TransportBridgeService.broadcastAndReport("BLE", routed)
        return acceptedByBle || acceptedByBridgedTransport
    }

    private fun isBleTransportEnabled(): Boolean {
        return try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().bleEnabled.value
        } catch (_: Exception) {
            try { com.bitchat.android.ui.debug.DebugPreferenceManager.getBleEnabled(true) } catch (_: Exception) { true }
        }
    }
    
    /**
     * Start periodic debug logging every 10 seconds
     */
    private fun startPeriodicDebugLogging() {
        serviceScope.launch {
            while (isActive) {
                try {
                    delay(10000) // 10 seconds
                    if (isActive) { // Double-check before logging
                        val debugInfo = getDebugStatus()
                        Log.d(TAG, "Periodic debug status:\n$debugInfo")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic debug logging: ${e.message}")
                }
            }
        }
    }

    /**
     * Setup delegate connections between components
     */
    private fun setupDelegates() {
        // Provide nickname resolver to BLE broadcaster and debug manager
        try {
            val resolver: (String) -> String? = { pid -> peerManager.getPeerNickname(pid) }
            connectionManager.setNicknameResolver(resolver)
            debugManager?.setNicknameResolver(resolver)
        } catch (_: Exception) { }
        // PeerManager delegates to main mesh service delegate
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerListUpdated(peerIDs: List<String>) {
                // Update process-wide state first
                try { com.bitchat.android.services.AppStateStore.setTransportPeers("BLE", peerIDs) } catch (_: Exception) { }
                // Then notify UI delegate if attached
                delegate?.didUpdatePeerList(peerIDs)
            }
            override fun onPeerRemoved(peerID: String) {
                authenticatedPeerState.clear(peerID)
                try { gossipSyncManager.removeAnnouncementForPeer(peerID) } catch (_: Exception) { }
                // Remove from mesh graph topology to prevent routing through stale peers
                try { com.bitchat.android.services.meshgraph.MeshGraphService.getInstance().removePeer(peerID) } catch (_: Exception) { }

                // Also drop any Noise session state for this peer when they go offline
                try {
                    encryptionService.removePeer(peerID)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove Noise session for $peerID: ${e.message}")
                }
            }
        }
        
        // SecurityManager delegate for key exchange notifications
        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(
                peerID: String,
                authenticatedRemoteStaticKey: ByteArray,
                authenticatedSessionToken: ByteArray,
                directRelayAddress: String?,
                ingressLinkID: String?
            ) {
                authenticatedPeerState.onSessionAuthenticated(
                    peerID,
                    authenticatedRemoteStaticKey,
                    authenticatedSessionToken
                )
                // Send announcement and cached messages after key exchange
                serviceScope.launch {
                    delay(100)
                    sendAnnouncementToPeer(peerID)
                    
                    delay(1000)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }
            
            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {
                // Send Noise handshake response
                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    ttl = MAX_TTL
                )
                // Sign the handshake response
                val signedPacket = signPacketBeforeBroadcast(responsePacket)
                broadcastRoutedPacket(RoutedPacket(signedPacket))
            }
            
            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }

            override fun getAuthenticatedSigningKey(noisePublicKey: ByteArray): ByteArray? =
                authenticatedPeerState.persistedSigningKeyFor(noisePublicKey)
        }
        
        // StoreForwardManager delegates
        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String): Boolean {
                return delegate?.isFavorite(peerID) ?: false
            }
            
            override fun isPeerOnline(peerID: String): Boolean {
                return peerManager.isPeerActive(peerID)
            }
            
            override fun sendPacket(packet: BitchatPacket) {
                broadcastRoutedPacket(RoutedPacket(packet))
            }
        }
        
        // MessageHandler delegates
        messageHandler.delegate = object : MessageHandlerDelegate {
            // Peer management
            override fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
                return peerManager.addOrUpdatePeer(peerID, nickname)
            }
            
            override fun removePeer(peerID: String) {
                peerManager.removePeer(peerID)
            }
            
            override fun updatePeerNickname(peerID: String, nickname: String) {
                peerManager.addOrUpdatePeer(peerID, nickname)
            }
            
            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }
            
            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }
            
            override fun getMyNickname(): String? {
                return delegate?.getNickname()
            }
            
            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }
            
            override fun updatePeerInfoFromVerifiedAnnouncement(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean, capabilities: com.bitchat.android.model.PeerCapabilities?): Boolean {
                return peerManager.updatePeerInfoFromVerifiedAnnouncement(
                    peerID,
                    nickname,
                    noisePublicKey,
                    signingPublicKey,
                    isVerified,
                    capabilities
                )
            }

            // Packet operations
            override fun sendPacket(packet: BitchatPacket) {
                // Sign the packet before broadcasting
                val signedPacket = signPacketBeforeBroadcast(packet)
                broadcastRoutedPacket(RoutedPacket(signedPacket))
            }
            
            override fun relayPacket(routed: RoutedPacket) {
                broadcastRoutedPacket(routed)
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }
            
            // Cryptographic operations
            override fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.verifySignature(packet, peerID)
            }
            
            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
                return securityManager.encryptForPeer(data, recipientPeerID)
            }
            
            override fun decryptFromPeer(
                encryptedData: ByteArray,
                senderPeerID: String
            ): com.bitchat.android.noise.NoiseDecryptionResult? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }
            
            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
                return encryptionService.verifyEd25519Signature(signature, data, publicKey)
            }

            override fun getAuthenticatedSigningKey(noisePublicKey: ByteArray): ByteArray? =
                authenticatedPeerState.persistedSigningKeyFor(noisePublicKey)
            
            // Noise protocol operations
            override fun hasNoiseSession(peerID: String): Boolean {
                return encryptionService.hasEstablishedSession(peerID)
            }

            override fun removeNoiseSession(peerID: String) {
                try {
                    encryptionService.removePeer(peerID)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to remove Noise session for $peerID: ${e.message}")
                }
            }
            
            override fun initiateNoiseHandshake(peerID: String) {
                try {
                    // Initiate proper Noise handshake with specific peer
                    val handshakeData = encryptionService.initiateHandshake(peerID)

                    if (handshakeData != null) {
                        val packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.NOISE_HANDSHAKE.value,
                            senderID = hexStringToByteArray(myPeerID),
                            recipientID = hexStringToByteArray(peerID),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = handshakeData,
                            ttl = MAX_TTL
                        )

                        // Sign the handshake packet before broadcasting
                        val signedPacket = signPacketBeforeBroadcast(packet)
                        broadcastRoutedPacket(RoutedPacket(signedPacket))
                    } else {
                        Log.w(TAG, "Failed to generate Noise handshake data for $peerID")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initiate Noise handshake with $peerID: ${e.message}")
                }
            }
            
            override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? {
                return try {
                    encryptionService.processHandshakeMessage(payload, peerID)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process handshake message from $peerID: ${e.message}")
                    null
                }
            }

            override fun onAuthenticatedPeerStateReceived(
                peerID: String,
                state: AuthenticatedPeerState,
                authenticatedSession: com.bitchat.android.noise.AuthenticatedNoiseSession
            ) {
                authenticatedPeerState.receive(peerID, state, authenticatedSession)
            }
            
            // Message operations  
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return delegate?.decryptChannelMessage(encryptedContent, channel)
            }
            
            // Callbacks
            override fun onMessageReceived(message: BitchatMessage) {
                // Private-message admission is authoritative. In particular, do not forward a
                // callback or notify after panic mode rejected the message while wiping state.
                if (
                    !com.bitchat.android.services.IncomingMessageAdmission
                        .admitToAppState(message)
                ) return

                // And forward to UI delegate if attached
                delegate?.didReceiveMessage(message)

                // If no UI delegate attached (app closed), show DM notification via service manager
                if (delegate == null && message.isPrivate && message.sender != "system") {
                    try {
                        val senderPeerID = message.senderPeerID
                        if (senderPeerID != null) {
                            val nick = try { peerManager.getPeerNickname(senderPeerID) } catch (_: Exception) { null } ?: senderPeerID
                            val preview = com.bitchat.android.ui.NotificationTextUtils.buildPrivateMessagePreview(message)
                            serviceNotificationManager.setAppBackgroundState(true)
                            serviceNotificationManager.showPrivateMessageNotification(senderPeerID, nick, preview)
                        }
                    } catch (_: Exception) { }
                }
            }
            
            override fun onChannelLeave(channel: String, fromPeer: String) {
                delegate?.didReceiveChannelLeave(channel, fromPeer)
            }
            
            override fun onDeliveryAckReceived(messageID: String, peerID: String) {
                // Status events can arrive while MainActivity has detached the UI delegate.
                // Persist first so the next UI collector observes the advancement.
                try {
                    com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(
                        messageID,
                        com.bitchat.android.model.DeliveryStatus.Delivered(peerID, Date())
                    )
                } catch (_: Exception) { }
                delegate?.didReceiveDeliveryAck(messageID, peerID)
            }
            
            override fun onReadReceiptReceived(messageID: String, peerID: String) {
                try {
                    com.bitchat.android.services.AppStateStore.updatePrivateMessageStatus(
                        messageID,
                        com.bitchat.android.model.DeliveryStatus.Read(peerID, Date())
                    )
                } catch (_: Exception) { }
                delegate?.didReceiveReadReceipt(messageID, peerID)
            }

            override fun onVerifyChallengeReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                delegate?.didReceiveVerifyChallenge(peerID, payload, timestampMs)
            }

            override fun onVerifyResponseReceived(peerID: String, payload: ByteArray, timestampMs: Long) {
                delegate?.didReceiveVerifyResponse(peerID, payload, timestampMs)
            }
        }
        
        // PacketProcessor delegates
        packetProcessor.delegate = object : PacketProcessorDelegate {
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.validatePacket(packet, peerID)
            }
            
            override fun updatePeerLastSeen(peerID: String) {
                peerManager.updatePeerLastSeen(peerID)
            }
            
            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }
            
            // Network information for relay manager
            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }
            
            override fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
                return runBlocking { securityManager.handleNoiseHandshake(routed) }
            }
            
            override fun handleNoiseEncrypted(routed: RoutedPacket): Boolean {
                return runBlocking { messageHandler.handleNoiseEncrypted(routed) }
            }
            
            override suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
                val result = messageHandler.handleAnnounceWithResult(routed)
                if (result !is AnnounceHandlingResult.Accepted) return false

                DirectLinkAnnouncementPolicy.observationFor(routed, MAX_TTL)?.let { observation ->
                    if (connectionManager.observePeerIfCurrent(
                            observation.relayAddress,
                            observation.ingressLinkID,
                            observation.peerID
                        )
                    ) {
                        Log.d(
                            TAG,
                            "Observed direct BLE route ${observation.relayAddress} to ${observation.peerID}"
                        )
                        try { peerManager.refreshPeerList() } catch (_: Exception) { }
                        try {
                            gossipSyncManager.scheduleInitialSyncToPeer(observation.peerID, 1_000)
                        } catch (_: Exception) { }
                    } else {
                        Log.d(TAG, "Ignoring ANNOUNCE from stale BLE link ${observation.relayAddress}")
                    }
                }
                try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                return true
            }
            
            override fun handleMessage(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleMessage(routed) }
                // Track broadcast messages for sync
                try {
                    val pkt = routed.packet
                    val isBroadcast = (pkt.recipientID == null || pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && pkt.type == MessageType.MESSAGE.value) {
                        gossipSyncManager.onPublicPacketSeen(pkt)
                    }
                } catch (_: Exception) { }
            }

            override fun handleVoiceFrame(routed: RoutedPacket): Boolean =
                messageHandler.handlePublicVoiceFrame(routed)
            
            override fun handleLeave(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleLeave(routed) }
            }
            
            override fun handleFragment(packet: BitchatPacket): BitchatPacket? {
                // Track broadcast fragments for gossip sync
                try {
                    val isBroadcast = (packet.recipientID == null || packet.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && packet.type == MessageType.FRAGMENT.value) {
                        gossipSyncManager.onPublicPacketSeen(packet)
                    }
                } catch (_: Exception) { }
                return fragmentManager.handleFragment(packet)
            }
            
            override fun sendAnnouncementToPeer(peerID: String) {
                this@BluetoothMeshService.sendAnnouncementToPeer(peerID)
            }
            
            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }
            
            override fun relayPacket(routed: RoutedPacket) {
                broadcastRoutedPacket(routed)
            }

            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                val sentOverBle = connectionManager.sendToPeer(peerID, routed)
                TransportBridgeService.sendToPeer("BLE", peerID, routed.packet)
                return sentOverBle
            }
            
            override fun handleRequestSync(routed: RoutedPacket) {
                // Decode request and respond with missing packets
                val fromPeer = routed.peerID ?: return
                val req = RequestSyncPacket.decode(routed.packet.payload) ?: return
                gossipSyncManager.handleRequestSync(fromPeer, req)
            }
        }
        
        // BluetoothConnectionManager delegates
        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
        override fun onPacketReceived(
            packet: BitchatPacket,
            peerID: String,
            device: android.bluetooth.BluetoothDevice?,
            ingressLinkID: String
        ) {
            // Log incoming for debug graphs (do not double-count anywhere else)
            try {
                com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().logIncoming(
                    packet = packet,
                    fromPeerID = peerID,
                    fromNickname = null,
                    fromDeviceAddress = device?.address,
                    myPeerID = myPeerID
                )
            } catch (_: Exception) { }
            packetProcessor.processPacket(
                RoutedPacket(packet, peerID, device?.address, ingressLinkID = ingressLinkID)
            )
        }
            
            override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
                // Send initial announcements after services are ready
                serviceScope.launch {
                    Log.i(TAG, "Device connected: ${device.address}")
                    delay(200)
                    sendBroadcastAnnounce()
                }
                // Verbose debug: device connected
                try {
                    val addr = device.address
                    val peer = connectionManager.addressPeerMap[addr]
                    val nick = peer?.let { peerManager.getPeerNickname(it) } ?: "unknown"
                    com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
                        .logPeerConnection(peer ?: "unknown", nick, addr, isInbound = !connectionManager.isClientConnection(addr)!!)
                } catch (_: Exception) { }
            }

            override fun onDeviceDisconnected(
                device: android.bluetooth.BluetoothDevice,
                linkID: String?,
                peerID: String?
            ) {
                Log.i(TAG, "Device disconnected: ${device.address} (peerID: $peerID)")

                // refresh peer list on disconnect.
                try { peerManager.refreshPeerList() } catch (_: Exception) { }

                // ConnectionTracker already removes an observed mapping only when this exact
                // link is still current. Do not remove by reusable address here: this may be a late
                // disconnect callback from a replaced GATT connection.

                // If the peer that used this link does not come back within a short grace
                // period (no other link, no traffic), tear down their Noise session instead of
                // waiting for the 3-minute stale-peer sweep.
                if (peerID != null) {
                    val deviceAddress = device.address
                    val disconnectedAt = System.currentTimeMillis()
                    serviceScope.launch {
                        delay(PEER_DISCONNECT_GRACE_MS)
                        try {
                            val linkBack =
                                connectionManager.addressPeerMap.containsKey(deviceAddress) ||
                                    connectionManager.addressPeerMap.containsValue(peerID)
                            val lastSeen = peerManager.getPeerInfo(peerID)?.lastSeen ?: 0L
                            val seenAfterDisconnect = lastSeen > disconnectedAt
                            if (!linkBack && !seenAfterDisconnect) {
                                Log.i(TAG, "Peer $peerID did not return after disconnect; removing peer and Noise session")
                                peerManager.removePeer(peerID)
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Disconnect grace check failed for $peerID: ${e.message}")
                        }
                    }
                }
            }
            
            override fun onRSSIUpdated(deviceAddress: String, rssi: Int) {
                // Find the peer ID for this device address and update RSSI in PeerManager
                connectionManager.addressPeerMap[deviceAddress]?.let { peerID ->
                    peerManager.updatePeerRSSI(peerID, rssi)
                }
            }
        }
    }

    /**
     * Start the mesh service
     */
    fun startServices() {
        // Prevent double starts (defensive programming)
        if (isActive) {
            Log.w(TAG, "Mesh service already active, ignoring duplicate start request")
            return
        }
        if (!isBleTransportEnabled()) {
            Log.i(TAG, "BLE transport disabled by debug settings; not starting mesh service")
            connectionManager.disableTransport()
            TransportBridgeService.unregister("BLE")
            com.bitchat.android.service.MeshServiceHolder.stopSharedGossip("BLE")
            try { com.bitchat.android.services.AppStateStore.clearTransportPeers("BLE") } catch (_: Exception) { }
            try { com.bitchat.android.services.AppStateStore.clearTransportDirectPeers("BLE") } catch (_: Exception) { }
            return
        }
        if (terminated) {
            // This instance's scope was cancelled previously; refuse to start to avoid using dead scopes.
            Log.e(TAG, "Mesh service instance was terminated; create a new instance instead of restarting")
            return
        }
        
        Log.i(TAG, "Starting Bluetooth mesh service with peer ID: $myPeerID")
        
        if (connectionManager.startServices()) {
            isActive = true
            TransportBridgeService.register("BLE", this)
            
            // Start periodic syncs
            com.bitchat.android.service.MeshServiceHolder.startSharedGossip("BLE")
        } else {
            Log.e(TAG, "Failed to start Bluetooth services")
        }
    }

    /**
     * Apply the debug master transport toggle without destroying this mesh instance.
     */
    fun setBleTransportEnabled(enabled: Boolean) {
        if (enabled) {
            startServices()
        } else {
            pauseServicesForTransportDisable()
        }
    }

    private fun pauseServicesForTransportDisable() {
        Log.i(TAG, "Disabling BLE mesh transport")
        isActive = false
        com.bitchat.android.service.MeshServiceHolder.stopSharedGossip("BLE")
        TransportBridgeService.unregister("BLE")
        try { com.bitchat.android.services.AppStateStore.clearTransportPeers("BLE") } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportDirectPeers("BLE") } catch (_: Exception) { }
        connectionManager.disableTransport()
        try { peerManager.refreshPeerList() } catch (_: Exception) { }
    }
    
    /**
     * Stop all mesh services
     */
    fun stopServices() {
        if (!isActive) {
            Log.w(TAG, "Mesh service not active, ignoring stop request")
            return
        }
        
        Log.i(TAG, "Stopping Bluetooth mesh service")
        isActive = false
        TransportBridgeService.unregister("BLE")
        try { com.bitchat.android.services.AppStateStore.clearTransportPeers("BLE") } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportDirectPeers("BLE") } catch (_: Exception) { }
        
        // Send leave announcement
        sendLeaveAnnouncement()
        
        serviceScope.launch {
            delay(200) // Give leave message time to send

            // Stop all components
            com.bitchat.android.service.MeshServiceHolder.stopSharedGossip("BLE")
            connectionManager.stopServices()
            peerManager.shutdown()
            fragmentManager.shutdown()
            securityManager.shutdown()
            storeForwardManager.shutdown()
            messageHandler.shutdown()
            packetProcessor.shutdown()
            
            // Mark this instance as terminated and cancel its scope so it won't be reused
            terminated = true
            serviceScope.cancel()
            Log.i(TAG, "BluetoothMeshService terminated and scope cancelled")
        }
    }

    /**
     * Whether this instance can be safely reused. Returns false after stopServices() or if
     * any critical internal scope has been cancelled.
     */
    fun isReusable(): Boolean {
        val reusable = !terminated && serviceScope.isActive && connectionManager.isReusable()
        return reusable
    }
    
    /**
     * Send public message
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

            // Sign the packet before broadcasting
            val signedPacket = signPacketBeforeBroadcast(packet)
            broadcastRoutedPacket(RoutedPacket(signedPacket))
            // Track our own broadcast message for sync
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    /**
     * Send a file over mesh as a broadcast MESSAGE (public mesh timeline/channels).
     */
    private fun sendAuthenticatedPeerState(
        peerID: String,
        state: AuthenticatedPeerState,
        authenticatedSession: com.bitchat.android.noise.AuthenticatedNoiseSession
    ): Boolean {
        val plaintext = NoisePayload(NoisePayloadType.PEER_STATE, state.encode()).encode()
        val ciphertext = securityManager.encryptForPeer(
            plaintext,
            peerID,
            authenticatedSession
        ) ?: return false
        val packet = BitchatPacket(
            version = if (ciphertext.size > 0xFFFF) 2u else 1u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = hexStringToByteArray(myPeerID),
            recipientID = hexStringToByteArray(peerID),
            timestamp = System.currentTimeMillis().toULong(),
            payload = ciphertext,
            ttl = MAX_TTL
        )
        val signed = signPacketBeforeBroadcast(packet)
        if (signed.signature?.size != 64) return false
        broadcastRoutedPacket(RoutedPacket(signed))
        return true
    }

    fun sendFileBroadcast(file: com.bitchat.android.model.BitchatFilePacket) {
        try {
            val payload = file.encode()
            if (payload == null) {
                Log.e(TAG, "Failed to encode file packet in sendFileBroadcast")
                return
            }
        serviceScope.launch {
            val packet = BitchatPacket(
                version = 2u,  // FILE_TRANSFER uses v2 for 4-byte payload length to support large files
                type = MessageType.FILE_TRANSFER.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = payload,
                signature = null,
                ttl = MAX_TTL
            )
            val signed = signPacketBeforeBroadcast(packet)
            // Use a stable transferId based on the file TLV payload for progress tracking
            val transferId = sha256Hex(payload)
            broadcastRoutedPacket(RoutedPacket(signed, transferId = transferId))
            try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
        }
            } catch (e: Exception) {
            Log.e(TAG, "sendFileBroadcast failed (size=${file.fileSize}): ${e.message}", e)
        }
    }

    /** Safe non-interactive entry point: encrypted sends commit; legacy sends require UI consent. */
    fun sendFilePrivate(recipientPeerID: String, file: com.bitchat.android.model.BitchatFilePacket) {
        val payload = file.encode() ?: return
        when (val prepared = prepareFilePrivate(
            recipientPeerID,
            file,
            sha256Hex(payload),
            allowLegacyFallback = false
        )) {
            is PrivateMediaPreparation.Ready -> prepared.transfer.commit()
            is PrivateMediaPreparation.RequiresLegacyConsent ->
                Log.w(TAG, "Private media requires explicit one-shot legacy consent")
            PrivateMediaPreparation.NeedsHandshake -> {
                Log.d(TAG, "Private media needs a Noise handshake; initiating without sending")
                initiateNoiseHandshake(recipientPeerID)
            }
            PrivateMediaPreparation.AwaitingPeerState -> Unit
            is PrivateMediaPreparation.Rejected ->
                Log.w(TAG, "Private media blocked: ${prepared.reason}")
        }
    }

    fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        if (payload.isEmpty()) return
        voiceFrameQueue.trySend(VoiceFrameRequest(recipientPeerID, payload.copyOf()))
    }

    private fun dispatchVoiceFrame(request: VoiceFrameRequest) {
        try {
            val recipientPeerID = request.recipientPeerID
            val packet = if (recipientPeerID == null) {
                BitchatPacket(
                    version = 1u,
                    type = MessageType.VOICE_FRAME.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = request.payload,
                    ttl = MAX_TTL
                )
            } else {
                if (!encryptionService.hasEstablishedSession(recipientPeerID)) return
                val plaintext = NoisePayload(NoisePayloadType.VOICE_FRAME, request.payload).encode()
                val ciphertext = encryptionService.encrypt(plaintext, recipientPeerID)
                BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = ciphertext,
                    ttl = MAX_TTL
                )
            }
            broadcastRoutedPacket(RoutedPacket(signPacketBeforeBroadcast(packet)))
        } catch (e: Exception) {
            Log.w(TAG, "Live voice frame send failed: ${e.message}")
        }
    }

    fun prepareFilePrivate(
        recipientPeerID: String,
        file: com.bitchat.android.model.BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation {
        return when (val outcome = privateMediaPreparer.prepare(
            recipientPeerID = recipientPeerID,
            recipientID = hexStringToByteArray(recipientPeerID),
            file = file,
            allowLegacyFallback = allowLegacyFallback
        )) {
            is PrivateMediaBuildOutcome.RequiresLegacyConsent ->
                PrivateMediaPreparation.RequiresLegacyConsent(outcome.warning)
            PrivateMediaBuildOutcome.NeedsHandshake ->
                PrivateMediaPreparation.NeedsHandshake
            PrivateMediaBuildOutcome.AwaitingPeerState ->
                PrivateMediaPreparation.AwaitingPeerState
            is PrivateMediaBuildOutcome.Rejected ->
                PrivateMediaPreparation.Rejected(outcome.reason)
            is PrivateMediaBuildOutcome.Ready -> {
                val built = outcome.built
                val routed = RoutedPacket(
                    packet = built.packet,
                    transferId = transferId,
                    preparedPackets = built.fragments
                )
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(transferId, built.wireMode) {
                        if (!isActive || terminated || !isBleTransportEnabled()) {
                            false
                        } else {
                            broadcastRoutedPacket(routed)
                        }
                    }
                )
            }
        }
    }

    fun cancelFileTransfer(transferId: String): Boolean {
        return connectionManager.cancelTransfer(transferId)
    }

    // Local helper to hash payloads to a stable hex ID for progress mapping
    private fun sha256Hex(bytes: ByteArray): String = try {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        md.update(bytes)
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { bytes.size.toString(16) }
    
    /**
     * Send private message - SIMPLIFIED iOS-compatible version 
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty()) return
        // Nicknames are presentation metadata. Routing and encryption are bound to the peer ID,
        // so a temporarily unresolved nickname must never suppress a private message.
        
        serviceScope.launch {
            val finalMessageID = messageID ?: java.util.UUID.randomUUID().toString()

            // Check if we have an established Noise session
            if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                try {
                    // Create TLV-encoded private message exactly like iOS
                    val privateMessage = com.bitchat.android.model.PrivateMessagePacket(
                        messageID = finalMessageID,
                        content = content
                    )
                    
                    val tlvData = privateMessage.encode()
                    if (tlvData == null) {
                        Log.e(TAG, "Failed to encode private message with TLV")
                        return@launch
                    }
                    
                    // Create message payload with NoisePayloadType prefix: [type byte] + [TLV data]
                    val messagePayload = com.bitchat.android.model.NoisePayload(
                        type = com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE,
                        data = tlvData
                    )
                    
                    // Encrypt the payload
                    val encrypted = encryptionService.encrypt(messagePayload.encode(), recipientPeerID)
                    
                    // Create NOISE_ENCRYPTED packet exactly like iOS
                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = hexStringToByteArray(recipientPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encrypted,
                        signature = null,
                        ttl = MAX_TTL
                    )
                    
                    // Sign the packet before broadcasting
                    val signedPacket = signPacketBeforeBroadcast(packet)
                    broadcastRoutedPacket(RoutedPacket(signedPacket))

                    // The UI handles sent messages through its own sending path.

                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt private message for $recipientPeerID: ${e.message}")
                }
            } else {
                // Fire and forget - initiate handshake but don't queue exactly like iOS
                messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)
                
                // The UI handles sent messages through its own sending path.
            }
        }
    }
    
    /**
     * Send read receipt for a received private message - NEW NoisePayloadType implementation
     * Uses same encryption approach as iOS SimplifiedBluetoothService
     */
    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        serviceScope.launch {
            // Route geohash read receipts via MessageRouter instead of here
            val geo = runCatching { com.bitchat.android.services.MessageRouter.tryGetInstance() }.getOrNull()
            val isGeoAlias = try {
                val map = com.bitchat.android.nostr.GeohashAliasRegistry.snapshot()
                map.containsKey(recipientPeerID)
            } catch (_: Exception) { false }
            if (isGeoAlias && geo != null) {
                geo.sendReadReceipt(com.bitchat.android.model.ReadReceipt(messageID), recipientPeerID)
                return@launch
            }

            try {
                // Create read receipt payload using NoisePayloadType exactly like iOS
                val readReceiptPayload = com.bitchat.android.model.NoisePayload(
                    type = com.bitchat.android.model.NoisePayloadType.READ_RECEIPT,
                    data = messageID.toByteArray(Charsets.UTF_8)
                )
                
                // Encrypt the payload
                val encrypted = encryptionService.encrypt(readReceiptPayload.encode(), recipientPeerID)
                
                // Create NOISE_ENCRYPTED packet exactly like iOS
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS // Same TTL as iOS messageTTL
                )
                
                // Sign the packet before broadcasting
                val signedPacket = signPacketBeforeBroadcast(packet)
                val retryKey = "$recipientPeerID:$messageID"
                readReceiptRetrySender.enqueue(
                    key = retryKey,
                    sendAttempt = { attempt ->
                        // Keep the addressed packet on the normal broadcaster actor so receipt
                        // attempts are ordered with other BLE traffic and can use mesh routing.
                        val accepted =
                            broadcastRoutedPacketAndReport(RoutedPacket(signedPacket))
                        Log.d(
                            TAG,
                            "Read receipt attempt $attempt accepted=$accepted " +
                                "peer=${recipientPeerID.take(8)} message=${messageID.take(8)}"
                        )
                        accepted
                    },
                    onComplete = { accepted ->
                        if (accepted) {
                            try {
                                com.bitchat.android.services.SeenMessageStore
                                    .getInstance(context.applicationContext)
                                    .markReadReceiptSent(messageID)
                            } catch (_: Exception) { }
                        }
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt to $recipientPeerID: ${e.message}")
            }
        }
    }

    // MARK: QR Verification over Noise

    fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = VerificationService.buildVerifyChallenge(noiseKeyHex, nonceA)
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_CHALLENGE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID, "verify challenge")
    }

    fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        val tlv = VerificationService.buildVerifyResponse(noiseKeyHex, nonceA) ?: return
        val payload = NoisePayload(
            type = NoisePayloadType.VERIFY_RESPONSE,
            data = tlv
        )
        sendNoisePayloadToPeer(payload, peerID, "verify response")
    }

    private fun sendNoisePayloadToPeer(payload: NoisePayload, recipientPeerID: String, label: String) {
        serviceScope.launch {
            try {
                val encrypted = encryptionService.encrypt(payload.encode(), recipientPeerID)
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = encrypted,
                    signature = null,
                    ttl = com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS
                )

                val signedPacket = signPacketBeforeBroadcast(packet)
                broadcastRoutedPacket(RoutedPacket(signedPacket))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send $label to $recipientPeerID: ${e.message}")
            }
        }
    }
    
    /**
     * Send broadcast announce with TLV-encoded identity announcement - exactly like iOS
     */
    fun sendBroadcastAnnounce() {
        serviceScope.launch {
            val nickname = try { com.bitchat.android.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { myPeerID }
            
            // Get the static public key for the announcement
            val staticKey = encryptionService.getStaticPublicKey()
            if (staticKey == null) {
                Log.e(TAG, "No static public key available for announcement")
                return@launch
            }
            
            // Get the signing public key for the announcement
            val signingKey = encryptionService.getSigningPublicKey()
            if (signingKey == null) {
                Log.e(TAG, "No signing public key available for announcement")
                return@launch
            }
            
            // Create iOS-compatible IdentityAnnouncement with TLV encoding
            val announcement = IdentityAnnouncement.forLocalPeer(nickname, staticKey, signingKey)
            var tlvPayload = announcement.encode()
            if (tlvPayload == null) {
                Log.e(TAG, "Failed to encode announcement as TLV")
                return@launch
            }

            // Append gossip TLV containing up to 10 direct neighbors (compact IDs)
            try {
                val directPeers = getDirectPeerIDsForGossip()
                if (directPeers.isNotEmpty()) {
                    val gossip = com.bitchat.android.services.meshgraph.GossipTLV.encodeNeighbors(directPeers)
                    tlvPayload = tlvPayload + gossip
                }
                // Always update our own node in the mesh graph with the neighbor list we used
                try {
                    com.bitchat.android.services.meshgraph.MeshGraphService.getInstance()
                        .updateFromAnnouncement(myPeerID, nickname, directPeers, System.currentTimeMillis().toULong())
                } catch (_: Exception) { }
            } catch (_: Exception) { }
            
            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = MAX_TTL,
                senderID = myPeerID,
                payload = tlvPayload
            )
            
            // Sign the packet using our signing key (exactly like iOS)
            val signedPacket = encryptionService.signData(announcePacket.toBinaryDataForSigning()!!)?.let { signature ->
                announcePacket.copy(signature = signature)
            } ?: announcePacket
            
            broadcastRoutedPacket(RoutedPacket(signedPacket))
            // Track announce for sync
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }
    
    /**
     * Send announcement to specific peer with TLV-encoded identity announcement - exactly like iOS
     */
    fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        
        val nickname = try { com.bitchat.android.services.NicknameProvider.getNickname(context, myPeerID) } catch (_: Exception) { myPeerID }
        
        // Get the static public key for the announcement
        val staticKey = encryptionService.getStaticPublicKey()
        if (staticKey == null) {
            Log.e(TAG, "No static public key available for peer announcement")
            return
        }
        
        // Get the signing public key for the announcement
        val signingKey = encryptionService.getSigningPublicKey()
        if (signingKey == null) {
            Log.e(TAG, "No signing public key available for peer announcement")
            return
        }
        
        // Create iOS-compatible IdentityAnnouncement with TLV encoding
        val announcement = IdentityAnnouncement.forLocalPeer(nickname, staticKey, signingKey)
        var tlvPayload = announcement.encode()
        if (tlvPayload == null) {
            Log.e(TAG, "Failed to encode peer announcement as TLV")
            return
        }

        // Append gossip TLV containing up to 10 direct neighbors (compact IDs)
        try {
            val directPeers = getDirectPeerIDsForGossip()
            if (directPeers.isNotEmpty()) {
                val gossip = com.bitchat.android.services.meshgraph.GossipTLV.encodeNeighbors(directPeers)
                tlvPayload = tlvPayload + gossip
            }
            // Always update our own node in the mesh graph with the neighbor list we used
            try {
                com.bitchat.android.services.meshgraph.MeshGraphService.getInstance()
                    .updateFromAnnouncement(myPeerID, nickname, directPeers, System.currentTimeMillis().toULong())
            } catch (_: Exception) { }
        } catch (_: Exception) { }
        
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = tlvPayload
        )
        
        // Sign the packet using our signing key (exactly like iOS)
        val signedPacket = encryptionService.signData(packet.toBinaryDataForSigning()!!)?.let { signature ->
            packet.copy(signature = signature)
        } ?: packet
        
        broadcastRoutedPacket(RoutedPacket(signedPacket))
        peerManager.markPeerAsAnnouncedTo(peerID)

        // Track announce for sync
        try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
    }

    /**
     * Collect up to 10 direct neighbors for gossip TLV.
     */
    private fun getDirectPeerIDsForGossip(): List<String> {
        return try {
            // Prefer verified peers that are currently marked as direct
            val verified = peerManager.getVerifiedPeers()
            val direct = verified.filter { it.value.isDirectConnection }.keys.toSet()
            // Publish this transport's direct peers and gossip the cross-transport union so a
            // node connected via multiple transports advertises a complete neighbor list.
            try { com.bitchat.android.services.AppStateStore.setTransportDirectPeers("BLE", direct) } catch (_: Exception) { }
            val union = try {
                com.bitchat.android.services.AppStateStore.getDirectPeers().ifEmpty { direct }
            } catch (_: Exception) { direct }
            union.distinct().take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Send leave announcement
     */
    private fun sendLeaveAnnouncement() {
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = byteArrayOf()
        )
        
        // Sign the packet before broadcasting
        val signedPacket = signPacketBeforeBroadcast(packet)
        broadcastRoutedPacket(RoutedPacket(signedPacket))
    }
    
    /**
     * Get peer nicknames
     */
    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()
    
    /**
     * Get peer RSSI values  
     */
    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()
    
    /**
     * Check if we have an established Noise session with a peer  
     */
    fun hasEstablishedSession(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get session state for a peer (for UI state display)
     */
    fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState {
        return encryptionService.getSessionState(peerID)
    }
    
    /**
     * Initiate Noise handshake with a specific peer (public API)
     */
    fun initiateNoiseHandshake(peerID: String) {
        // Delegate to the existing implementation in the MessageHandler delegate
        messageHandler.delegate?.initiateNoiseHandshake(peerID)
    }
    
    /**
     * Get peer fingerprint for identity management
     */
    fun getPeerFingerprint(peerID: String): String? {
        return peerManager.getFingerprintForPeer(peerID)
    }

    /**
     * Get current active peer count (for status/notifications)
     */
    fun getActivePeerCount(): Int {
        return try { peerManager.getActivePeerCount() } catch (_: Exception) { 0 }
    }

    /**
     * Get peer info for verification purposes
     */
    fun getPeerInfo(peerID: String): PeerInfo? {
        return peerManager.getPeerInfo(peerID)
    }

    /**
     * Update peer information with verification data
     */
    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean {
        return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
    }
    
    /**
     * Get our identity fingerprint
     */
    fun getIdentityFingerprint(): String {
        return encryptionService.getIdentityFingerprint()
    }

    fun getStaticNoisePublicKey(): ByteArray? {
        return encryptionService.getStaticPublicKey()
    }
    
    /**
     * Check if encryption icon should be shown for a peer
     */
    fun shouldShowEncryptionIcon(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get all peers with established encrypted sessions
     */
    fun getEncryptedPeers(): List<String> {
        // SIMPLIFIED: Return empty list for now since we don't have direct access to sessionManager
        // This method is not critical for the session retention fix
        return emptyList()
    }
    
    /**
     * Get device address for a specific peer ID
     */
    fun getDeviceAddressForPeer(peerID: String): String? {
        return connectionManager.addressPeerMap.entries.find { it.value == peerID }?.key
    }
    
    /**
     * Get all device addresses mapped to their peer IDs
     */
    fun getDeviceAddressToPeerMapping(): Map<String, String> {
        return connectionManager.addressPeerMap.toMap()
    }
    
    /**
     * Print device addresses for all connected peers
     */
    fun printDeviceAddressesForPeers(): String {
        return peerManager.getDebugInfoWithDeviceAddresses(connectionManager.addressPeerMap)
    }

    /**
     * Get debug status information
     */
    fun getDebugStatus(): String {
        return buildString {
            appendLine("=== Bluetooth Mesh Service Debug Status ===")
            appendLine("My Peer ID: $myPeerID")
            appendLine()
            appendLine(connectionManager.getDebugInfo())
            appendLine()
            appendLine(peerManager.getDebugInfo(connectionManager.addressPeerMap))
            appendLine()
            appendLine(peerManager.getFingerprintDebugInfo())
            appendLine()
            appendLine(fragmentManager.getDebugInfo())
            appendLine()
            appendLine(securityManager.getDebugInfo())
            appendLine()
            appendLine(storeForwardManager.getDebugInfo())
            appendLine()
            appendLine(messageHandler.getDebugInfo())
            appendLine()
            appendLine(packetProcessor.getDebugInfo())
        }
    }
    
    /**
     * Convert hex string peer ID to binary data (8 bytes) - exactly same as iOS
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
        var tempID = hexString
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        return result
    }
    
    /**
     * Sign packet before broadcasting using our signing private key
     */
    private fun applyRouteIfAvailable(packet: BitchatPacket): BitchatPacket {
        return try {
            val recipient = packet.recipientID
            if (recipient != null && !recipient.contentEquals(SpecialRecipients.BROADCAST)) {
                val destination = recipient.joinToString("") { byte -> "%02x".format(byte) }
                val path = com.bitchat.android.services.meshgraph.RoutePlanner.shortestPath(
                    myPeerID,
                    destination
                )
                if (path != null && path.size >= 3) {
                    val intermediates = path.subList(1, path.size - 1)
                    packet.copy(
                        route = intermediates.map(::hexStringToByteArray),
                        version = 2u
                    )
                } else {
                    packet.copy(route = null)
                }
            } else {
                packet
            }
        } catch (_: Exception) {
            packet
        }
    }

    /** Private media must never fall back to an unsigned packet. */
    private fun routeAndSignPrivateMediaStrict(packet: BitchatPacket): BitchatPacket? {
        val routed = applyRouteIfAvailable(packet)
        val signingBytes = routed.toBinaryDataForSigning() ?: return null
        val signature = encryptionService.signData(signingBytes) ?: return null
        return routed.copy(signature = signature)
    }

    private fun signPacketBeforeBroadcast(packet: BitchatPacket): BitchatPacket {
        return try {
            // Optionally compute and attach a source route for addressed packets
            val withRoute = applyRouteIfAvailable(packet)

            // Get the canonical packet data for signing (without signature)
            val packetDataForSigning = withRoute.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "Failed to encode packet type ${packet.type} for signing, sending unsigned")
                return withRoute
            }
            
            // Sign the packet data using our signing key
            val signature = encryptionService.signData(packetDataForSigning)
            if (signature != null) {
                withRoute.copy(signature = signature)
            } else {
                Log.w(TAG, "Failed to sign packet type ${packet.type}, sending unsigned")
                withRoute
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error signing packet type ${packet.type}: ${e.message}, sending unsigned")
            packet
        }
    }
    
    // MARK: - Panic Mode Support
    
    /**
     * Clear all internal mesh service data (for panic mode)
     */
    fun clearAllInternalData() {
        Log.w(TAG, "Clearing all mesh service internal data")
        try {
            // Stop services to cease broadcasting old ID immediately
            stopServices()

            // Clear all managers
            fragmentManager.clearAllFragments()
            storeForwardManager.clearAllCache()
            securityManager.clearAllData()
            peerManager.clearAllPeers()
            peerManager.clearAllFingerprints()
            try { gossipSyncManager.clear() } catch (_: Exception) { }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing mesh service internal data: ${e.message}")
        }
    }
    
    /**
     * Clear all encryption and cryptographic data (for panic mode)
     */
    fun clearAllEncryptionData() {
        Log.w(TAG, "Clearing all encryption data")
        try {
            // Clear encryption service persistent identity (includes Ed25519 signing keys)
            encryptionService.clearPersistentIdentity()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing encryption data: ${e.message}")
        }
    }
}

/**
 * Delegate interface for BLE mesh callbacks. Extends the shared mesh delegate so
 * transport-agnostic facades can receive the same callback stream.
 */
interface BluetoothMeshDelegate : MeshDelegate {
    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long)
    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long)
}
