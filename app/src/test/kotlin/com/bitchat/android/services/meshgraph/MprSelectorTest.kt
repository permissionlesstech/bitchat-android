package com.bitchat.android.services.meshgraph

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MprSelectorTest {

    private var nowMs: Long = 100_000L

    private fun newGraph(edges: List<Pair<String, String>>): MeshGraphService {
        val graph = MeshGraphService { nowMs }
        val adj = mutableMapOf<String, MutableList<String>>()
        for ((a, b) in edges) {
            adj.getOrPut(a) { mutableListOf() }.add(b)
            adj.getOrPut(b) { mutableListOf() }.add(a)
        }
        adj.forEach { (peer, neighbors) ->
            graph.updateFromAnnouncement(peer, peer, neighbors, 1_000UL)
        }
        return graph
    }

    @Test
    fun `linear chain elects every intermediate node and stops at leaf`() {
        // A -- B -- C -- D
        val graph = newGraph(
            listOf(
                "peer_a" to "peer_b",
                "peer_b" to "peer_c",
                "peer_c" to "peer_d"
            )
        )

        // From A: B is the sole cover for C
        assertEquals(setOf("peer_b"), MprSelector.selectMprs("peer_a", graph, coverageK = 1))
        val bDecision = MprSelector.evaluateRelay(
            myPeerID = "peer_b",
            previousHopPeerID = "peer_a",
            originPeerID = "peer_a",
            graphService = graph,
            directConnectedNeighbors = setOf("peer_a", "peer_c")
        )
        assertTrue(bDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.SELECTED_MPR, bDecision.reason)

        // From B: C is the sole cover for D
        assertEquals(setOf("peer_c"), MprSelector.selectMprs("peer_b", graph, coverageK = 1))
        val cDecision = MprSelector.evaluateRelay(
            myPeerID = "peer_c",
            previousHopPeerID = "peer_b",
            originPeerID = "peer_a",
            graphService = graph,
            directConnectedNeighbors = setOf("peer_b", "peer_d")
        )
        assertTrue(cDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.SELECTED_MPR, cDecision.reason)

        // From C: D is a leaf with no neighbors beyond C; D does not relay
        val dDecision = MprSelector.evaluateRelay(
            myPeerID = "peer_d",
            previousHopPeerID = "peer_c",
            originPeerID = "peer_a",
            graphService = graph,
            directConnectedNeighbors = setOf("peer_c")
        )
        assertFalse(dDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.SUPPRESSED_NON_MPR, dDecision.reason)
    }

    @Test
    fun `full clique produces empty MPR set and zero redundant relays`() {
        val peers = listOf("p1", "p2", "p3", "p4", "p5")
        val edges = mutableListOf<Pair<String, String>>()
        for (i in peers.indices) {
            for (j in i + 1 until peers.size) {
                edges += peers[i] to peers[j]
            }
        }
        val graph = newGraph(edges)

        assertTrue(MprSelector.selectMprs("p1", graph, coverageK = 1).isEmpty())
        assertTrue(MprSelector.selectMprs("p1", graph, coverageK = 2).isEmpty())

        for (node in peers.drop(1)) {
            val decision = MprSelector.evaluateRelay(
                myPeerID = node,
                previousHopPeerID = "p1",
                originPeerID = "p1",
                graphService = graph,
                directConnectedNeighbors = peers.filter { it != node }.toSet()
            )
            assertFalse("Clique node $node should not redundantly relay", decision.shouldRelay)
            assertEquals(MprSelector.RelayDecisionReason.SUPPRESSED_NON_MPR, decision.reason)
        }
    }

    @Test
    fun `two dense clusters joined by a single bridge always elect only the bridge`() {
        // Cluster 1: {a1, a2, a3, bridge} clique
        // Cluster 2: {bridge, c1, c2, c3} clique
        val cluster1 = listOf("a1", "a2", "a3", "bridge")
        val cluster2 = listOf("bridge", "c1", "c2", "c3")
        val edges = mutableListOf<Pair<String, String>>()
        for (group in listOf(cluster1, cluster2)) {
            for (i in group.indices) {
                for (j in i + 1 until group.size) {
                    edges += group[i] to group[j]
                }
            }
        }
        val graph = newGraph(edges)

        // From a1: only bridge reaches c1, c2, c3
        val mprs = MprSelector.selectMprs("a1", graph, coverageK = 1)
        assertEquals(setOf("bridge"), mprs)

        // Bridge MUST relay
        val bridgeDecision = MprSelector.evaluateRelay(
            myPeerID = "bridge",
            previousHopPeerID = "a1",
            originPeerID = "a1",
            graphService = graph,
            directConnectedNeighbors = setOf("a1", "a2", "a3", "c1", "c2", "c3")
        )
        assertTrue(bridgeDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.SELECTED_MPR, bridgeDecision.reason)

        // Cluster 1 siblings suppress
        for (sibling in listOf("a2", "a3")) {
            val decision = MprSelector.evaluateRelay(
                myPeerID = sibling,
                previousHopPeerID = "a1",
                originPeerID = "a1",
                graphService = graph,
                directConnectedNeighbors = cluster1.filter { it != sibling }.toSet()
            )
            assertFalse("Cluster 1 peer $sibling should not relay", decision.shouldRelay)
        }

        // After bridge relays to Cluster 2, none of c1..c3 need to relay further
        assertTrue(MprSelector.selectMprs("bridge", graph, coverageK = 1).isEmpty())
        for (cPeer in listOf("c1", "c2", "c3")) {
            val decision = MprSelector.evaluateRelay(
                myPeerID = cPeer,
                previousHopPeerID = "bridge",
                originPeerID = "a1",
                graphService = graph,
                directConnectedNeighbors = cluster2.filter { it != cPeer }.toSet()
            )
            assertFalse("Cluster 2 peer $cPeer should not relay", decision.shouldRelay)
        }
    }

    @Test
    fun `two-covered MPR elects both bridges on dual-bridge graph and top two on triple-bridge graph`() {
        // Dual-bridge: P -- {b1, b2} -- {y1, y2}
        val dualGraph = newGraph(
            listOf(
                "p" to "b1", "p" to "b2",
                "b1" to "y1", "b1" to "y2",
                "b2" to "y1", "b2" to "y2"
            )
        )
        assertEquals(setOf("b1"), MprSelector.selectMprs("p", dualGraph, coverageK = 1))
        assertEquals(setOf("b1", "b2"), MprSelector.selectMprs("p", dualGraph, coverageK = 2))

        // Triple-bridge: P -- {b1, b2, b3} -- y1; b1 and b2 have higher degree or smaller ID
        val tripleGraph = newGraph(
            listOf(
                "p" to "b1", "p" to "b2", "p" to "b3",
                "b1" to "y1", "b2" to "y1", "b3" to "y1"
            )
        )
        assertEquals(setOf("b1", "b2"), MprSelector.selectMprs("p", tripleGraph, coverageK = 2))

        // b3 is not elected and y1 is already 2-covered by {b1, b2} -> b3 suppresses
        val b3Decision = MprSelector.evaluateRelay(
            myPeerID = "b3",
            previousHopPeerID = "p",
            originPeerID = "p",
            graphService = tripleGraph,
            directConnectedNeighbors = setOf("p", "y1"),
            coverageK = 2
        )
        assertFalse(b3Decision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.SUPPRESSED_NON_MPR, b3Decision.reason)
    }

    @Test
    fun `tie breaking prefers highest uncovered reach then highest confirmed degree then smallest peerID`() {
        // Reach tie-breaker: u_high_reach covers {w1, w2}; u_low_reach covers {w1}
        val reachSelected = MprSelector.selectMprs(
            previousHopPeerID = "p",
            n1 = setOf("a_low_reach", "z_high_reach", "m_other"),
            neighborsByN1 = mapOf(
                "a_low_reach" to setOf("p", "w1"),
                "z_high_reach" to setOf("p", "w1", "w2"),
                "m_other" to setOf("p", "w2")
            ),
            coverageK = 1
        )
        assertEquals(setOf("z_high_reach"), reachSelected)

        // Degree tie-breaker: both cover {w1}, u_high_deg also connects to extra N1 peer x1
        val degreeSelected = MprSelector.selectMprs(
            previousHopPeerID = "p",
            n1 = setOf("a_low_deg", "z_high_deg", "x1"),
            neighborsByN1 = mapOf(
                "a_low_deg" to setOf("p", "w1"),
                "z_high_deg" to setOf("p", "w1", "x1"),
                "x1" to setOf("p", "z_high_deg")
            ),
            coverageK = 1
        )
        assertEquals(setOf("z_high_deg"), degreeSelected)

        // PeerID lexicographical tie-breaker: equal reach and equal degree -> smallest peerID wins
        val idSelectedFromOrder1 = MprSelector.selectMprs(
            previousHopPeerID = "p",
            n1 = linkedSetOf("peer_c", "peer_a", "peer_b"),
            neighborsByN1 = linkedMapOf(
                "peer_c" to setOf("p", "w1"),
                "peer_a" to setOf("p", "w1"),
                "peer_b" to setOf("p", "w1")
            ),
            coverageK = 1
        )
        val idSelectedFromOrder2 = MprSelector.selectMprs(
            previousHopPeerID = "p",
            n1 = linkedSetOf("peer_b", "peer_c", "peer_a"),
            neighborsByN1 = linkedMapOf(
                "peer_b" to setOf("p", "w1"),
                "peer_c" to setOf("p", "w1"),
                "peer_a" to setOf("p", "w1")
            ),
            coverageK = 1
        )
        assertEquals(setOf("peer_a"), idSelectedFromOrder1)
        assertEquals(idSelectedFromOrder1, idSelectedFromOrder2)
    }

    @Test
    fun `fail-safe rule forces non-MPR to relay for legacy, newly joined, stale, or unidentified neighbors`() {
        // Confirmed clique {p, m1, m2}: neither m1 nor m2 is an MPR for p
        val graph = newGraph(
            listOf(
                "p" to "m1", "p" to "m2", "m1" to "m2"
            )
        )

        // 1. Legacy client (omits TLV 0x04 -> never confirmed in graph) connected directly to m2
        graph.updateFromAnnouncement("legacy_peer", "Legacy", null, 1_000UL)
        val legacyDecision = MprSelector.evaluateRelay(
            myPeerID = "m2",
            previousHopPeerID = "p",
            originPeerID = "p",
            graphService = graph,
            directConnectedNeighbors = setOf("p", "m1", "legacy_peer")
        )
        assertTrue("Non-MPR must relay to legacy direct neighbor", legacyDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.UNCONFIRMED_DIRECT_NEIGHBOR, legacyDecision.reason)

        // 2. Newly joined peer (only announced one-way so not confirmed with p)
        graph.updateFromAnnouncement("new_peer", "Newbie", listOf("m2"), 1_000UL)
        val newPeerDecision = MprSelector.evaluateRelay(
            myPeerID = "m2",
            previousHopPeerID = "p",
            originPeerID = "p",
            graphService = graph,
            directConnectedNeighbors = setOf("p", "m1", "new_peer")
        )
        assertTrue("Non-MPR must relay to newly joined peer", newPeerDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.UNCONFIRMED_DIRECT_NEIGHBOR, newPeerDecision.reason)

        // 3. Stale neighbor whose link to p expired
        graph.updateFromAnnouncement("stale_peer", "Stale", listOf("p", "m2"), 1_000UL)
        graph.updateFromAnnouncement("p", "P", listOf("m1", "m2", "stale_peer"), 2_000UL)
        graph.updateFromAnnouncement("m2", "M2", listOf("p", "m1", "stale_peer"), 2_000UL)
        // Advance clock and refresh p, m1, m2 but NOT stale_peer
        nowMs += 40_000L
        graph.updateFromAnnouncement("p", "P", listOf("m1", "m2", "stale_peer"), 3_000UL)
        graph.updateFromAnnouncement("m1", "M1", listOf("p", "m2"), 3_000UL)
        graph.updateFromAnnouncement("m2", "M2", listOf("p", "m1", "stale_peer"), 3_000UL)
        val staleNeighborDecision = MprSelector.evaluateRelay(
            myPeerID = "m2",
            previousHopPeerID = "p",
            originPeerID = "p",
            graphService = graph,
            directConnectedNeighbors = setOf("p", "m1", "stale_peer"),
            maxAgeMs = 30_000L
        )
        assertTrue("Non-MPR must relay when direct neighbor's edge to P is stale", staleNeighborDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.UNCONFIRMED_DIRECT_NEIGHBOR, staleNeighborDecision.reason)

        // 4. Unidentified direct BLE link triggers fail-safe relay
        val unidentifiedDecision = MprSelector.evaluateRelay(
            myPeerID = "m1",
            previousHopPeerID = "p",
            originPeerID = "p",
            graphService = graph,
            directConnectedNeighbors = setOf("p", "m2"),
            hasUnidentifiedDirectLink = true,
            maxAgeMs = 30_000L
        )
        assertTrue(unidentifiedDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.UNIDENTIFIED_DIRECT_NEIGHBOR, unidentifiedDecision.reason)

        // 5. Stale graph for previousHop P falls back safely to relaying
        nowMs += 50_000L
        val staleCenterDecision = MprSelector.evaluateRelay(
            myPeerID = "m1",
            previousHopPeerID = "p",
            originPeerID = "p",
            graphService = graph,
            directConnectedNeighbors = setOf("p", "m2"),
            maxAgeMs = 30_000L
        )
        assertTrue(staleCenterDecision.shouldRelay)
        assertEquals(MprSelector.RelayDecisionReason.STALE_OR_MISSING_GRAPH, staleCenterDecision.reason)
    }

    @Test
    fun `broadcast pruning skips loopback, origin, previousHop, and their fresh confirmed neighbors`() {
        val origin = "1111111111111111"
        val prevHop = "2222222222222222"
        val me = "3333333333333333"
        val originNeighbor = "4444444444444444"
        val prevHopNeighbor = "5555555555555555"
        val farPeer = "6666666666666666"

        val graph = newGraph(
            listOf(
                origin to originNeighbor,
                prevHop to prevHopNeighbor,
                me to prevHop,
                me to farPeer
            )
        )

        val broadcastMsg = RoutedPacket(
            packet = BitchatPacket(
                version = 1u,
                type = MessageType.MESSAGE.value,
                senderID = hexToBytes(origin),
                recipientID = SpecialRecipients.BROADCAST,
                timestamp = 1_000UL,
                payload = "hi".toByteArray(),
                ttl = 5u
            ),
            peerID = origin,
            relayAddress = "AA:BB:CC:DD:EE:02",
            previousHopPeerID = prevHop
        )

        // Skips exact relayAddress, origin, previousHop, origin's fresh neighbor, prevHop's fresh neighbor
        assertTrue(BroadcastPruningPolicy.shouldSkipTarget(broadcastMsg, me, null, "AA:BB:CC:DD:EE:02", prevHop, graph))
        assertTrue(BroadcastPruningPolicy.shouldSkipTarget(broadcastMsg, me, origin, "AA:BB:CC:DD:EE:01", prevHop, graph))
        assertTrue(BroadcastPruningPolicy.shouldSkipTarget(broadcastMsg, me, prevHop, "AA:BB:CC:DD:EE:99", prevHop, graph))
        assertTrue(BroadcastPruningPolicy.shouldSkipTarget(broadcastMsg, me, originNeighbor, "AA:BB:CC:DD:EE:04", prevHop, graph))
        assertTrue(BroadcastPruningPolicy.shouldSkipTarget(broadcastMsg, me, prevHopNeighbor, "AA:BB:CC:DD:EE:05", prevHop, graph))

        // Delivers to far peer and to unidentified target links
        assertFalse(BroadcastPruningPolicy.shouldSkipTarget(broadcastMsg, me, farPeer, "AA:BB:CC:DD:EE:06", prevHop, graph))
        assertFalse(BroadcastPruningPolicy.shouldSkipTarget(broadcastMsg, me, null, "AA:BB:CC:DD:EE:07", prevHop, graph))

        // Conservative / skipNeighborPruning packets still skip echo-back but do NOT neighbor-prune
        val conservativePacket = broadcastMsg.copy(skipNeighborPruning = true)
        assertTrue(BroadcastPruningPolicy.shouldSkipTarget(conservativePacket, me, prevHop, "AA:BB:CC:DD:EE:99", prevHop, graph))
        assertFalse(BroadcastPruningPolicy.shouldSkipTarget(conservativePacket, me, prevHopNeighbor, "AA:BB:CC:DD:EE:05", prevHop, graph))

        // Locally originated packets are never neighbor-pruned
        val localMsg = broadcastMsg.copy(
            packet = broadcastMsg.packet.copy(senderID = hexToBytes(me)),
            peerID = me,
            relayAddress = null,
            previousHopPeerID = null
        )
        assertFalse(BroadcastPruningPolicy.shouldSkipTarget(localMsg, me, prevHop, "AA:BB:CC:DD:EE:02", null, graph))
    }

    private fun hexToBytes(hex: String): ByteArray =
        hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
