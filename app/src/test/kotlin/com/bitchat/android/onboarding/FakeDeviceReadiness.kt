package com.bitchat.android.onboarding

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * A [DeviceReadiness] for JVM tests. [readiness] is what [snapshot] returns; it
 * defaults to a returning user whose device is ready. [systemEvents] emits what
 * a test sends to [events].
 */
class FakeDeviceReadiness : DeviceReadiness {

    var readiness = Readiness(
        bluetooth = BluetoothStatus.ENABLED,
        location = LocationStatus.ENABLED,
        batteryOptimization = BatteryOptimizationStatus.DISABLED,
        requiredPermissionsGranted = true,
        missingPermissions = emptyList(),
        unrequestedOptionalPermissions = emptyList(),
        backgroundLocation = BackgroundLocation.GRANTED,
        firstLaunch = false,
        batterySkipped = false,
        backgroundLocationSkipped = false,
        bleRequired = true,
    )

    val events = MutableSharedFlow<SystemEvent>()

    override fun snapshot() = readiness
    override val systemEvents: Flow<SystemEvent> = events

    override fun requiredPermissions() = emptyList<String>()
    override fun optionalPermissions() = emptyList<String>()
    override fun backgroundLocationPermission(): String? = null
    override fun wifiAwarePermissions() = emptyList<String>()
    override fun isPermissionGranted(p: String) = true

    override fun markOnboardingComplete() {
        readiness = readiness.copy(firstLaunch = false)
    }
    override fun markOptionalPermissionsRequested(p: Collection<String>) {
        readiness = readiness.copy(unrequestedOptionalPermissions = readiness.unrequestedOptionalPermissions - p.toSet())
    }
    override fun setBatterySkipped() {
        readiness = readiness.copy(batterySkipped = true)
    }
    override fun setBackgroundLocationSkipped() {
        readiness = readiness.copy(backgroundLocationSkipped = true)
    }
}
