package com.bitchat

import com.bitchat.android.MainViewModel
import com.bitchat.android.onboarding.BatteryOptimizationStatus
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.OnboardingState
import org.junit.Assert.assertEquals
import org.junit.Test

class MainViewModelTest {
    private val viewModel = MainViewModel()

    @Test
    fun shouldUpdateOnboardingStateCorrectly() {
        val cases = listOf(
            OnboardingState.ERROR,
            OnboardingState.CHECKING,
            OnboardingState.BLUETOOTH_CHECK,
            OnboardingState.LOCATION_CHECK,
            OnboardingState.BATTERY_OPTIMIZATION_CHECK,
            OnboardingState.PERMISSION_REQUESTING,
            OnboardingState.PERMISSION_EXPLANATION,
            OnboardingState.COMPLETE,
            OnboardingState.INITIALIZING
        )

        cases.forEach { input ->
            viewModel.updateOnboardingState(input)
            assertEquals(input, viewModel.onboardingState.value)
        }
    }

    @Test
    fun shouldUpdateLocationStatusCorrectly() {
        val cases = listOf(
            LocationStatus.ENABLED,
            LocationStatus.DISABLED,
            LocationStatus.NOT_AVAILABLE
        )

        cases.forEach { input ->
            viewModel.updateLocationStatus(input)
            assertEquals(input, viewModel.locationStatus.value)
        }
    }

    @Test
    fun shouldUpdateErrorMessageCorrectly() {
        val errorMessage = "Error message"

        viewModel.updateErrorMessage(errorMessage)

        assertEquals(errorMessage, viewModel.errorMessage.value)
    }

    @Test
    fun shouldUpdateLocationLoadingCorrectly() {
        val cases = listOf(true, false)

        cases.forEach { input ->
            viewModel.updateLocationLoading(input)
            assertEquals(input, viewModel.isLocationLoading.value)
        }
    }

    @Test
    fun shouldUpdateBatteryOptimizationStatus() {
        val cases = listOf(
            BatteryOptimizationStatus.ENABLED,
            BatteryOptimizationStatus.DISABLED,
            BatteryOptimizationStatus.NOT_SUPPORTED
        )

        cases.forEach { input ->
            viewModel.updateBatteryOptimizationStatus(input)
            assertEquals(input, viewModel.batteryOptimizationStatus.value)
        }
    }

    @Test
    fun shouldUpdateBatteryOptimizationLoadingCorrectly() {
        val cases = listOf(true, false)

        cases.forEach { input ->
            viewModel.updateBatteryOptimizationLoading(input)
            assertEquals(input, viewModel.isBatteryOptimizationLoading.value)
        }
    }

    @Test
    fun shouldUpdateBluetoothLoadingCorrectly() {
        val cases = listOf(true, false)

        cases.forEach { input ->
            viewModel.updateBluetoothLoading(input)
            assertEquals(input, viewModel.isBluetoothLoading.value)
        }
    }
}
