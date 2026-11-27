package com.bitchat.android.onboarding

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.ui.debug.DebugPreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The one place onboarding reads the device from: radio states, battery
 * optimization, runtime permissions, and the stored first-launch and skip
 * choices. It also owns the writes to those choices.
 */
interface DeviceReadiness {

    fun snapshot(): Readiness

    /**
     * Bluetooth and location changes while collected. Collecting registers the
     * receivers; cancelling unregisters them.
     */
    val systemEvents: Flow<SystemEvent>

    /**
     * Required permissions that can be requested together. Background location
     * is requested separately, after these. Notifications are optional, so they
     * are not in this list.
     */
    fun requiredPermissions(): List<String>

    /** Permissions that improve the experience but are not required: notifications on Android 13+. */
    fun optionalPermissions(): List<String>

    /** ACCESS_BACKGROUND_LOCATION on Android 10+, null below it. */
    fun backgroundLocationPermission(): String?

    /**
     * Runtime permissions for the Wi-Fi Aware transport, version-gated because
     * neither exists at minSdk 26. They are required only while Wi-Fi Aware is
     * switched on in the debug settings and the device supports it.
     */
    fun wifiAwarePermissions(): List<String>

    fun isPermissionGranted(p: String): Boolean

    fun markOnboardingComplete()
    fun markOptionalPermissionsRequested(p: Collection<String>)
    fun setBatterySkipped()
    fun setBackgroundLocationSkipped()
}

