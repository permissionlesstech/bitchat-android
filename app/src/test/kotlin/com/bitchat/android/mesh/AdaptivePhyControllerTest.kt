package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptivePhyControllerTest {

    // No smoothing so each sample is the signal itself; thresholds -78 / -65.
    private val tuning = AdaptivePhyTuning(smoothing = 1.0)
    private fun controller() = AdaptivePhyController { tuning }

    @Test
    fun `adaptive is the default mode`() {
        assertEquals(BleLongRangeMode.ADAPTIVE, BleLongRangeMode.DEFAULT)
    }

    @Test
    fun `weak signal moves to coded only after it is confirmed`() {
        val c = controller()

        assertNull(c.onRssi(-80, currentlyCoded = false, nowMs = 0L))
        assertNull(c.onRssi(-80, currentlyCoded = false, nowMs = 2_000L))
        assertEquals(
            AdaptivePhyController.Decision(AdaptivePhyController.Target.CODED, AdaptivePhyController.Reason.WEAK_SIGNAL),
            c.onRssi(-80, currentlyCoded = false, nowMs = 3_000L)
        )
    }

    @Test
    fun `a recovery during confirmation cancels the switch`() {
        val c = controller()

        assertNull(c.onRssi(-80, currentlyCoded = false, nowMs = 0L))
        assertNull(c.onRssi(-70, currentlyCoded = false, nowMs = 2_000L))
        assertNull(c.onRssi(-80, currentlyCoded = false, nowMs = 3_000L))
    }

    @Test
    fun `sudden drop below the emergency level switches at once`() {
        val c = controller()

        assertEquals(
            AdaptivePhyController.Reason.SIGNAL_DROP,
            c.onRssi(tuning.emergencyBelowDbm - 1, currentlyCoded = false, nowMs = 0L)?.reason
        )
    }

    @Test
    fun `returning to 1M needs a strong signal held for the full window`() {
        val c = controller()

        assertNull(c.onRssi(-60, currentlyCoded = true, nowMs = 0L))
        assertNull(c.onRssi(-60, currentlyCoded = true, nowMs = 29_000L))
        // One dip resets the window.
        assertNull(c.onRssi(-70, currentlyCoded = true, nowMs = 29_500L))
        assertNull(c.onRssi(-60, currentlyCoded = true, nowMs = 30_000L))
        assertNull(c.onRssi(-60, currentlyCoded = true, nowMs = 59_000L))
        assertEquals(
            AdaptivePhyController.Target.ONE_M,
            c.onRssi(-60, currentlyCoded = true, nowMs = 60_000L)?.target
        )
    }

    @Test
    fun `signal between thresholds keeps the current phy`() {
        val c = controller()

        assertNull(c.onRssi(-72, currentlyCoded = true, nowMs = 0L))
        assertNull(c.onRssi(-72, currentlyCoded = true, nowMs = 100_000L))
        assertNull(c.onRssi(-72, currentlyCoded = false, nowMs = 200_000L))
    }

    @Test
    fun `repeated send failures on 1M move to coded and a success resets the count`() {
        val c = controller()

        assertNull(c.onSendResult(success = false, currentlyCoded = false))
        assertNull(c.onSendResult(success = false, currentlyCoded = false))
        assertNull(c.onSendResult(success = true, currentlyCoded = false))
        assertNull(c.onSendResult(success = false, currentlyCoded = false))
        assertNull(c.onSendResult(success = false, currentlyCoded = false))
        assertEquals(
            AdaptivePhyController.Reason.SEND_FAILURES,
            c.onSendResult(success = false, currentlyCoded = false)?.reason
        )
        // Already on Coded: failures have nowhere further to go.
        repeat(5) { assertNull(c.onSendResult(success = false, currentlyCoded = true)) }
    }

    @Test
    fun `weak connection goes to coded immediately`() {
        assertEquals(
            AdaptivePhyController.Reason.WEAK_AT_CONNECT,
            controller().onConnected(initialRssi = -85, currentlyCoded = false)?.reason
        )
        assertNull(controller().onConnected(initialRssi = -60, currentlyCoded = false))
        assertNull(controller().onConnected(initialRssi = -85, currentlyCoded = true))
        assertNull(controller().onConnected(initialRssi = null, currentlyCoded = false))
    }

    @Test
    fun `sampling is faster near the relevant threshold`() {
        val c = controller()

        c.onRssi(-50, currentlyCoded = false, nowMs = 0L)
        assertEquals(tuning.slowPollMs, c.pollIntervalMs(currentlyCoded = false))
        c.onRssi(-72, currentlyCoded = false, nowMs = 1L)
        assertEquals(tuning.fastPollMs, c.pollIntervalMs(currentlyCoded = false))
        // On Coded, the relevant threshold is the way back up to 1M.
        c.onRssi(-90, currentlyCoded = true, nowMs = 2L)
        assertEquals(tuning.slowPollMs, c.pollIntervalMs(currentlyCoded = true))
    }

    @Test
    fun `thresholds are kept apart so links cannot oscillate`() {
        val tooClose = AdaptivePhyTuning(enterCodedBelowDbm = -70, leaveCodedAboveDbm = -68).sanitized()
        assertEquals(-70 + AdaptivePhyTuning.MIN_GAP_DB, tooClose.leaveCodedAboveDbm)

        val outOfRange = AdaptivePhyTuning(enterCodedBelowDbm = -200, leaveCodedAboveDbm = 0).sanitized()
        assertEquals(AdaptivePhyTuning.MIN_THRESHOLD_DBM, outOfRange.enterCodedBelowDbm)
        assertEquals(AdaptivePhyTuning.MAX_THRESHOLD_DBM, outOfRange.leaveCodedAboveDbm)
    }
}
