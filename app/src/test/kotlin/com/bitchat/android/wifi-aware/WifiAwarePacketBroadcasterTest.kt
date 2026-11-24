package com.bitchat.android.wifiaware

import android.net.ConnectivityManager
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.meshgraph.MeshGraphService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

class WifiAwarePacketBroadcasterTest {

    private var nowMs: Long = 100_000L
    private lateinit var scope: CoroutineScope
    private lateinit var graph: MeshGraphService
    private lateinit var tracker: WifiAwareConnectionTracker
    private lateinit var broadcaster: WifiAwarePacketBroadcaster

    private val myPeerID = "1111111111111111"
    private val originPeerID = "2222222222222222"
    private val prevHopPeerID = "3333333333333333"
    private val originNeighborPeerID = "4444444444444444"
    private val prevHopNeighborPeerID = "5555555555555555"
    private val farPeerID = "6666666666666666"

    @Before
    fun setUp() {
        nowMs = 100_000L
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        graph = MeshGraphService { nowMs }
        tracker = WifiAwareConnectionTracker(scope, mock<ConnectivityManager>())
        broadcaster = WifiAwarePacketBroadcaster(
            myPeerID = myPeerID,
            connectionTracker = tracker,
            meshGraphProvider = { graph },
            edgeMaxAgeMs = 30_000L
        )
    }

    @After
    fun tearDown() {
        tracker.stop()
        scope.cancel()
    }

    @Test
    fun `relayed broadcast skips previous hop, origin, and their fresh confirmed neighbors`() {
        seedConfirmedEdges(
            originPeerID to originNeighborPeerID,
            prevHopPeerID to prevHopNeighborPeerID,
            myPeerID to prevHopPeerID,
            myPeerID to farPeerID
        )

        val originOut = connectPeer(originPeerID)
        val prevHopOut = connectPeer(prevHopPeerID)
        val originNeighborOut = connectPeer(originNeighborPeerID)
        val prevHopNeighborOut = connectPeer(prevHopNeighborPeerID)
        val farOut = connectPeer(farPeerID)

        val routed = RoutedPacket(
            packet = broadcastPacket(MessageType.MESSAGE, originPeerID),
            peerID = originPeerID,
            relayAddress = prevHopPeerID,
            previousHopPeerID = prevHopPeerID
        )

        assertTrue(broadcaster.broadcastSinglePacket(routed))

        assertEquals("Origin must not receive echo", 0, originOut.size())
        assertEquals("Previous hop must not receive bounce-back", 0, prevHopOut.size())
        assertEquals("Origin fresh neighbor must be pruned", 0, originNeighborOut.size())
        assertEquals("Previous-hop fresh neighbor must be pruned", 0, prevHopNeighborOut.size())
        assertTrue("Far peer must receive relayed packet", farOut.size() > 0)
    }

    @Test
    fun `rebound provisional relayAddress still prevents bounce-back to incoming socket`() {
        val provisionalId = "provisional-socket-1"
        val prevOut = ByteArrayOutputStream()
        val prevSocket = syncedSocket(prevOut)
        tracker.onClientConnected(provisionalId, prevSocket)
        assertTrue(tracker.rebindPeerIdIfCurrent(provisionalId, prevHopPeerID, prevSocket))

        val farOut = connectPeer(farPeerID)

        val routed = RoutedPacket(
            packet = broadcastPacket(MessageType.MESSAGE, originPeerID),
            peerID = originPeerID,
            relayAddress = provisionalId,
            previousHopPeerID = null
        )

        assertTrue(broadcaster.broadcastSinglePacket(routed))
        assertEquals("Rebound incoming socket must not receive bounce-back", 0, prevOut.size())
        assertTrue("Far peer must receive packet", farOut.size() > 0)
    }

    @Test
    fun `stale neighbor and conservative packets are not neighbor-pruned while still avoiding echo`() {
        seedConfirmedEdges(prevHopPeerID to prevHopNeighborPeerID)
        val prevOut = connectPeer(prevHopPeerID)
        val neighborOut = connectPeer(prevHopNeighborPeerID)

        // Conservative NOISE_ENCRYPTED fallback broadcast: skip prevHop, do NOT prune prevHopNeighbor
        val noiseFallback = RoutedPacket(
            packet = BitchatPacket(
                version = 1u,
                type = MessageType.NOISE_ENCRYPTED.value,
                senderID = hexToBytes(originPeerID),
                recipientID = hexToBytes(farPeerID),
                timestamp = 1_000UL,
                payload = byteArrayOf(1, 2, 3),
                ttl = 5u
            ),
            peerID = originPeerID,
            relayAddress = prevHopPeerID,
            previousHopPeerID = prevHopPeerID,
            skipNeighborPruning = true
        )
        assertTrue(broadcaster.broadcastSinglePacket(noiseFallback))
        assertEquals(0, prevOut.size())
        assertTrue(neighborOut.size() > 0)

        // Advance clock past freshness window -> MESSAGE is no longer pruned for stale neighbor
        neighborOut.reset()
        nowMs += 31_000L
        val msgAfterExpiry = RoutedPacket(
            packet = broadcastPacket(MessageType.MESSAGE, originPeerID),
            peerID = originPeerID,
            relayAddress = prevHopPeerID,
            previousHopPeerID = prevHopPeerID
        )
        assertTrue(broadcaster.broadcastSinglePacket(msgAfterExpiry))
        assertEquals(0, prevOut.size())
        assertTrue(neighborOut.size() > 0)
    }

