
package com.bitchat.android.mesh

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
class PacketRelayManagerTest {

    private lateinit var packetRelayManager: PacketRelayManager
    private val delegate: PacketRelayManagerDelegate = mock()
    private var nowMs: Long = 50_000L

    private val myPeerID = "1111111111111111"
    private val otherPeerID = "2222222222222222"
    private val nextHopPeerID = "3333333333333333"
    private val finalRecipientID = "4444444444444444"

    @Before
    fun setUp() {
        nowMs = 50_000L
        MeshGraphService.resetForTesting { nowMs }
        packetRelayManager = PacketRelayManager(myPeerID)
        packetRelayManager.delegate = delegate
        whenever(delegate.getNetworkSize()).thenReturn(10)
        whenever(delegate.getBroadcastRecipient()).thenReturn(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0))
        whenever(delegate.getDirectConnectedPeers()).thenReturn(emptySet())
        whenever(delegate.hasUnidentifiedDirectLink()).thenReturn(false)
    }

    @After
    fun tearDown() {
        packetRelayManager.shutdown()
        MeshGraphService.resetForTesting()
    }

    private fun createPacket(route: List<ByteArray>?, recipient: String? = null): BitchatPacket {
        return BitchatPacket(
            type = MessageType.MESSAGE.value,
            senderID = hexStringToPeerBytes(otherPeerID),
            recipientID = recipient?.let { hexStringToPeerBytes(it) },
            timestamp = System.currentTimeMillis().toULong(),
            payload = "hello".toByteArray(),
            ttl = 5u,
            route = route
        )
    }

    @Test
    fun `low TTL relay obtains network size once`() = runTest {
        val packet = createPacket(null).copy(ttl = 3u)

        packetRelayManager.handlePacketRelay(RoutedPacket(packet, otherPeerID))

        verify(delegate).getNetworkSize()
        verify(delegate).broadcastPacket(any())
    }

    @Test
    fun `packet with duplicate hops is dropped`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(nextHopPeerID),
            hexStringToPeerBytes(nextHopPeerID)
        )
        val packet = createPacket(route)
        val routedPacket = RoutedPacket(packet, otherPeerID)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate, never()).sendToPeer(any(), any())
        verify(delegate, never()).broadcastPacket(any())
    }

    @Test
    fun `valid source-routed packet is relayed to next hop`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(myPeerID),
            hexStringToPeerBytes(nextHopPeerID)
        )
        val packet = createPacket(route, finalRecipientID)
        val routedPacket = RoutedPacket(packet, otherPeerID)
        whenever(delegate.sendToPeer(any(), any())).thenReturn(true)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate).sendToPeer(org.mockito.kotlin.eq(nextHopPeerID), any())
        verify(delegate, never()).broadcastPacket(any())
    }

    @Test
    fun `last hop does not relay further`() = runTest {
        val route = listOf(
            hexStringToPeerBytes(myPeerID)
        )
        val packet = createPacket(route, finalRecipientID)
        val routedPacket = RoutedPacket(packet, otherPeerID)
        whenever(delegate.sendToPeer(any(), any())).thenReturn(true)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate).sendToPeer(org.mockito.kotlin.eq(finalRecipientID), any())
        verify(delegate, never()).broadcastPacket(any())
    }
    
    @Test
    fun `packet with empty route is broadcast`() = runTest {
        val packet = createPacket(null)
        val routedPacket = RoutedPacket(packet, otherPeerID)

        packetRelayManager.handlePacketRelay(routedPacket)

        verify(delegate, never()).sendToPeer(any(), any())
        verify(delegate).broadcastPacket(any())
    }

    @Test
    fun `1-covered MPR suppresses non-MPR for broadcast MESSAGE and VOICE_FRAME`() = runTest {
        // Dual-bridge topology: prevHop -- {hubLow, me} -- farPeer
        // hubLow < me lexicographically, so 1-covered MPR elects only hubLow.
        val prevHop = "0101010101010101"
        val hubLow = "0202020202020202"
        val me = "0909090909090909"
        val farPeer = "0a0a0a0a0a0a0a0a"
        seedConfirmedEdges(
            prevHop to hubLow,
            prevHop to me,
            hubLow to farPeer,
            me to farPeer
        )

        val nonMprRelay = PacketRelayManager(me).apply { delegate = this@PacketRelayManagerTest.delegate }
        whenever(delegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, farPeer))

        val msg = broadcastPacket(MessageType.MESSAGE, prevHop)
        nonMprRelay.handlePacketRelay(RoutedPacket(msg, prevHop, "link-p", previousHopPeerID = prevHop))

        val voice = broadcastPacket(MessageType.VOICE_FRAME, prevHop)
        nonMprRelay.handlePacketRelay(RoutedPacket(voice, prevHop, "link-p", previousHopPeerID = prevHop))

        verify(delegate, never()).broadcastPacket(any())
        nonMprRelay.shutdown()
    }

    @Test
    fun `2-covered MPR elects both bridges for FRAGMENT and FILE_TRANSFER and suppresses third`() = runTest {
        // Dual-bridge: prevHop -- {hubLow, me} -- farPeer -> both are elected under k=2
        val prevHop = "0101010101010101"
        val hubLow = "0202020202020202"
        val me = "0909090909090909"
        val farPeer = "0a0a0a0a0a0a0a0a"
        seedConfirmedEdges(
            prevHop to hubLow,
            prevHop to me,
            hubLow to farPeer,
            me to farPeer
        )

        val secondBridgeRelay = PacketRelayManager(me).apply { delegate = this@PacketRelayManagerTest.delegate }
        whenever(delegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, farPeer))

        val signedFragment = broadcastPacket(MessageType.FRAGMENT, prevHop).copy(signature = ByteArray(64) { 0x11 })
        secondBridgeRelay.handlePacketRelay(RoutedPacket(signedFragment, prevHop, "link-p", previousHopPeerID = prevHop))

        val file = broadcastPacket(MessageType.FILE_TRANSFER, prevHop)
        secondBridgeRelay.handlePacketRelay(RoutedPacket(file, prevHop, "link-p", previousHopPeerID = prevHop))

        val captor = argumentCaptor<RoutedPacket>()
        verify(delegate, org.mockito.kotlin.times(2)).broadcastPacket(captor.capture())
        assertEquals(prevHop, captor.firstValue.previousHopPeerID)
        assertFalse(captor.firstValue.skipNeighborPruning)
        assertEquals(prevHop, captor.secondValue.previousHopPeerID)
        assertFalse(captor.secondValue.skipNeighborPruning)
        secondBridgeRelay.shutdown()

        // Triple-bridge: adding hubMid ("05...") between hubLow and me means me ("09...") is 3rd ->
        // signed FRAGMENT & FILE_TRANSFER are suppressed under k=2, while legacy unsigned FRAGMENT
        // fails closed to pruned relaying so arrival order cannot strand downstream peers.
        val hubMid = "0505050505050505"
        MeshGraphService.resetForTesting { nowMs }
        seedConfirmedEdges(
            prevHop to hubLow,
            prevHop to hubMid,
            prevHop to me,
            hubLow to farPeer,
            hubMid to farPeer,
            me to farPeer
        )
        val thirdDelegate: PacketRelayManagerDelegate = mock()
        whenever(thirdDelegate.getNetworkSize()).thenReturn(10)
        whenever(thirdDelegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(thirdDelegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, farPeer))
        val thirdBridgeRelay = PacketRelayManager(me).apply { delegate = thirdDelegate }

        thirdBridgeRelay.handlePacketRelay(RoutedPacket(signedFragment, prevHop, "link-p", previousHopPeerID = prevHop))
        thirdBridgeRelay.handlePacketRelay(RoutedPacket(file, prevHop, "link-p", previousHopPeerID = prevHop))
        verify(thirdDelegate, never()).broadcastPacket(any())

        val unsignedFragment = broadcastPacket(MessageType.FRAGMENT, prevHop).copy(timestamp = 2_000UL, signature = null)
        thirdBridgeRelay.handlePacketRelay(RoutedPacket(unsignedFragment, prevHop, "link-p", previousHopPeerID = prevHop))
        val unsignedCaptor = argumentCaptor<RoutedPacket>()
        verify(thirdDelegate, org.mockito.kotlin.times(1)).broadcastPacket(unsignedCaptor.capture())
        assertFalse("Legacy unsigned FRAGMENT still applies neighbor pruning", unsignedCaptor.firstValue.skipNeighborPruning)
        thirdBridgeRelay.shutdown()
    }

    @Test
    fun `conservative traffic classes and topology-changing ANNOUNCE always flood even on non-MPR`() = runTest {
        // Full clique: me is never an MPR for prevHop
        val prevHop = "0101010101010101"
        val peerB = "0202020202020202"
        val peerC = "0303030303030303"
        val me = "0909090909090909"
        seedConfirmedEdges(
            prevHop to peerB, prevHop to peerC, prevHop to me,
            peerB to peerC, peerB to me, peerC to me
        )

        val relay = PacketRelayManager(me).apply { delegate = this@PacketRelayManagerTest.delegate }
        whenever(delegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, peerB, peerC))

        // 1. Topology-changing ANNOUNCE floods conservatively
        val announce = broadcastPacket(MessageType.ANNOUNCE, prevHop)
        relay.handlePacketRelay(
            RoutedPacket(announce, prevHop, "link-p", previousHopPeerID = prevHop),
            forceConservativeRelay = true
        )

        // 2. LEAVE floods conservatively
        val leave = broadcastPacket(MessageType.LEAVE, prevHop)
        relay.handlePacketRelay(RoutedPacket(leave, prevHop, "link-p", previousHopPeerID = prevHop))

        // 3. NOISE_HANDSHAKE floods conservatively
        val handshake = BitchatPacket(
            type = MessageType.NOISE_HANDSHAKE.value,
            senderID = hexStringToPeerBytes(prevHop),
            recipientID = hexStringToPeerBytes(finalRecipientID),
            timestamp = 1_000UL,
            payload = byteArrayOf(1, 2, 3),
            ttl = 5u
        )
        relay.handlePacketRelay(RoutedPacket(handshake, prevHop, "link-p", previousHopPeerID = prevHop))

        // 4. Broken source route NOISE_ENCRYPTED falls back to conservative flood
        whenever(delegate.sendToPeer(any(), any())).thenReturn(false)
        val brokenRouteNoise = BitchatPacket(
            version = 2u,
            type = MessageType.NOISE_ENCRYPTED.value,
            senderID = hexStringToPeerBytes(prevHop),
            recipientID = hexStringToPeerBytes(finalRecipientID),
            timestamp = 1_000UL,
            payload = byteArrayOf(4, 5, 6),
            ttl = 5u,
            route = listOf(hexStringToPeerBytes(me), hexStringToPeerBytes(nextHopPeerID))
        )
        relay.handlePacketRelay(RoutedPacket(brokenRouteNoise, prevHop, "link-p", previousHopPeerID = prevHop))

        val captor = argumentCaptor<RoutedPacket>()
        verify(delegate, org.mockito.kotlin.times(4)).broadcastPacket(captor.capture())
        assertTrue(captor.allValues.all { it.skipNeighborPruning })
        assertTrue(captor.allValues.all { it.previousHopPeerID == prevHop })

        // 5. Unchanged periodic ANNOUNCE for already-fresh peer is MPR-suppressed in the clique
        val quietDelegate: PacketRelayManagerDelegate = mock()
        whenever(quietDelegate.getNetworkSize()).thenReturn(10)
        whenever(quietDelegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(quietDelegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, peerB, peerC))
        relay.delegate = quietDelegate
        relay.handlePacketRelay(
            RoutedPacket(announce, prevHop, "link-p", previousHopPeerID = prevHop),
            forceConservativeRelay = false
        )
        verify(quietDelegate, never()).broadcastPacket(any())
        relay.shutdown()
    }

    @Test
    fun `1-hop REQUEST_SYNC is never relayed`() = runTest {
        val syncTtl1 = broadcastPacket(MessageType.REQUEST_SYNC, otherPeerID).copy(ttl = 1u)
        val syncTtl0 = broadcastPacket(MessageType.REQUEST_SYNC, otherPeerID).copy(ttl = 0u)

        packetRelayManager.handlePacketRelay(RoutedPacket(syncTtl1, otherPeerID, previousHopPeerID = otherPeerID))
        packetRelayManager.handlePacketRelay(RoutedPacket(syncTtl0, otherPeerID, previousHopPeerID = otherPeerID))

        verify(delegate, never()).broadcastPacket(any())
    }

    @Test
    fun `small network and legacy direct neighbor fall back safely to relaying`() = runTest {
        // Clique where me would be suppressed in a large network
        val prevHop = "0101010101010101"
        val peerB = "0202020202020202"
        val me = "0909090909090909"
        seedConfirmedEdges(prevHop to peerB, prevHop to me, peerB to me)

        val relay = PacketRelayManager(me).apply { delegate = this@PacketRelayManagerTest.delegate }
        val msg = broadcastPacket(MessageType.MESSAGE, prevHop)

        // Small network (<= 3 peers): always relay
        whenever(delegate.getNetworkSize()).thenReturn(3)
        whenever(delegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, peerB))
        relay.handlePacketRelay(RoutedPacket(msg, prevHop, "link-p", previousHopPeerID = prevHop))

        // Large network (> 3) with a legacy direct neighbor not in N1(prevHop): fail-safe relay
        whenever(delegate.getNetworkSize()).thenReturn(12)
        whenever(delegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, peerB, "deadbeef00000001"))
        relay.handlePacketRelay(RoutedPacket(msg.copy(timestamp = 2_000UL), prevHop, "link-p", previousHopPeerID = prevHop))

        verify(delegate, org.mockito.kotlin.times(2)).broadcastPacket(any())
        relay.shutdown()
    }

    @Test
    fun `unknown previous hop and stale graph fallbacks disable neighbor pruning`() = runTest {
        val prevHop = "0101010101010101"
        val peerB = "0202020202020202"
        val me = "0909090909090909"
        seedConfirmedEdges(prevHop to peerB, prevHop to me, peerB to me)

        val relay = PacketRelayManager(me).apply { delegate = this@PacketRelayManagerTest.delegate }
        whenever(delegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, peerB))

        // 1. Unknown previous hop in large network -> relay with skipNeighborPruning = true
        whenever(delegate.getNetworkSize()).thenReturn(10)
        relay.handlePacketRelay(
            RoutedPacket(broadcastPacket(MessageType.MESSAGE, prevHop).copy(timestamp = 1_001UL), prevHop, "link-p", previousHopPeerID = null)
        )

        // 2. Small network with fresh graph and known previous hop -> skipNeighborPruning = false
        whenever(delegate.getNetworkSize()).thenReturn(3)
        relay.handlePacketRelay(
            RoutedPacket(broadcastPacket(MessageType.MESSAGE, prevHop).copy(timestamp = 1_002UL), prevHop, "link-p", previousHopPeerID = prevHop)
        )

        // 3. Stale graph in both large and small networks -> skipNeighborPruning = true
        nowMs += MeshGraphService.DEFAULT_EDGE_MAX_AGE_MS + 10_000L
        whenever(delegate.getNetworkSize()).thenReturn(10)
        relay.handlePacketRelay(
            RoutedPacket(broadcastPacket(MessageType.MESSAGE, prevHop).copy(timestamp = 1_003UL), prevHop, "link-p", previousHopPeerID = prevHop)
        )
        whenever(delegate.getNetworkSize()).thenReturn(3)
        relay.handlePacketRelay(
            RoutedPacket(broadcastPacket(MessageType.MESSAGE, prevHop).copy(timestamp = 1_004UL), prevHop, "link-p", previousHopPeerID = prevHop)
        )

        val captor = argumentCaptor<RoutedPacket>()
        verify(delegate, org.mockito.kotlin.times(4)).broadcastPacket(captor.capture())
        assertTrue("Unknown previous hop must disable neighbor pruning", captor.allValues[0].skipNeighborPruning)
        assertFalse("Fresh small-network relay keeps neighbor pruning enabled", captor.allValues[1].skipNeighborPruning)
        assertTrue("Stale graph fallback in large network must disable neighbor pruning", captor.allValues[2].skipNeighborPruning)
        assertTrue("Stale graph fallback in small network must disable neighbor pruning", captor.allValues[3].skipNeighborPruning)
        relay.shutdown()
    }

    @Test
    fun `suppressed broadcast relays once when authenticated duplicate arrives from electing hop`() = runTest {
        // Non-electing prevHopA shares clique with me, prevHopB, farPeer;
        // electing prevHopB elects me ("01...") over prevHopA ("08...") to cover farPeer.
        val origin = "0000000000000001"
        val prevHopA = "0808080808080808"
        val prevHopB = "0202020202020202"
        val me = "0101010101010101"
        val farPeer = "0a0a0a0a0a0a0a0a"
        seedConfirmedEdges(
            prevHopA to me,
            prevHopA to prevHopB,
            prevHopA to farPeer,
            prevHopB to me,
            me to farPeer
        )

        val relay = PacketRelayManager(me).apply { delegate = this@PacketRelayManagerTest.delegate }
        whenever(delegate.getNetworkSize()).thenReturn(10)
        whenever(delegate.getDirectConnectedPeers()).thenReturn(setOf(prevHopA, prevHopB, farPeer))

        val msg = broadcastPacket(MessageType.MESSAGE, origin).copy(timestamp = 5_000UL)

        // First ingress from prevHopA is suppressed because farPeer is already in N1(prevHopA)
        relay.handlePacketRelay(RoutedPacket(msg, origin, "link-a", previousHopPeerID = prevHopA))
        assertTrue(relay.isPendingSuppressedRelay(msg, origin))
        verify(delegate, never()).broadcastPacket(any())

        // Duplicate ingress from prevHopB elects me as sole cover for farPeer -> relays once
        relay.handlePacketRelay(
            RoutedPacket(msg, origin, "link-b", previousHopPeerID = prevHopB),
            isDuplicateIngress = true
        )
        assertFalse(relay.isPendingSuppressedRelay(msg, origin))

        // Third duplicate ingress is ignored (never double-relayed)
        relay.handlePacketRelay(
            RoutedPacket(msg, origin, "link-b", previousHopPeerID = prevHopB),
            isDuplicateIngress = true
        )
        verify(delegate, org.mockito.kotlin.times(1)).broadcastPacket(any())
        relay.shutdown()
    }

    @Test
    fun `queued packet re-validates ingress link generation and falls back safely if link was replaced`() = runTest {
        // Full clique where prevHop would normally suppress me
        val prevHop = "0101010101010101"
        val peerB = "0202020202020202"
        val me = "0909090909090909"
        seedConfirmedEdges(prevHop to peerB, prevHop to me, peerB to me)

        val relay = PacketRelayManager(me).apply { delegate = this@PacketRelayManagerTest.delegate }
        whenever(delegate.getNetworkSize()).thenReturn(10)
        whenever(delegate.getDirectConnectedPeers()).thenReturn(setOf(prevHop, peerB))
        // Link was replaced between ingress admission and worker execution -> resolver returns null
        whenever(delegate.resolvePeerIDForLink("AA:BB:CC:DD:EE:01", "stale-gen")).thenReturn(null)

        val msg = broadcastPacket(MessageType.MESSAGE, prevHop).copy(timestamp = 7_000UL)
        relay.handlePacketRelay(
            RoutedPacket(
                packet = msg,
                peerID = prevHop,
                relayAddress = "AA:BB:CC:DD:EE:01",
                ingressLinkID = "stale-gen",
                previousHopPeerID = prevHop // Cached at admission time before replacement
            )
        )

        val captor = argumentCaptor<RoutedPacket>()
        verify(delegate, org.mockito.kotlin.times(1)).broadcastPacket(captor.capture())
        assertEquals(null, captor.firstValue.previousHopPeerID)
        assertTrue("Replaced ingress generation must fall back safely with skipNeighborPruning=true", captor.firstValue.skipNeighborPruning)
        relay.shutdown()
    }

    private fun seedConfirmedEdges(vararg edges: Pair<String, String>) {
        val graph = MeshGraphService.getInstance()
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
            senderID = hexStringToPeerBytes(senderHex),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = 1_000UL,
            payload = "payload".toByteArray(),
            ttl = 5u
        )

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
}
