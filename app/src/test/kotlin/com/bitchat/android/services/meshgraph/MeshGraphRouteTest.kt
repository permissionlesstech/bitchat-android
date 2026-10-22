package com.bitchat.android.services.meshgraph

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class MeshGraphRouteTest {
    private lateinit var graph: MeshGraphService

    @Before
    fun setUp() {
        MeshGraphService.resetForTesting()
        graph = MeshGraphService.getInstance()
    }

    @After
    fun tearDown() {
        MeshGraphService.resetForTesting()
    }

    @Test
    fun `route uses shortest confirmed path`() {
        graph.updateFromAnnouncement("a", "alice", listOf("b"), 1u)
        graph.updateFromAnnouncement("b", "bob", listOf("a", "c"), 2u)
        graph.updateFromAnnouncement("c", "carol", listOf("b"), 3u)

        assertEquals(listOf("a", "b", "c"), graph.computeRoute("a", "c"))
    }

    @Test
    fun `route ignores one-sided claims`() {
        graph.updateFromAnnouncement("a", "alice", listOf("b"), 1u)
        graph.updateFromAnnouncement("b", "bob", emptyList(), 2u)

        assertNull(graph.computeRoute("a", "b"))
    }
}
