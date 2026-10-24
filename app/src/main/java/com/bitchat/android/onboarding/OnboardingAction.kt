package com.bitchat.android.onboarding

/**
 * Every user or system event the onboarding flow reacts to. Screens, dialog
 * results and receivers turn what happened into one of these and hand it to
 * [OnboardingFlow.dispatch].
 */
sealed interface OnboardingAction {

    /** The Activity was created; starts the checks if nothing has run yet. */
    data object Start : OnboardingAction

    // Bluetooth
    data object BluetoothEnableTapped : OnboardingAction
    data object BluetoothSkipTapped : OnboardingAction

    /** The Bluetooth screen's retry callback. The screen never renders it today. */
    data object BluetoothCheckAgainTapped : OnboardingAction
    data class BluetoothResult(val enabled: Boolean) : OnboardingAction
    data class BluetoothLaunchFailed(val message: String) : OnboardingAction

    /**
     * The adapter reported itself on, when monitoring starts or on STATE_ON. Re-runs
     * the Bluetooth check if its screen is showing. STATE_ON is followed by
     * [BluetoothResult] with `enabled = true`, as the receiver does today.
     */
    data object BluetoothTurnedOn : OnboardingAction
    data object BluetoothTurnedOff : OnboardingAction
    data object BluetoothNotSupportedAtStart : OnboardingAction

    // Location services
    data object LocationOpenSettingsTapped : OnboardingAction
    data object LocationCheckAgainTapped : OnboardingAction
    data class LocationResult(val enabled: Boolean) : OnboardingAction
    data class LocationLaunchFailed(val message: String) : OnboardingAction
    data class LocationChanged(val enabled: Boolean) : OnboardingAction

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

    /** The Activity resumed while onboarding was complete. */
    data object ResumedWhileComplete : OnboardingAction
}
