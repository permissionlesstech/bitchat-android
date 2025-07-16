package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.RoutedPacket
import kotlinx.coroutines.*

/**
 * Processes incoming packets and routes them to appropriate handlers
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class PacketProcessor(private val myPeerID: String) {
    
    companion object {
        private const val TAG = "PacketProcessor"
    }
    
    // Delegate for callbacks
    var delegate: PacketProcessorDelegate? = null
    
    // Coroutines
    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Process received packet - main entry point for all incoming packets
     */
    fun processPacket(routed: RoutedPacket) {
        processorScope.launch {
            handleReceivedPacket(routed)
        }
    }
    
    /**
     * Handle received packet - core protocol logic (exact same as iOS)
     */
    private suspend fun handleReceivedPacket(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Basic validation and security checks
        if (!delegate?.validatePacketSecurity(packet, peerID)!!) {
            Log.d(TAG, "Packet failed security validation from $peerID")
            return
        }
        
        // Update last seen timestamp
        delegate?.updatePeerLastSeen(peerID)
        
        Log.d(TAG, "Processing packet type ${packet.type} from $peerID")
        val DEBUG_MESSAGE_TYPE = MessageType.fromValue(packet.type)
        // Process based on message type (exact same logic as iOS)
        when (MessageType.fromValue(packet.type)) {
            MessageType.NOISE_HANDSHAKE_INIT -> handleNoiseHandshake(routed, 1)
            MessageType.NOISE_HANDSHAKE_RESP -> handleNoiseHandshake(routed, 2)
            MessageType.NOISE_ENCRYPTED -> handleNoiseEncrypted(routed)
            MessageType.NOISE_IDENTITY_ANNOUNCE -> handleNoiseIdentityAnnouncement(routed)
            MessageType.ANNOUNCE -> handleAnnounce(routed)
            MessageType.MESSAGE -> handleMessage(routed)
            MessageType.LEAVE -> handleLeave(routed)
            MessageType.FRAGMENT_START,
            MessageType.FRAGMENT_CONTINUE,
            MessageType.FRAGMENT_END -> handleFragment(routed)
            MessageType.DELIVERY_ACK -> handleDeliveryAck(routed)
            MessageType.READ_RECEIPT -> handleReadReceipt(routed)
            else -> {
                Log.w(TAG, "Unknown message type: ${packet.type}")
            }
        }
    }
    
    /**
     * Handle Noise handshake message
     */
    private suspend fun handleNoiseHandshake(routed: RoutedPacket, step: Int) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise handshake step $step from $peerID")
        
        val success = delegate?.handleNoiseHandshake(routed, step) ?: false
        
        if (success) {
            // Handshake successful, may need to send announce and cached messages
            // This will be determined by the Noise implementation when session is established
            delay(100)
            delegate?.sendAnnouncementToPeer(peerID)
            
            delay(500)
            delegate?.sendCachedMessages(peerID)
        }
    }
    
    /**
     * Handle Noise encrypted transport message
     */
    private suspend fun handleNoiseEncrypted(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise encrypted message from $peerID")
        delegate?.handleNoiseEncrypted(routed)
    }
    
    /**
     * Handle Noise identity announcement (after peer ID rotation)
     */
    private suspend fun handleNoiseIdentityAnnouncement(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        Log.d(TAG, "Processing Noise identity announcement from $peerID")
        delegate?.handleNoiseIdentityAnnouncement(routed)
    }
    
    /**
     * Handle announce message
     */
    private suspend fun handleAnnounce(routed: RoutedPacket) {
        Log.d(TAG, "Processing announce from ${routed.peerID}")
        delegate?.handleAnnounce(routed)
    }
    
    /**
     * Handle regular message
     */
    private suspend fun handleMessage(routed: RoutedPacket) {
        Log.d(TAG, "Processing message from ${routed.peerID}")
        delegate?.handleMessage(routed)
    }
    
    /**
     * Handle leave message
     */
    private suspend fun handleLeave(routed: RoutedPacket) {
        Log.d(TAG, "Processing leave from ${routed.peerID}")
        delegate?.handleLeave(routed)
    }
    
    /**
     * Handle message fragments
     */
    private suspend fun handleFragment(routed: RoutedPacket) {
        Log.d(TAG, "Processing fragment from ${routed.peerID}")
        
        val reassembledPacket = delegate?.handleFragment(routed.packet)
        if (reassembledPacket != null) {
            Log.d(TAG, "Fragment reassembled, processing complete message")
            handleReceivedPacket(RoutedPacket(reassembledPacket, routed.peerID, routed.relayAddress))
        }
        
        // Relay fragment regardless of reassembly
        if (routed.packet.ttl > 0u) {
            val relayPacket = routed.packet.copy(ttl = (routed.packet.ttl - 1u).toUByte())
            delegate?.relayPacket(RoutedPacket(relayPacket, routed.peerID, routed.relayAddress))
        }
    }
    
    /**
     * Handle delivery acknowledgment
     */
    private suspend fun handleDeliveryAck(routed: RoutedPacket) {
        Log.d(TAG, "Processing delivery ACK from ${routed.peerID}")
        delegate?.handleDeliveryAck(routed)
    }
    
    /**
     * Handle read receipt
     */
    private suspend fun handleReadReceipt(routed: RoutedPacket) {
        Log.d(TAG, "Processing read receipt from ${routed.peerID}")
        delegate?.handleReadReceipt(routed)
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Processor Debug Info ===")
            appendLine("Processor Scope Active: ${processorScope.isActive}")
            appendLine("My Peer ID: $myPeerID")
        }
    }
    
    /**
     * Shutdown the processor
     */
    fun shutdown() {
        processorScope.cancel()
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
    
    // Message type handlers
    fun handleNoiseHandshake(routed: RoutedPacket, step: Int): Boolean
    fun handleNoiseEncrypted(routed: RoutedPacket)
    fun handleNoiseIdentityAnnouncement(routed: RoutedPacket)
    fun handleAnnounce(routed: RoutedPacket)
    fun handleMessage(routed: RoutedPacket)
    fun handleLeave(routed: RoutedPacket)
    fun handleFragment(packet: BitchatPacket): BitchatPacket?
    fun handleDeliveryAck(routed: RoutedPacket)
    fun handleReadReceipt(routed: RoutedPacket)
    
    // Communication
    fun sendAnnouncementToPeer(peerID: String)
    fun sendCachedMessages(peerID: String)
    fun relayPacket(routed: RoutedPacket)
}
