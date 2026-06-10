package com.bitchat.android.mesh
import com.bitchat.android.protocol.MessageType

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.PeerId
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

        // Double-check this packet isn't addressed to us
        if (isPacketAddressedToMe(packet)) {
            return
        }
        
        // Skip our own packets
        if (peerID == myPeerID) {
            return
        }
        
        // Check TTL and decrement
        if (packet.ttl == 0u.toUByte()) {
            return
        }
        
        // Decrement TTL by 1
        val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        
        // Source-based routing: if route is set and includes us, try targeted next-hop forwarding
        val route = relayPacket.route
        if (!route.isNullOrEmpty()) {
            // Check for duplicate hops to prevent routing loops
            if (route.map { it.toHexString() }.toSet().size < route.size) {
                Log.w(TAG, "Packet with duplicate hops dropped")
                return
            }
            val myIdBytes = PeerId.toBytes(myPeerID)
            val index = route.indexOfFirst { it.contentEquals(myIdBytes) }
            if (index >= 0) {
                val nextHopIdHex: String? = run {
                    val nextIndex = index + 1
                    if (nextIndex < route.size) {
                        route[nextIndex].toHexString()
                    } else {
                        // We are the last intermediate; try final recipient as next hop
                        relayPacket.recipientID?.toHexString()
                    }
                }
                if (nextHopIdHex != null) {
                    val success = try { delegate?.sendToPeer(nextHopIdHex, RoutedPacket(relayPacket, peerID, routed.relayAddress)) } catch (_: Exception) { false } ?: false
                    if (success) {
                        return
                    } else {
                        Log.w(TAG, "Source-route next hop ${nextHopIdHex.take(8)} not directly connected; falling back to broadcast")
                    }
                }
            }
        }

        // Apply relay logic based on packet type and debug switch
        val shouldRelay = isRelayEnabled() && shouldRelayPacket(relayPacket)
        if (shouldRelay) {
            relayPacket(RoutedPacket(relayPacket, peerID, routed.relayAddress))
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
    private fun shouldRelayPacket(packet: BitchatPacket): Boolean {
        // Always relay if TTL is high enough (indicates important message)
        if (packet.ttl >= 4u) {
            return true
        }
        
        // Get network size for adaptive relay probability
        val networkSize = delegate?.getNetworkSize() ?: 1
        
        // Small networks always relay to ensure connectivity
        if (networkSize <= 3) {
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
        
        return Random.nextDouble() < relayProb
    }
    
    /**
     * Actually broadcast the packet for relay
     */
    private fun relayPacket(routed: RoutedPacket) {
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
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}
