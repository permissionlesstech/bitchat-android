package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.services.meshgraph.MprSelector
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.*
import kotlin.random.Random

/**
 * Centralized packet relay management.
 *
 * Handles all relay decisions for packets not specifically addressed to us:
 * - Preserves explicit source-routed unicast (`packet.route`) and 1-hop `REQUEST_SYNC` behavior.
 * - Uses deterministic 2-hop Multipoint Relay (MPR) hub selection with fail-safe unconfirmed-peer
 *   protection for broadcast traffic classes when the immediate previous hop is known and the
 *   local 2-hop graph is fresh.
 * - Keeps topology-healing control traffic (`ANNOUNCE` for new peers / neighbor changes, `LEAVE`)
 *   and unicast/broken-route fallbacks (`NOISE_HANDSHAKE`, `NOISE_ENCRYPTED`) on conservative
 *   flooding so partitions heal promptly.
 */
class PacketRelayManager(
    private val myPeerID: String,
    private val meshGraphProvider: () -> MeshGraphService = MeshGraphService::getInstance,
    private val edgeMaxAgeMs: Long = MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS
) {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "PacketRelayManager"
        internal const val SMALL_NETWORK_RELAY_THRESHOLD = 3
    }

    internal enum class RelayPolicy {
        MPR_1_COVERED,
        MPR_2_COVERED,
        CONSERVATIVE_FLOOD
    }

    private data class BroadcastRelayDecision(
        val shouldRelay: Boolean,
        val skipNeighborPruning: Boolean,
        val reason: String
    )

    private fun isRelayEnabled(): Boolean = try {
        com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().packetRelayEnabled.value
    } catch (_: Exception) { true }

    // Delegate for callbacks
    var delegate: PacketRelayManagerDelegate? = null

    // Coroutines
    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Main entry point for relay decisions.
     * Only packets that aren't specifically addressed to us should be passed here.
     *
     * @param forceConservativeRelay Set true for topology-changing or first-time ANNOUNCE packets
     *   so they are never MPR-suppressed or neighbor-pruned.
     */
    suspend fun handlePacketRelay(
        routed: RoutedPacket,
        forceConservativeRelay: Boolean = false
    ) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val messageType = MessageType.fromValue(packet.type)

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

        // Check TTL
        if (packet.ttl == 0u.toUByte()) {
            Log.d(TAG, "TTL expired, not relaying packet")
            return
        }

        // Preserve strict 1-hop REQUEST_SYNC behavior (never multi-hop flood TTL <= 1 sync requests)
        if (messageType == MessageType.REQUEST_SYNC && packet.ttl <= 1u.toUByte()) {
            Log.d(TAG, "1-hop REQUEST_SYNC (TTL=${packet.ttl}), not relaying")
            return
        }

        // Decrement TTL by 1
        val networkSize = delegate?.getNetworkSize() ?: 1
        val decrementedTtl = (packet.ttl - 1u).toUByte()
        val voiceTtl = if (
            messageType == MessageType.VOICE_FRAME && networkSize > 6
        ) minOf(decrementedTtl, 5u.toUByte()) else decrementedTtl
        val relayPacket = packet.copy(ttl = voiceTtl)
        Log.d(TAG, "Decremented TTL from ${packet.ttl} to ${relayPacket.ttl}")
        if (messageType == MessageType.VOICE_FRAME) {
            delay(Random.nextLong(8L, 26L))
        }

        val effectivePreviousHop = routed.previousHopPeerID
            ?: routed.relayAddress?.let { delegate?.resolvePeerIDForRelayAddress(it) }

        // Source-based routing: if route is set and includes us, try targeted next-hop forwarding
        var sourceRouteFallback = false
        val route = relayPacket.route
        if (!route.isNullOrEmpty()) {
            // Check for duplicate hops to prevent routing loops
            if (route.map { it.toHexString() }.toSet().size < route.size) {
                Log.w(TAG, "Packet with duplicate hops dropped")
                return
            }
            val myIdBytes = hexStringToPeerBytes(myPeerID)
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
                    val nextHopRouted = RoutedPacket(
                        packet = relayPacket,
                        peerID = peerID,
                        relayAddress = routed.relayAddress,
                        previousHopPeerID = effectivePreviousHop
                    )
                    val success = try { delegate?.sendToPeer(nextHopIdHex, nextHopRouted) } catch (_: Exception) { false } ?: false
                    if (success) {
                        Log.i(TAG, "📦 Source-route relay: ${peerID.take(8)} -> ${nextHopIdHex.take(8)} (type ${packet.type}, TTL ${relayPacket.ttl})")
                        return
                    } else {
                        Log.w(TAG, "Source-route next hop ${nextHopIdHex.take(8)} not directly connected; falling back to broadcast")
                        sourceRouteFallback = true
                    }
                }
            }
        }

        if (!isRelayEnabled()) {
            Log.d(TAG, "Relay decision: disabled by settings for packet type ${packet.type}")
            return
        }

        val decision = evaluateBroadcastRelay(
            packet = relayPacket,
            messageType = messageType,
            originPeerID = peerID,
            previousHopPeerID = effectivePreviousHop,
            networkSize = networkSize,
            forceConservativeRelay = forceConservativeRelay,
            sourceRouteFallback = sourceRouteFallback
        )
        if (decision.shouldRelay) {
            Log.d(TAG, "Relay decision: RELAY type=${packet.type} reason=${decision.reason}")
            relayPacket(
                RoutedPacket(
                    packet = relayPacket,
                    peerID = peerID,
                    relayAddress = routed.relayAddress,
                    previousHopPeerID = effectivePreviousHop,
                    skipNeighborPruning = decision.skipNeighborPruning
                )
            )
        } else {
            Log.d(TAG, "Relay decision: SUPPRESS type=${packet.type} reason=${decision.reason}")
        }
    }

    /**
     * Check if a packet is specifically addressed to us.
     */
    internal fun isPacketAddressedToMe(packet: BitchatPacket): Boolean {
        val recipientID = packet.recipientID

        // No recipient means broadcast (not addressed to us specifically)
        if (recipientID == null) {
            return false
        }

        // Check if it's a broadcast recipient
        if (recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            return false
        }
        val broadcastRecipient = delegate?.getBroadcastRecipient()
        if (broadcastRecipient != null && recipientID.contentEquals(broadcastRecipient)) {
            return false
        }

        // Check if recipient matches our peer ID
        val recipientIDString = recipientID.toHexString()
        return recipientIDString == myPeerID
    }

    private fun isBroadcastPacket(packet: BitchatPacket): Boolean {
        val recipientID = packet.recipientID ?: return true
        if (recipientID.contentEquals(SpecialRecipients.BROADCAST)) return true
        val broadcastRecipient = delegate?.getBroadcastRecipient()
        return broadcastRecipient != null && recipientID.contentEquals(broadcastRecipient)
    }

    internal fun relayPolicyFor(
        packet: BitchatPacket,
        messageType: MessageType?,
        originPeerID: String,
        forceConservativeRelay: Boolean,
        sourceRouteFallback: Boolean,
        graph: MeshGraphService
    ): RelayPolicy {
        if (sourceRouteFallback) return RelayPolicy.CONSERVATIVE_FLOOD

        return when (messageType) {
            MessageType.VOICE_FRAME -> RelayPolicy.MPR_1_COVERED
            MessageType.MESSAGE -> {
                if (isBroadcastPacket(packet)) RelayPolicy.MPR_1_COVERED
                else RelayPolicy.CONSERVATIVE_FLOOD
            }
            MessageType.FRAGMENT -> {
                if (isBroadcastPacket(packet)) RelayPolicy.MPR_2_COVERED
                else RelayPolicy.CONSERVATIVE_FLOOD
            }
            MessageType.FILE_TRANSFER -> {
                if (isBroadcastPacket(packet)) RelayPolicy.MPR_2_COVERED
                else RelayPolicy.CONSERVATIVE_FLOOD
            }
            MessageType.ANNOUNCE -> {
                if (forceConservativeRelay || !graph.isFreshAnnouncement(originPeerID, edgeMaxAgeMs)) {
                    RelayPolicy.CONSERVATIVE_FLOOD
                } else {
                    RelayPolicy.MPR_1_COVERED
                }
            }
            MessageType.LEAVE,
            MessageType.NOISE_HANDSHAKE,
            MessageType.NOISE_ENCRYPTED,
            MessageType.REQUEST_SYNC,
            null -> RelayPolicy.CONSERVATIVE_FLOOD
        }
    }

    private fun evaluateBroadcastRelay(
        packet: BitchatPacket,
        messageType: MessageType?,
        originPeerID: String,
        previousHopPeerID: String?,
        networkSize: Int,
        forceConservativeRelay: Boolean,
        sourceRouteFallback: Boolean
    ): BroadcastRelayDecision {
        val graph = meshGraphProvider()
        val policy = relayPolicyFor(
            packet = packet,
            messageType = messageType,
            originPeerID = originPeerID,
            forceConservativeRelay = forceConservativeRelay,
            sourceRouteFallback = sourceRouteFallback,
            graph = graph
        )

        if (policy == RelayPolicy.CONSERVATIVE_FLOOD) {
            return BroadcastRelayDecision(
                shouldRelay = true,
                skipNeighborPruning = true,
                reason = if (sourceRouteFallback) "source-route-fallback" else "conservative-policy"
            )
        }

        // Small networks always relay to ensure connectivity
        if (networkSize <= SMALL_NETWORK_RELAY_THRESHOLD) {
            return BroadcastRelayDecision(
                shouldRelay = true,
                skipNeighborPruning = false,
                reason = "small-network($networkSize)"
            )
        }

        // Unknown previous hop cannot safely compute N1(P)/N2(P) — fall back to relaying
        if (previousHopPeerID.isNullOrBlank()) {
            return BroadcastRelayDecision(
                shouldRelay = true,
                skipNeighborPruning = false,
                reason = "unknown-previous-hop"
            )
        }

        val coverageK = if (policy == RelayPolicy.MPR_2_COVERED) 2 else 1
        val mprDecision = MprSelector.evaluateRelay(
            myPeerID = myPeerID,
            previousHopPeerID = previousHopPeerID,
            originPeerID = originPeerID,
            graphService = graph,
            directConnectedNeighbors = delegate?.getDirectConnectedPeers().orEmpty(),
            hasUnidentifiedDirectLink = delegate?.hasUnidentifiedDirectLink() ?: false,
            coverageK = coverageK,
            maxAgeMs = edgeMaxAgeMs
        )
        return BroadcastRelayDecision(
            shouldRelay = mprDecision.shouldRelay,
            skipNeighborPruning = false,
            reason = "mpr(k=$coverageK):${mprDecision.reason}"
        )
    }

    /**
     * Actually broadcast the packet for relay.
     */
    private fun relayPacket(routed: RoutedPacket) {
        Log.d(TAG, "🔄 Relaying packet type ${routed.packet.type} with TTL ${routed.packet.ttl}")
        delegate?.broadcastPacket(routed)
    }

    /**
     * Get debug information.
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
     * Shutdown the relay manager.
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down PacketRelayManager")
        relayScope.cancel()
    }
}

/**
 * Delegate interface for packet relay manager callbacks.
 */
interface PacketRelayManagerDelegate {
    // Network information
    fun getNetworkSize(): Int
    fun getBroadcastRecipient(): ByteArray

    /** Currently connected direct 1-hop neighbor peer IDs (for MPR fail-safe protection). */
    fun getDirectConnectedPeers(): Set<String> = emptySet()

    /** True if any active direct link has not yet mapped to a peerID. */
    fun hasUnidentifiedDirectLink(): Boolean = false

    /** Optional fallback resolver from transport relayAddress to immediate neighbor peerID. */
    fun resolvePeerIDForRelayAddress(relayAddress: String): String? = null

    // Packet operations
    fun broadcastPacket(routed: RoutedPacket)
    fun sendToPeer(peerID: String, routed: RoutedPacket): Boolean
}

private fun hexStringToPeerBytes(hex: String): ByteArray {
    val result = ByteArray(8)
    var idx = 0
    var out = 0
    while (idx + 1 < hex.length && out < 8) {
        val b = hex.substring(idx, idx + 2).toIntOrNull(16)?.toByte() ?: 0
        result[out++] = b
        idx += 2
    }
    return result
}
