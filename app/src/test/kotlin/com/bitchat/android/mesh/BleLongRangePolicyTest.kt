package com.bitchat.android.mesh

import android.bluetooth.BluetoothDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BleLongRangePolicyTest {

    private val coded = BleRadioCapabilities(
        leCodedPhy = true,
        le2MPhy = true,
        leExtendedAdvertising = true,
        lePeriodicAdvertising = true,
        multipleAdvertisement = true,
        maxAdvertisingDataLength = 251
    )
    private val legacyOnly = BleRadioCapabilities.NONE.copy(multipleAdvertisement = true)

    @Test
    fun `coded advertising and extended scanning need coded phy and extended advertising`() {
        assertTrue(BleLongRangePolicy.shouldAdvertiseCoded(BleLongRangeMode.MAX_RANGE, coded))
        assertTrue(BleLongRangePolicy.shouldUseExtendedScan(BleLongRangeMode.ADAPTIVE, coded, false))

        assertFalse(BleLongRangePolicy.shouldAdvertiseCoded(BleLongRangeMode.MAX_RANGE, legacyOnly))
        assertFalse(BleLongRangePolicy.shouldUseExtendedScan(BleLongRangeMode.MAX_RANGE, legacyOnly, false))
        assertFalse(
            BleLongRangePolicy.shouldAdvertiseCoded(
                BleLongRangeMode.MAX_RANGE,
                coded.copy(leExtendedAdvertising = false)
            )
        )
    }

    @Test
    fun `off mode keeps stock legacy behavior even on coded hardware`() {
        assertFalse(BleLongRangePolicy.shouldAdvertiseCoded(BleLongRangeMode.OFF, coded))
        assertFalse(BleLongRangePolicy.shouldUseExtendedScan(BleLongRangeMode.OFF, coded, false))
        assertFalse(BleLongRangePolicy.shouldRequestCodedOnConnect(BleLongRangeMode.OFF, coded))
        assertEquals(
            BluetoothDevice.PHY_LE_1M_MASK,
            BleLongRangePolicy.connectPhyMask(BleLongRangeMode.OFF, coded, BluetoothDevice.PHY_LE_CODED)
        )
    }

    @Test
    fun `runtime rejection of extended scan falls back to legacy scanning`() {
        assertFalse(BleLongRangePolicy.shouldUseExtendedScan(BleLongRangeMode.MAX_RANGE, coded, true))
    }

    @Test
    fun `advertisement too large for the controller disables coded advertising`() {
        val shortData = coded.copy(maxAdvertisingDataLength = BleLongRangePolicy.CODED_ADVERTISEMENT_BYTES - 1)
        assertFalse(BleLongRangePolicy.shouldAdvertiseCoded(BleLongRangeMode.MAX_RANGE, shortData))
    }

    @Test
    fun `connection initiates on the phy the advertisement was heard on`() {
        assertEquals(
            BluetoothDevice.PHY_LE_CODED_MASK,
            BleLongRangePolicy.connectPhyMask(BleLongRangeMode.MAX_RANGE, coded, BluetoothDevice.PHY_LE_CODED)
        )
        assertEquals(
            BluetoothDevice.PHY_LE_1M_MASK,
            BleLongRangePolicy.connectPhyMask(BleLongRangeMode.MAX_RANGE, coded, BluetoothDevice.PHY_LE_1M)
        )
        assertEquals(
            BluetoothDevice.PHY_LE_1M_MASK or BluetoothDevice.PHY_LE_CODED_MASK,
            BleLongRangePolicy.connectPhyMask(BleLongRangeMode.MAX_RANGE, coded, null)
        )
        assertEquals(
            BluetoothDevice.PHY_LE_1M_MASK,
            BleLongRangePolicy.connectPhyMask(BleLongRangeMode.MAX_RANGE, legacyOnly, null)
        )
    }

    @Test
    fun `only max range requests coded on connect`() {
        assertTrue(BleLongRangePolicy.shouldRequestCodedOnConnect(BleLongRangeMode.MAX_RANGE, coded))
        assertFalse(BleLongRangePolicy.shouldRequestCodedOnConnect(BleLongRangeMode.ADAPTIVE, coded))
        assertFalse(BleLongRangePolicy.shouldRequestCodedOnConnect(BleLongRangeMode.MAX_RANGE, legacyOnly))
    }

    @Test
    fun `weak coded advertisements are accepted while stock thresholds are kept when off`() {
        val balanced = PowerManager.PowerMode.BALANCED
        assertEquals(-85, BleLongRangePolicy.connectRssiThreshold(BleLongRangeMode.OFF, balanced, heardOnCoded = true))
        assertEquals(-90, BleLongRangePolicy.connectRssiThreshold(BleLongRangeMode.MAX_RANGE, balanced, heardOnCoded = false))
        assertTrue(BleLongRangePolicy.connectRssiThreshold(BleLongRangeMode.MAX_RANGE, balanced, heardOnCoded = true) <= -100)
    }

    @Test
    fun `unknown persisted mode falls back to default`() {
        assertEquals(BleLongRangeMode.DEFAULT, BleLongRangeMode.fromName("bogus"))
        assertEquals(BleLongRangeMode.DEFAULT, BleLongRangeMode.fromName(null))
        assertEquals(BleLongRangeMode.ADAPTIVE, BleLongRangeMode.fromName("ADAPTIVE"))
    }
}

