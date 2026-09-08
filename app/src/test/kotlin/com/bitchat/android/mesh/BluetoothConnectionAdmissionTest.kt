package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class BluetoothConnectionAdmissionTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
    private val tracker = BluetoothConnectionTracker(scope, mock())

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `pending client reservations cannot race past two total links`() {
        tracker.start()

        assertTrue(tracker.tryReserveClientConnection("first", 2, 2))
        assertTrue(tracker.tryReserveClientConnection("second", 2, 2))
        assertFalse(tracker.tryReserveClientConnection("third", 2, 2))
        assertEquals(2, tracker.getPendingConnectionCount())
    }

    @Test
    fun `active and pending links share the same hard limit`() {
        tracker.start()
        addConnection("active", isClient = false)

        assertTrue(tracker.tryReserveClientConnection("pending", 2, 2))
        assertFalse(tracker.tryReserveClientConnection("excess", 2, 2))
        assertFalse(tryAddServerConnection("server"))
    }

    @Test
    fun `two server links are accepted but a third is rejected`() {
        assertTrue(tryAddServerConnection("server-a"))
        assertTrue(tryAddServerConnection("server-b"))

        assertFalse(tryAddServerConnection("server-c"))
    }

    @Test
    fun `simultaneous inbound callbacks cannot race past two links`() {
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val admissions = (0 until 8).map { index ->
                executor.submit<Boolean> {
                    start.await()
                    tryAddServerConnection("server-$index")
                }
            }
            start.countDown()

            assertEquals(2, admissions.count { it.get(5, TimeUnit.SECONDS) })
            assertEquals(2, tracker.getConnectedDeviceCount())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun tryAddServerConnection(address: String): Boolean {
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)
        return tracker.tryAddServerConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(
                device = device,
                isClient = false,
                linkID = "link-$address"
            ),
            maxOverall = 2,
            maxServer = 2
        )
    }

    private fun addConnection(address: String, isClient: Boolean) {
        val device = mock<BluetoothDevice>()
        whenever(device.address).thenReturn(address)
        tracker.addDeviceConnection(
            address,
            BluetoothConnectionTracker.DeviceConnection(
                device = device,
                isClient = isClient,
                linkID = "link-$address"
            )
        )
    }
}
