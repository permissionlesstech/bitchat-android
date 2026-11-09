package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLinkGroupingTest {

    private fun link(
        id: String,
        address: String,
        client: Boolean,
        peerID: String? = null,
        at: Long = 0L,
        sent: Long = 0L,
        received: Long = 0L
    ) = BleLinkDiagnostics.Link(
        linkID = id,
        address = address,
        isClient = client,
        connectedAt = at,
        peerID = peerID,
        sendOk = sent,
        received = received
    )

    @Test
    fun `three entries for one peer collapse into two radio connections`() {
        val peers = BleLinkGrouping.group(
            listOf(
                link("out", "PEER-ADV", client = true, peerID = "peer-1", at = 1, sent = 5, received = 2),
                link("mirror", "PEER-ADV", client = false, at = 2, sent = 1, received = 3),
                link("in", "PEER-INITIATOR", client = false, peerID = "peer-1", at = 3)
            )
        )

        assertEquals(1, peers.size)
        val peer = peers.single()
        assertEquals("peer-1", peer.peerID)
        assertEquals(2, peer.connections.size)
        val outgoing = peer.connections.first()
        assertTrue(outgoing.outgoing)
        assertEquals("mirror", outgoing.mirror?.linkID)
        // Counters from the mirror belong to the same radio link.
        assertEquals(6L, outgoing.sendOk)
        assertEquals(5L, outgoing.received)
        assertNull(peer.connections[1].mirror)
        assertEquals(setOf("PEER-ADV", "PEER-INITIATOR"), peer.addresses)
    }

    @Test
    fun `peers stay separate and an unidentified incoming link forms its own group`() {
        val peers = BleLinkGrouping.group(
            listOf(
                link("a-out", "A", client = true, peerID = "peer-a", at = 1),
                link("a-mirror", "A", client = false, at = 2),
                link("b-out", "B", client = true, peerID = "peer-b", at = 3),
                link("unknown-in", "C", client = false, at = 4)
            )
        )

        assertEquals(listOf("peer-a", "peer-b", null), peers.map { it.peerID })
        assertEquals(listOf(1, 1, 1), peers.map { it.connections.size })
    }

    @Test
    fun `server link without a matching outgoing connection is an incoming connection`() {
        val peers = BleLinkGrouping.group(listOf(link("in", "X", client = false, peerID = "peer-x")))

        val connection = peers.single().connections.single()
        assertEquals(false, connection.outgoing)
        assertNull(connection.mirror)
    }
}
