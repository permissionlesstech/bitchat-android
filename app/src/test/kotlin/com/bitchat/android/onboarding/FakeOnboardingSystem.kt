package com.bitchat.android.onboarding

/**
 * An [OnboardingSystem] for JVM tests. Every read is a mutable field, set up by
 * default as a returning user whose device is ready: Bluetooth and location on,
 * battery optimization already off, every required permission granted and
 * background location granted. Every launch and side effect is appended to
 * [effects] as a string, in the order the flow made it.
 */
class FakeOnboardingSystem : OnboardingSystem {

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

    override fun snapshot() = Readiness(
        bluetooth = bluetooth,
        location = location,
        batteryOptimization = battery,
        requiredPermissionsGranted = areRequiredPermissionsGranted(),
        missingPermissions = missingPermissions(),
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

    override fun bluetoothStatus() = bluetooth
    override fun locationStatus() = location
    override fun batteryStatus() = battery

    override fun isFirstTimeLaunch() = firstTimeLaunch
    override fun markOnboardingComplete() {
        effects += "markOnboardingComplete"
        firstTimeLaunch = false
    }

    override fun requiredPermissions() = required
    override fun missingPermissions() = required.filterNot { it in granted }
    override fun unrequestedOptionalPermissions() = unrequestedOptional
    override fun markOptionalPermissionsRequested(p: Collection<String>) {
        effects += "markOptionalPermissionsRequested:$p"
        unrequestedOptional = unrequestedOptional - p.toSet()
    }
    override fun isPermissionGranted(p: String) = p in granted
    override fun areRequiredPermissionsGranted() = missingPermissions().isEmpty()

    override fun needsBackgroundLocation() = needsBackgroundLocation
    override fun isBackgroundLocationGranted() = backgroundLocationGranted
    override fun backgroundLocationPermission() = backgroundPermission
    override fun isBackgroundLocationSkipped() = backgroundLocationSkipped
    override fun setBackgroundLocationSkipped() {
        effects += "setBackgroundLocationSkipped"
        backgroundLocationSkipped = true
    }

    override fun isBatterySkipped() = batterySkipped
    override fun setBatterySkipped() {
        effects += "setBatterySkipped"
        batterySkipped = true
    }

    override fun bleRequired() = bleRequired

    override fun launchBluetoothEnable() {
        effects += "launchBluetoothEnable"
    }
    override fun launchLocationSettings() {
        effects += "launchLocationSettings"
    }
    override fun launchBatteryOptimization() {
        effects += "launchBatteryOptimization"
    }
    override fun launchPermissions(p: List<String>) {
        effects += "launchPermissions:$p"
    }
    override fun launchBackgroundLocation(p: String) {
        effects += "launchBackgroundLocation:$p"
    }
    override fun openAppSettings() {
        effects += "openAppSettings"
    }

    override fun startWifiAwareIfPossible() {
        effects += "startWifiAwareIfPossible"
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
