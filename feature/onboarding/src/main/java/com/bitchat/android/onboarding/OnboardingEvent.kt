package com.bitchat.android.onboarding

/**
 * A system dialog or screen for the onboarding route to open. Each result comes
 * back to the view model as an [OnboardingAction].
 */
sealed interface OnboardingEvent {
    data object LaunchBluetoothEnable : OnboardingEvent
    data object LaunchLocationSettings : OnboardingEvent
    data object LaunchBatteryOptimization : OnboardingEvent
    data class RequestPermissions(val permissions: List<String>) : OnboardingEvent
    data class RequestBackgroundLocation(val permission: String) : OnboardingEvent
    data object OpenAppSettings : OnboardingEvent
}
