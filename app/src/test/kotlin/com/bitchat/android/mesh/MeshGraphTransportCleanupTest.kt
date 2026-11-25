package com.bitchat.android.mesh

import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.meshgraph.MeshGraphService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MeshGraphTransportCleanupTest {

    private var nowMs: Long = 100_000L
    private val me = "0000000000000001"
    private val bleOnlyPeer = "0000000000000002"
    private val dualTransportPeer = "0000000000000003"
    private val wifiOnlyPeer = "0000000000000004"

    @Before
    fun setUp() {
        nowMs = 100_000L
        AppStateStore.clear()
        MeshGraphService.resetForTesting { nowMs }
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
        MeshGraphService.resetForTesting()
    }

    @Test
    fun `unidentified direct link propagates across transports and clears on transport disable`() {
        assertFalse(AppStateStore.hasAnyUnidentifiedDirectLink())

        MeshGraphTransportCleanup.publishDirectState("BLE", setOf(bleOnlyPeer), hasUnidentifiedDirectLink = true)
        assertTrue(AppStateStore.hasAnyUnidentifiedDirectLink())

        // Wi-Fi publishing false must not wipe BLE's unidentified link flag
        MeshGraphTransportCleanup.publishDirectState("WIFI", setOf(wifiOnlyPeer), hasUnidentifiedDirectLink = false)
        assertTrue(AppStateStore.hasAnyUnidentifiedDirectLink())

        // Disabling BLE clears BLE's unidentified flag
        MeshGraphTransportCleanup.onTransportDisabled(
            myPeerID = me,
            transportId = "BLE",
            previousDirectPeers = setOf(bleOnlyPeer),
            previousActivePeers = setOf(bleOnlyPeer)
        )
        assertFalse(AppStateStore.hasAnyUnidentifiedDirectLink())

        // AppStateStore.clear() must also wipe unidentified direct-link flags
        MeshGraphTransportCleanup.publishDirectState("WIFI", setOf(wifiOnlyPeer), hasUnidentifiedDirectLink = true)
        assertTrue(AppStateStore.hasAnyUnidentifiedDirectLink())
        AppStateStore.clear()
        assertFalse("AppStateStore.clear() must clear unidentified-link flags", AppStateStore.hasAnyUnidentifiedDirectLink())
    }

    @Test
    fun `disabling one transport prunes only its exclusive edges and preserves other transport edges`() {
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement(me, "Me", listOf(bleOnlyPeer, dualTransportPeer, wifiOnlyPeer), 1_000UL)
        graph.updateFromAnnouncement(bleOnlyPeer, "BleOnly", listOf(me), 1_000UL)
        graph.updateFromAnnouncement(dualTransportPeer, "Dual", listOf(me), 1_000UL)
        graph.updateFromAnnouncement(wifiOnlyPeer, "WifiOnly", listOf(me), 1_000UL)

        AppStateStore.setTransportPeers("BLE", listOf(bleOnlyPeer, dualTransportPeer))
        AppStateStore.setTransportDirectPeers("BLE", setOf(bleOnlyPeer, dualTransportPeer))
        AppStateStore.setTransportPeers("WIFI", listOf(dualTransportPeer, wifiOnlyPeer))
        AppStateStore.setTransportDirectPeers("WIFI", setOf(dualTransportPeer, wifiOnlyPeer))

        MeshGraphTransportCleanup.onTransportDisabled(
            myPeerID = me,
            transportId = "BLE",
            previousDirectPeers = setOf(bleOnlyPeer, dualTransportPeer),
            previousActivePeers = setOf(bleOnlyPeer, dualTransportPeer),
            graphService = graph
        )

        assertFalse(graph.isFreshConfirmedNeighbor(me, bleOnlyPeer))
        assertFalse(graph.isFreshAnnouncement(bleOnlyPeer))
        assertTrue(graph.isFreshConfirmedNeighbor(me, dualTransportPeer))
        assertTrue(graph.isFreshConfirmedNeighbor(me, wifiOnlyPeer))
    }

    @Test
    fun `losing one of two links to same peer preserves direct graph edge until last link drops`() {
        val graph = MeshGraphService.getInstance()
        graph.updateFromAnnouncement(me, "Me", listOf(bleOnlyPeer), 1_000UL)
        graph.updateFromAnnouncement(bleOnlyPeer, "BleOnly", listOf(me), 1_000UL)

        // First link drops while second BLE link to the same peer remains
        MeshGraphTransportCleanup.onDirectLinkLost(
            myPeerID = me,
            transportId = "BLE",
            peerID = bleOnlyPeer,
            remainingLocalDirectPeers = setOf(bleOnlyPeer),
            hasUnidentifiedDirectLink = false,
            graphService = graph
        )
        assertTrue("Edge must stay while another link on same transport exists", graph.isFreshConfirmedNeighbor(me, bleOnlyPeer))

        // Last link drops -> edge is pruned promptly
        MeshGraphTransportCleanup.onDirectLinkLost(
            myPeerID = me,
            transportId = "BLE",
            peerID = bleOnlyPeer,
            remainingLocalDirectPeers = emptySet(),
            hasUnidentifiedDirectLink = false,
            graphService = graph
        )
        assertFalse("Edge must be pruned when last direct link drops", graph.isFreshConfirmedNeighbor(me, bleOnlyPeer))
    }

    @Test
    fun `scheduleTopologyChangeAnnounce fires across mixed transports and cancels on deactivation`() = runTest {
        // 1. BLE loses its last local peer while Wi-Fi still has a direct peer -> fires after 500ms
        AppStateStore.setTransportDirectPeers("WIFI", setOf(wifiOnlyPeer))
        AppStateStore.setTransportDirectPeers("BLE", emptySet())
        var bleAnnounces = 0
        MeshGraphTransportCleanup.scheduleTopologyChangeAnnounce(
            scope = this,
            removedPeerID = bleOnlyPeer,
            remainingLocalDirectPeers = emptySet(),
            isServiceActive = { true },
            sendAnnounce = { bleAnnounces += 1 }
        )
        advanceTimeBy(499L)
        runCurrent()
        assertEquals(0, bleAnnounces)
        advanceTimeBy(1L)
        runCurrent()
        assertEquals("BLE disconnect must announce to remaining Wi-Fi peer", 1, bleAnnounces)

        // 2. Wi-Fi loses its last local peer while BLE still has a direct peer -> fires after 500ms
        AppStateStore.setTransportDirectPeers("BLE", setOf(bleOnlyPeer))
        AppStateStore.setTransportDirectPeers("WIFI", emptySet())
        var wifiAnnounces = 0
        MeshGraphTransportCleanup.scheduleTopologyChangeAnnounce(
            scope = this,
            removedPeerID = wifiOnlyPeer,
            remainingLocalDirectPeers = emptySet(),
            isServiceActive = { true },
            sendAnnounce = { wifiAnnounces += 1 }
        )
        advanceTimeBy(500L)
        runCurrent()
        assertEquals("Wi-Fi disconnect must announce to remaining BLE peer", 1, wifiAnnounces)

        // 3. Deactivating service before the 500ms timer fires suppresses the pending announce
        var serviceActive = true
        var deactivatedAnnounces = 0
        MeshGraphTransportCleanup.scheduleTopologyChangeAnnounce(
            scope = this,
            removedPeerID = wifiOnlyPeer,
            remainingLocalDirectPeers = emptySet(),
            isServiceActive = { serviceActive },
            sendAnnounce = { deactivatedAnnounces += 1 }
        )
        advanceTimeBy(499L)
        runCurrent()
        serviceActive = false
        advanceTimeBy(100L)
        runCurrent()
        assertEquals("Deactivated service must not emit delayed topology announce", 0, deactivatedAnnounces)
    }
}