class SystemDeviceReadiness @Inject constructor(
    @ApplicationContext private val context: Context,
    private val debugPreferences: DebugPreferenceManager,
    private val wifiAwareAvailability: WifiAwareAvailability,
) : DeviceReadiness {

    private companion object {
        const val TAG = "DeviceReadiness"

        // Persisted names. Installed copies have these on disk: never rename them.
        const val PREFS_NAME = "bitchat_permissions"
        const val KEY_FIRST_TIME_COMPLETE = "first_time_onboarding_complete"
        const val KEY_OPTIONAL_PERMISSION_REQUESTED_PREFIX = "optional_permission_requested_"
    }

    private val permissionPrefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun snapshot(): Readiness {
        val missing = requiredPermissions().filter { !isPermissionGranted(it) }
        return Readiness(
            bluetooth = bluetoothStatus(),
            location = locationStatus(),
            batteryOptimization = batteryOptimizationStatus(),
            requiredPermissionsGranted = missing.isEmpty(),
            missingPermissions = missing,
            unrequestedOptionalPermissions = unrequestedOptionalPermissions(),
            backgroundLocation = backgroundLocation(),
            firstLaunch = !permissionPrefs.getBoolean(KEY_FIRST_TIME_COMPLETE, false),
            batterySkipped = BatteryOptimizationPreferenceManager.isSkipped(context),
            backgroundLocationSkipped = BackgroundLocationPreferenceManager.isSkipped(context),
            bleRequired = try { debugPreferences.getBleEnabled(true) } catch (_: Exception) { true },
        )
    }

    override val systemEvents: Flow<SystemEvent> = callbackFlow {
        val bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                // STATE_TURNING_ON and STATE_TURNING_OFF are transitions; only the ends count.
                when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> {
                        Log.d(TAG, "Bluetooth turned ON")
                        trySend(SystemEvent.BluetoothOn)
                    }
                    BluetoothAdapter.STATE_OFF -> {
                        Log.d(TAG, "Bluetooth turned OFF")
                        trySend(SystemEvent.BluetoothOff)
                    }
                }
            }
        }
        val locationReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == LocationManager.MODE_CHANGED_ACTION ||
                    intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION
                ) {
                    Log.d(TAG, "Location settings changed, checking status")
                    trySend(SystemEvent.LocationChanged(enabled = isLocationEnabled()))
                }
            }
        }

        ContextCompat.registerReceiver(
            context,
            bluetoothReceiver,
            IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        ContextCompat.registerReceiver(
            context,
            locationReceiver,
            IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        awaitClose {
            listOf(bluetoothReceiver, locationReceiver).forEach { receiver ->
                try {
                    context.unregisterReceiver(receiver)
                } catch (e: IllegalArgumentException) {
                    Log.w(TAG, "Receiver was not registered: ${e.message}")
                }
            }
        }
    }

    override fun requiredPermissions(): List<String> {
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

        // Wi-Fi Aware: Android 13+ requires NEARBY_WIFI_DEVICES runtime permission
        if (shouldRequireWifiAwarePermission()) {
            permissions.addAll(wifiAwarePermissions())
        }

        // Notification permission intentionally excluded to keep it optional

        return permissions
    }

    override fun optionalPermissions(): List<String> {
        val optional = mutableListOf<String>()
        // Notifications on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            optional.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return optional
    }

    override fun backgroundLocationPermission(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        } else {
            null
        }

    /**
     * ACCESS_LOCAL_NETWORK is defensive: Android 17 gates local network access, and the
     * transport reaches peers over link-local IPv6 sockets. It is granted separately from
     * NEARBY_WIFI_DEVICES but shares its permission group, so the two prompt only once.
     */
    override fun wifiAwarePermissions(): List<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        // API 37 == Android 17; no named VERSION_CODES constant is available yet.
        if (Build.VERSION.SDK_INT >= 37) {
            permissions.add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        return permissions
    }

    override fun isPermissionGranted(p: String): Boolean =
        ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    override fun markOnboardingComplete() {
        permissionPrefs.edit()
            .putBoolean(KEY_FIRST_TIME_COMPLETE, true)
            .apply()
        Log.d(TAG, "First-time onboarding marked as complete")
    }

    override fun markOptionalPermissionsRequested(p: Collection<String>) {
        if (p.isEmpty()) return

        permissionPrefs.edit().apply {
            p.forEach { permission ->
                putBoolean(optionalPermissionRequestKey(permission), true)
            }
        }.apply()
    }

    override fun setBatterySkipped() = BatteryOptimizationPreferenceManager.setSkipped(context, true)

    override fun setBackgroundLocationSkipped() = BackgroundLocationPreferenceManager.setSkipped(context, true)

    private fun shouldRequireWifiAwarePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val enabled = try {
            debugPreferences.getWifiAwareEnabled(false)
        } catch (_: Exception) {
            false
        }
        if (!enabled) return false

        return try {
            wifiAwareAvailability.isSupported()
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Optional permissions are prompted once. A denial must not trap returning users in
     * onboarding, while users upgrading to a notification-permission Android version
     * should still receive one contextual request.
     */
    private fun unrequestedOptionalPermissions(): List<String> =
        optionalPermissions().filter { permission ->
            !isPermissionGranted(permission) &&
                !permissionPrefs.getBoolean(optionalPermissionRequestKey(permission), false)
        }

    private fun optionalPermissionRequestKey(permission: String): String =
        KEY_OPTIONAL_PERMISSION_REQUESTED_PREFIX + permission

    private fun backgroundLocation(): BackgroundLocation {
        val permission = backgroundLocationPermission() ?: return BackgroundLocation.NOT_NEEDED
        return if (isPermissionGranted(permission)) BackgroundLocation.GRANTED else BackgroundLocation.NEEDED
    }

    private fun bluetoothStatus(): BluetoothStatus {
        val adapter = try {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get the Bluetooth adapter", e)
            null
        }
        val enabled = try {
            adapter?.isEnabled == true
        } catch (_: SecurityException) {
            // If we can't check due to permissions, assume disabled
            Log.w(TAG, "Cannot check Bluetooth enabled state due to missing permissions")
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking Bluetooth enabled state: ${e.message}")
            false
        }
        return when {
            adapter == null -> BluetoothStatus.NOT_SUPPORTED
            !enabled -> BluetoothStatus.DISABLED
            else -> BluetoothStatus.ENABLED
        }
    }

    private fun locationManager(): LocationManager? = try {
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    } catch (e: Exception) {
        Log.e(TAG, "Failed to get LocationManager", e)
        null
    }

    private fun locationStatus(): LocationStatus {
        val locationManager = locationManager() ?: return LocationStatus.NOT_AVAILABLE
        return if (isLocationEnabled(locationManager)) LocationStatus.ENABLED else LocationStatus.DISABLED
    }

    /** The system-wide location setting, not the location permission. */
    private fun isLocationEnabled(locationManager: LocationManager? = locationManager()): Boolean = try {
        locationManager?.let { lm ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
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

    // Battery optimization exists on every supported API level (M < minSdk 26), so
    // NOT_SUPPORTED is never the answer here.
    private fun batteryOptimizationStatus(): BatteryOptimizationStatus = try {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(context.packageName)) {
            BatteryOptimizationStatus.DISABLED
        } else {
            BatteryOptimizationStatus.ENABLED
        }
    } catch (e: Exception) {
        // If we can't check, assume it's enabled (more conservative)
        Log.e(TAG, "Error checking battery optimization status", e)
        BatteryOptimizationStatus.ENABLED
    }
}