    @Test
    fun `locally originated packet fans out to all connected Wi-Fi Aware peers`() {
        seedConfirmedEdges(myPeerID to prevHopPeerID, myPeerID to farPeerID)
        val peer1Out = connectPeer(prevHopPeerID)
        val peer2Out = connectPeer(farPeerID)

        val local = RoutedPacket(
            packet = broadcastPacket(MessageType.MESSAGE, myPeerID),
            peerID = myPeerID
        )
        assertTrue(broadcaster.broadcastSinglePacket(local))
        assertTrue(peer1Out.size() > 0)
        assertTrue(peer2Out.size() > 0)
    }

    @Test
    fun `unverified provisional socket is never neighbor-pruned and replaced ingress socket cannot suppress replacement peer`() {
        seedConfirmedEdges(prevHopPeerID to prevHopNeighborPeerID)

        // 1. Provisional (unverified) socket with discovery ID matching prevHopNeighborPeerID
        // must NOT be pruned until verified by a direct ANNOUNCE.
        val provisionalOut = ByteArrayOutputStream()
        val provisionalSocket = syncedSocket(provisionalOut)
        tracker.onClientConnected(prevHopNeighborPeerID, provisionalSocket)

        // 2. Replacement socket on an alias previously used by a stale ingress link generation
        val replacementPeer = "7777777777777777"
        val replacementOut = ByteArrayOutputStream()
        val replacementSocket = syncedSocket(replacementOut)
        tracker.onClientConnected("reused-alias", replacementSocket)
        assertTrue(tracker.rebindPeerIdIfCurrent("reused-alias", replacementPeer, replacementSocket))

        val genAwareBroadcaster = WifiAwarePacketBroadcaster(
            myPeerID = myPeerID,
            connectionTracker = tracker,
            meshGraphProvider = { graph },
            edgeMaxAgeMs = 30_000L,
            // Stale ingressLinkID resolves to null because the original socket was replaced
            ingressSocketResolver = { _, _ -> null }
        )

        val lateRouted = RoutedPacket(
            packet = broadcastPacket(MessageType.MESSAGE, originPeerID),
            peerID = originPeerID,
            relayAddress = "reused-alias",
            ingressLinkID = "stale-link-gen",
            previousHopPeerID = null
        )

        assertTrue(genAwareBroadcaster.broadcastSinglePacket(lateRouted))
        assertTrue("Unverified provisional socket must not be pruned", provisionalOut.size() > 0)
        assertTrue("Replacement peer on reused alias must not be suppressed by stale ingress generation", replacementOut.size() > 0)
    }

    private fun connectPeer(peerID: String): ByteArrayOutputStream {
        val out = ByteArrayOutputStream()
        val sock = syncedSocket(out)
        tracker.onClientConnected(peerID, sock)
        tracker.markSocketVerified(peerID, sock)
        return out
    }

    private fun syncedSocket(out: ByteArrayOutputStream): SyncedSocket {
        val raw = mock<Socket> {
            on { getInputStream() } doReturn ByteArrayInputStream(byteArrayOf())
            on { getOutputStream() } doReturn out
        }
        return SyncedSocket(raw)
    }

    private fun seedConfirmedEdges(vararg edges: Pair<String, String>) {
        val adj = mutableMapOf<String, MutableList<String>>()
        for ((a, b) in edges) {
            adj.getOrPut(a) { mutableListOf() }.add(b)
            adj.getOrPut(b) { mutableListOf() }.add(a)
        }
        adj.forEach { (peer, neighbors) ->
            graph.updateFromAnnouncement(peer, peer, neighbors, 1_000UL)
        }
    }

    private fun broadcastPacket(type: MessageType, senderHex: String): BitchatPacket =
        BitchatPacket(
            version = 1u,
            type = type.value,
            senderID = hexToBytes(senderHex),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = 1_000UL,
            payload = "hello-wifi".toByteArray(),
            ttl = 5u
        )

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
