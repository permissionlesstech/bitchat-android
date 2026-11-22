package com.bitchat.android.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

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

/**
 * Coordinates single-flight app initialization so overlapping onboarding callbacks coalesce while
 * [OnboardingState.INITIALIZING] stays active, while leaving that state cancels any in-flight
 * attempt so a later recovery can start fresh instead of being dropped.
 */
internal class OnboardingInitializer(
    private val scope: CoroutineScope
) {
    private var activeJob: Job? = null

    fun start(block: suspend () -> Unit): Boolean {
        if (activeJob?.isActive == true) return false
        val launched = scope.launch {
            try {
                block()
            } finally {
                if (activeJob === coroutineContext[Job]) {
                    activeJob = null
                }
            }
        }
        if (launched.isActive) {
            activeJob = launched
        }
        return true
    }

    fun cancel() {
        val current = activeJob
        activeJob = null
        current?.cancel()
    }
}
