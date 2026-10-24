package com.bitchat.android

import com.bitchat.android.onboarding.BluetoothSkipSession
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.FakeDeviceReadiness
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.SystemEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app gate: whether the app shows onboarding, initializes, or is ready.
 * Ids are from the Phase 9 inventory; F1 is the bug the gate removes by
 * construction (init once per entry into INITIALIZING, a readiness drop during
 * init ends in onboarding).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppStartupGateTest {

    private val readiness = FakeDeviceReadiness()
    private val skip = BluetoothSkipSession()

    private fun TestScope.gate() = AppStartupGate(readiness, skip, this)

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.readyGate(): AppStartupGate {
        val gate = gate()
        gate.start()
        advance(500)
        assertTrue(gate.takeInitRequest())
        gate.initSucceeded()
        assertEquals(AppPhase.READY, gate.phase.value)
        return gate
    }

    @Test
    fun returningReady_goesToInitializingThenReady() = runTest {
        val gate = gate()
        assertEquals(AppPhase.CHECKING, gate.phase.value)

        gate.start()
        advance(499)
        assertEquals(AppPhase.CHECKING, gate.phase.value)
        advance(1)
        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        assertTrue(gate.takeInitRequest())

        gate.initSucceeded()
        assertEquals(AppPhase.READY, gate.phase.value)
    }

    /**
     * Review Focus 2: after process death the saved back stack restores
     * onboarding while the gate starts fresh. The gate must land on onboarding
     * when readiness says so, never on READY and never without a decision.
     */
    @Test
    fun coldStartWithBluetoothOff_goesToOnboarding() = runTest {
        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        val gate = gate()

        gate.start()
        advance(500)

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
        assertFalse(gate.takeInitRequest())
    }

    @Test
    fun firstLaunch_goesToOnboarding() = runTest {
        readiness.readiness = readiness.readiness.copy(firstLaunch = true)
        val gate = gate()

        gate.start()
        advance(500)

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun t3_startTwiceOrAfterChecking_decidesOnce() = runTest {
        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        val gate = gate()

        gate.start()
        advance(300)
        gate.start() // a recreated Activity starts the gate again
        advance(200)
        assertEquals(AppPhase.ONBOARDING, gate.phase.value)

        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.ENABLED)
        gate.start()
        advance(500)
        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun onboardingFinishedTwice_initRequestedOnce() = runTest {
        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        val gate = gate()
        gate.start()
        advance(500)

        gate.onboardingFinished()
        gate.onboardingFinished()

        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        assertTrue(gate.takeInitRequest())
        assertFalse(gate.takeInitRequest())
    }

    @Test
    fun readinessDropDuringInit_endsInOnboarding() = runTest {
        val gate = gate()
        gate.start()
        advance(500)
        assertEquals(AppPhase.INITIALIZING, gate.phase.value)

        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        gate.initSucceeded()

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun locationDropDuringInit_endsInOnboarding() = runTest {
        val gate = gate()
        gate.start()
        advance(500)

        readiness.readiness = readiness.readiness.copy(location = LocationStatus.DISABLED)
        gate.initSucceeded()

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun bluetoothSkippedDuringInit_endsReady() = runTest {
        val gate = gate()
        gate.start()
        advance(500)

        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        skip.skip()
        gate.initSucceeded()

        assertEquals(AppPhase.READY, gate.phase.value)
    }

    @Test
    fun readyThenBluetoothOff_goesToOnboarding() = runTest {
        val gate = readyGate()
        // T12 ignores the session skip and the debug BLE switch, as it does today.
        skip.skip()
        readiness.readiness = readiness.readiness.copy(bleRequired = false)

        gate.onSystemEvent(SystemEvent.BluetoothOff)

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun t19_readyThenLocationOff_goesToOnboarding() = runTest {
        val gate = readyGate()

        gate.onSystemEvent(SystemEvent.LocationChanged(enabled = false))

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun readyThenRadioTurnedOn_staysReady() = runTest {
        val gate = readyGate()

        gate.onSystemEvent(SystemEvent.BluetoothOn)
        gate.onSystemEvent(SystemEvent.LocationChanged(enabled = true))

        assertEquals(AppPhase.READY, gate.phase.value)
        assertFalse(gate.takeInitRequest())
    }

    @Test
    fun systemEventWhileNotReady_ignored() = runTest {
        val gate = gate()
        gate.start()
        advance(500)
        assertEquals(AppPhase.INITIALIZING, gate.phase.value)

        gate.onSystemEvent(SystemEvent.BluetoothOff)

        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
    }

    @Test
    fun t42_readyResumedWithBluetoothOff_goesToOnboarding() = runTest {
        val gate = readyGate()
        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)

        gate.onResumed()

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun readyResumedWithBluetoothSkipped_staysReady() = runTest {
        val gate = readyGate()
        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        skip.skip()

        gate.onResumed()

        assertEquals(AppPhase.READY, gate.phase.value)
    }

    @Test
    fun t42_readyResumedWithBleSwitchedOff_staysReady() = runTest {
        val gate = readyGate()
        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED, bleRequired = false)

        gate.onResumed()

        assertEquals(AppPhase.READY, gate.phase.value)
    }

    @Test
    fun t43_readyResumedWithLocationOff_goesToOnboarding() = runTest {
        val gate = readyGate()
        readiness.readiness = readiness.readiness.copy(location = LocationStatus.NOT_AVAILABLE)

        gate.onResumed()

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun t43_readyResumedAllReady_staysReady() = runTest {
        val gate = readyGate()

        gate.onResumed()

        assertEquals(AppPhase.READY, gate.phase.value)
    }

    @Test
    fun p6_permissionsRevoked_initFailedWithMessage() = runTest {
        val gate = gate()
        gate.start()
        advance(500)

        gate.initFailed("Some permissions were revoked. Please grant all permissions to continue.")

        assertEquals(AppPhase.INIT_FAILED, gate.phase.value)
        assertEquals(
            "Some permissions were revoked. Please grant all permissions to continue.",
            gate.initError.value,
        )
    }

    @Test
    fun p6_initThrew_initFailedWithMessage() = runTest {
        val gate = gate()
        gate.start()
        advance(500)

        gate.initFailed("Failed to initialize the app: synthetic failure")

        assertEquals(AppPhase.INIT_FAILED, gate.phase.value)
        assertEquals("Failed to initialize the app: synthetic failure", gate.initError.value)
    }

    @Test
    fun t44_initCancelled_initFailedWithCancellationMessage_currentBehaviour() = runTest {
        // initializeApp's catch (e: Exception) also catches the cancellation and reports it here.
        val gate = gate()
        gate.start()
        advance(500)

        gate.initFailed("Failed to initialize the app: Job was cancelled")

        assertEquals(AppPhase.INIT_FAILED, gate.phase.value)
        assertEquals("Failed to initialize the app: Job was cancelled", gate.initError.value)
    }

    @Test
    fun retry_returnsToChecking() = runTest {
        val gate = gate()
        gate.start()
        advance(500)
        gate.takeInitRequest()
        gate.initFailed("Failed to initialize the app: synthetic failure")

        gate.retry()

        assertEquals(AppPhase.CHECKING, gate.phase.value)
        assertNull(gate.initError.value)
        advance(499)
        assertEquals(AppPhase.CHECKING, gate.phase.value)
        advance(1)
        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        assertTrue(gate.takeInitRequest())
    }

    @Test
    fun t40_retryFromOnboardingError_returnsToChecking() = runTest {
        readiness.readiness = readiness.readiness.copy(firstLaunch = true)
        val gate = gate()
        gate.start()
        advance(500)

        gate.retry()

        assertEquals(AppPhase.CHECKING, gate.phase.value)
        advance(500)
        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun onboardingFinishedAfterInitFailed_initializesAgain() = runTest {
        // An init error screen that Bluetooth or location events moved on from, as today.
        val gate = gate()
        gate.start()
        advance(500)
        gate.takeInitRequest()
        gate.initFailed("Failed to initialize the app: synthetic failure")

        gate.onboardingFinished()

        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        assertNull(gate.initError.value)
        assertTrue(gate.takeInitRequest())
    }
}