class PhyHysteresisTest {

    @Test
    fun `weak smoothed rssi moves link to coded and strong rssi moves it back after dwell`() {
        val controller = PhyHysteresis(enterCodedBelowDbm = -80, leaveCodedAboveDbm = -66, minDwellMs = 10_000L, smoothing = 1.0)

        assertNull(controller.onRssi(-70, currentlyCoded = false, nowMs = 0L))
        assertEquals(PhyHysteresis.Target.CODED, controller.onRssi(-85, currentlyCoded = false, nowMs = 5_000L))
        // Inside the dwell window nothing changes even if the signal recovers.
        assertNull(controller.onRssi(-50, currentlyCoded = true, nowMs = 10_000L))
        assertEquals(PhyHysteresis.Target.ONE_M, controller.onRssi(-50, currentlyCoded = true, nowMs = 15_001L))
    }

    @Test
    fun `rssi between thresholds keeps the current phy`() {
        val controller = PhyHysteresis(smoothing = 1.0, minDwellMs = 0L)

        assertNull(controller.onRssi(-72, currentlyCoded = true, nowMs = 0L))
        assertNull(controller.onRssi(-72, currentlyCoded = false, nowMs = 1L))
    }

    @Test
    fun `single fade is smoothed out`() {
        val controller = PhyHysteresis(smoothing = 0.3, minDwellMs = 0L)

        assertNull(controller.onRssi(-60, currentlyCoded = false, nowMs = 0L))
        // One deep sample: -60 + 0.3 * (-95 + 60) = -70.5, still above the -80 entry threshold.
        assertNull(controller.onRssi(-95, currentlyCoded = false, nowMs = 5_000L))
    }
}

class BluetoothConnectionTrackerPeerReservationTest {

    @Test
    fun `second address for the same peer is refused while the first attempt is live`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val tracker = BluetoothConnectionTracker(scope, org.mockito.kotlin.mock<PowerManager>())

        assertTrue(tracker.reservePeerConnectionAttempt("peer", "AA", nowMs = 0L))
        assertTrue(tracker.addPendingConnection("AA"))
        assertFalse(tracker.reservePeerConnectionAttempt("peer", "BB", nowMs = 1_000L))
        // Same address may re-reserve (retries).
        assertTrue(tracker.reservePeerConnectionAttempt("peer", "AA", nowMs = 2_000L))

        tracker.releasePeerReservation("AA")
        assertTrue(tracker.reservePeerConnectionAttempt("peer", "BB", nowMs = 3_000L))
    }

    @Test
    fun `stale reservation expires`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val tracker = BluetoothConnectionTracker(scope, org.mockito.kotlin.mock<PowerManager>())

        assertTrue(tracker.reservePeerConnectionAttempt("peer", "AA", nowMs = 0L))
        tracker.addPendingConnection("AA")
        assertTrue(
            tracker.reservePeerConnectionAttempt(
                "peer",
                "BB",
                nowMs = BluetoothConnectionTracker.PEER_RESERVATION_MS + 1
            )
        )
    }

    @Test
    fun `connected address keeps blocking a second address however old the reservation`() {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        val tracker = BluetoothConnectionTracker(scope, org.mockito.kotlin.mock<PowerManager>())
        val device = org.mockito.kotlin.mock<android.bluetooth.BluetoothDevice>()

        assertTrue(tracker.reservePeerConnectionAttempt("peer", "AA", nowMs = 0L))
        tracker.addDeviceConnection("AA", BluetoothConnectionTracker.DeviceConnection(device = device, linkID = "link-a"))

        assertFalse(
            tracker.reservePeerConnectionAttempt(
                "peer",
                "BB",
                nowMs = BluetoothConnectionTracker.PEER_RESERVATION_MS * 10
            )
        )
    }
}
