package com.bitchat.android.onboarding

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Centralized permission management for bitchat app
 * Handles all Bluetooth and notification permissions required for the app to function
 */
class PermissionManager(private val context: Context) {

    companion object {
        private const val TAG = "PermissionManager"
        private const val PREFS_NAME = "bitchat_permissions"
        private const val KEY_FIRST_TIME_COMPLETE = "first_time_onboarding_complete"
    }

    private val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Check if this is the first time the user is launching the app
     */
    fun isFirstTimeLaunch(): Boolean {
        return !sharedPrefs.getBoolean(KEY_FIRST_TIME_COMPLETE, false)
    }

    /**
     * Mark the first-time onboarding as complete
     */
    fun markOnboardingComplete() {
        sharedPrefs.edit()
            .putBoolean(KEY_FIRST_TIME_COMPLETE, true)
            .apply()
        Log.d(TAG, "First-time onboarding marked as complete")
    }

    /**
     * Get all permissions required by the app
     * Note: Notification permission is optional and not included here,
     * so the app works without notification access.
     */
    fun getRequiredPermissions(): List<String> {
        val permissions = mutableListOf<String>()

        // Bluetooth permissions (API level dependent)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            ))
        } else {
            permissions.addAll(listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN
            ))
        }

        // Location permissions (required for Bluetooth LE scanning)
        permissions.addAll(listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION
        ))

        // Notification permission intentionally excluded to keep it optional

        return permissions
    }

    /**
     * Get optional permissions that improve the experience but aren't required.
     * Currently includes POST_NOTIFICATIONS on Android 13+.
     */
    fun getOptionalPermissions(): List<String> {
        val optional = mutableListOf<String>()
        // Notifications on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            optional.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return optional
    }

    /**
     * Check if a specific permission is granted
     */
    fun isPermissionGranted(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Check if all required permissions are granted
     */
    fun areAllPermissionsGranted(): Boolean {
        return getRequiredPermissions().all { isPermissionGranted(it) }
    }

    /**
     * Check if battery optimization is disabled for this app
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery optimization status", e)
                false
            }
        } else {
            // Battery optimization doesn't exist on Android < 6.0
            true
        }
    }

    /**
     * Check if battery optimization is supported on this device
     */
    fun isBatteryOptimizationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    /**
     * Get the list of permissions that are missing
     */
    fun getMissingPermissions(): List<String> {
        return getRequiredPermissions().filter { !isPermissionGranted(it) }
    }

    /**
     * Get categorized permission information for display
     */
    fun getCategorizedPermissions(): List<PermissionCategory> {
        val categories = mutableListOf<PermissionCategory>()

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
                isGranted = bluetoothPermissions.all { isPermissionGranted(it) },
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
                isGranted = locationPermissions.all { isPermissionGranted(it) },
                systemDescription = "bitchat needs this to scan for nearby devices"
            )
        )

        // Notifications category (if applicable)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.NOTIFICATIONS,
                    description = "Receive notifications when you receive private messages",
                    permissions = listOf(Manifest.permission.POST_NOTIFICATIONS),
                    isGranted = isPermissionGranted(Manifest.permission.POST_NOTIFICATIONS),
                    systemDescription = "Allow bitchat to send you notifications"
                )
            )
        }

        // Microphone category removed from onboarding

        // Battery optimization category (if applicable)
        if (isBatteryOptimizationSupported()) {
            categories.add(
                PermissionCategory(
                    type = PermissionType.BATTERY_OPTIMIZATION,
                    description = "Disable battery optimization to ensure bitchat runs reliably in the background and maintains mesh network connections",
                    permissions = listOf("BATTERY_OPTIMIZATION"), // Custom identifier
                    isGranted = isBatteryOptimizationDisabled(),
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
            appendLine("First time launch: ${isFirstTimeLaunch()}")
            appendLine("All permissions granted: ${areAllPermissionsGranted()}")
            appendLine()
            
            getCategorizedPermissions().forEach { category ->
                appendLine("${category.type.nameValue}: ${if (category.isGranted) "✅ GRANTED" else "❌ MISSING"}")
                category.permissions.forEach { permission ->
                    val granted = isPermissionGranted(permission)
                    appendLine("  - ${permission.substringAfterLast(".")}: ${if (granted) "✅" else "❌"}")
                }
                appendLine()
            }
            
            val missing = getMissingPermissions()
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
    MICROPHONE("Microphone"),
    NOTIFICATIONS("Notifications"),
    BATTERY_OPTIMIZATION("Battery Optimization"),
    OTHER("Other")
}
