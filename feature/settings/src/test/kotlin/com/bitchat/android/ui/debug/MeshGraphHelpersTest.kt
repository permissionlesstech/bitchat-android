package com.bitchat.android.ui.debug

import com.bitchat.android.services.meshgraph.MeshGraphService.GraphEdge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the mesh graph's pure helpers and its settle rule (inventory D28). */
class MeshGraphHelpersTest {

    private val local = "0123456789abcdef"
    private val peer = "fedcba9876543210"
    private val far = "00000000000000aa"

    @Test
    fun directEdge_withALocalID_onlyEdgesFromItToATransportPeer() {
        val transport = setOf(peer)
        assertTrue(shouldMarkDirectEdge(GraphEdge(local, peer, isConfirmed = true), transport, local))
        assertTrue(shouldMarkDirectEdge(GraphEdge(peer, local, isConfirmed = false), transport, local))
        assertFalse(shouldMarkDirectEdge(GraphEdge(peer, far, isConfirmed = true), transport, local))
        assertFalse(shouldMarkDirectEdge(GraphEdge(local, far, isConfirmed = true), transport, local))
    }

    @Test
    fun directEdge_withoutALocalID_anyEdgeTouchingATransportPeer_andNoneWithoutPeers() {
        assertTrue(shouldMarkDirectEdge(GraphEdge(peer, far, isConfirmed = true), setOf(peer), null))
        assertFalse(shouldMarkDirectEdge(GraphEdge(local, far, isConfirmed = true), setOf(peer), null))
        assertFalse(shouldMarkDirectEdge(GraphEdge(local, peer, isConfirmed = true), emptySet(), local))
    }

    @Test
    fun markerPosition_isOffsetFromTheMidpoint_perpendicular_onTheGivenSide() {
        assertEquals(5f to 5f, edgeMarkerPosition(0f, 0f, 10f, 0f, offsetPx = 5f, sideSign = 1f))
        assertEquals(5f to -5f, edgeMarkerPosition(0f, 0f, 10f, 0f, offsetPx = 5f, sideSign = -1f))
        // A zero-length edge puts the marker on the midpoint.
        assertEquals(3f to 4f, edgeMarkerPosition(3f, 4f, 3f, 4f, offsetPx = 5f, sideSign = 1f))
    }

    private fun simulationWith(vararg nodes: GraphNodeState): Simulation = Simulation().apply {
        width = 200f
        height = 200f
        nodes.forEach { this.nodes[it.id] = it }
    }

    @Test
    fun step_aNodeAtRestInTheCentre_isSettled() {
        val sim = simulationWith(GraphNodeState(local, "a", 100f, 100f))
        assertFalse(sim.step())
    }

    @Test
    fun step_movingDraggedPulsingOrRouting_isNotSettled_untilItDecays() {
        val moving = GraphNodeState(local, "a", 100f, 100f).apply { vx = 10f }
        assertTrue(simulationWith(moving).step())

        val dragged = GraphNodeState(local, "a", 100f, 100f).apply { isDragged = true }
        val draggedSim = simulationWith(dragged)
        repeat(100) { assertTrue(draggedSim.step()) }

        // A pulse decays by 0.05 a frame: about 20 frames.
        val pulsing = simulationWith(GraphNodeState(local, "a", 100f, 100f).apply { pulseLevel = 1f })
        val pulseFrames = generateSequence { pulsing.step() }.takeWhile { it }.count()
        assertTrue("pulse frames $pulseFrames", pulseFrames in 19..21)

        // A route decays by 0.02 a frame: about 50 frames.
        val routing = simulationWith(GraphNodeState(local, "a", 100f, 100f))
        routing.triggerRouteAnimation(listOf(local, peer))
        val routeFrames = generateSequence { routing.step() }.takeWhile { it }.count()
        assertTrue("route frames $routeFrames", routeFrames in 49..51)
    }

    @Test
    fun step_twoLinkedNodes_settle() {
        val sim = simulationWith(
            GraphNodeState(local, "a", 60f, 100f),
            GraphNodeState(peer, "b", 140f, 100f),
        )
        sim.edges.add(GraphEdge(local, peer, isConfirmed = true))
        val frames = generateSequence { sim.step() }.take(10_000).takeWhile { it }.count()
        assertTrue("settled after $frames frames", frames < 10_000)
    }
}
