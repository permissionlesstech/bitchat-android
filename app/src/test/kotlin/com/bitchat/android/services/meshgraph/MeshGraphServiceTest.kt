package com.bitchat.android.services.meshgraph

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

class MeshGraphServiceTest {

    private var nowMs: Long = 10_000L
    private lateinit var service: MeshGraphService

    @Before
    fun setUp() {
        nowMs = 10_000L
        // Use the test-only API to reset the singleton state safely with a deterministic clock
        MeshGraphService.resetForTesting { nowMs }
        service = MeshGraphService.getInstance()
    }

    @After
    fun tearDown() {
        MeshGraphService.resetForTesting()
    }

    @Test
    fun testUpdateFromAnnouncement_AddsNeighbors() {
        val origin = "PeerA"
        val neighbors = listOf("PeerB", "PeerC")
        val timestamp = 100UL

        service.updateFromAnnouncement(origin, "Alice", neighbors, timestamp)

        val snapshot = service.graphState.value
        // Verify nodes
        assertTrue(snapshot.nodes.any { it.peerID == "PeerA" })
        assertTrue(snapshot.nodes.any { it.peerID == "PeerB" })
        assertTrue(snapshot.nodes.any { it.peerID == "PeerC" })

        // Verify edges (unconfirmed because B and C haven't announced A)
        // A -> B
        val edgeAB = snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") }
        assertNotNull(edgeAB)
        assertFalse(edgeAB!!.isConfirmed)
        assertEquals("PeerA", edgeAB.confirmedBy)

        // A -> C
        val edgeAC = snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") }
        assertNotNull(edgeAC)
        assertFalse(edgeAC!!.isConfirmed)
        assertEquals("PeerA", edgeAC.confirmedBy)
    }

    @Test
    fun testUpdateFromAnnouncement_NewerTimestampReplacesNeighbors() {
        val origin = "PeerA"
        
        // Initial state: A -> {B, C}
        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerC"), 100UL)
        
        // Update: A -> {B, D} (newer timestamp)
        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerD"), 200UL)

        val snapshot = service.graphState.value
        
        // Verify Edge A-B exists
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
        
        // Verify Edge A-D exists
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerD") || (it.a == "PeerD" && it.b == "PeerA") })
        
        // Verify Edge A-C does NOT exist
        assertNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") })
    }

    @Test
    fun testUpdateFromAnnouncement_OlderTimestampIsIgnored() {
        val origin = "PeerA"
        
        // Initial state: A -> {B, C} at ts=200
        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerC"), 200UL)
        
        // Old Update: A -> {D} at ts=100
        service.updateFromAnnouncement(origin, "Alice", listOf("PeerD"), 100UL)

        val snapshot = service.graphState.value
        
        // Should still be {B, C}
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerC") || (it.a == "PeerC" && it.b == "PeerA") })
        assertNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerD") || (it.a == "PeerD" && it.b == "PeerA") })
    }

    @Test
    fun testUpdateFromAnnouncement_NullNeighborsClearsList_TheFix() {
        val origin = "PeerA"
        
        // Initial state: A -> {B, C} at ts=100
        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerC"), 100UL)
        
        // Update with NULL neighbors (omitted TLV) at ts=200
        service.updateFromAnnouncement(origin, "Alice", null, 200UL)

        val snapshot = service.graphState.value
        
        // All edges from A should be gone
        val edgesFromA = snapshot.edges.filter { it.a == "PeerA" || it.b == "PeerA" }
        assertTrue("Edges from PeerA should be empty after null update", edgesFromA.isEmpty())
        
        // Nodes B and C might still exist if they were added to the node list, but connected edges are gone.
        // Actually, publishSnapshot collects nodes from nicknames and announcements.
        // Since we provided nicknames for PeerA, it should be there.
        // PeerB and PeerC were only in announcements. Since A's announcement is cleared, and B/C never announced, 
        // they might disappear from the node list if 'nicknames' doesn't contain them.
        // Let's check edges primarily as that's what routing cares about.
    }
    
    @Test
    fun testUpdateFromAnnouncement_NullNeighborsWithOlderTimestampIsIgnored() {
        val origin = "PeerA"
        
        // Initial state: A -> {B, C} at ts=200
        service.updateFromAnnouncement(origin, "Alice", listOf("PeerB", "PeerC"), 200UL)
        
        // Old Update with NULL neighbors at ts=100
        service.updateFromAnnouncement(origin, "Alice", null, 100UL)

        val snapshot = service.graphState.value
        
        // Should still be {B, C} because the null update was older
        assertFalse(snapshot.edges.isEmpty())
        assertNotNull(snapshot.edges.find { (it.a == "PeerA" && it.b == "PeerB") || (it.a == "PeerB" && it.b == "PeerA") })
    }

    @Test
    fun removePeer_RemovesInboundAndOutboundGraphReferences() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB"), 100UL)
        service.updateFromAnnouncement("PeerB", "Bob", listOf("PeerA", "PeerC"), 100UL)
        service.updateFromAnnouncement("PeerC", "Carol", listOf("PeerB"), 100UL)

        service.removePeer("PeerB")

