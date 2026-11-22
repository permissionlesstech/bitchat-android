package com.bitchat.android.onboarding

import com.bitchat.android.onboarding.OnboardingSystemEventPolicy.BluetoothEnabledAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingSystemEventPolicyTest {
    @Test
    fun `bluetooth turning on advances only the bluetooth check and never re-runs onboarding afterwards`() {
        val expected = OnboardingState.entries.associateWith { BluetoothEnabledAction.NONE } +
            mapOf(
                OnboardingState.BLUETOOTH_CHECK to BluetoothEnabledAction.ADVANCE_ONBOARDING,
                OnboardingState.INITIALIZING to BluetoothEnabledAction.ENSURE_MESH_STARTED,
                OnboardingState.COMPLETE to BluetoothEnabledAction.ENSURE_MESH_STARTED
            )

        assertEquals(expected, OnboardingState.entries.associateWith(OnboardingSystemEventPolicy::onBluetoothEnabled))
    }

    @Test
    fun `location turning on advances only the location check`() {
        assertEquals(
            setOf(OnboardingState.LOCATION_CHECK),
            OnboardingState.entries.filter(OnboardingSystemEventPolicy::advancesOnLocationEnabled).toSet()
        )
    }

    @Test
    fun `bluetooth turning off leaves permission error and startup states alone`() {
        assertEquals(
            setOf(
                OnboardingState.BLUETOOTH_CHECK,
                OnboardingState.LOCATION_CHECK,
                OnboardingState.BATTERY_OPTIMIZATION_CHECK,
                OnboardingState.INITIALIZING,
                OnboardingState.COMPLETE
            ),
            OnboardingState.entries.filter(OnboardingSystemEventPolicy::returnsToBluetoothCheck).toSet()
        )
    }

    @Test
    fun `location turning off does not displace the earlier bluetooth check`() {
        assertEquals(
            setOf(
                OnboardingState.LOCATION_CHECK,
                OnboardingState.BATTERY_OPTIMIZATION_CHECK,
                OnboardingState.INITIALIZING,
                OnboardingState.COMPLETE
            ),
            OnboardingState.entries.filter(OnboardingSystemEventPolicy::returnsToLocationCheck).toSet()
        )
    }

    @Test
    fun `parallel initializeApp calls coalesce while an attempt is still active`() = runTest {
        val initializer = OnboardingInitializer(this)
        var starts = 0

        assertTrue(initializer.start {
            delay(1_000)
            starts++
        })
        assertFalse(initializer.start {
            delay(1_000)
            starts++
        })

        advanceUntilIdle()
        assertEquals(1, starts)
    }

    @Test
    fun `bluetooth off then on during initialize delay cancels stale job and runs fresh startServices`() = runTest {
        val initializer = OnboardingInitializer(this)
        var state = OnboardingState.INITIALIZING
        var bluetoothEnabled = true
        var startServicesWhileBluetoothOn = 0
        var startServicesWhileBluetoothOff = 0
        var errors = 0

        fun startInitialize(): Boolean = initializer.start {
            try {
                delay(1_000)
                if (state != OnboardingState.INITIALIZING) return@start
                if (bluetoothEnabled) startServicesWhileBluetoothOn++ else startServicesWhileBluetoothOff++
                delay(500)
                if (state == OnboardingState.INITIALIZING) {
                    state = OnboardingState.COMPLETE
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                errors++
                state = OnboardingState.ERROR
            }
        }

        // Initial start enters the 1s delay.
        assertTrue(startInitialize())
        advanceTimeBy(300)
        runCurrent()

        // Bluetooth drops during the delay: cancel the in-flight attempt and show BLUETOOTH_CHECK.
        bluetoothEnabled = false
        initializer.cancel()
        state = OnboardingState.BLUETOOTH_CHECK
        runCurrent()

        // Bluetooth returns before the original 1.5s window would have finished.
        bluetoothEnabled = true
        state = OnboardingState.INITIALIZING
        assertTrue("recovery start must not be dropped by the cancelled attempt", startInitialize())

        advanceUntilIdle()
        assertEquals(0, startServicesWhileBluetoothOff)
        assertEquals(1, startServicesWhileBluetoothOn)
        assertEquals(0, errors)
        assertEquals(OnboardingState.COMPLETE, state)
    }
}
