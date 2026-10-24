package com.bitchat.android.onboarding

/**
 * Every tap and dialog result onboarding reacts to. Screens and the route's
 * launchers hand these to [OnboardingViewModel.onAction]. Radio changes arrive
 * as [SystemEvent]s instead.
 */
sealed interface OnboardingAction {

    // Bluetooth
    data object BluetoothEnableTapped : OnboardingAction
    data object BluetoothSkipTapped : OnboardingAction

    /** Runs the Bluetooth check again (P1). The Bluetooth screen has no button for it. */
    data object BluetoothCheckAgainTapped : OnboardingAction
    data class BluetoothResult(val enabled: Boolean) : OnboardingAction
    data class BluetoothLaunchFailed(val message: String) : OnboardingAction

    /** No Bluetooth adapter when onboarding opens: the error step (T2). */
    data object BluetoothNotSupportedAtStart : OnboardingAction

    // Location services
    data object LocationOpenSettingsTapped : OnboardingAction
    data object LocationCheckAgainTapped : OnboardingAction
    data class LocationResult(val enabled: Boolean) : OnboardingAction
    data class LocationLaunchFailed(val message: String) : OnboardingAction

    // Battery optimization
    data object BatteryDisableTapped : OnboardingAction
    data object BatteryCheckAgainTapped : OnboardingAction
    data object BatterySkipTapped : OnboardingAction
    data object BatteryResult : OnboardingAction
    data class BatteryLaunchFailed(val message: String) : OnboardingAction

    // Runtime permissions
    data object GrantPermissionsTapped : OnboardingAction
    data class PermissionResults(val results: Map<String, Boolean>) : OnboardingAction
    data object BackgroundGrantTapped : OnboardingAction
    data class BackgroundResult(val granted: Boolean) : OnboardingAction
    data object BackgroundCheckAgainTapped : OnboardingAction
    data object BackgroundSkipTapped : OnboardingAction

    // Error screen
    data object TryAgainTapped : OnboardingAction
    data object OpenSettingsTapped : OnboardingAction
}
