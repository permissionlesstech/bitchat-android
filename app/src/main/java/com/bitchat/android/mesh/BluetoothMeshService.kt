package com.bitchat.android.mesh

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.crypto.MessagePadding
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import kotlinx.coroutines.*
import java.util.*
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
        startPeriodicDebugLogging()
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
                    
                    delay(500)
                    storeForwardManager.sendCachedMessages(peerID)
                }
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
            
            override fun handleKeyExchange(routed: RoutedPacket): Boolean {
                return runBlocking { securityManager.handleKeyExchange(routed) }
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
            // Send initial announcements after services are ready
            serviceScope.launch {
                delay(1000)
                sendBroadcastAnnounce()
            }
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
                // Sign the message
                val signature = securityManager.signPacket(messageData)
                
                val packet = BitchatPacket(
                    type = MessageType.MESSAGE.value,
                    senderID = myPeerID.toByteArray(),
                    recipientID = SpecialRecipients.BROADCAST,
                    timestamp = System.currentTimeMillis().toULong(),
                    payload = messageData,
                    signature = signature,
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
        
        serviceScope.launch {
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
                    // Pad and encrypt
                    val blockSize = MessagePadding.optimalBlockSize(messageData.size)
                    val paddedData = MessagePadding.pad(messageData, blockSize)
                    val encryptedPayload = securityManager.encryptForPeer(paddedData, recipientPeerID)
                    
                    if (encryptedPayload != null) {
                        // Sign
                        val signature = securityManager.signPacket(encryptedPayload)
                        
                        val packet = BitchatPacket(
                            type = MessageType.MESSAGE.value,
                            senderID = myPeerID.toByteArray(),
                            recipientID = recipientPeerID.toByteArray(),
                            timestamp = System.currentTimeMillis().toULong(),
                            payload = encryptedPayload,
                            signature = signature,
                            ttl = MAX_TTL
                        )
                        
                        // Cache for offline favorites
                        if (storeForwardManager.shouldCacheForPeer(recipientPeerID)) {
                            storeForwardManager.cacheMessage(packet, messageID ?: message.id)
                        }
                        
                        // Send with delay
                        delay(Random.nextLong(50, 500))
                        connectionManager.broadcastPacket(RoutedPacket(packet))
                    }
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send private message: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Send broadcast announce
     */
    fun sendBroadcastAnnounce() {
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
     * Send key exchange
     */
    private fun sendKeyExchangeToDevice() {
        val publicKeyData = securityManager.getCombinedPublicKeyData()
        val packet = BitchatPacket(
            type = MessageType.KEY_EXCHANGE.value,
            ttl = 1u,
            senderID = myPeerID,
            payload = publicKeyData
        )
        
        connectionManager.broadcastPacket(RoutedPacket(packet))
        Log.d(TAG, "Sent key exchange")
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
     * Generate peer ID compatible with iOS
     */
    private fun generateCompatiblePeerID(): String {
        val randomBytes = ByteArray(4)
        Random.nextBytes(randomBytes)
        return randomBytes.joinToString("") { "%02x".format(it) }
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
