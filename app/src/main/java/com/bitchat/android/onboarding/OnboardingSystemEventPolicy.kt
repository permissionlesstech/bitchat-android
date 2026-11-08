package com.bitchat.android.onboarding

/**
 * Decides what a Bluetooth or location system event may do in each onboarding state.
 * These events arrive repeatedly and at any time, including long after onboarding finished.
 */
internal object OnboardingSystemEventPolicy {
    enum class BluetoothEnabledAction {
        ADVANCE_ONBOARDING,
        ENSURE_MESH_STARTED,
        NONE
    }

    // Permission, error and startup states re-check Bluetooth and location when they finish.
    private val BLUETOOTH_REQUIRED_STATES = setOf(
        OnboardingState.BLUETOOTH_CHECK,
        OnboardingState.LOCATION_CHECK,
        OnboardingState.BATTERY_OPTIMIZATION_CHECK,
        OnboardingState.INITIALIZING,
        OnboardingState.COMPLETE
    )
    private val LOCATION_REQUIRED_STATES = BLUETOOTH_REQUIRED_STATES - OnboardingState.BLUETOOTH_CHECK

    fun onBluetoothEnabled(state: OnboardingState): BluetoothEnabledAction = when (state) {
        OnboardingState.BLUETOOTH_CHECK -> BluetoothEnabledAction.ADVANCE_ONBOARDING
        // Nothing else restarts BLE when the adapter returns, for example after the check was skipped.
        OnboardingState.INITIALIZING, OnboardingState.COMPLETE -> BluetoothEnabledAction.ENSURE_MESH_STARTED
        else -> BluetoothEnabledAction.NONE
    }

    fun advancesOnLocationEnabled(state: OnboardingState): Boolean = state == OnboardingState.LOCATION_CHECK

    fun returnsToBluetoothCheck(state: OnboardingState): Boolean = state in BLUETOOTH_REQUIRED_STATES

    fun returnsToLocationCheck(state: OnboardingState): Boolean = state in LOCATION_REQUIRED_STATES
}
