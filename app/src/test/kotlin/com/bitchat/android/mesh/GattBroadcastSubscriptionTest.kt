package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

/**
 * A CCCD write is the one GATT operation an unauthenticated peer can repeat at
 * will on an open connection. Everything the server hangs off it — the broadcast
 * fan-out list and the peer-connect side effects — therefore has to be
 * idempotent, and an explicit unsubscribe has to be honoured.
 */
@RunWith(RobolectricTestRunner::class)
class GattBroadcastSubscriptionTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val tracker = BluetoothConnectionTracker(scope, mock())

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun device(address: String): BluetoothDevice = mock<BluetoothDevice>().also {
        whenever(it.address).thenReturn(address)
    }

    @Test
    fun `enabling notifications is recognized`() {
        assertEquals(
            GattSubscriptionPolicy.Request.ENABLE,
            GattSubscriptionPolicy.classify(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        )
    }

    @Test
    fun `disabling notifications is recognized instead of being ignored`() {
        assertEquals(
            GattSubscriptionPolicy.Request.DISABLE,
            GattSubscriptionPolicy.classify(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        )
    }

    @Test
    fun `any other descriptor value changes nothing`() {
        assertEquals(
            GattSubscriptionPolicy.Request.UNRECOGNIZED,
            GattSubscriptionPolicy.classify(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)
        )
        assertEquals(GattSubscriptionPolicy.Request.UNRECOGNIZED, GattSubscriptionPolicy.classify(null))
        assertEquals(GattSubscriptionPolicy.Request.UNRECOGNIZED, GattSubscriptionPolicy.classify(ByteArray(0)))
    }

    @Test
    fun `repeated enables subscribe an address exactly once`() {
        val peer = device("AA:BB:CC:DD:EE:01")

        assertTrue("first enable must be a new subscription", tracker.addSubscribedDevice(peer))
        repeat(64) {
            assertFalse("a repeat enable is not a new subscription", tracker.addSubscribedDevice(peer))
        }

        assertEquals(1, tracker.getSubscribedDevices().count { it.address == peer.address })
    }

    @Test
    fun `a second framework instance for the same address does not add a second entry`() {
        // The stack hands out distinct BluetoothDevice objects for one address,
        // so identity comparison would let the fan-out list grow anyway.
        assertTrue(tracker.addSubscribedDevice(device("AA:BB:CC:DD:EE:02")))
        assertFalse(tracker.addSubscribedDevice(device("AA:BB:CC:DD:EE:02")))

        assertEquals(1, tracker.getSubscribedDevices().size)
    }

    @Test
    fun `unsubscribing removes the address whichever instance carries it`() {
        assertTrue(tracker.addSubscribedDevice(device("AA:BB:CC:DD:EE:03")))

        tracker.removeSubscribedDevice(device("AA:BB:CC:DD:EE:03"))

        assertTrue(tracker.getSubscribedDevices().isEmpty())
        assertFalse(tracker.isSubscribed("AA:BB:CC:DD:EE:03"))
    }

    @Test
    fun `unsubscribing one peer leaves the others receiving broadcasts`() {
        val first = device("AA:BB:CC:DD:EE:04")
        val second = device("AA:BB:CC:DD:EE:05")
        tracker.addSubscribedDevice(first)
        tracker.addSubscribedDevice(second)

        tracker.removeSubscribedDevice(first)

        assertEquals(listOf(second.address), tracker.getSubscribedDevices().map { it.address })
    }

    @Test
    fun `resubscribing after an unsubscribe counts as new again`() {
        val peer = device("AA:BB:CC:DD:EE:06")

        assertTrue(tracker.addSubscribedDevice(peer))
        tracker.removeSubscribedDevice(peer)

        assertTrue(tracker.addSubscribedDevice(peer))
        assertEquals(1, tracker.getSubscribedDevices().size)
    }

    @Test
    fun `disconnect cleanup still drops the subscription`() {
        val peer = device("AA:BB:CC:DD:EE:07")
        tracker.addSubscribedDevice(peer)

        tracker.cleanupDeviceConnection(peer.address)

        assertFalse(tracker.isSubscribed(peer.address))
    }
}
