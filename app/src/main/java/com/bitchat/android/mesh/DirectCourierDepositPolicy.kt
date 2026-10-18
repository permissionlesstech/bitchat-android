package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket

/** Accepts courier custody only from the authenticated peer on the current direct link. */
internal object DirectCourierDepositPolicy {
    fun accepts(
        routed: RoutedPacket,
        maxTtl: UByte,
        currentLinkID: (String) -> String?,
        peerForAddress: (String) -> String?
    ): Boolean {
        val peerID = routed.peerID ?: return false
        val relayAddress = routed.relayAddress ?: return false
        val ingressLinkID = routed.ingressLinkID ?: return false
        return routed.packet.ttl == maxTtl &&
            currentLinkID(relayAddress) == ingressLinkID &&
            peerForAddress(relayAddress) == peerID
    }
}
