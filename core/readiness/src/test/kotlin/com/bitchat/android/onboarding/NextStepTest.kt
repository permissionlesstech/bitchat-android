package com.bitchat.android.onboarding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NextStepTest {
    private fun ready() = Readiness(
        bluetooth = BluetoothStatus.ENABLED,
        location = LocationStatus.ENABLED,
        batteryOptimization = BatteryOptimizationStatus.DISABLED,
        requiredPermissionsGranted = true,
        missingPermissions = emptyList(),
        unrequestedOptionalPermissions = emptyList(),
        backgroundLocation = BackgroundLocation.GRANTED,
        firstLaunch = false,
        batterySkipped = false,
        backgroundLocationSkipped = false,
        bleRequired = true,
    )

    private fun returning(r: Readiness, bluetoothSkipped: Boolean = false) =
        nextStep(r, OnboardingPhase.RETURNING, bluetoothSkipped)

    private fun afterPermissions(r: Readiness, bluetoothSkipped: Boolean = false) =
        nextStep(r, OnboardingPhase.AFTER_PERMISSIONS, bluetoothSkipped)

    @Test
    fun firstLaunch_explainsPermissions() {
        assertEquals(
            OnboardingStep.PERMISSION_EXPLANATION,
            nextStep(ready(), OnboardingPhase.FIRST_LAUNCH, false),
        )
    }

    @Test
    fun returning_bluetoothOff_asksForBluetooth() {
        val r = ready().copy(bluetooth = BluetoothStatus.DISABLED)
        assertEquals(OnboardingStep.BLUETOOTH, returning(r))
    }

    @Test
    fun returning_bluetoothSkipped_movesPastBluetooth() {
        val r = ready().copy(bluetooth = BluetoothStatus.DISABLED)
        assertNull(returning(r, bluetoothSkipped = true))
    }

    @Test
    fun returning_bleNotRequired_skipsBluetooth() {
        val r = ready().copy(bluetooth = BluetoothStatus.DISABLED, bleRequired = false)
        assertNull(returning(r))
    }

    @Test
    fun returning_locationOff_asksForLocation() {
        val r = ready().copy(location = LocationStatus.DISABLED)
        assertEquals(OnboardingStep.LOCATION, returning(r))
    }

    @Test
    fun returning_batteryOptimized_asksForBatteryOptimization() {
        val r = ready().copy(batteryOptimization = BatteryOptimizationStatus.ENABLED)
        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, returning(r))
    }

    @Test
    fun returning_batterySkipped_movesPastBattery() {
        val r = ready().copy(
            batteryOptimization = BatteryOptimizationStatus.ENABLED,
            batterySkipped = true,
        )
        assertNull(returning(r))
    }

    @Test
    fun returning_unrequestedOptional_explainsPermissions() {
        val r = ready().copy(unrequestedOptionalPermissions = listOf("optional.permission"))
        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, returning(r))
    }

    @Test
    fun returning_backgroundLocationNeeded_asksForBackgroundLocation() {
        val r = ready().copy(backgroundLocation = BackgroundLocation.NEEDED)
        assertEquals(OnboardingStep.BACKGROUND_LOCATION, returning(r))
    }

    @Test
    fun returning_backgroundLocationSkipped_isReady() {
        val r = ready().copy(
            backgroundLocation = BackgroundLocation.NEEDED,
            backgroundLocationSkipped = true,
        )
        assertNull(returning(r))
    }

    @Test
    fun returning_missingRequiredPermissions_explainsPermissions() {
        val r = ready().copy(
            requiredPermissionsGranted = false,
            missingPermissions = listOf("required.permission"),
        )
        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, returning(r))
    }

    @Test
    fun returning_everythingReady_initializes() {
        assertNull(returning(ready()))
    }

    @Test
    fun returning_bluetoothComesBeforeLocation() {
        val r = ready().copy(
            bluetooth = BluetoothStatus.DISABLED,
            location = LocationStatus.DISABLED,
        )
        assertEquals(OnboardingStep.BLUETOOTH, returning(r))
    }

    @Test
    fun afterPermissions_stopsAfterBatteryStep() {
        val r = ready().copy(
            location = LocationStatus.DISABLED,
            backgroundLocation = BackgroundLocation.NEEDED,
        )
        assertEquals(OnboardingStep.LOCATION, afterPermissions(r))
        assertNull(afterPermissions(ready().copy(backgroundLocation = BackgroundLocation.NEEDED)))
    }

    @Test
    fun afterPermissions_honoursBatterySkip() {
        val r = ready().copy(
            batteryOptimization = BatteryOptimizationStatus.ENABLED,
            batterySkipped = true,
        )
        assertNull(afterPermissions(r))
    }

    @Test
    fun afterPermissions_honoursBluetoothSkip() {
        val r = ready().copy(bluetooth = BluetoothStatus.DISABLED)
        assertNull(afterPermissions(r, bluetoothSkipped = true))
    }
}
