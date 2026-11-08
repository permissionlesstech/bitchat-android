package com.bitchat.android.onboarding

enum class OnboardingStep {
    CHECKING,
    BLUETOOTH,
    LOCATION,
    BATTERY_OPTIMIZATION,
    PERMISSION_EXPLANATION,
    PERMISSION_REQUESTING,
    BACKGROUND_LOCATION,
    ERROR,
}

/** Where the user is in onboarding, which decides how much of the checklist applies. */
enum class OnboardingPhase {
    FIRST_LAUNCH,
    AFTER_PERMISSIONS,
    RETURNING,
}

/**
 * The step onboarding shows next, or null when the device is ready and the app can
 * initialize. Pure: the same readiness, phase and skip always give the same answer.
 */
fun nextStep(r: Readiness, phase: OnboardingPhase, bluetoothSkipped: Boolean): OnboardingStep? {
    if (phase == OnboardingPhase.FIRST_LAUNCH) return OnboardingStep.PERMISSION_EXPLANATION

    if (!bluetoothSkipped && r.bleRequired && r.bluetooth != BluetoothStatus.ENABLED) {
        return OnboardingStep.BLUETOOTH
    }
    if (r.location != LocationStatus.ENABLED) return OnboardingStep.LOCATION
    if (!r.batterySkipped && r.batteryOptimization == BatteryOptimizationStatus.ENABLED) {
        return OnboardingStep.BATTERY_OPTIMIZATION
    }
    if (phase == OnboardingPhase.AFTER_PERMISSIONS) return null

    return when {
        r.unrequestedOptionalPermissions.isNotEmpty() -> OnboardingStep.PERMISSION_EXPLANATION
        r.requiredPermissionsGranted &&
            r.backgroundLocation == BackgroundLocation.NEEDED &&
            !r.backgroundLocationSkipped -> OnboardingStep.BACKGROUND_LOCATION
        !r.requiredPermissionsGranted -> OnboardingStep.PERMISSION_EXPLANATION
        else -> null
    }
}
