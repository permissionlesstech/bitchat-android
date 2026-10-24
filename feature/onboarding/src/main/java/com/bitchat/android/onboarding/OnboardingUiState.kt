package com.bitchat.android.onboarding

/**
 * What the onboarding route shows. [step] is [OnboardingStep.CHECKING] while no
 * step is on screen: before onboarding has picked one, and once it has finished.
 */
data class OnboardingUiState(
    val step: OnboardingStep = OnboardingStep.CHECKING,
    val bluetooth: BluetoothStatus = BluetoothStatus.ENABLED,
    val location: LocationStatus = LocationStatus.ENABLED,
    val battery: BatteryOptimizationStatus = BatteryOptimizationStatus.ENABLED,
    val bluetoothLoading: Boolean = false,
    val locationLoading: Boolean = false,
    val batteryLoading: Boolean = false,
    val errorMessage: String = "",
    /** The permission explanation's cards, read when that step opens. */
    val permissionCards: List<PermissionCategory> = emptyList(),
)
