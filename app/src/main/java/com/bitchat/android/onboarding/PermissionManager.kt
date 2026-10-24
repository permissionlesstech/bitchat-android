package com.bitchat.android.onboarding

import android.Manifest
import android.content.Context
import android.os.Build
import android.util.Log
import com.bitchat.android.R

/**
 * The permission explanation screen's categories and the permission diagnostics.
 * The permission lists, grants and stored markers themselves come from [DeviceReadiness].
 */
class PermissionManager(
    private val context: Context,
    private val readiness: DeviceReadiness,
) {

    companion object {
        private const val TAG = "PermissionManager"
    }

    /**
     * Get categorized permission information for display
     */
    fun getCategorizedPermissions(): List<PermissionCategory> {
        val categories = mutableListOf<PermissionCategory>()
        val snapshot = readiness.snapshot()

        // Bluetooth/Nearby Devices category
        val bluetoothPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            )
        }

        categories.add(
            PermissionCategory(
                type = PermissionType.NEARBY_DEVICES,
                description = "Required to discover bitchat users via Bluetooth",
                permissions = bluetoothPermissions,
                isGranted = bluetoothPermissions.all { readiness.isPermissionGranted(it) },
                systemDescription = "Allow bitchat to connect to nearby devices"
            )
        )

        // Location category
        val locationPermissions = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        categories.add(
            PermissionCategory(
                type = PermissionType.PRECISE_LOCATION,
                description = "Required by Android to discover nearby bitchat users via Bluetooth",
                permissions = locationPermissions,
                isGranted = locationPermissions.all { readiness.isPermissionGranted(it) },
                systemDescription = "bitchat needs this to scan for nearby devices"
            )
        )

        // Wi‑Fi Aware category (Android 13+), shown while its permissions are required:
        // switched on in the debug settings on a device that supports it
        val wifiAwarePermissions = readiness.wifiAwarePermissions()
        if (wifiAwarePermissions.isNotEmpty() &&
            readiness.requiredPermissions().containsAll(wifiAwarePermissions)
        ) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.WIFI_AWARE,
                    description = "Enable Wi‑Fi Aware to discover and connect to nearby bitchat users over Wi‑Fi.",
                    permissions = wifiAwarePermissions,
                    isGranted = wifiAwarePermissions.all { readiness.isPermissionGranted(it) },
                    systemDescription = "Allow bitchat to discover nearby Wi‑Fi devices"
                )
            )
        }

        if (readiness.backgroundLocationPermission() != null) {
            val backgroundPermission = listOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            categories.add(
                PermissionCategory(
                    type = PermissionType.BACKGROUND_LOCATION,
                    description = context.getString(R.string.perm_background_location_desc),
                    permissions = backgroundPermission,
                    isGranted = backgroundPermission.all { readiness.isPermissionGranted(it) },
                    systemDescription = context.getString(R.string.perm_background_location_system)
                )
            )
        }

        // Notifications category (if applicable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.NOTIFICATIONS,
                    description = "Receive notifications when you receive private messages",
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    isGranted = readiness.isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS),
                    systemDescription = "Allow bitchat to send you notifications"
                )
            )
        }

        // Microphone category removed from onboarding

        // Battery optimization category (if applicable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.BATTERY_OPTIMIZATION,
                    description = "Disable battery optimization to ensure bitchat runs reliably in the background and maintains mesh network connections",
                    permissions = listOf("BATTERY_OPTIMIZATION"), // Custom identifier
                    isGranted = snapshot.batteryOptimization != BatteryOptimizationStatus.ENABLED,
                    systemDescription = "Allow bitchat to run without battery restrictions"
                )
            )
        }

        return categories
    }

    /**
     * Get detailed diagnostic information about permission status
     */
    fun getPermissionDiagnostics(): String {
        return buildString {
            appendLine("Permission Diagnostics:")
            appendLine("Android SDK: ${Build.VERSION.SDK_INT}")
            val snapshot = readiness.snapshot()
            appendLine("First time launch: ${snapshot.firstLaunch}")
            appendLine("Required permissions granted: ${snapshot.requiredPermissionsGranted}")
            appendLine()
            
            getCategorizedPermissions().forEach { category ->
                appendLine("${category.type.nameValue}: ${if (category.isGranted) "✅ GRANTED" else "❌ MISSING"}")
                category.permissions.forEach { permission ->
                    val granted = readiness.isPermissionGranted(permission)
                    appendLine("  - ${permission.substringAfterLast(".")}: ${if (granted) "✅" else "❌"}")
                }
                appendLine()
            }
            
            val missingBackground = readiness.backgroundLocationPermission()
                ?.takeIf { snapshot.backgroundLocation == BackgroundLocation.NEEDED }
            val missing = snapshot.missingPermissions + listOfNotNull(missingBackground)
            if (missing.isNotEmpty()) {
                appendLine("Missing permissions:")
                missing.forEach { permission ->
                    appendLine("- $permission")
                }
            }
        }
    }

    /**
     * Log permission status for debugging
     */
    fun logPermissionStatus() {
        Log.d(TAG, getPermissionDiagnostics())
    }
}

/**
 * Data class representing a category of related permissions
 */
data class PermissionCategory(
    val type: PermissionType,
    val description: String,
    val permissions: List<String>,
    val isGranted: Boolean,
    val systemDescription: String
)

enum class PermissionType(val nameValue: String) {
    NEARBY_DEVICES("Nearby Devices"),
    PRECISE_LOCATION("Precise Location"),
    BACKGROUND_LOCATION("Background Location"),
    MICROPHONE("Microphone"),
    NOTIFICATIONS("Notifications"),
    WIFI_AWARE("Wi‑Fi Aware"),
    BATTERY_OPTIMIZATION("Battery Optimization"),
    OTHER("Other")
}