        val snapshot = service.graphState.value
        assertFalse(snapshot.nodes.any { it.peerID == "PeerB" })
        assertFalse(snapshot.edges.any { it.a == "PeerB" || it.b == "PeerB" })
        assertTrue(snapshot.nodes.any { it.peerID == "PeerA" })
        assertTrue(snapshot.nodes.any { it.peerID == "PeerC" })
        assertFalse(service.isConfirmedNeighbor("PeerA", "PeerB"))
        assertFalse(service.isFreshConfirmedNeighbor("PeerA", "PeerB", 60_000L))
        assertFalse(service.isFreshAnnouncement("PeerB", 60_000L))
        assertTrue(service.freshConfirmedNeighborsOf("PeerA", 60_000L).isEmpty())
    }

    @Test
    fun freshConfirmedNeighbors_ExpiresAtMaxAgeBoundary() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB"), 100UL)
        service.updateFromAnnouncement("PeerB", "Bob", listOf("PeerA"), 100UL)

        assertTrue(service.isFreshConfirmedNeighbor("PeerA", "PeerB", maxAgeMs = 30_000L))
        assertEquals(setOf("PeerB"), service.freshConfirmedNeighborsOf("PeerA", maxAgeMs = 30_000L))

        // Exactly at maxAgeMs is still fresh
        nowMs += 30_000L
        assertTrue(service.isFreshConfirmedNeighbor("PeerA", "PeerB", maxAgeMs = 30_000L))

        // 1ms past maxAgeMs expires
        nowMs += 1L
        assertFalse(service.isFreshConfirmedNeighbor("PeerA", "PeerB", maxAgeMs = 30_000L))
        assertTrue(service.freshConfirmedNeighborsOf("PeerA", maxAgeMs = 30_000L).isEmpty())
        // Underlying structural edge still exists until pruned or overwritten
        assertTrue(service.isConfirmedNeighbor("PeerA", "PeerB"))
    }

    @Test
    fun freshConfirmedNeighbor_RequiresBothEndpointsFresh() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB"), 100UL)
        service.updateFromAnnouncement("PeerB", "Bob", listOf("PeerA"), 100UL)

        nowMs += 25_000L
        // Refresh only PeerA; PeerB remains older
        val changed = service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB"), 200UL)
        assertFalse("Unchanged neighbor set refresh should report topologyChanged=false", changed)

        nowMs += 10_000L // PeerA age = 10s (fresh), PeerB age = 35s (stale for 30s window)
        assertTrue(service.isFreshAnnouncement("PeerA", maxAgeMs = 30_000L))
        assertFalse(service.isFreshAnnouncement("PeerB", maxAgeMs = 30_000L))
        assertFalse(service.isFreshConfirmedNeighbor("PeerA", "PeerB", maxAgeMs = 30_000L))
        assertTrue(service.freshConfirmedNeighborsOf("PeerA", maxAgeMs = 30_000L).isEmpty())
    }

    @Test
    fun updateFromAnnouncement_ReportsTopologyChangesAccurately() {
        assertTrue(
            "First announcement for peer should report topologyChanged=true",
            service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB"), 100UL)
        )
        assertFalse(
            "Identical neighbor set with newer timestamp should report topologyChanged=false",
            service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB"), 200UL)
        )
        assertTrue(
            "Changed neighbor set should report topologyChanged=true",
            service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB", "PeerC"), 300UL)
        )
        assertFalse(
            "Older timestamp must be ignored and report topologyChanged=false",
            service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerD"), 250UL)
        )
    }

    @Test
    fun removeDirectEdge_PrunesOnlyDisconnectedLinkImmediately() {
        service.updateFromAnnouncement("PeerA", "Alice", listOf("PeerB", "PeerC"), 100UL)
        service.updateFromAnnouncement("PeerB", "Bob", listOf("PeerA", "PeerC"), 100UL)
        service.updateFromAnnouncement("PeerC", "Carol", listOf("PeerA", "PeerB"), 100UL)

        service.removeDirectEdge("PeerA", "PeerB")

        assertFalse(service.isConfirmedNeighbor("PeerA", "PeerB"))
        assertFalse(service.isFreshConfirmedNeighbor("PeerA", "PeerB", 60_000L))
        assertTrue(service.isFreshConfirmedNeighbor("PeerA", "PeerC", 60_000L))
        assertTrue(service.isFreshConfirmedNeighbor("PeerB", "PeerC", 60_000L))
    }

    @Test
    fun removeDirectEdge_DoesNotMaskSubsequentAnnounceTopologyChange() {
        assertTrue(service.updateFromAnnouncement("PeerP", "PeerP", listOf("PeerM", "PeerR"), 100UL))
        assertTrue(service.updateFromAnnouncement("PeerM", "PeerM", listOf("PeerP", "PeerR"), 100UL))

        // Local disconnect pruning removes PeerP <-> PeerM from the active edge view
        service.removeDirectEdge("PeerM", "PeerP")
        assertFalse(service.isConfirmedNeighbor("PeerM", "PeerP"))

        // When PeerP later announces [PeerR] (dropping PeerM), topologyChanged MUST still be true
        // so the relay floods PeerP's disconnect notification to downstream peers.
        assertTrue(
            "Announce dropping a locally pruned neighbor must still report topologyChanged=true",
            service.updateFromAnnouncement("PeerP", "PeerP", listOf("PeerR"), 200UL)
        )
        assertFalse(
            "Subsequent unchanged announce after the drop must report topologyChanged=false",
            service.updateFromAnnouncement("PeerP", "PeerP", listOf("PeerR"), 300UL)
        )
    }
}
