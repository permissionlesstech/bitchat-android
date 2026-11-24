package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class BluetoothConnectionTrackerLinkObservationTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val tracker = BluetoothConnectionTracker(scope, mock())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `stale connection callbacks cannot mutate or remove replacement link`() {
        val address = "AA:BB:CC:DD:EE:FF"
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)

        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-a")
        )
        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-b")
        )

        assertFalse(
            tracker.updateDeviceConnectionIfCurrent(address, "link-a") {
                it.copy(rssi = -10)
            }
        )
        assertFalse(tracker.cleanupDeviceConnectionIfCurrent(address, "link-a"))
        assertEquals("link-b", tracker.getCurrentLinkID(address))

        assertTrue(tracker.observePeerIfCurrent(address, "link-b", "0011223344556677"))
        assertEquals("0011223344556677", tracker.addressPeerMap[address])
        assertSame(device, tracker.getDeviceConnection(address)?.device)
    }

    @Test
    fun `one peer can remain directly observed over multiple current links`() {
        val firstAddress = "AA:BB:CC:DD:EE:01"
        val secondAddress = "AA:BB:CC:DD:EE:02"
        val firstDevice = mock<BluetoothDevice>()
        val secondDevice = mock<BluetoothDevice>()
        whenever(firstDevice.address).thenReturn(firstAddress)
        whenever(secondDevice.address).thenReturn(secondAddress)

        tracker.addDeviceConnection(
            firstAddress,
            BluetoothConnectionTracker.DeviceConnection(device = firstDevice, linkID = "link-a")
        )
        tracker.addDeviceConnection(
            secondAddress,
            BluetoothConnectionTracker.DeviceConnection(device = secondDevice, linkID = "link-b")
        )

        assertTrue(tracker.observePeerIfCurrent(firstAddress, "link-a", PEER_ID))
        assertTrue(tracker.observePeerIfCurrent(secondAddress, "link-b", PEER_ID))
        assertTrue(tracker.observePeerIfCurrent(secondAddress, "link-b", PEER_ID))
        assertEquals(2, tracker.addressPeerMap.values.count { it == PEER_ID })

        assertTrue(tracker.cleanupDeviceConnectionIfCurrent(firstAddress, "link-a"))
        assertEquals(PEER_ID, tracker.addressPeerMap[secondAddress])
        assertTrue(tracker.addressPeerMap.containsValue(PEER_ID))
    }

    @Test
    fun `getPeerForLinkIfCurrent rejects stale linkID after replacement and notifies listener`() {
        val address = "AA:BB:CC:DD:EE:03"
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)

        var changeNotifications = 0
        tracker.onConnectionStateChanged = { changeNotifications += 1 }

        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-1")
        )
        assertTrue(tracker.observePeerIfCurrent(address, "link-1", "1111111111111111"))
        assertEquals("1111111111111111", tracker.getPeerForLinkIfCurrent(address, "link-1"))
        assertEquals(null, tracker.getPeerForLinkIfCurrent(address, "link-stale"))

        // Replace link at same address with a new generation and different peer
        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-2")
        )
        assertTrue(tracker.observePeerIfCurrent(address, "link-2", "2222222222222222"))

        assertEquals(null, tracker.getPeerForLinkIfCurrent(address, "link-1"))
        assertEquals("2222222222222222", tracker.getPeerForLinkIfCurrent(address, "link-2"))
        assertTrue("Connection state listener should fire on link/peer mutations", changeNotifications >= 4)
    }

    @Test
    fun `explicit disconnect immediately notifies observed peer listener and prunes direct graph edge`() {
        val myPeer = "0000000000000001"
        val address = "AA:BB:CC:DD:EE:04"
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)

        val graph = com.bitchat.android.services.meshgraph.MeshGraphService { 100_000L }
        graph.updateFromAnnouncement(myPeer, "Me", listOf(PEER_ID), 1_000UL)
        graph.updateFromAnnouncement(PEER_ID, "Peer", listOf(myPeer), 1_000UL)
        assertTrue(graph.isFreshConfirmedNeighbor(myPeer, PEER_ID))

        val disconnectedPeers = mutableListOf<String>()
        tracker.onObservedPeerDisconnected = { removedPeer ->
            disconnectedPeers += removedPeer
            MeshGraphTransportCleanup.onDirectLinkLost(
                myPeerID = myPeer,
                transportId = "BLE",
                peerID = removedPeer,
                remainingLocalDirectPeers = tracker.addressPeerMap.values.toSet(),
                hasUnidentifiedDirectLink = tracker.hasUnidentifiedConnection(),
                graphService = graph
            )
        }

        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-explicit")
        )
        assertTrue(tracker.observePeerIfCurrent(address, "link-explicit", PEER_ID))

        // Explicit disconnect clears addressPeerMap synchronously BEFORE Android's delayed GATT callback
        tracker.disconnect(address)

        assertEquals(listOf(PEER_ID), disconnectedPeers)
        assertFalse("Direct graph edge must be pruned immediately on explicit disconnect", graph.isFreshConfirmedNeighbor(myPeer, PEER_ID))
    }

    @Test
    fun `link replacement prunes displaced peer edge immediately when last link is replaced and preserves when another link remains`() {
        val myPeer = "0000000000000001"
        val peerA = "1111111111111111"
        val peerB = "2222222222222222"
        val addr1 = "AA:BB:CC:DD:EE:11"
        val addr2 = "AA:BB:CC:DD:EE:12"
        val dev1 = mock<BluetoothDevice>().also { whenever(it.address).thenReturn(addr1) }
        val dev2 = mock<BluetoothDevice>().also { whenever(it.address).thenReturn(addr2) }

        val graph = com.bitchat.android.services.meshgraph.MeshGraphService { 100_000L }
        graph.updateFromAnnouncement(myPeer, "Me", listOf(peerA, peerB), 1_000UL)
        graph.updateFromAnnouncement(peerA, "A", listOf(myPeer), 1_000UL)
        graph.updateFromAnnouncement(peerB, "B", listOf(myPeer), 1_000UL)

        var topologyAnnouncesScheduled = 0
        tracker.onObservedPeerDisconnected = { removedPeer ->
            val remainingDirect = tracker.addressPeerMap.values.toSet()
            MeshGraphTransportCleanup.onDirectLinkLost(
                myPeerID = myPeer,
                transportId = "BLE",
                peerID = removedPeer,
                remainingLocalDirectPeers = remainingDirect,
                hasUnidentifiedDirectLink = tracker.hasUnidentifiedConnection(),
                graphService = graph
            )
            if (removedPeer !in remainingDirect && remainingDirect.isNotEmpty()) {
                topologyAnnouncesScheduled += 1
            }
        }

        // Case 1: peerA has TWO links (addr1 and addr2); replacing addr1 preserves peerA's edge
        tracker.addDeviceConnection(addr1, BluetoothConnectionTracker.DeviceConnection(device = dev1, linkID = "a-1"))
        tracker.observePeerIfCurrent(addr1, "a-1", peerA)
        tracker.addDeviceConnection(addr2, BluetoothConnectionTracker.DeviceConnection(device = dev2, linkID = "a-2"))
        tracker.observePeerIfCurrent(addr2, "a-2", peerA)

        tracker.addDeviceConnection(addr1, BluetoothConnectionTracker.DeviceConnection(device = dev1, linkID = "b-1"))
        assertTrue("Edge for peerA must remain while addr2 still maps to peerA", graph.isFreshConfirmedNeighbor(myPeer, peerA))
        assertEquals(0, topologyAnnouncesScheduled)

        // Observe peerB on addr1 so remainingDirect stays non-empty when peerA's last link is replaced
        tracker.observePeerIfCurrent(addr1, "b-1", peerB)

        // Case 2: replacing peerA's LAST link (addr2) immediately prunes peerA's edge and triggers topology announce
        tracker.addDeviceConnection(addr2, BluetoothConnectionTracker.DeviceConnection(device = dev2, linkID = "c-1"))
        assertFalse("Edge for peerA must be pruned immediately when last link is replaced", graph.isFreshConfirmedNeighbor(myPeer, peerA))
        assertEquals("Prompt topology announce must fire when peerA loses last link while peerB remains", 1, topologyAnnouncesScheduled)
        assertTrue("Edge for remaining peerB must stay intact", graph.isFreshConfirmedNeighbor(myPeer, peerB))
    }

    private companion object {
        const val PEER_ID = "0011223344556677"
    }
}
