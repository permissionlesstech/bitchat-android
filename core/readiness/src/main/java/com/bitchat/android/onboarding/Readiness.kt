package com.bitchat.android.onboarding

/**
 * Everything onboarding decides on, read from the device and the stored choices
 * at one moment. Taken fresh by [DeviceReadiness.snapshot] each time; nothing in
 * it updates itself.
 */
data class Readiness(
    val bluetooth: BluetoothStatus,
    val location: LocationStatus,
    val batteryOptimization: BatteryOptimizationStatus,
    /** Every permission in [DeviceReadiness.requiredPermissions] is granted. */
    val requiredPermissionsGranted: Boolean,
    /** The required permissions not granted, in [DeviceReadiness.requiredPermissions] order. */
    val missingPermissions: List<String>,
    /** Optional permissions neither granted nor asked for before. Each is asked for once. */
    val unrequestedOptionalPermissions: List<String>,
    val backgroundLocation: BackgroundLocation,
    /** Onboarding has never been completed on this install. */
    val firstLaunch: Boolean,
    val batterySkipped: Boolean,
    val backgroundLocationSkipped: Boolean,
    /** False when Bluetooth LE is switched off in the debug settings. */
    val bleRequired: Boolean,
)

/** Background location, which Android 10+ grants separately from foreground location. */
enum class BackgroundLocation {
    /** Below Android 10 there is no separate background location permission. */
    NOT_NEEDED,
    GRANTED,
    NEEDED,
}
