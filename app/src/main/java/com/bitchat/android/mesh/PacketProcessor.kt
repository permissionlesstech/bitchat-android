package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.RoutedPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * Processes incoming packets and routes them to appropriate handlers
 * 
 * Bounded worker queues serialize packets from the same peer.
 * Prevents race condition where multiple threads process packets
 * from the same peer simultaneously, causing session management conflicts.
 */
class PacketProcessor(
    private val myPeerID: String,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    
    companion object {
        private const val TAG = "PacketProcessor"
        internal const val WORKER_COUNT = 16
        internal const val QUEUE_CAPACITY = 64
        internal const val MAX_PENDING_PAYLOAD_BYTES = 8 * 1024 * 1024
    }
    
    // Delegate for callbacks
    var delegate: PacketProcessorDelegate? = null
    
    // Packet relay manager for centralized relay decisions
    private val packetRelayManager = PacketRelayManager(myPeerID)
    
    // Coroutines
    private val processorScope = CoroutineScope(dispatcher + SupervisorJob())
    private val admissionLock = Any()
    private var pendingPayloadBytes = 0L
    private data class QueuedPacket(val routed: RoutedPacket, val payloadBytes: Int)
    private val queues = List(WORKER_COUNT) {
        Channel<QueuedPacket>(QUEUE_CAPACITY, onUndeliveredElement = ::release)
    }

    private fun release(packet: QueuedPacket) = synchronized(admissionLock) {
        pendingPayloadBytes -= packet.payloadBytes
    }
    
    init {
        // Set up the packet relay manager delegate immediately
        setupRelayManager()
        queues.forEach { queue ->
            processorScope.launch {
                for (queued in queue) {
                    try {
                        handleReceivedPacket(queued.routed)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // A bad packet cannot kill a worker or bypass its serialization.
                        Log.w(TAG, "Packet processing failed")
                    } finally {
                        release(queued)
                    }
                }
            }
        }
    }
    
    /**
     * Process received packet - main entry point for all incoming packets
     * The same peer always hashes to the same FIFO worker. Admission never suspends or launches
     * a coroutine per packet. Under overload the newest packet is dropped to bound resource use.
     */
    fun processPacket(routed: RoutedPacket) {
        val peerID = routed.peerID

        if (peerID == null) {
            Log.w(TAG, "Received packet with no peer ID, skipping")
            return
        }
        
        val queued = QueuedPacket(routed, routed.packet.payload.size)
        val queue = queues[(peerID.hashCode() and Int.MAX_VALUE) % WORKER_COUNT]
        synchronized(admissionLock) {
            if (!processorScope.isActive ||
                pendingPayloadBytes + queued.payloadBytes > MAX_PENDING_PAYLOAD_BYTES
            ) return
            pendingPayloadBytes += queued.payloadBytes
            if (queue.trySend(queued).isFailure) release(queued)
        }
    }
    
    /**
     * Set up the packet relay manager with its delegate
     */
    fun setupRelayManager() {
        packetRelayManager.delegate = object : PacketRelayManagerDelegate {
            override fun getNetworkSize(): Int {
                return delegate?.getNetworkSize() ?: 1
            }
            
            override fun getBroadcastRecipient(): ByteArray {
                return delegate?.getBroadcastRecipient() ?: ByteArray(0)
            }
            
            override fun broadcastPacket(routed: RoutedPacket) {
                delegate?.relayPacket(routed)
            }
            override fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean {
                return delegate?.sendToPeer(peerID, routed) ?: false
            }
        }
    }
    
    /**
     * Handle received packet - core protocol logic (exact same as iOS)
     */
    private suspend fun handleReceivedPacket(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Basic validation and security checks
        if (delegate?.validatePacketSecurity(packet, peerID) != true) {
            return
        }

        var validPacket = true
        val messageType = MessageType.fromValue(packet.type)
        // Verbose logging to debug manager (and chat via ChatViewModel observer)
        try {
            val mt = messageType?.name ?: packet.type.toString()
            val routeDevice = routed.relayAddress
            val nick = delegate?.getPeerNickname(peerID)
            debugManager?.logIncomingPacket(peerID, nick, mt, routeDevice)
        } catch (_: Exception) { }
        
        
        // Handle public packet types (no address check needed)
        when (messageType) {
            MessageType.ANNOUNCE -> validPacket = handleAnnounce(routed)
            MessageType.MESSAGE -> handleMessage(routed)
            MessageType.FILE_TRANSFER -> handleMessage(routed) // treat same routing path; parsing happens in handler
            MessageType.VOICE_FRAME -> validPacket = delegate?.handleVoiceFrame(routed) ?: false
            MessageType.LEAVE -> handleLeave(routed)
            MessageType.FRAGMENT -> handleFragment(routed)
            MessageType.REQUEST_SYNC -> handleRequestSync(routed)
            else -> {
                // Handle private packet types (address check required)
                if (packetRelayManager.isPacketAddressedToMe(packet)) {
                    when (messageType) {
                        MessageType.NOISE_HANDSHAKE -> validPacket = handleNoiseHandshake(routed)
                        MessageType.NOISE_ENCRYPTED -> validPacket = handleNoiseEncrypted(routed)
                        MessageType.FILE_TRANSFER -> handleMessage(routed)
                        else -> {
                            validPacket = false
                            Log.w(TAG, "Unknown message type: ${packet.type}")
                        }
                    }
                } else {
                    // Not addressed to us; only relay handling below applies
                }
            }
        }
        
        // Update last seen timestamp
        if (validPacket) {
            delegate?.updatePeerLastSeen(peerID)
            
            // CENTRALIZED RELAY LOGIC: Handle relay decisions for all packets not addressed to us
            packetRelayManager.handlePacketRelay(routed)
        }
    }
    
    /**
     * Handle Noise handshake message - SIMPLIFIED iOS-compatible version
     */
    private suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        return delegate?.handleNoiseHandshake(routed) ?: false
    }
    
    /**
     * Handle Noise encrypted transport message
     * Returns false when decryption fails so undecryptable packets do not prove liveness.
     */
    private suspend fun handleNoiseEncrypted(routed: RoutedPacket): Boolean {
        return delegate?.handleNoiseEncrypted(routed) ?: false
    }
    
    /**
     * Handle announce message
     */
    private suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
        return delegate?.handleAnnounce(routed) ?: false
    }
    
    /**
     * Handle regular message
     */
    private suspend fun handleMessage(routed: RoutedPacket) {
        delegate?.handleMessage(routed)
    }
    
    /**
     * Handle leave message
     */
    private suspend fun handleLeave(routed: RoutedPacket) {
        delegate?.handleLeave(routed)
    }
    
    /**
     * Handle message fragments
     */
    private suspend fun handleFragment(routed: RoutedPacket) {
        val reassembledPacket = delegate?.handleFragment(routed.packet)
        if (reassembledPacket != null) {
            handleReceivedPacket(
                RoutedPacket(
                    packet = reassembledPacket,
                    peerID = routed.peerID,
                    relayAddress = routed.relayAddress,
                    ingressLinkID = routed.ingressLinkID
                )
            )
        }
        
        // Fragment relay is now handled by centralized PacketRelayManager
    }

    /**
     * Handle REQUEST_SYNC packets (public, TTL=1)
     */
    private suspend fun handleRequestSync(routed: RoutedPacket) {
        delegate?.handleRequestSync(routed)
    }
    
    /**
     * Handle delivery acknowledgment
     */
