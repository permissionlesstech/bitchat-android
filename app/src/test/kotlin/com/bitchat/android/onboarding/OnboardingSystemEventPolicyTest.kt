package com.bitchat.android.onboarding

import com.bitchat.android.onboarding.OnboardingSystemEventPolicy.BluetoothEnabledAction
import org.junit.Assert.assertEquals
import org.junit.Test

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
}
