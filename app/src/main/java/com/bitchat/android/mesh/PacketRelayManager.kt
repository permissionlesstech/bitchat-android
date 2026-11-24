package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.services.meshgraph.MprSelector
import com.bitchat.android.sync.PacketIdUtil
import com.bitchat.android.util.AppConstants
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
 * - Tracks MPR-suppressed vs. relayed packet IDs separately from local-delivery deduplication so a
 *   later authenticated copy arriving from an electing previous hop still relays at most once.
 * - Keeps topology-healing control traffic (`ANNOUNCE` for new peers / neighbor changes, `LEAVE`)
 *   and unicast/broken-route fallbacks (`NOISE_HANDSHAKE`, `NOISE_ENCRYPTED`) on conservative
 *   flooding so partitions heal promptly.
 */
class PacketRelayManager(
    private val myPeerID: String,
    private val meshGraphProvider: () -> MeshGraphService = MeshGraphService::getInstance,
    private val edgeMaxAgeMs: Long = MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS,
    private val clockMs: () -> Long = { System.currentTimeMillis() }
) {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "PacketRelayManager"
        internal const val SMALL_NETWORK_RELAY_THRESHOLD = 3
        private const val MAX_TRACKED_RELAY_PACKETS = AppConstants.Security.MAX_PROCESSED_MESSAGES
        private const val RELAY_DEDUP_TTL_MS = AppConstants.Security.MESSAGE_TIMEOUT_MS
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

    private val relayDedupLock = Any()
    private val suppressedPacketIds = LinkedHashMap<String, Long>()
    private val relayedPacketIds = LinkedHashMap<String, Long>()

    private fun isRelayEnabled(): Boolean = try {
        com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().packetRelayEnabled.value
    } catch (_: Exception) { true }

    // Delegate for callbacks
    var delegate: PacketRelayManagerDelegate? = null

    // Coroutines
    private val relayScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Returns true when [packet] from [peerID] was previously suppressed solely by MPR selection
     * (and has not yet been relayed or expired), so a duplicate copy arriving via an electing
     * previous hop may be reconsidered for relay without re-delivering locally.
     */
    fun isPendingSuppressedRelay(packet: BitchatPacket, peerID: String): Boolean {
        val key = relayDedupKey(packet, peerID)
        val now = clockMs()
        synchronized(relayDedupLock) {
            pruneExpiredLocked(now)
            return suppressedPacketIds.containsKey(key) && !relayedPacketIds.containsKey(key)
        }
    }

    /**
     * Main entry point for relay decisions.
     * Only packets that aren't specifically addressed to us should be passed here.
     *
     * @param forceConservativeRelay Set true for topology-changing or first-time ANNOUNCE packets
     *   so they are never MPR-suppressed or neighbor-pruned.
     * @param isDuplicateIngress Set true when reconsidering a packet already accepted for local
     *   delivery whose earlier ingress was MPR-suppressed.
     */
    suspend fun handlePacketRelay(
        routed: RoutedPacket,
        forceConservativeRelay: Boolean = false,
        isDuplicateIngress: Boolean = false
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

        val dedupKey = relayDedupKey(packet, peerID)
        val canProceed = synchronized(relayDedupLock) {
            val now = clockMs()
            pruneExpiredLocked(now)
            when {
                relayedPacketIds.containsKey(dedupKey) -> false
                isDuplicateIngress -> suppressedPacketIds.containsKey(dedupKey)
                else -> true
            }
        }
        if (!canProceed) {
            Log.d(TAG, "Relay dedup skipped packet type ${packet.type} (duplicate=$isDuplicateIngress)")
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
            ?: routed.relayAddress?.let { delegate?.resolvePeerIDForLink(it, routed.ingressLinkID) }

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
                        ingressLinkID = routed.ingressLinkID,
                        previousHopPeerID = effectivePreviousHop
                    )
                    val success = try { delegate?.sendToPeer(nextHopIdHex, nextHopRouted) } catch (_: Exception) { false } ?: false
                    if (success) {
                        markRelayed(dedupKey)
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
            if (!tryClaimRelay(dedupKey, isDuplicateIngress)) {
                Log.d(TAG, "Relay decision already claimed concurrently for packet type ${packet.type}")
                return
            }
            Log.d(TAG, "Relay decision: RELAY type=${packet.type} reason=${decision.reason}")
            relayPacket(
                RoutedPacket(
                    packet = relayPacket,
                    peerID = peerID,
                    relayAddress = routed.relayAddress,
                    ingressLinkID = routed.ingressLinkID,
                    previousHopPeerID = effectivePreviousHop,
                    skipNeighborPruning = decision.skipNeighborPruning
                )
            )
        } else {
            markSuppressed(dedupKey)
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

        // Small networks always relay to ensure connectivity; still guard pruning on a fresh graph
        if (networkSize <= SMALL_NETWORK_RELAY_THRESHOLD) {
            val missingOrStalePrevHop = previousHopPeerID.isNullOrBlank() ||
                !graph.isFreshAnnouncement(previousHopPeerID, edgeMaxAgeMs)
            return BroadcastRelayDecision(
                shouldRelay = true,
                skipNeighborPruning = missingOrStalePrevHop,
                reason = "small-network($networkSize)"
            )
        }

        // Unknown previous hop cannot safely compute N1(P)/N2(P) or prune neighbors
        if (previousHopPeerID.isNullOrBlank()) {
            return BroadcastRelayDecision(
                shouldRelay = true,
                skipNeighborPruning = true,
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
            skipNeighborPruning = mprDecision.reason == MprSelector.RelayDecisionReason.STALE_OR_MISSING_GRAPH,
            reason = "mpr(k=$coverageK):${mprDecision.reason}"
        )
    }

    private fun relayDedupKey(packet: BitchatPacket, peerID: String): String {
        return "$peerID-${PacketIdUtil.computeIdHex(packet)}"
    }

    private fun tryClaimRelay(key: String, isDuplicateIngress: Boolean): Boolean {
        val now = clockMs()
        synchronized(relayDedupLock) {
            pruneExpiredLocked(now)
            if (relayedPacketIds.containsKey(key)) return false
            if (isDuplicateIngress && !suppressedPacketIds.containsKey(key)) return false
            suppressedPacketIds.remove(key)
            relayedPacketIds[key] = now
            enforceCapacityLocked(relayedPacketIds)
            return true
        }
    }

    private fun markRelayed(key: String) {
        val now = clockMs()
        synchronized(relayDedupLock) {
            pruneExpiredLocked(now)
            suppressedPacketIds.remove(key)
            relayedPacketIds[key] = now
            enforceCapacityLocked(relayedPacketIds)
        }
    }

    private fun markSuppressed(key: String) {
        val now = clockMs()
        synchronized(relayDedupLock) {
            pruneExpiredLocked(now)
            if (relayedPacketIds.containsKey(key)) return
            suppressedPacketIds[key] = now
            enforceCapacityLocked(suppressedPacketIds)
        }
    }

    private fun pruneExpiredLocked(nowMs: Long) {
        val cutoff = nowMs - RELAY_DEDUP_TTL_MS
        pruneMapLocked(suppressedPacketIds, cutoff)
        pruneMapLocked(relayedPacketIds, cutoff)
    }

    private fun pruneMapLocked(map: LinkedHashMap<String, Long>, cutoffMs: Long) {
        val iter = map.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            if (entry.value < cutoffMs) {
                iter.remove()
            } else {
                break
            }
        }
    }

    private fun enforceCapacityLocked(map: LinkedHashMap<String, Long>) {
        val iter = map.entries.iterator()
        while (map.size > MAX_TRACKED_RELAY_PACKETS && iter.hasNext()) {
            iter.next()
            iter.remove()
        }
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
        synchronized(relayDedupLock) {
            suppressedPacketIds.clear()
            relayedPacketIds.clear()
        }
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

    /** Optional link-generation-checked fallback resolver from transport link to immediate neighbor peerID. */
    fun resolvePeerIDForLink(relayAddress: String, ingressLinkID: String?): String? = null

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
