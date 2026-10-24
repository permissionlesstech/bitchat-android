package com.bitchat.android

import com.bitchat.android.onboarding.BluetoothSkipSession
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.FakeDeviceReadiness
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.SystemEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
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
    private val initializer = FakeAppInitializer()

    private fun TestScope.gate(scope: CoroutineScope = this) =
        AppStartupGate(readiness, skip, initializer, scope)

    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private fun TestScope.readyGate(): AppStartupGate {
        val gate = gate()
        gate.start()
        advance(500 + INIT_MS)
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

        // P6: 1000 ms, the libraries, the permission re-check, the services, 500 ms.
        advance(1000)
        assertEquals(1, initializer.librariesInitialized)
        assertEquals(1, initializer.servicesStarted)
        advance(499)
        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        advance(1)
        assertEquals(AppPhase.READY, gate.phase.value)
        assertTrue(gate.takeInitCompleted())
        assertFalse(gate.takeInitCompleted())
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

        advance(INIT_MS)
        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
        assertEquals(0, initializer.servicesStarted)
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
    fun onboardingFinishedTwice_initializesOnce() = runTest {
        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        val gate = gate()
        gate.start()
        advance(500)
        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.ENABLED)

        gate.onboardingFinished()
        gate.onboardingFinished()

        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        advance(INIT_MS)
        assertEquals(1, initializer.servicesStarted)
        assertEquals(AppPhase.READY, gate.phase.value)
    }

    @Test
    fun readinessDropDuringInit_endsInOnboarding() = runTest {
        val gate = gate()
        gate.start()
        advance(500)
        assertEquals(AppPhase.INITIALIZING, gate.phase.value)

        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        advance(INIT_MS)

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun locationDropDuringInit_endsInOnboarding() = runTest {
        val gate = gate()
        gate.start()
        advance(500)

        readiness.readiness = readiness.readiness.copy(location = LocationStatus.DISABLED)
        advance(INIT_MS)

        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
    }

    @Test
    fun bluetoothSkippedDuringInit_endsReady() = runTest {
        val gate = gate()
        gate.start()
        advance(500)

        readiness.readiness = readiness.readiness.copy(bluetooth = BluetoothStatus.DISABLED)
        skip.skip()
        advance(INIT_MS)

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
        assertEquals(1, initializer.servicesStarted)
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

        readiness.readiness = readiness.readiness.copy(
            requiredPermissionsGranted = false,
            missingPermissions = listOf("android.permission.BLUETOOTH_SCAN"),
        )
        advance(INIT_MS)

        assertEquals(AppPhase.INIT_FAILED, gate.phase.value)
        assertEquals(0, initializer.servicesStarted)
        assertEquals(
            "Some permissions were revoked. Please grant all permissions to continue.",
            gate.initError.value,
        )
    }

    @Test
    fun p6_initThrew_initFailedWithMessage() = runTest {
        val gate = gate()
        initializer.failure = IllegalStateException("synthetic failure")
        gate.start()
        advance(500 + INIT_MS)

        assertEquals(AppPhase.INIT_FAILED, gate.phase.value)
        assertEquals("Failed to initialize the app: synthetic failure", gate.initError.value)
        assertFalse(gate.takeInitCompleted())
    }

    /**
     * F2: init runs on the gate's retained scope, so recreating the Activity
     * mid-init neither cancels it nor reports an error. Recreation is the
     * Activity calling start() again; the gate is the same instance.
     */
    @Test
    fun initSurvivesActivityRecreation_noInitFailed() = runTest {
        val gate = gate()
        gate.start()
        advance(500 + 700) // inside P6's first delay

        gate.start() // the recreated Activity
        advance(INIT_MS - 700)

        assertEquals(AppPhase.READY, gate.phase.value)
        assertNull(gate.initError.value)
        assertEquals(1, initializer.servicesStarted)
        assertTrue(gate.takeInitCompleted())
    }

    @Test
    fun t44_initCancelled_noInitFailed() = runTest {
        // The retained scope is cancelled when the Activity is finished for good.
        // A cancelled init is not an init failure.
        val gateScope = CoroutineScope(coroutineContext + SupervisorJob())
        val gate = gate(gateScope)
        gate.start()
        advance(500 + 700)

        gateScope.cancel()
        advance(INIT_MS)

        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        assertNull(gate.initError.value)
        assertEquals(0, initializer.servicesStarted)
    }

    @Test
    fun retry_returnsToChecking() = runTest {
        initializer.failure = IllegalStateException("synthetic failure")
        val gate = gate()
        gate.start()
        advance(500 + INIT_MS)
        assertEquals(AppPhase.INIT_FAILED, gate.phase.value)
        initializer.failure = null

        val showsOnboarding = async { gate.retry() }
        runCurrent()

        assertEquals(AppPhase.CHECKING, gate.phase.value)
        assertTrue(gate.retrying.value)
        assertNull(gate.initError.value)
        advance(499)
        assertEquals(AppPhase.CHECKING, gate.phase.value)
        advance(1)
        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        assertFalse(gate.retrying.value)
        assertFalse(showsOnboarding.await())
        advance(INIT_MS)
        assertEquals(AppPhase.READY, gate.phase.value)
    }

    @Test
    fun t40_retryFromOnboardingError_returnsToChecking() = runTest {
        readiness.readiness = readiness.readiness.copy(firstLaunch = true)
        val gate = gate()
        gate.start()
        advance(500)

        val showsOnboarding = async { gate.retry() }
        runCurrent()

        assertEquals(AppPhase.CHECKING, gate.phase.value)
        assertTrue(gate.retrying.value)
        advance(500)
        assertEquals(AppPhase.ONBOARDING, gate.phase.value)
        assertFalse(gate.retrying.value)
        assertTrue(showsOnboarding.await())
    }

    @Test
    fun coldStart_isNotRetrying() = runTest {
        val gate = gate()
        gate.start()
        runCurrent()

        assertEquals(AppPhase.CHECKING, gate.phase.value)
        assertFalse(gate.retrying.value)
    }

    @Test
    fun onboardingFinishedAfterInitFailed_initializesAgain() = runTest {
        // An init error screen that Bluetooth or location events moved on from, as today.
        initializer.failure = IllegalStateException("synthetic failure")
        val gate = gate()
        gate.start()
        advance(500 + INIT_MS)
        assertEquals(AppPhase.INIT_FAILED, gate.phase.value)
        initializer.failure = null

        gate.onboardingFinished()

        assertEquals(AppPhase.INITIALIZING, gate.phase.value)
        assertNull(gate.initError.value)
        advance(INIT_MS)
        assertEquals(AppPhase.READY, gate.phase.value)
        assertEquals(2, initializer.librariesInitialized)
    }

    private companion object {
        /** P6's two delays, 1000 ms and 500 ms. */
        const val INIT_MS = 1500L
    }
}

/** Counts what init asked of the app; throws [failure] from the libraries step when set. */
private class FakeAppInitializer : AppInitializer {
    var librariesInitialized = 0
    var servicesStarted = 0
    var failure: Exception? = null

    override fun initializeLibraries() {
        librariesInitialized++
        failure?.let { throw it }
    }

    override fun startServices() {
        servicesStarted++
    }
}