//    private suspend fun handleDeliveryAck(routed: RoutedPacket) {
//        val peerID = routed.peerID ?: "unknown"
//        Log.d(TAG, "Processing delivery ACK from ${formatPeerForLog(peerID)}")
//        delegate?.handleDeliveryAck(routed)
//    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Processor Debug Info ===")
            appendLine("Processor Scope Active: ${processorScope.isActive}")
            appendLine("Packet Workers: $WORKER_COUNT")
            appendLine("Pending Payload Bytes: ${synchronized(admissionLock) { pendingPayloadBytes }}")
        }
    }
    
    /**
     * Shutdown the processor and release all queued packets.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down PacketProcessor")
        processorScope.cancel()
        queues.forEach { it.cancel() }
        
        // Shutdown the relay manager
        packetRelayManager.shutdown()
        
    }
}

/**
 * Delegate interface for packet processor callbacks
 */
interface PacketProcessorDelegate {
    // Security validation
    fun validatePacketSecurity(packet: BitchatPacket, peerID: String): Boolean
    
    // Peer management
    fun updatePeerLastSeen(peerID: String)
    fun getPeerNickname(peerID: String): String?
    
    // Network information
    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Message type handlers
    fun handleNoiseHandshake(routed: RoutedPacket): Boolean
    fun handleNoiseEncrypted(routed: RoutedPacket): Boolean
    suspend fun handleAnnounce(routed: RoutedPacket): Boolean
    suspend fun handleMessage(routed: RoutedPacket)
    fun handleVoiceFrame(routed: RoutedPacket): Boolean = false
    suspend fun handleLeave(routed: RoutedPacket)
    fun handleFragment(packet: BitchatPacket): BitchatPacket?
    fun handleRequestSync(routed: RoutedPacket)
    
    // Communication
    fun sendAnnouncementToPeer(peerID: String)
    fun sendCachedMessages(peerID: String)
    fun relayPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}
