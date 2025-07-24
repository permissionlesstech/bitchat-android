package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.MessagePadding
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.model.NoiseIdentityAnnouncement
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.*
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
class BluetoothMeshService(private val context: Context) {
    
    companion object {
        private const val TAG = "BluetoothMeshService"
        private const val MAX_TTL: UByte = 7u
    }
    
    // My peer identification - same format as iOS
    val myPeerID: String = generateCompatiblePeerID()
    
    // Core components - each handling specific responsibilities
    private val encryptionService = EncryptionService(context)
    private val peerManager = PeerManager()
    private val fragmentManager = FragmentManager()
    private val securityManager = SecurityManager(encryptionService, myPeerID)
    private val storeForwardManager = StoreForwardManager()
    private val messageHandler = MessageHandler(myPeerID)
    internal val connectionManager = BluetoothConnectionManager(context, myPeerID, fragmentManager) // Made internal for access
    private val packetProcessor = PacketProcessor(myPeerID)
    
    // Service state management
    private var isActive = false
    
    // Delegate for message callbacks (maintains same interface)
    var delegate: BluetoothMeshDelegate? = null
    
    // Coroutines
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        setupDelegates()

        // Wire up PacketProcessor reference for recursive handling in MessageHandler
        messageHandler.packetProcessor = packetProcessor
        sendPeriodicBroadcastAnnounce()
        //startPeriodicDebugLogging()
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
                        Log.d(TAG, "=== PERIODIC DEBUG STATUS ===\n$debugInfo\n=== END DEBUG STATUS ===")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic debug logging: ${e.message}")
                }
            }
        }
    }

    /**
     * Send broadcast announcement every 10 seconds
     */
    private fun sendPeriodicBroadcastAnnounce() {
        serviceScope.launch {
            while (isActive) {
                try {
                    delay(10000) // 10 seconds
                    sendBroadcastAnnounce()
                } catch (e: Exception) {
                    Log.e(TAG, "Error in periodic broadcast announce: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Setup delegate connections between components
     */
    private fun setupDelegates() {
        // PeerManager delegates to main mesh service delegate
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerConnected(nickname: String) {
                delegate?.didConnectToPeer(nickname)
            }
            
            override fun onPeerDisconnected(nickname: String) {
                delegate?.didDisconnectFromPeer(nickname)
            }
            
            override fun onPeerListUpdated(peerIDs: List<String>) {
                delegate?.didUpdatePeerList(peerIDs)
            }
        }
        
        // SecurityManager delegate for key exchange notifications
        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray, receivedAddress: String?) {
                // Notify delegate about key exchange completion so it can register peer fingerprint
                delegate?.registerPeerPublicKey(peerID, peerPublicKeyData)
                
                receivedAddress?.let { address ->
                    connectionManager.addressPeerMap[address] = peerID
                }

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
                    type = MessageType.NOISE_HANDSHAKE_RESP.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(peerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = response,
                    ttl = 1u
                )
                connectionManager.broadcastPacket(RoutedPacket(responsePacket))
                Log.d(TAG, "Sent Noise handshake response to $peerID (${response.size} bytes)")
            }
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
                connectionManager.broadcastPacket(RoutedPacket(packet))
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
            
            // Packet operations
            override fun sendPacket(packet: BitchatPacket) {
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
            
            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
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
            
            override fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray? {
                return securityManager.decryptFromPeer(encryptedData, senderPeerID)
            }
            
            // Noise protocol operations
            override fun hasNoiseSession(peerID: String): Boolean {
                return encryptionService.hasEstablishedSession(peerID)
            }
            
            override fun initiateNoiseHandshake(peerID: String) {
                try {
                    // Initiate proper Noise handshake with specific peer
                    val handshakeData = encryptionService.initiateHandshake(peerID)
                    
                    if (handshakeData != null) {
                        val packet = BitchatPacket(
                            version = 1u,
                            type = MessageType.NOISE_HANDSHAKE_INIT.value,
                            senderID = hexStringToByteArray(myPeerID),
                            recipientID = hexStringToByteArray(peerID),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = handshakeData,
                            ttl = 1u
                        )
                        
                        connectionManager.broadcastPacket(RoutedPacket(packet))
                        Log.d(TAG, "Initiated Noise handshake with $peerID (${handshakeData.size} bytes)")
                    } else {
                        Log.w(TAG, "Failed to generate Noise handshake data for $peerID")
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initiate Noise handshake with $peerID: ${e.message}")
                }
            }
            
            override fun updatePeerIDBinding(newPeerID: String, fingerprint: String, nickname: String, 
                                           publicKey: ByteArray, previousPeerID: String?) {
                // Update peer mapping in the PeerManager for peer ID rotation support
                peerManager.addOrUpdatePeer(newPeerID, nickname)
                
                // If there was a previous peer ID, remove it to avoid duplicates
                previousPeerID?.let { oldPeerID ->
                    peerManager.removePeer(oldPeerID)
                }
                
                // Register the public key with the delegate (ChatViewModel)
                delegate?.registerPeerPublicKey(newPeerID, publicKey)
                
                Log.d(TAG, "Updated peer ID binding: $newPeerID (was: $previousPeerID), fingerprint: ${fingerprint.take(16)}...")
            }
            
            // Message operations  
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return delegate?.decryptChannelMessage(encryptedContent, channel)
            }
            
            override fun sendDeliveryAck(message: BitchatMessage, senderPeerID: String) {
                this@BluetoothMeshService.sendDeliveryAck(message, senderPeerID)
            }
            
            // Callbacks
            override fun onMessageReceived(message: BitchatMessage) {
                delegate?.didReceiveMessage(message)
            }
            
            override fun onChannelLeave(channel: String, fromPeer: String) {
                delegate?.didReceiveChannelLeave(channel, fromPeer)
            }
            
            override fun onPeerDisconnected(nickname: String) {
                delegate?.didDisconnectFromPeer(nickname)
            }
            
            override fun onDeliveryAckReceived(ack: DeliveryAck) {
                delegate?.didReceiveDeliveryAck(ack)
            }
            
            override fun onReadReceiptReceived(receipt: ReadReceipt) {
                delegate?.didReceiveReadReceipt(receipt)
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
            
            override fun handleNoiseHandshake(routed: RoutedPacket, step: Int): Boolean {
                return runBlocking { securityManager.handleNoiseHandshake(routed, step) }
            }
            
            override fun handleNoiseEncrypted(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleNoiseEncrypted(routed) }
            }
            
            override fun handleNoiseIdentityAnnouncement(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleNoiseIdentityAnnouncement(routed) }
            }
            
            override fun handleAnnounce(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleAnnounce(routed) }
            }
            
            override fun handleMessage(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleMessage(routed) }
            }
            
            override fun handleLeave(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleLeave(routed) }
            }
            
            override fun handleFragment(packet: BitchatPacket): BitchatPacket? {
                return fragmentManager.handleFragment(packet)
            }
            
            override fun handleDeliveryAck(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleDeliveryAck(routed) }
            }
            
            override fun handleReadReceipt(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleReadReceipt(routed) }
            }
            
            override fun sendAnnouncementToPeer(peerID: String) {
                this@BluetoothMeshService.sendAnnouncementToPeer(peerID)
            }
            
            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }
            
            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
            }
        }
        
        // BluetoothConnectionManager delegates
        connectionManager.delegate = object : BluetoothConnectionManagerDelegate {
            override fun onPacketReceived(packet: BitchatPacket, peerID: String, device: android.bluetooth.BluetoothDevice?) {
                packetProcessor.processPacket(RoutedPacket(packet, peerID, device?.address))
            }
            
            override fun onDeviceConnected(device: android.bluetooth.BluetoothDevice) {
                // Send initial announcements after services are ready
                serviceScope.launch {
                    delay(100)
                    sendBroadcastAnnounce()
                }
                // Send key exchange to newly connected device
                serviceScope.launch {
                    delay(100) // Ensure connection is stable
                    sendKeyExchangeToDevice()
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
        
        Log.i(TAG, "Starting Bluetooth mesh service with peer ID: $myPeerID")
        
        if (connectionManager.startServices()) {
            isActive = true            
        } else {
            Log.e(TAG, "Failed to start Bluetooth services")
        }
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
        
        // Send leave announcement
        sendLeaveAnnouncement()
        
        serviceScope.launch {
            delay(200) // Give leave message time to send
            
            // Stop all components
            connectionManager.stopServices()
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
     * Send public message
     */
    fun sendMessage(content: String, mentions: List<String> = emptyList(), channel: String? = null) {
        if (content.isEmpty()) return
        
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
            val message = BitchatMessage(
                sender = nickname,
                content = content,
                timestamp = Date(),
                isRelay = false,
                senderPeerID = myPeerID,
                mentions = if (mentions.isNotEmpty()) mentions else null,
                channel = channel
            )
            
            message.toBinaryPayload()?.let { messageData ->
                // Sign the message: TODO: NOT SIGNED
                // val signature = securityManager.signPacket(messageData)
                
                val packet = BitchatPacket(
                    version = 1u,
                    type = MessageType.MESSAGE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = messageData,
                    signature = null,
                    ttl = MAX_TTL
                )
                
                // Send with random delay and retry for reliability
                // delay(Random.nextLong(50, 500))
                connectionManager.broadcastPacket(RoutedPacket(packet))
            }
        }
    }
    
    /**
     * Send private message
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty() || recipientNickname.isEmpty()) return
        
        val nickname = delegate?.getNickname() ?: myPeerID
        
        val message = BitchatMessage(
            id = messageID ?: UUID.randomUUID().toString(),
            sender = nickname,
            content = content,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = myPeerID
        )
        
        message.toBinaryPayload()?.let { messageData ->
            try {
                
                // Create inner packet with the padded message data
                val innerPacket = BitchatPacket(
                    type = MessageType.MESSAGE.value,
                    senderID = hexStringToByteArray(myPeerID),
                    recipientID = hexStringToByteArray(recipientPeerID),
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = messageData,
                    signature = null,
                    ttl = MAX_TTL
                )
                
                // Cache for offline favorites
                if (storeForwardManager.shouldCacheForPeer(recipientPeerID)) {
                    storeForwardManager.cacheMessage(innerPacket, messageID ?: message.id)
                }
                
                // Use the new encrypt and broadcast function
                encryptAndBroadcastNoisePacket(innerPacket, recipientPeerID)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send private message: ${e.message}")
            }
        }
    }
    
    /**
     * Send delivery acknowledgment for a received private message
     */
    fun sendDeliveryAck(message: BitchatMessage, senderPeerID: String) {
        val nickname = delegate?.getNickname() ?: myPeerID
        val ack = DeliveryAck(
            originalMessageID = message.id,
            recipientID = myPeerID,
            recipientNickname = nickname,
            hopCount = 0u // Will be calculated during relay
        )
        
        try {
            // TODO: THIS FORMAT FOR DELIVERY ACKS SHOULD BE DEPRECATED
            val ackData = ack.encode() ?: return
            val typeMarker = MessageType.DELIVERY_ACK.value.toByte()
            val payloadWithMarker = byteArrayOf(typeMarker) + ackData
            val encryptedPayload = securityManager.encryptForPeer(payloadWithMarker, senderPeerID)

            if (encryptedPayload == null) {
                Log.w(TAG, "Failed to encrypt delivery ACK for $senderPeerID")
                return
            }
            
            // Create inner packet with the delivery ACK data
            val packet = BitchatPacket(
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexStringToByteArray(myPeerID),
                recipientID = hexStringToByteArray(senderPeerID),
                timestamp = System.currentTimeMillis().toULong(),
                payload = encryptedPayload,
                signature = null,
                ttl = 3u
            )
            
            // Use the new encrypt and broadcast function
            connectionManager.broadcastPacket(RoutedPacket(packet))
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send delivery ACK: ${e.message}")
        }
    }
    
    /**
     * Encrypt a BitchatPacket and broadcast it as a NOISE_ENCRYPTED message
     * This is the correct protocol implementation - encrypt the entire packet, not just the payload
     */
    private fun encryptAndBroadcastNoisePacket(innerPacket: BitchatPacket, recipientPeerID: String) {
        serviceScope.launch {
            try {
                // Serialize the inner packet to binary data
                val innerPacketData = innerPacket.toBinaryData()
                if (innerPacketData == null) {
                    Log.e(TAG, "Failed to serialize inner packet for encryption")
                    return@launch
                }
                
                // Encrypt the serialized packet using Noise encryption
                val encryptedPayload = securityManager.encryptForPeer(innerPacketData, recipientPeerID)
                
                if (encryptedPayload != null) {
                    // Create the outer NOISE_ENCRYPTED packet
                    val outerPacket = BitchatPacket(
                        type = MessageType.NOISE_ENCRYPTED.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = hexStringToByteArray(recipientPeerID),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encryptedPayload,
                        signature = null,
                        ttl = MAX_TTL
                    )
                    
                    // Broadcast the encrypted packet
                    connectionManager.broadcastPacket(RoutedPacket(outerPacket))
                    
                    Log.d(TAG, "Encrypted and sent packet type ${innerPacket.type} to $recipientPeerID (${encryptedPayload.size} bytes encrypted)")
                } else {
                    Log.w(TAG, "Failed to encrypt packet for $recipientPeerID - no session available")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to encrypt and broadcast Noise packet to $recipientPeerID: ${e.message}")
            }
        }
    }
    
    /**
     * Send broadcast announce
     */
    fun sendBroadcastAnnounce() {
        Log.d(TAG, "Sending broadcast announce")
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
            val announcePacket = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = 3u,
                senderID = myPeerID,
                payload = nickname.toByteArray()
            )
            
            // Send multiple times for reliability
            delay(Random.nextLong(0, 500))
            connectionManager.broadcastPacket(RoutedPacket(announcePacket))
            
            delay(500 + Random.nextLong(0, 500))
            connectionManager.broadcastPacket(RoutedPacket(announcePacket))
            
            delay(1000 + Random.nextLong(0, 500))
            connectionManager.broadcastPacket(RoutedPacket(announcePacket))
        }
    }
    
    /**
     * Send announcement to specific peer
     */
    private fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 3u,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        
        connectionManager.broadcastPacket(RoutedPacket(packet))
        peerManager.markPeerAsAnnouncedTo(peerID)
    }
    
    /**
     * Send Noise identity announcement (broadcast our static public key and signing key)
     * Now properly formatted as NoiseIdentityAnnouncement to match iOS
     */
    private fun sendKeyExchangeToDevice() {
        serviceScope.launch {
            try {
                val nickname = delegate?.getNickname() ?: myPeerID
                
                // Create the identity announcement using proper binary format
                val announcement = createNoiseIdentityAnnouncement(nickname, null)
                if (announcement != null) {
                    val announcementData = announcement.toBinaryData()
                    
                    val packet = BitchatPacket(
                        version = 1u,
                        type = MessageType.NOISE_IDENTITY_ANNOUNCE.value,
                        senderID = hexStringToByteArray(myPeerID),
                        recipientID = SpecialRecipients.BROADCAST,
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = announcementData,
                        ttl = 1u
                    )
                    
                    connectionManager.broadcastPacket(RoutedPacket(packet))
                    Log.d(TAG, "Sent NoiseIdentityAnnouncement (${announcementData.size} bytes)")
                } else {
                    Log.e(TAG, "Failed to create NoiseIdentityAnnouncement")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send NoiseIdentityAnnouncement: ${e.message}")
            }
        }
    }
    
    /**
     * Create a properly formatted NoiseIdentityAnnouncement exactly like iOS
     */
    private fun createNoiseIdentityAnnouncement(nickname: String, previousPeerID: String?): NoiseIdentityAnnouncement? {
        return try {
            // Get the static public key for Noise protocol
            val staticKey = encryptionService.getStaticPublicKey()
            if (staticKey == null) {
                Log.e(TAG, "No static public key available for identity announcement")
                return null
            }
            
            // Get the signing public key for Ed25519 signatures
            val signingKey = encryptionService.getSigningPublicKey()
            if (signingKey == null) {
                Log.e(TAG, "No signing public key available for identity announcement")
                return null
            }
            
            val now = Date()
            
            // Create the binding data to sign (same format as iOS)
            val timestampMs = now.time
            val bindingData = myPeerID.toByteArray(Charsets.UTF_8) + 
                            staticKey + 
                            timestampMs.toString().toByteArray(Charsets.UTF_8)
            
            // Sign the binding with our Ed25519 signing key
            val signature = encryptionService.signData(bindingData) ?: ByteArray(0)
            
            // Create the identity announcement
            NoiseIdentityAnnouncement(
                peerID = myPeerID,
                publicKey = staticKey,
                signingPublicKey = signingKey,
                nickname = nickname,
                timestamp = now,
                previousPeerID = previousPeerID,
                signature = signature
            )
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create NoiseIdentityAnnouncement: ${e.message}")
            null
        }
    }
    
    /**
     * Send leave announcement
     */
    private fun sendLeaveAnnouncement() {
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        
        connectionManager.broadcastPacket(RoutedPacket(packet))
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
     * Get peer fingerprint for identity management
     */
    fun getPeerFingerprint(peerID: String): String? {
        return encryptionService.getPeerFingerprint(peerID)
    }
    
    /**
     * Get our identity fingerprint
     */
    fun getIdentityFingerprint(): String {
        return encryptionService.getIdentityFingerprint()
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
     * Generate peer ID compatible with iOS - exactly 8 bytes (16 hex characters)
     */
    private fun generateCompatiblePeerID(): String {
        val randomBytes = ByteArray(8)  // 8 bytes = 16 hex characters (like iOS)
        Random.nextBytes(randomBytes)
        return randomBytes.joinToString("") { "%02x".format(it) }
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
}

/**
 * Delegate interface for mesh service callbacks (maintains exact same interface)
 */
interface BluetoothMeshDelegate {
    fun didReceiveMessage(message: BitchatMessage)
    fun didConnectToPeer(peerID: String)
    fun didDisconnectFromPeer(peerID: String)
    fun didUpdatePeerList(peers: List<String>)
    fun didReceiveChannelLeave(channel: String, fromPeer: String)
    fun didReceiveDeliveryAck(ack: DeliveryAck)
    fun didReceiveReadReceipt(receipt: ReadReceipt)
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun getNickname(): String?
    fun isFavorite(peerID: String): Boolean
    fun registerPeerPublicKey(peerID: String, publicKeyData: ByteArray)
}
