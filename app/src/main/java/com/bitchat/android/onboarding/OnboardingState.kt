package com.bitchat.android.onboarding

enum class OnboardingState {
    CHECKING,
    BLUETOOTH_CHECK,
    LOCATION_CHECK,
    BATTERY_OPTIMIZATION_CHECK,
    PERMISSION_EXPLANATION,
    PERMISSION_REQUESTING,
    INITIALIZING,
    COMPLETE,
    ERROR
}