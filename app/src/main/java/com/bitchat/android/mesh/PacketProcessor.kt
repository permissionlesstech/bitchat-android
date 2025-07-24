package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.RoutedPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.actor

/**
 * Processes incoming packets and routes them to appropriate handlers
 * 
 * Per-peer packet serialization using Kotlin coroutine actors
 * Prevents race condition where multiple threads process packets
 * from the same peer simultaneously, causing session management conflicts.
 */
class PacketProcessor(private val myPeerID: String) {
    
    companion object {
        private const val TAG = "PacketProcessor"
    }
    
    // Delegate for callbacks
    var delegate: PacketProcessorDelegate? = null
    
    // Coroutines
    private val processorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Per-peer actors to serialize packet processing
    // Each peer gets its own actor that processes packets sequentially
    // This prevents race conditions in session management
    private val peerActors = mutableMapOf<String, CompletableDeferred<Unit>>()
    
    @OptIn(ObsoleteCoroutinesApi::class)
    private fun getOrCreateActorForPeer(peerID: String) = processorScope.actor<RoutedPacket>(
        capacity = Channel.UNLIMITED
    ) {
        Log.d(TAG, "🎭 Created packet actor for peer: $peerID")
        try {
            for (packet in channel) {
                Log.d(TAG, "📦 Processing packet type ${packet.packet.type} from $peerID (serialized)")
                handleReceivedPacket(packet)
                Log.d(TAG, "Completed packet type ${packet.packet.type} from $peerID")
            }
        } finally {
            Log.d(TAG, "🎭 Packet actor for $peerID terminated")
        }
    }
    
    // Cache actors to reuse them
    private val actors = mutableMapOf<String, kotlinx.coroutines.channels.SendChannel<RoutedPacket>>()
    
    /**
     * Process received packet - main entry point for all incoming packets
     * SURGICAL FIX: Route to per-peer actor for serialized processing
     */
    fun processPacket(routed: RoutedPacket) {
        val peerID = routed.peerID ?: "unknown"
        
        // Get or create actor for this peer
        val actor = actors.getOrPut(peerID) { getOrCreateActorForPeer(peerID) }
        
        // Send packet to peer's dedicated actor for serialized processing
        processorScope.launch {
            try {
                actor.send(routed)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send packet to actor for $peerID: ${e.message}")
                // Fallback to direct processing if actor fails
                handleReceivedPacket(routed)
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
        if (!delegate?.validatePacketSecurity(packet, peerID)!!) {
            Log.d(TAG, "Packet failed security validation from $peerID")
            return
        }

        var validPacket = true
        Log.d(TAG, "Processing packet type ${packet.type} from $peerID")
        val DEBUG_MESSAGE_TYPE = MessageType.fromValue(packet.type)
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
                validPacket = false
                Log.w(TAG, "Unknown message type: ${packet.type}")
            }
        }
        // Update last seen timestamp
        if (validPacket)
            delegate?.updatePeerLastSeen(peerID)
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
            appendLine("Active Peer Actors: ${actors.size}")
            appendLine("My Peer ID: $myPeerID")
            
            if (actors.isNotEmpty()) {
                appendLine("Peer Actors:")
                actors.keys.forEach { peerID ->
                    appendLine("  - $peerID")
                }
            }
        }
    }
    
    /**
     * Shutdown the processor and all peer actors
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down PacketProcessor and ${actors.size} peer actors")
        
        // Close all peer actors gracefully
        actors.values.forEach { actor ->
            actor.close()
        }
        actors.clear()
        
        // Cancel the main scope
        processorScope.cancel()
        
        Log.d(TAG, "PacketProcessor shutdown complete")
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
