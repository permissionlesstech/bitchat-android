package com.bitchat.android.onboarding

/**
 * Every read and side effect the onboarding flow needs from the device, the
 * stored preferences and the Activity. [OnboardingFlow] makes its decisions
 * through this and nothing else.
 */
interface OnboardingSystem {

    // Status reads
    fun bluetoothStatus(): BluetoothStatus
    fun locationStatus(): LocationStatus
    fun batteryStatus(): BatteryOptimizationStatus

    // First launch
    fun isFirstTimeLaunch(): Boolean
    fun markOnboardingComplete()

    // Runtime permissions
    fun requiredPermissions(): List<String>
    fun missingPermissions(): List<String>
    fun unrequestedOptionalPermissions(): List<String>
    fun markOptionalPermissionsRequested(p: Collection<String>)
    fun isPermissionGranted(p: String): Boolean
    fun areRequiredPermissionsGranted(): Boolean

    // Background location
    fun needsBackgroundLocation(): Boolean
    fun isBackgroundLocationGranted(): Boolean
    fun backgroundLocationPermission(): String?
    fun isBackgroundLocationSkipped(): Boolean
    fun setBackgroundLocationSkipped()

    // Skips and switches
    fun isBatterySkipped(): Boolean
    fun setBatterySkipped()

    /** False when Bluetooth LE is switched off in the debug settings. */
    fun bleRequired(): Boolean

    // Dialog launches; each result comes back as an [OnboardingAction]
    fun launchBluetoothEnable()
    fun launchLocationSettings()
    fun launchBatteryOptimization()
    fun launchPermissions(p: List<String>)
    fun launchBackgroundLocation(p: String)
    fun openAppSettings()

    // Other side effects
    fun startWifiAwareIfPossible()

    /** Starts the app once onboarding is through; reports back through [OnboardingFlow.onInitComplete] or [OnboardingFlow.onInitFailed]. */
    fun initializeApp()

    // Diagnostics logged at the points the flow logged them before. Logging only.
    fun logBluetoothDiagnostics() {}
    fun logLocationDiagnostics() {}
    fun logBatteryDiagnostics() {}
    fun logPermissionDiagnostics() {}
}
