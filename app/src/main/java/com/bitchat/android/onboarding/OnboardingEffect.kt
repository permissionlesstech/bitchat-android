package com.bitchat.android.onboarding

/**
 * A system dialog or screen for the onboarding route to open. Each result comes
 * back to the view model as an [OnboardingAction].
 */
sealed interface OnboardingEffect {
    data object LaunchBluetoothEnable : OnboardingEffect
    data object LaunchLocationSettings : OnboardingEffect
    data object LaunchBatteryOptimization : OnboardingEffect
    data class RequestPermissions(val permissions: List<String>) : OnboardingEffect
    data class RequestBackgroundLocation(val permission: String) : OnboardingEffect
    data object OpenAppSettings : OnboardingEffect
}
