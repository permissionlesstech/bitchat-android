package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCourierDepositPolicyTest {
    private val packet = BitchatPacket(
        type = MessageType.COURIER_ENVELOPE.value,
        senderID = ByteArray(8),
        timestamp = 1u,
        payload = byteArrayOf(1),
        ttl = 7u
    )

    @Test
    fun `accepts current direct ingress from claimed sender`() {
        val routed = RoutedPacket(packet, "peer", "address", ingressLinkID = "link")
        assertTrue(DirectCourierDepositPolicy.accepts(routed, 7u, { "link" }, { "peer" }))
    }

    @Test
    fun `rejects relayed stale-link and rebound-sender deposits`() {
        val direct = RoutedPacket(packet, "peer", "address", ingressLinkID = "link")
        assertFalse(DirectCourierDepositPolicy.accepts(direct.copy(packet = packet.copy(ttl = 6u)), 7u, { "link" }, { "peer" }))
        assertFalse(DirectCourierDepositPolicy.accepts(direct, 7u, { "replacement" }, { "peer" }))
        assertFalse(DirectCourierDepositPolicy.accepts(direct, 7u, { "link" }, { "other" }))
    }
}
