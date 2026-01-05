package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.IdentityAnnouncement
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.model.RequestSyncPacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.sync.GossipSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Shared mesh coordinator that wires all mesh-layer components and provides common APIs
 * for send/receive operations across transports.
 */
class MeshCore(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transport: MeshTransport,
    private val encryptionService: EncryptionService,
    val myPeerID: String,
    private val maxTtl: UByte,
    sharedGossipManager: GossipSyncManager?,
    gossipConfigProvider: GossipSyncManager.ConfigProvider,
    private val hooks: Hooks = Hooks()
) {
    data class Hooks(
        val onMessageReceived: ((BitchatMessage) -> Unit)? = null,
        val onPeerIdBindingUpdated: ((String, String, ByteArray, String?) -> Unit)? = null,
        val onAnnounceProcessed: ((RoutedPacket, Boolean) -> Unit)? = null,
        val readReceiptInterceptor: ((String, String) -> Boolean)? = null,
        val onReadReceiptSent: ((String) -> Unit)? = null,
        val announcementNicknameProvider: (() -> String?)? = null,
        val leavePayloadProvider: (() -> ByteArray)? = null
    )

    private val peerManager = PeerManager()
    val fragmentManager = FragmentManager()
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID, context.applicationContext)
    private val packetProcessor = PacketProcessor(myPeerID)

    val gossipSyncManager: GossipSyncManager =
        sharedGossipManager ?: GossipSyncManager(myPeerID = myPeerID, scope = scope, configProvider = gossipConfigProvider)
    private val ownsGossipManager: Boolean = sharedGossipManager == null

    var delegate: MeshDelegate? = null

    private var announceJob: Job? = null
    private var isActive = false

    init {
        messageHandler.packetProcessor = packetProcessor
        setupDelegates()

        if (sharedGossipManager == null) {
            gossipSyncManager.delegate = object : GossipSyncManager.Delegate {
                override fun sendPacket(packet: BitchatPacket) {
                    dispatchGlobal(RoutedPacket(packet))
                }

                override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) {
                    transport.sendPacketToPeer(peerID, packet)
                    TransportBridgeService.sendToPeer(transport.id, peerID, packet)
                }

                override fun signPacketForBroadcast(packet: BitchatPacket): BitchatPacket {
                    return signPacketBeforeBroadcast(packet)
                }
            }
        }
    }

    fun startCore() {
        if (isActive) return
        isActive = true
        startPeriodicBroadcastAnnounce()
        if (ownsGossipManager) {
            gossipSyncManager.start()
        }
    }

    fun stopCore() {
        if (!isActive) return
        isActive = false
        announceJob?.cancel()
        announceJob = null
        if (ownsGossipManager) {
            gossipSyncManager.stop()
        }
    }

    fun shutdown() {
        peerManager.shutdown()
        fragmentManager.shutdown()
        securityManager.shutdown()
        storeForwardManager.shutdown()
        messageHandler.shutdown()
        packetProcessor.shutdown()
    }

    fun processIncoming(packet: BitchatPacket, peerID: String?, relayAddress: String?) {
        packetProcessor.processPacket(RoutedPacket(packet, peerID, relayAddress))
    }

    fun sendFromBridge(packet: RoutedPacket) {
        transport.broadcastPacket(packet)
    }

    private fun dispatchGlobal(routed: RoutedPacket) {
        transport.broadcastPacket(routed)
        TransportBridgeService.broadcast(transport.id, routed)
    }

    private fun startPeriodicBroadcastAnnounce() {
        announceJob?.cancel()
        announceJob = scope.launch {
            while (isActive) {
                try {
                    delay(30_000)
                    sendBroadcastAnnounce()
                } catch (_: Exception) { }
            }
        }
    }

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
                scope.launch {
                    delay(100)
                    sendAnnouncementToPeer(peerID)
                    delay(1000)
                    storeForwardManager.sendCachedMessages(peerID)
                }
            }

            override fun sendHandshakeResponse(peerID: String, response: ByteArray) {
                val responsePacket = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    ttl = maxTtl
                )
                dispatchGlobal(RoutedPacket(signPacketBeforeBroadcast(responsePacket)))
            }

            override fun getPeerInfo(peerID: String): PeerInfo? = peerManager.getPeerInfo(peerID)
        }

        storeForwardManager.delegate = object : StoreForwardManagerDelegate {
            override fun isFavorite(peerID: String): Boolean {
                return delegate?.isFavorite(peerID) ?: false
            }

            override fun isPeerOnline(peerID: String): Boolean {
                return peerManager.isPeerActive(peerID)
            }

            override fun sendPacket(packet: BitchatPacket) {
                dispatchGlobal(RoutedPacket(packet))
            }
        }

        messageHandler.delegate = object : MessageHandlerDelegate {
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

            override fun updatePeerInfo(
                peerID: String,
                nickname: String,
                noisePublicKey: ByteArray,
                signingPublicKey: ByteArray,
                isVerified: Boolean
            ): Boolean {
                return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
            }

            override fun sendPacket(packet: BitchatPacket) {
                val signedPacket = signPacketBeforeBroadcast(packet)
                dispatchGlobal(RoutedPacket(signedPacket))
            }

            override fun relayPacket(routed: RoutedPacket) {
                dispatchGlobal(routed)
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.verifySignature(packet, peerID)
            }

            override fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
                return securityManager.encryptForPeer(data, recipientPeerID)
            }

            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }

            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
                return encryptionService.verifyEd25519Signature(signature, data, publicKey)
            }

            override fun hasNoiseSession(peerID: String): Boolean {
                return encryptionService.hasEstablishedSession(peerID)
            }

            override fun initiateNoiseHandshake(peerID: String) {
                this@MeshCore.initiateNoiseHandshake(peerID)
            }

            override fun processNoiseHandshakeMessage(payload: ByteArray, peerID: String): ByteArray? {
                return try {
                    encryptionService.processHandshakeMessage(payload, peerID)
                } catch (_: Exception) {
                    null
                }
            }

            override fun updatePeerIDBinding(
                newPeerID: String,
                nickname: String,
                publicKey: ByteArray,
                previousPeerID: String?
            ) {
                peerManager.addOrUpdatePeer(newPeerID, nickname)
                val fingerprint = peerManager.storeFingerprintForPeer(newPeerID, publicKey)
                previousPeerID?.let { peerManager.removePeer(it) }
                Log.d("MeshCore", "Updated peer ID binding: $newPeerID fp=${fingerprint.take(16)}")
                hooks.onPeerIdBindingUpdated?.invoke(newPeerID, nickname, publicKey, previousPeerID)
            }

            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return delegate?.decryptChannelMessage(encryptedContent, channel)
            }

            override fun onMessageReceived(message: BitchatMessage) {
                hooks.onMessageReceived?.invoke(message)
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
            override fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean {
                return securityManager.validatePacket(packet, peerID)
            }

            override fun updatePeerLastSeen(peerID: String) {
                peerManager.updatePeerLastSeen(peerID)
            }

            override fun getPeerNickname(peerID: String): String? {
                return peerManager.getPeerNickname(peerID)
            }

            override fun getNetworkSize(): Int {
                return peerManager.getActivePeerCount()
            }

            override fun getBroadcastRecipient(): ByteArray {
                return SpecialRecipients.BROADCAST
            }

            override fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
                return runBlocking { securityManager.handleNoiseHandshake(routed) }
            }

            override fun handleNoiseEncrypted(routed: RoutedPacket) {
                scope.launch { messageHandler.handleNoiseEncrypted(routed) }
            }

            override fun handleAnnounce(routed: RoutedPacket) {
                scope.launch {
                    val isFirst = messageHandler.handleAnnounce(routed)
                    hooks.onAnnounceProcessed?.invoke(routed, isFirst)
                    try { gossipSyncManager.onPublicPacketSeen(routed.packet) } catch (_: Exception) { }
                }
            }

            override fun handleMessage(routed: RoutedPacket) {
                scope.launch { messageHandler.handleMessage(routed) }
                try {
                    val pkt = routed.packet
                    val isBroadcast = (pkt.recipientID == null || pkt.recipientID.contentEquals(SpecialRecipients.BROADCAST))
                    if (isBroadcast && pkt.type == MessageType.MESSAGE.value) {
                        gossipSyncManager.onPublicPacketSeen(pkt)
                    }
                } catch (_: Exception) { }
            }

            override fun handleLeave(routed: RoutedPacket) {
                scope.launch { messageHandler.handleLeave(routed) }
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

            override fun sendAnnouncementToPeer(peerID: String) {
                this@MeshCore.sendAnnouncementToPeer(peerID)
            }

            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }

            override fun relayPacket(routed: RoutedPacket) {
                dispatchGlobal(routed)
            }

            override fun handleRequestSync(routed: RoutedPacket) {
                val fromPeer = routed.peerID ?: return
                val req = RequestSyncPacket.decode(routed.packet.payload) ?: return
                gossipSyncManager.handleRequestSync(fromPeer, req)
            }
        }
    }

    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        if (content.isEmpty()) return
        scope.launch {
            val packet = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = System.currentTimeMillis().toULong(),
                payload = content.toByteArray(Charsets.UTF_8),
                signature = null,
                ttl = maxTtl
            )
            val signedPacket = signPacketBeforeBroadcast(packet)
            dispatchGlobal(RoutedPacket(signedPacket))
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    fun sendFileBroadcast(file: BitchatFilePacket) {
        try {
            val payload = file.encode() ?: return
            scope.launch {
                val packet = BitchatPacket(
                    version = 2u,
                    type = MessageType.FILE_TRANSFER.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = payload,
                    signature = null,
                    ttl = maxTtl
                )
                val signed = signPacketBeforeBroadcast(packet)
                val transferId = MeshPacketUtils.sha256Hex(payload)
                dispatchGlobal(RoutedPacket(signed, transferId = transferId))
                try { gossipSyncManager.onPublicPacketSeen(signed) } catch (_: Exception) { }
            }
        } catch (e: Exception) {
            Log.e("MeshCore", "sendFileBroadcast failed: ${e.message}", e)
        }
    }

    fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        try {
            scope.launch {
                if (!encryptionService.hasEstablishedSession(recipientPeerID)) {
                    initiateNoiseHandshake(recipientPeerID)
                    return@launch
                }
                val tlv = file.encode() ?: return@launch
                val np = NoisePayload(type = NoisePayloadType.FILE_TRANSFER, data = tlv).encode()
                val enc = encryptionService.encrypt(np, recipientPeerID)
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = enc,
                    signature = null,
                    ttl = maxTtl
                )
                val signed = signPacketBeforeBroadcast(packet)
                val transferId = MeshPacketUtils.sha256Hex(tlv)
                dispatchGlobal(RoutedPacket(signed, transferId = transferId))
            }
        } catch (e: Exception) {
            Log.e("MeshCore", "sendFilePrivate failed: ${e.message}", e)
        }
    }

    fun cancelFileTransfer(transferId: String): Boolean {
        return transport.cancelTransfer(transferId)
    }

    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty()) return
        scope.launch {
            val finalMessageID = messageID ?: java.util.UUID.randomUUID().toString()

            if (encryptionService.hasEstablishedSession(recipientPeerID)) {
                try {
                    val privateMessage = PrivateMessagePacket(messageID = finalMessageID, content = content)
                    val tlvData = privateMessage.encode() ?: return@launch
                    val messagePayload = NoisePayload(
                        type = NoisePayloadType.PRIVATE_MESSAGE,
                        data = tlvData
                    )
                    val encrypted = encryptionService.encrypt(messagePayload.encode(), recipientPeerID)
                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                        recipientID = MeshPacketUtils.hexStringToByteArray(recipientPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encrypted,
                        signature = null,
                        ttl = maxTtl
                    )
                    val signedPacket = signPacketBeforeBroadcast(packet)
                    dispatchGlobal(RoutedPacket(signedPacket))
                } catch (e: Exception) {
                    Log.e("MeshCore", "Failed to encrypt private message: ${e.message}")
                }
            } else {
                initiateNoiseHandshake(recipientPeerID)
            }
        }
    }

    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        scope.launch {
            if (hooks.readReceiptInterceptor?.invoke(messageID, recipientPeerID) == true) return@launch
            try {
                val payload = NoisePayload(
                    type = NoisePayloadType.READ_RECEIPT,
                    data = messageID.toByteArray(Charsets.UTF_8)
                ).encode()
                val enc = encryptionService.encrypt(payload, recipientPeerID)
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_ENCRYPTED.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = enc,
                    signature = null,
                    ttl = maxTtl
                )
                dispatchGlobal(RoutedPacket(signPacketBeforeBroadcast(packet)))
                hooks.onReadReceiptSent?.invoke(messageID)
            } catch (e: Exception) {
                Log.e("MeshCore", "Failed to send read receipt: ${e.message}")
            }
        }
    }

    fun sendBroadcastAnnounce() {
        scope.launch {
            val nickname = hooks.announcementNicknameProvider?.invoke()
                ?: delegate?.getNickname()
                ?: myPeerID
            val staticKey = encryptionService.getStaticPublicKey() ?: run {
                Log.e("MeshCore", "No static public key available for announcement")
                return@launch
            }
            val signingKey = encryptionService.getSigningPublicKey() ?: run {
                Log.e("MeshCore", "No signing public key available for announcement")
                return@launch
            }
            val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
            val tlvPayload = announcement.encode() ?: return@launch
            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = maxTtl,
                senderID = myPeerID,
                payload = tlvPayload
            )
            val signedPacket = signPacketBeforeBroadcast(announcePacket)
            dispatchGlobal(RoutedPacket(signedPacket))
            try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
        }
    }

    fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        val nickname = hooks.announcementNicknameProvider?.invoke()
            ?: delegate?.getNickname()
            ?: myPeerID
        val staticKey = encryptionService.getStaticPublicKey() ?: return
        val signingKey = encryptionService.getSigningPublicKey() ?: return
        val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
        val tlvPayload = announcement.encode() ?: return
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = maxTtl,
            senderID = myPeerID,
            payload = tlvPayload
        )
        val signedPacket = signPacketBeforeBroadcast(packet)
        dispatchGlobal(RoutedPacket(signedPacket))
        peerManager.markPeerAsAnnouncedTo(peerID)
        try { gossipSyncManager.onPublicPacketSeen(signedPacket) } catch (_: Exception) { }
    }

    fun sendLeaveAnnouncement() {
        val payload = hooks.leavePayloadProvider?.invoke() ?: byteArrayOf()
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = maxTtl,
            senderID = myPeerID,
            payload = payload
        )
        val signedPacket = signPacketBeforeBroadcast(packet)
        dispatchGlobal(RoutedPacket(signedPacket))
    }

    fun getPeerNicknames(): Map<String, String> = peerManager.getAllPeerNicknames()

    fun getPeerRSSI(): Map<String, Int> = peerManager.getAllPeerRSSI()

    fun getPeerNickname(peerID: String): String? = peerManager.getPeerNickname(peerID)

    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean {
        return peerManager.addOrUpdatePeer(peerID, nickname)
    }

    fun removePeer(peerID: String) {
        peerManager.removePeer(peerID)
    }

    fun setDirectConnection(peerID: String, isDirect: Boolean) {
        peerManager.setDirectConnection(peerID, isDirect)
    }

    fun updatePeerRSSI(peerID: String, rssi: Int) {
        peerManager.updatePeerRSSI(peerID, rssi)
    }

    fun getDebugInfoWithDeviceAddresses(deviceMap: Map<String, String>): String {
        return peerManager.getDebugInfoWithDeviceAddresses(deviceMap)
    }

    fun getFingerprintDebugInfo(): String {
        return peerManager.getFingerprintDebugInfo()
    }

    fun hasEstablishedSession(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }

    fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState {
        return encryptionService.getSessionState(peerID)
    }

    fun initiateNoiseHandshake(peerID: String) {
        scope.launch {
            try {
                val handshakeData = encryptionService.initiateHandshake(peerID) ?: return@launch
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.NOISE_HANDSHAKE.value,
                    senderID = MeshPacketUtils.hexStringToByteArray(myPeerID),
                    recipientID = MeshPacketUtils.hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = handshakeData,
                    ttl = maxTtl
                )
                val signedPacket = signPacketBeforeBroadcast(packet)
                dispatchGlobal(RoutedPacket(signedPacket))
            } catch (e: Exception) {
                Log.e("MeshCore", "Failed to initiate Noise handshake with $peerID: ${e.message}")
            }
        }
    }

    fun getPeerFingerprint(peerID: String): String? = peerManager.getFingerprintForPeer(peerID)

    fun getPeerInfo(peerID: String): PeerInfo? = peerManager.getPeerInfo(peerID)

    fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean = peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)

    fun getIdentityFingerprint(): String = encryptionService.getIdentityFingerprint()

    fun shouldShowEncryptionIcon(peerID: String): Boolean = encryptionService.hasEstablishedSession(peerID)

    fun getEncryptedPeers(): List<String> = emptyList()

    fun getActivePeerCount(): Int = try { peerManager.getActivePeerCount() } catch (_: Exception) { 0 }

    fun refreshPeerList() {
        try { peerManager.refreshPeerList() } catch (_: Exception) { }
    }

    fun getDeviceAddressForPeer(peerID: String): String? = transport.getDeviceAddressForPeer(peerID)

    fun getDeviceAddressToPeerMapping(): Map<String, String> = transport.getDeviceAddressToPeerMapping()

    fun getDebugStatus(
        transportInfo: String,
        deviceMap: Map<String, String>,
        extraLines: List<String> = emptyList(),
        title: String? = null
    ): String {
        return buildString {
            appendLine("=== ${title ?: "${transport.id} Mesh Debug Status"} ===")
            appendLine("My Peer ID: $myPeerID")
            if (extraLines.isNotEmpty()) {
                extraLines.forEach { appendLine(it) }
            }
            appendLine(transportInfo)
            appendLine(peerManager.getDebugInfo(deviceMap))
            appendLine(fragmentManager.getDebugInfo())
            appendLine(securityManager.getDebugInfo())
            appendLine(storeForwardManager.getDebugInfo())
            appendLine(messageHandler.getDebugInfo())
            appendLine(packetProcessor.getDebugInfo())
        }
    }

    fun clearAllInternalData() {
        fragmentManager.clearAllFragments()
        storeForwardManager.clearAllCache()
        securityManager.clearAllData()
        peerManager.clearAllPeers()
        peerManager.clearAllFingerprints()
    }

    fun clearAllEncryptionData() {
        encryptionService.clearPersistentIdentity()
    }

    private fun signPacketBeforeBroadcast(packet: BitchatPacket): BitchatPacket {
        return try {
            val packetDataForSigning = packet.toBinaryDataForSigning() ?: return packet
            val signature = encryptionService.signData(packetDataForSigning)
            if (signature != null) {
                packet.copy(signature = signature)
            } else {
                packet
            }
        } catch (_: Exception) {
            packet
        }
    }
}
