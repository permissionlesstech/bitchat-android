package com.bitchat.android.mesh
import com.bitchat.android.protocol.MessageType

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Centralized packet relay management
 * 
 * This class handles all relay decisions and logic for bitchat packets.
 * All packets that aren't specifically addressed to us get processed here.
 */
class PacketRelayManager(private val myPeerID: String) {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }
    
    companion object {
        private const val TAG = "PacketRelayManager"
    }
    
    private fun isRelayEnabled(): Boolean = try {
        com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().packetRelayEnabled.value
    } catch (_: Exception) { true }

    // Logging moved to BluetoothPacketBroadcaster per actual transmission target
    
    // Delegate for callbacks
    var delegate: PacketRelayManagerDelegate? = null
    
    // Coroutines
    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Main entry point for relay decisions
     * Only packets that aren't specifically addressed to us should be passed here
     */
    suspend fun handlePacketRelay(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.d(TAG, "Evaluating relay for packet type ${packet.type} from ${peerID} (TTL: ${packet.ttl})")
        
        // Double-check this packet isn't addressed to us
        if (isPacketAddressedToMe(packet)) {
            Log.d(TAG, "Packet addressed to us, skipping relay")
            return
        }
        
        // Skip our own packets
        if (peerID == myPeerID) {
            Log.d(TAG, "Packet from ourselves, skipping relay")
            return
        }
        
        // Check TTL and decrement
        if (packet.ttl == 0u.toUByte()) {
            Log.d(TAG, "TTL expired, not relaying packet")
            return
        }
        
        // Decrement TTL by 1
        val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        Log.d(TAG, "Decremented TTL from ${packet.ttl} to ${relayPacket.ttl}")
        
        // Apply relay logic based on packet type and debug switch
        val shouldRelay = isRelayEnabled() && shouldRelayPacket(relayPacket, peerID)
        
        if (shouldRelay) {
            relayPacket(RoutedPacket(relayPacket, peerID, routed.relayAddress))
        } else {
            Log.d(TAG, "Relay decision: NOT relaying packet type ${packet.type}")
        }
    }
    
    /**
     * Check if a packet is specifically addressed to us
     */
    internal fun isPacketAddressedToMe(packet: BitchatPacket): Boolean {
        val recipientID = packet.recipientID
        
        // No recipient means broadcast (not addressed to us specifically)
        if (recipientID == null) {
            return false
        }
        
        // Check if it's a broadcast recipient
        val broadcastRecipient = delegate?.getBroadcastRecipient()
        if (broadcastRecipient != null && recipientID.contentEquals(broadcastRecipient)) {
            return false
        }
        
        // Check if recipient matches our peer ID
        val recipientIDString = recipientID.toHexString()
        return recipientIDString == myPeerID
    }
    
    /**
     * Determine if we should relay this packet based on type and network conditions
     */
    private fun shouldRelayPacket(packet: BitchatPacket, fromPeerID: String): Boolean {
        // Always relay if TTL is high enough (indicates important message)
        if (packet.ttl >= 4u) {
            Log.d(TAG, "High TTL (${packet.ttl}), relaying")
            return true
        }
        
        // Get network size for adaptive relay probability
        val networkSize = delegate?.getNetworkSize() ?: 1
        
        // Small networks always relay to ensure connectivity
        if (networkSize <= 3) {
            Log.d(TAG, "Small network (${networkSize} peers), relaying")
            return true
        }
        
        // Apply adaptive relay probability based on network size
        val relayProb = when {
            networkSize <= 10 -> 1.0    // Always relay in small networks
            networkSize <= 30 -> 0.85   // High probability for medium networks
            networkSize <= 50 -> 0.7    // Moderate probability
            networkSize <= 100 -> 0.55  // Lower probability for large networks
            else -> 0.4                 // Lowest probability for very large networks
        }
        
        val shouldRelay = Random.nextDouble() < relayProb
        Log.d(TAG, "Network size: ${networkSize}, Relay probability: ${relayProb}, Decision: ${shouldRelay}")
        
        return shouldRelay
    }
    
    /**
     * Actually broadcast the packet for relay
     */
    private fun relayPacket(routed: RoutedPacket) {
        Log.d(TAG, "🔄 Relaying packet type ${routed.packet.type} with TTL ${routed.packet.ttl}")
        delegate?.broadcastPacket(routed)
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Packet Relay Manager Debug Info ===")
            appendLine("Relay Scope Active: ${relayScope.isActive}")
            appendLine("My Peer ID: ${myPeerID}")
            appendLine("Network Size: ${delegate?.getNetworkSize() ?: "unknown"}")
        }
    }
    
    /**
     * Shutdown the relay manager
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down PacketRelayManager")
        relayScope.cancel()
    }
}

/**
 * Delegate interface for packet relay manager callbacks
 */
interface PacketRelayManagerDelegate {
    // Network information
    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray
    
    // Packet operations
    fun broadcastPacket(routed: RoutedPacket)
}
