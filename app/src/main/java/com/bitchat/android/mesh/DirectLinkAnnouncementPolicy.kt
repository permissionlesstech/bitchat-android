package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket

/**
 * Describes transport reachability learned from an already-validated ANNOUNCE.
 *
 * This is deliberately only a routing observation. Noise authenticates the peer independently and
 * must not be restarted merely to associate the current transport link with that peer.
 */
internal object DirectLinkAnnouncementPolicy {
    data class Observation(
        val peerID: String,
        val relayAddress: String,
        val ingressLinkID: String
    )

    fun observationFor(routed: RoutedPacket, maxTtl: UByte): Observation? {
        if (routed.packet.ttl != maxTtl) return null
        return Observation(
            peerID = routed.peerID ?: return null,
            relayAddress = routed.relayAddress ?: return null,
            ingressLinkID = routed.ingressLinkID ?: return null
        )
    }
}
