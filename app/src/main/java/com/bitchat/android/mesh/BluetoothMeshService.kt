package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.protocol.MessagePadding
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.model.IdentityAnnouncement
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
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    
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
        messageHandler.packetProcessor = packetProcessor
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
     * Send broadcast announcement every 30 seconds
     */
    private fun sendPeriodicBroadcastAnnounce() {
        serviceScope.launch {
            while (isActive) {
                try {
                    delay(30000) // 30 seconds
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
        // Provide nickname resolver to BLE broadcaster for detailed logs
        try {
            connectionManager.setNicknameResolver { pid -> peerManager.getPeerNickname(pid) }
        } catch (_: Exception) { }
        // PeerManager delegates to main mesh service delegate
        peerManager.delegate = object : PeerManagerDelegate {
            override fun onPeerListUpdated(peerIDs: List<String>) {
                delegate?.didUpdatePeerList(peerIDs)
            }
        }
        
        // SecurityManager delegate for key exchange notifications
        securityManager.delegate = object : SecurityManagerDelegate {
            override fun onKeyExchangeCompleted(peerID: String, peerPublicKeyData: ByteArray) {
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
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                Log.d(TAG, "Sent Noise handshake response to $peerID (${response.size} bytes)")
            }
            
            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
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
            
            override fun getPeerInfo(peerID: String): PeerInfo? {
                return peerManager.getPeerInfo(peerID)
            }
            
            override fun updatePeerInfo(peerID: String, nickname: String, noisePublicKey: ByteArray, signingPublicKey: ByteArray, isVerified: Boolean): Boolean {
                return peerManager.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)
            }
            
            // Packet operations
            override fun sendPacket(packet: BitchatPacket) {
                // Sign the packet before broadcasting
                val signedPacket = signPacketBeforeBroadcast(packet)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
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
            
            override fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean {
                return encryptionService.verifyEd25519Signature(signature, data, publicKey)
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
                            type = MessageType.NOISE_HANDSHAKE.value,
                            senderID = hexStringToByteArray(myPeerID),
                            recipientID = hexStringToByteArray(peerID),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = handshakeData,
                            ttl = MAX_TTL
                        )

                        // Sign the handshake packet before broadcasting
                        val signedPacket = signPacketBeforeBroadcast(packet)
                        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                        Log.d(TAG, "Initiated Noise handshake with $peerID (${handshakeData.size} bytes)")
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
            
            override fun updatePeerIDBinding(newPeerID: String, nickname: String,
                                           publicKey: ByteArray, previousPeerID: String?) {

                Log.d(TAG, "Updating peer ID binding: $newPeerID (was: $previousPeerID) with nickname: $nickname and public key: ${publicKey.toHexString().take(16)}...")
                // Update peer mapping in the PeerManager for peer ID rotation support
                peerManager.addOrUpdatePeer(newPeerID, nickname)
                
                // Store fingerprint for the peer via centralized fingerprint manager
                val fingerprint = peerManager.storeFingerprintForPeer(newPeerID, publicKey)

                // Index existing Nostr mapping by the new peerID if we have it
                try {
                    com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(publicKey)?.let { npub ->
                        com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateNostrPublicKeyForPeerID(newPeerID, npub)
                    }
                } catch (_: Exception) { }
                
                // If there was a previous peer ID, remove it to avoid duplicates
                previousPeerID?.let { oldPeerID ->
                    peerManager.removePeer(oldPeerID)
                }
                
                Log.d(TAG, "Updated peer ID binding: $newPeerID (was: $previousPeerID), fingerprint: ${fingerprint.take(16)}...")
            }
            
            // Message operations  
            override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
                return delegate?.decryptChannelMessage(encryptedContent, channel)
            }
            
            // Callbacks
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
            
            override fun handleNoiseEncrypted(routed: RoutedPacket) {
                serviceScope.launch { messageHandler.handleNoiseEncrypted(routed) }
            }
            
            override fun handleAnnounce(routed: RoutedPacket) {
                serviceScope.launch {
                    // Process the announce
                    val isFirst = messageHandler.handleAnnounce(routed)

                    // Map device address -> peerID on first announce seen over this device connection
                    val deviceAddress = routed.relayAddress
                    val pid = routed.peerID
                    if (deviceAddress != null && pid != null) {
                        // Only set mapping if not already mapped
                        if (!connectionManager.addressPeerMap.containsKey(deviceAddress)) {
                            connectionManager.addressPeerMap[deviceAddress] = pid
                            Log.d(TAG, "Mapped device $deviceAddress to peer $pid on ANNOUNCE")

                            // Mark this peer as directly connected for UI
                            try {
                                peerManager.getPeerInfo(pid)?.let {
                                    // Set direct connection flag
                                    // (This will also trigger a peer list update)
                                    peerManager.setDirectConnection(pid, true)
                                    // Also push reactive directness state to UI (best-effort)
                                    try {
                                        // Note: UI observes via didUpdatePeerList, but we can also update ChatState on a timer
                                    } catch (_: Exception) { }
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
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
            
            override fun sendAnnouncementToPeer(peerID: String) {
                this@BluetoothMeshService.sendAnnouncementToPeer(peerID)
            }
            
            override fun sendCachedMessages(peerID: String) {
                storeForwardManager.sendCachedMessages(peerID)
            }
            
            override fun relayPacket(routed: RoutedPacket) {
                connectionManager.broadcastPacket(routed)
            }

            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return connectionManager.sendToPeer(peerID, routed)
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

            override fun onDeviceDisconnected(device: android.bluetooth.BluetoothDevice) {
                val addr = device.address
                // Remove mapping and, if that was the last direct path for the peer, clear direct flag
                val peer = connectionManager.addressPeerMap[addr]
                // ConnectionTracker has already removed the address mapping; be defensive either way
                connectionManager.addressPeerMap.remove(addr)
                if (peer != null) {
                    val stillMapped = connectionManager.addressPeerMap.values.any { it == peer }
                    if (!stillMapped) {
                        // Peer might still be reachable indirectly; mark as not-direct
                        try { peerManager.setDirectConnection(peer, false) } catch (_: Exception) { }
                    }
                    // Verbose debug: device disconnected
                    try {
                        val nick = peerManager.getPeerNickname(peer) ?: "unknown"
                        com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
                            .logPeerDisconnection(peer, nick, addr)
                    } catch (_: Exception) { }
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
            
            // Start periodic announcements for peer discovery and connectivity
            sendPeriodicBroadcastAnnounce()
            Log.d(TAG, "Started periodic broadcast announcements (every 30 seconds)")
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
            connectionManager.broadcastPacket(RoutedPacket(signedPacket))
        }
    }
    
    /**
     * Send private message - SIMPLIFIED iOS-compatible version 
     * Uses NoisePayloadType system exactly like iOS SimplifiedBluetoothService
     */
    fun sendPrivateMessage(content: String, recipientPeerID: String, recipientNickname: String, messageID: String? = null) {
        if (content.isEmpty() || recipientPeerID.isEmpty()) return
        if (recipientNickname.isEmpty()) return
        
        serviceScope.launch {
            val finalMessageID = messageID ?: java.util.UUID.randomUUID().toString()
            
            Log.d(TAG, "📨 Sending PM to $recipientPeerID: ${content.take(30)}...")
            
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
                    connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                    Log.d(TAG, "📤 Sent encrypted private message to $recipientPeerID (${encrypted.size} bytes)")
                    
                    // FIXED: Don't send didReceiveMessage for our own sent messages
                    // This was causing self-notifications - iOS doesn't do this
                    // The UI handles showing sent messages through its own message sending logic
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to encrypt private message for $recipientPeerID: ${e.message}")
                }
            } else {
                // Fire and forget - initiate handshake but don't queue exactly like iOS
                Log.d(TAG, "🤝 No session with $recipientPeerID, initiating handshake")
                messageHandler.delegate?.initiateNoiseHandshake(recipientPeerID)
                
                // FIXED: Don't send didReceiveMessage for our own sent messages
                // The UI will handle showing the message in the chat interface
            }
        }
    }
    
    /**
     * Send read receipt for a received private message - NEW NoisePayloadType implementation
     * Uses same encryption approach as iOS SimplifiedBluetoothService
     */
    fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        serviceScope.launch {
            Log.d(TAG, "📖 Sending read receipt for message $messageID to $recipientPeerID")
            
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
                    ttl = 7u // Same TTL as iOS messageTTL
                )
                
                // Sign the packet before broadcasting
                val signedPacket = signPacketBeforeBroadcast(packet)
                connectionManager.broadcastPacket(RoutedPacket(signedPacket))
                Log.d(TAG, "📤 Sent read receipt to $recipientPeerID for message $messageID")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt to $recipientPeerID: ${e.message}")
            }
        }
    }
    
    /**
     * Send broadcast announce with TLV-encoded identity announcement - exactly like iOS
     */
    fun sendBroadcastAnnounce() {
        Log.d(TAG, "Sending broadcast announce")
        serviceScope.launch {
            val nickname = delegate?.getNickname() ?: myPeerID
            
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
            val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
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
            
            connectionManager.broadcastPacket(RoutedPacket(signedPacket))
            Log.d(TAG, "Sent iOS-compatible signed TLV announce (${tlvPayload.size} bytes)")
        }
    }
    
    /**
     * Send announcement to specific peer with TLV-encoded identity announcement - exactly like iOS
     */
    fun sendAnnouncementToPeer(peerID: String) {
        if (peerManager.hasAnnouncedToPeer(peerID)) return
        
        val nickname = delegate?.getNickname() ?: myPeerID
        
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
        val announcement = IdentityAnnouncement(nickname, staticKey, signingKey)
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
        
        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
        peerManager.markPeerAsAnnouncedTo(peerID)
        Log.d(TAG, "Sent iOS-compatible signed TLV peer announce to $peerID (${tlvPayload.size} bytes)")
    }

    /**
     * Collect up to 10 direct neighbors for gossip TLV.
     */
    private fun getDirectPeerIDsForGossip(): List<String> {
        return try {
            // Prefer verified peers that are currently marked as direct
            val verified = peerManager.getVerifiedPeers()
            val direct = verified.filter { it.value.isDirectConnection }.keys.toList()
            direct.take(10)
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Send leave announcement
     */
    private fun sendLeaveAnnouncement() {
        val nickname = delegate?.getNickname() ?: myPeerID
        val packet = BitchatPacket(
            type = MessageType.LEAVE.value,
            ttl = MAX_TTL,
            senderID = myPeerID,
            payload = nickname.toByteArray()
        )
        
        // Sign the packet before broadcasting
        val signedPacket = signPacketBeforeBroadcast(packet)
        connectionManager.broadcastPacket(RoutedPacket(signedPacket))
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
    
    /**
     * Sign packet before broadcasting using our signing private key
     */
    private fun signPacketBeforeBroadcast(packet: BitchatPacket): BitchatPacket {
        return try {
            // Optionally compute and attach a source route for addressed packets
            val withRoute = try {
                val rec = packet.recipientID
                if (rec != null && !rec.contentEquals(SpecialRecipients.BROADCAST)) {
                    val dest = rec.joinToString("") { b -> "%02x".format(b) }
                    val path = com.bitchat.android.services.meshgraph.RoutePlanner.shortestPath(myPeerID, dest)
                    if (path != null && path.size >= 3) {
                        // Exclude first (sender) and last (recipient); only intermediates
                        val intermediates = path.subList(1, path.size - 1)
                        val hopsBytes = intermediates.map { hexStringToByteArray(it) }
                        Log.d(TAG, "✅ Signed packet type ${packet.type} (route ${hopsBytes.size} hops: $intermediates)")
                        packet.copy(route = hopsBytes)
                    } else packet.copy(route = null)
                } else packet
            } catch (_: Exception) { packet }

            // Get the canonical packet data for signing (without signature)
            val packetDataForSigning = withRoute.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "Failed to encode packet type ${packet.type} for signing, sending unsigned")
                return withRoute
            }
            
            // Sign the packet data using our signing key
            val signature = encryptionService.signData(packetDataForSigning)
            if (signature != null) {
                Log.d(TAG, "✅ Signed packet type ${packet.type} (signature ${signature.size} bytes)")
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
        Log.w(TAG, "🚨 Clearing all mesh service internal data")
        try {
            // Clear all managers
            fragmentManager.clearAllFragments()
            storeForwardManager.clearAllCache()
            securityManager.clearAllData()
            peerManager.clearAllPeers()
            peerManager.clearAllFingerprints()
            Log.d(TAG, "✅ Cleared all mesh service internal data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service internal data: ${e.message}")
        }
    }
    
    /**
     * Clear all encryption and cryptographic data (for panic mode)
     */
    fun clearAllEncryptionData() {
        Log.w(TAG, "🚨 Clearing all encryption data")
        try {
            // Clear encryption service persistent identity (includes Ed25519 signing keys)
            encryptionService.clearPersistentIdentity()
            Log.d(TAG, "✅ Cleared all encryption data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing encryption data: ${e.message}")
        }
    }
}

/**
 * Delegate interface for mesh service callbacks (maintains exact same interface)
 */
interface BluetoothMeshDelegate {
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
