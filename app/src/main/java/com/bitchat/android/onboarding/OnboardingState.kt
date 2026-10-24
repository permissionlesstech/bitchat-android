package com.bitchat.android.onboarding

/**
 * The onboarding step on screen. Whether onboarding shows at all, and the
 * checking, initializing and ready states around it, are [com.bitchat.android.AppPhase].
 */
enum class OnboardingState {
    BLUETOOTH_CHECK,
    LOCATION_CHECK,
    BATTERY_OPTIMIZATION_CHECK,
    PERMISSION_EXPLANATION,
    BACKGROUND_LOCATION_EXPLANATION,
    PERMISSION_REQUESTING,
    ERROR
}
