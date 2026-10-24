package com.bitchat.android.onboarding

import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [DeviceReadiness] for the onboarding view model's tests. Every read is a
 * mutable field, set up by default as a returning user whose device is ready:
 * Bluetooth and location on, battery optimization already off, every required
 * permission granted and background location granted. Every write is appended
 * to [effects] as a string, in the order it was made; the tests add the view
 * model's effects and host calls to the same list.
 */
class FakeOnboardingReadiness : DeviceReadiness {

    var bluetooth = BluetoothStatus.ENABLED
    var location = LocationStatus.ENABLED
    var battery = BatteryOptimizationStatus.DISABLED

    var firstTimeLaunch = false
    var bleRequired = true
    var batterySkipped = false

    var required = listOf(BLUETOOTH_SCAN, BLUETOOTH_CONNECT, COARSE_LOCATION, FINE_LOCATION)
    val granted = required.toMutableSet()
    var unrequestedOptional = listOf<String>()

    var needsBackgroundLocation = true
    var backgroundLocationGranted = true
    var backgroundLocationSkipped = false
    var backgroundPermission: String? = BACKGROUND_LOCATION

    val effects = mutableListOf<String>()

    /** What a test sends here reaches the view model as a radio change. */
    val events = MutableSharedFlow<SystemEvent>()
    override val systemEvents = events

    override fun snapshot() = Readiness(
        bluetooth = bluetooth,
        location = location,
        batteryOptimization = battery,
        requiredPermissionsGranted = missing().isEmpty(),
        missingPermissions = missing(),
        unrequestedOptionalPermissions = unrequestedOptional,
        backgroundLocation = when {
            !needsBackgroundLocation -> BackgroundLocation.NOT_NEEDED
            backgroundLocationGranted -> BackgroundLocation.GRANTED
            else -> BackgroundLocation.NEEDED
        },
        firstLaunch = firstTimeLaunch,
        batterySkipped = batterySkipped,
        backgroundLocationSkipped = backgroundLocationSkipped,
        bleRequired = bleRequired,
    )

    private fun missing() = required.filterNot { it in granted }

    override fun requiredPermissions() = required
    override fun optionalPermissions() = emptyList<String>()
    override fun backgroundLocationPermission() = backgroundPermission
    override fun wifiAwarePermissions() = emptyList<String>()
    override fun isPermissionGranted(p: String) = p in granted

    override fun markOnboardingComplete() {
        effects += "markOnboardingComplete"
        firstTimeLaunch = false
    }
    override fun markOptionalPermissionsRequested(p: Collection<String>) {
        effects += "markOptionalPermissionsRequested:$p"
        unrequestedOptional = unrequestedOptional - p.toSet()
    }
    override fun setBatterySkipped() {
        effects += "setBatterySkipped"
        batterySkipped = true
    }
    override fun setBackgroundLocationSkipped() {
        effects += "setBackgroundLocationSkipped"
        backgroundLocationSkipped = true
    }

    companion object {
        const val BLUETOOTH_SCAN = "android.permission.BLUETOOTH_SCAN"
        const val BLUETOOTH_CONNECT = "android.permission.BLUETOOTH_CONNECT"
        const val COARSE_LOCATION = "android.permission.ACCESS_COARSE_LOCATION"
        const val FINE_LOCATION = "android.permission.ACCESS_FINE_LOCATION"
        const val BACKGROUND_LOCATION = "android.permission.ACCESS_BACKGROUND_LOCATION"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    }
}
