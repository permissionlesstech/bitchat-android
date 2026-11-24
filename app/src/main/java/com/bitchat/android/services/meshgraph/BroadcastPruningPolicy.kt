package com.bitchat.android.services.meshgraph

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString

/**
 * Shared target-filtering policy for broadcast relaying across BLE and Wi-Fi Aware.
 *
 * Guarantees two invariants:
 * 1. Echo/loopback suppression always skips the incoming transport link (`relayAddress`),
 *    packet origin (`senderID`), and immediate previous hop (`previousHopPeerID`).
 * 2. When relaying a non-conservative broadcast packet, skips forwarding to any target peer that
 *    is a fresh confirmed neighbor of either `senderID` (origin) or `previousHopPeerID`.
 */
object BroadcastPruningPolicy {

    /**
     * Returns true if transmitting [routed] to the candidate link/peer should be skipped.
     */
    fun shouldSkipTarget(
        routed: RoutedPacket,
        myPeerID: String,
        targetPeerID: String?,
        targetAddress: String? = null,
        resolvedPreviousHopPeerID: String? = routed.previousHopPeerID,
        graphService: MeshGraphService = MeshGraphService.getInstance(),
        edgeMaxAgeMs: Long = MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS,
        targetLinkID: String? = null
    ): Boolean {
        // 1. Never echo back onto the exact link/address the packet arrived on.
        // When both ingress and target link generations are known, require matching linkID so a
        // late packet from a replaced connection cannot suppress a replacement peer at the same address.
        val sameIncomingAddress = targetAddress != null && routed.relayAddress != null && targetAddress == routed.relayAddress
        val sameLinkGeneration = routed.ingressLinkID == null || targetLinkID == null || targetLinkID == routed.ingressLinkID
        if (sameIncomingAddress && sameLinkGeneration) {
            return true
        }

        val originPeerID = routed.packet.senderID.toHexString()

        // 2. Never send back to the packet's originator.
        if (targetPeerID != null && targetPeerID == originPeerID) {
            return true
        }

        // 3. Never echo back to the immediate previous hop (even across transports or alternate links).
        if (targetPeerID != null &&
            resolvedPreviousHopPeerID != null &&
            targetPeerID == resolvedPreviousHopPeerID
        ) {
            return true
        }

        // 4. Locally originated packets always fan out to all direct neighbors.
        if (originPeerID == myPeerID) {
            return false
        }

        // 5. Conservative / topology-healing packets skip neighbor pruning so broken links heal.
        if (routed.skipNeighborPruning || isConservativePacketType(routed.packet.type)) {
            return false
        }

        // 6. Unidentified target links are never pruned (fail-safe for newly connected/legacy peers).
        if (targetPeerID == null) {
            return false
        }

        // 7. Unknown or replaced previous hop disables neighbor pruning (including origin neighbors)
        // so late packets after an ingress link replacement cannot prune the only remaining path.
        if (resolvedPreviousHopPeerID.isNullOrBlank()) {
            return false
        }

        // 8. Prune targets that are fresh confirmed neighbors of origin or previous hop.
        if (graphService.isFreshConfirmedNeighbor(originPeerID, targetPeerID, edgeMaxAgeMs)) {
            return true
        }
        if (resolvedPreviousHopPeerID != originPeerID &&
            graphService.isFreshConfirmedNeighbor(resolvedPreviousHopPeerID, targetPeerID, edgeMaxAgeMs)
        ) {
            return true
        }

        return false
    }

    private fun isConservativePacketType(packetType: UByte): Boolean {
        return when (MessageType.fromValue(packetType)) {
            MessageType.LEAVE,
            MessageType.NOISE_HANDSHAKE,
            MessageType.NOISE_ENCRYPTED -> true
            else -> false
        }
    }
}
