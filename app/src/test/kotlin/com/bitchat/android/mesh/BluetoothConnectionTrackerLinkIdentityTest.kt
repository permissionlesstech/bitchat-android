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

class BluetoothConnectionTrackerLinkIdentityTest {
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

        assertTrue(tracker.bindPeerIfCurrent(address, "link-b", "0011223344556677"))
        assertEquals("0011223344556677", tracker.addressPeerMap[address])
        assertSame(device, tracker.getDeviceConnection(address)?.device)
    }
}
