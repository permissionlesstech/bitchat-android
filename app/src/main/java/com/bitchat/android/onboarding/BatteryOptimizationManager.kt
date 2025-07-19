package com.bitchat.android.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Manages battery optimization settings for the app
 * Handles checking if the app is whitelisted from battery optimization
 * and requesting the user to disable battery optimization
 */
class BatteryOptimizationManager(
    private val activity: ComponentActivity,
    private val context: Context,
    private val onBatteryOptimizationDisabled: () -> Unit,
    private val onBatteryOptimizationFailed: (String) -> Unit
) {

    companion object {
        private const val TAG = "BatteryOptimizationManager"
    }

    private var batteryOptimizationLauncher: ActivityResultLauncher<Intent>? = null

    init {
        setupBatteryOptimizationLauncher()
    }

    /**
     * Setup the battery optimization request launcher
     */
    private fun setupBatteryOptimizationLauncher() {
        batteryOptimizationLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            Log.d(TAG, "Battery optimization request result: ${result.resultCode}")
            
            // Check if battery optimization is now disabled
            if (isBatteryOptimizationDisabled()) {
                Log.d(TAG, "Battery optimization successfully disabled")
                onBatteryOptimizationDisabled()
            } else {
                Log.w(TAG, "Battery optimization still enabled after user interaction")
                // Don't treat this as a failure - user might have chosen not to disable it
                // We'll proceed anyway but log the status
                onBatteryOptimizationDisabled()
            }
        }
    }

    /**
     * Check if battery optimization is disabled for this app
     */
    fun isBatteryOptimizationDisabled(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.packageName)
                Log.d(TAG, "Battery optimization disabled: $isIgnoring")
                isIgnoring
            } catch (e: Exception) {
                Log.e(TAG, "Error checking battery optimization status", e)
                // If we can't check, assume it's enabled (more conservative)
                false
            }
        } else {
            // Battery optimization doesn't exist on Android < 6.0
            Log.d(TAG, "Battery optimization not applicable for Android < 6.0")
            true
        }
    }

    /**
     * Request to disable battery optimization for this app
     */
    fun requestDisableBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Log.d(TAG, "Requesting to disable battery optimization")
                
                val intent = Intent().apply {
                    action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
                    data = Uri.parse("package:${context.packageName}")
                }
                
                // Check if the intent can be resolved
                if (intent.resolveActivity(context.packageManager) != null) {
                    batteryOptimizationLauncher?.launch(intent)
                } else {
                    Log.w(TAG, "Battery optimization settings not available, opening general settings")
                    openBatteryOptimizationSettings()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting battery optimization disable", e)
                onBatteryOptimizationFailed("Unable to open battery optimization settings: ${e.message}")
            }
        } else {
            Log.d(TAG, "Battery optimization not applicable for Android < 6.0")
            onBatteryOptimizationDisabled()
        }
    }

    /**
     * Open general battery optimization settings if direct request fails
     */
    private fun openBatteryOptimizationSettings() {
        try {
            val intent = Intent().apply {
                action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
            }
            
            if (intent.resolveActivity(context.packageManager) != null) {
                batteryOptimizationLauncher?.launch(intent)
            } else {
                // Fallback to general application settings
                openAppSettings()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error opening battery optimization settings", e)
            onBatteryOptimizationFailed("Unable to open settings: ${e.message}")
        }
    }

    /**
     * Open app settings as a last resort
     */
    private fun openAppSettings() {
        try {
            val intent = Intent().apply {
                action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                data = Uri.fromParts("package", context.packageName, null)
            }
            batteryOptimizationLauncher?.launch(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening app settings", e)
            onBatteryOptimizationFailed("Unable to open app settings: ${e.message}")
        }
    }

    /**
     * Check if battery optimization is supported on this device
     */
    fun isBatteryOptimizationSupported(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    /**
     * Get battery optimization status for logging
     */
    fun getBatteryOptimizationStatus(): String {
        return when {
            !isBatteryOptimizationSupported() -> "Not supported (Android < 6.0)"
            isBatteryOptimizationDisabled() -> "Disabled (app is whitelisted)"
            else -> "Enabled (app is being optimized)"
        }
    }

    /**
     * Log battery optimization status for debugging
     */
    fun logBatteryOptimizationStatus() {
        Log.d(TAG, "Battery optimization status: ${getBatteryOptimizationStatus()}")
    }
}

enum class BatteryOptimizationStatus {
    ENABLED,
    DISABLED,
    NOT_SUPPORTED
}