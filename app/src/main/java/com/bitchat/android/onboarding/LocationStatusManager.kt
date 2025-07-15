package com.bitchat.android.onboarding

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Manages Location Services enable/disable state and user prompts
 * Checks location services status on every app startup
 * Note: This is for system location services, not location permissions
 */
class LocationStatusManager(
    private val activity: ComponentActivity,
    private val context: Context,
    private val onLocationEnabled: () -> Unit,
    private val onLocationDisabled: (String) -> Unit
) {

    companion object {
        private const val TAG = "LocationStatusManager"
    }

    private var locationSettingsLauncher: ActivityResultLauncher<Intent>? = null
    private var locationManager: LocationManager? = null
    private var locationStateReceiver: BroadcastReceiver? = null

    init {
        setupLocationManager()
        setupLocationSettingsLauncher()
        setupLocationStateReceiver()
    }

    /**
     * Setup LocationManager reference
     */
    private fun setupLocationManager() {
        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Log.d(TAG, "LocationManager initialized: ${locationManager != null}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize LocationManager", e)
            locationManager = null
        }
    }

    /**
     * Setup launcher for location settings request
     */
    private fun setupLocationSettingsLauncher() {
        locationSettingsLauncher = activity.registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val isEnabled = isLocationEnabled()
            Log.d(TAG, "Location settings request result: $isEnabled (result code: ${result.resultCode})")
            if (isEnabled) {
                onLocationEnabled()
            } else {
                onLocationDisabled("Location services are required for Bluetooth scanning on Android. Please enable location services to continue.")
            }
        }
    }

    /**
     * Setup broadcast receiver to listen for location settings changes
     */
    private fun setupLocationStateReceiver() {
        locationStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == LocationManager.MODE_CHANGED_ACTION || 
                    intent.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                    Log.d(TAG, "Location settings changed, checking status")
                    val isEnabled = isLocationEnabled()
                    if (isEnabled) {
                        onLocationEnabled()
                    } else {
                        onLocationDisabled("Location services have been disabled.")
                    }
                }
            }
        }
        
        // Register receiver for location changes
        val filter = IntentFilter().apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
        }
        context.registerReceiver(locationStateReceiver, filter)
    }

    /**
     * Check if location services are enabled (system-wide setting)
     * Uses proper API depending on Android version
     */
    fun isLocationEnabled(): Boolean {
        return try {
            locationManager?.let { lm ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // API 28+ (Android 9) - Modern approach
                    lm.isLocationEnabled
                } else {
                    // Older devices - Check individual providers
                    lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                }
            } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking location enabled state: ${e.message}")
            false
        }
    }

    /**
     * Check location services status
     * This should be called on every app startup
     */
    fun checkLocationStatus(): LocationStatus {
        Log.d(TAG, "Checking location services status")
        
        return when {
            locationManager == null -> {
                Log.e(TAG, "LocationManager not available on this device")
                LocationStatus.NOT_AVAILABLE
            }
            !isLocationEnabled() -> {
                Log.w(TAG, "Location services are disabled")
                LocationStatus.DISABLED
            }
            else -> {
                Log.d(TAG, "Location services are enabled and ready")
                LocationStatus.ENABLED
            }
        }
    }

    /**
     * Request user to enable location services
     * Opens system location settings screen
     */
    fun requestEnableLocation() {
        Log.d(TAG, "Requesting user to enable location services")
        
        try {
            val enableLocationIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
            locationSettingsLauncher?.launch(enableLocationIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request location enable", e)
            onLocationDisabled("Failed to open location settings: ${e.message}")
        }
    }

    /**
     * Handle location status check result
     */
    fun handleLocationStatus(status: LocationStatus) {
        when (status) {
            LocationStatus.ENABLED -> {
                Log.d(TAG, "Location services enabled, proceeding")
                onLocationEnabled()
            }
            LocationStatus.DISABLED -> {
                Log.d(TAG, "Location services disabled, requesting enable")
                requestEnableLocation()
            }
            LocationStatus.NOT_AVAILABLE -> {
                Log.e(TAG, "Location services not available")
                onLocationDisabled("Location services are not available on this device.")
            }
        }
    }

    /**
     * Get user-friendly status message
     */
    fun getStatusMessage(status: LocationStatus): String {
        return when (status) {
            LocationStatus.ENABLED -> "Location services are enabled and ready"
            LocationStatus.DISABLED -> "Location services are disabled. Please enable location services for Bluetooth scanning."
            LocationStatus.NOT_AVAILABLE -> "Location services are not available on this device."
        }
    }

    /**
     * Get detailed diagnostics
     */
    fun getDiagnostics(): String {
        return buildString {
            appendLine("Location Services Status Diagnostics:")
            appendLine("LocationManager available: ${locationManager != null}")
            appendLine("Location services enabled: ${isLocationEnabled()}")
            appendLine("Current status: ${checkLocationStatus()}")
            appendLine("Android version: ${Build.VERSION.SDK_INT}")
            
            locationManager?.let { lm ->
                try {
                    appendLine("GPS provider enabled: ${lm.isProviderEnabled(LocationManager.GPS_PROVIDER)}")
                    appendLine("Network provider enabled: ${lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)}")
                } catch (e: Exception) {
                    appendLine("Provider details: [Error: ${e.message}]")
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    appendLine("Using modern isLocationEnabled() API")
                } else {
                    appendLine("Using legacy provider check API")
                }
            }
        }
    }

    /**
     * Log current location status for debugging
     */
    fun logLocationStatus() {
        Log.d(TAG, getDiagnostics())
    }

    /**
     * Cleanup resources - call this when activity is destroyed
     */
    fun cleanup() {
        locationStateReceiver?.let { receiver ->
            try {
                context.unregisterReceiver(receiver)
                Log.d(TAG, "Location state receiver unregistered")
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering location state receiver: ${e.message}")
            }
        }
    }
}

/**
 * Location services status enum
 */
enum class LocationStatus {
    ENABLED,
    DISABLED, 
    NOT_AVAILABLE
}
