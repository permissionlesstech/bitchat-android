package com.bitchat.android

import androidx.lifecycle.ViewModel
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.OnboardingState
import com.bitchat.android.onboarding.BatteryOptimizationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MainViewModel : ViewModel() {

    private val _onboardingState = MutableStateFlow(OnboardingState.CHECKING)
    val onboardingState: StateFlow<OnboardingState> = _onboardingState.asStateFlow()

    private val _bluetoothStatus = MutableStateFlow(BluetoothStatus.ENABLED)
    val bluetoothStatus: StateFlow<BluetoothStatus> = _bluetoothStatus.asStateFlow()

    private val _locationStatus = MutableStateFlow(LocationStatus.ENABLED)
    val locationStatus: StateFlow<LocationStatus> = _locationStatus.asStateFlow()

    private val _errorMessage = MutableStateFlow("")
    val errorMessage: StateFlow<String> = _errorMessage.asStateFlow()

    private val _isBluetoothLoading = MutableStateFlow(false)
    val isBluetoothLoading: StateFlow<Boolean> = _isBluetoothLoading.asStateFlow()

    private val _isLocationLoading = MutableStateFlow(false)
    val isLocationLoading: StateFlow<Boolean> = _isLocationLoading.asStateFlow()

    private val _batteryOptimizationStatus = MutableStateFlow(BatteryOptimizationStatus.ENABLED)
    val batteryOptimizationStatus: StateFlow<BatteryOptimizationStatus> = _batteryOptimizationStatus.asStateFlow()

    private val _isBatteryOptimizationLoading = MutableStateFlow(false)
    val isBatteryOptimizationLoading: StateFlow<Boolean> = _isBatteryOptimizationLoading.asStateFlow()

    // Public update functions for MainActivity
    fun updateOnboardingState(state: OnboardingState) {
        _onboardingState.value = state
    }

    fun updateBluetoothStatus(status: BluetoothStatus) {
        _bluetoothStatus.value = status
    }

    fun updateLocationStatus(status: LocationStatus) {
        _locationStatus.value = status
    }

    fun updateErrorMessage(message: String) {
        _errorMessage.value = message
    }

    fun updateBluetoothLoading(loading: Boolean) {
        _isBluetoothLoading.value = loading
    }

    fun updateLocationLoading(loading: Boolean) {
        _isLocationLoading.value = loading
    }

    fun updateBatteryOptimizationStatus(status: BatteryOptimizationStatus) {
        _batteryOptimizationStatus.value = status
    }

    fun updateBatteryOptimizationLoading(loading: Boolean) {
        _isBatteryOptimizationLoading.value = loading
    }
}