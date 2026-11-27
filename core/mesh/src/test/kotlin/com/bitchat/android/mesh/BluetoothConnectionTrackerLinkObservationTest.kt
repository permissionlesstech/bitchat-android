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

    private companion object {
        const val PEER_ID = "0011223344556677"
    }
}
