package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DirectLinkAnnouncementPolicyTest {
    @Test
    fun `accepted max ttl announce is a direct routing observation`() {
        val routed = announce(ttl = MAX_TTL)

        assertEquals(
            DirectLinkAnnouncementPolicy.Observation(PEER_ID, RELAY_ADDRESS, LINK_ID),
            DirectLinkAnnouncementPolicy.observationFor(routed, MAX_TTL)
        )
    }

    @Test
    fun `relayed announce is not a direct routing observation`() {
        assertNull(
            DirectLinkAnnouncementPolicy.observationFor(
                announce(ttl = (MAX_TTL - 1u).toUByte()),
                MAX_TTL
            )
        )
    }

    @Test
    fun `repeated announce remains the same observation without transport authentication state`() {
        val routed = announce(ttl = MAX_TTL)

        val first = DirectLinkAnnouncementPolicy.observationFor(routed, MAX_TTL)
        val second = DirectLinkAnnouncementPolicy.observationFor(routed, MAX_TTL)

        assertEquals(first, second)
    }

    private fun announce(ttl: UByte) = RoutedPacket(
        packet = BitchatPacket(
            version = 1u,
            type = MessageType.ANNOUNCE.value,
            senderID = PEER_ID.hexToBytes(),
            timestamp = 1u,
            payload = byteArrayOf(1),
            ttl = ttl
        ),
        peerID = PEER_ID,
        relayAddress = RELAY_ADDRESS,
        ingressLinkID = LINK_ID
    )

    private fun String.hexToBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val PEER_ID = "0011223344556677"
        const val RELAY_ADDRESS = "transport-neighbor"
        const val LINK_ID = "current-link"
        val MAX_TTL: UByte = 7u
    }
}
