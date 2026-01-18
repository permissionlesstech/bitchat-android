package com.bitchat.android.services.meshgraph

import org.junit.Assert.*
import org.junit.Test
import org.junit.Before

class MeshGraphServiceTest {

    private lateinit var service: MeshGraphService

    @Before
    fun setUp() {
        // Use the test-only API to reset the singleton state safely
        MeshGraphService.resetForTesting()
        service = MeshGraphService.getInstance()
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
}
