package com.bitchat.android.onboarding

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private const val BLUETOOTH_TAG = "BluetoothStatusManager"
private const val LOCATION_TAG = "LocationStatusManager"
private const val BATTERY_TAG = "BatteryOptimizationManager"
private const val SETTINGS_TAG = "OnboardingCoordinator"

/**
 * The onboarding route. It stays composed across every step, so its five
 * launchers are registered here once: a launcher inside a step screen would
 * leave composition when the step changes while its dialog is up, as it does
 * from the permission explanation to the request, and lose the result.
 */
@Composable
fun OnboardingRouteContent(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val onAction = viewModel::onAction

    val bluetoothEnable = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val isEnabled = isBluetoothEnabled(context)
        Log.d(BLUETOOTH_TAG, "Bluetooth enable request result: $isEnabled (result code: ${result.resultCode})")
        onAction(OnboardingAction.BluetoothResult(enabled = isEnabled))
    }
    val locationSettings = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val isEnabled = isLocationEnabled(context)
        Log.d(LOCATION_TAG, "Location settings request result: $isEnabled (result code: ${result.resultCode})")
        onAction(OnboardingAction.LocationResult(enabled = isEnabled))
    }
    val batteryOptimization = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(BATTERY_TAG, "Battery optimization request result: ${result.resultCode}")
        // The view model re-reads the status: still optimized stays on the battery step (F7)
        onAction(OnboardingAction.BatteryResult)
    }
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onAction(OnboardingAction.PermissionResults(results))
    }
    val backgroundLocation = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        onAction(OnboardingAction.BackgroundResult(granted))
    }

    ObserveEvents(viewModel.events) { event ->
        when (event) {
            OnboardingEvent.LaunchBluetoothEnable -> {
                Log.d(BLUETOOTH_TAG, "Requesting user to enable Bluetooth")
                try {
                    bluetoothEnable.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (securityException: SecurityException) {
                    // Permission not granted yet - this is expected during onboarding
                    Log.w(BLUETOOTH_TAG, "Cannot request Bluetooth enable due to missing BLUETOOTH_CONNECT permission")
                    onAction(
                        OnboardingAction.BluetoothLaunchFailed(
                            "Bluetooth permissions are required before enabling Bluetooth. Please grant permissions first."
                        )
                    )
                } catch (e: Exception) {
                    Log.e(BLUETOOTH_TAG, "Failed to request Bluetooth enable", e)
                    onAction(OnboardingAction.BluetoothLaunchFailed("Failed to request Bluetooth enable: ${e.message}"))
                }
            }
            OnboardingEvent.LaunchLocationSettings -> {
                Log.d(LOCATION_TAG, "Requesting user to enable location services")
                try {
                    locationSettings.launch(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                } catch (e: Exception) {
                    Log.e(LOCATION_TAG, "Failed to request location enable", e)
                    onAction(OnboardingAction.LocationLaunchFailed("Failed to open location settings: ${e.message}"))
                }
            }
            OnboardingEvent.LaunchBatteryOptimization -> {
                Log.d(BATTERY_TAG, "Requesting to disable battery optimization")
                val intents = batteryOptimizationIntents(context)
                // The last is the fallback and is launched without a resolve check.
                val index = intents.indexOfFirst { it.resolveActivity(context.packageManager) != null }
                    .takeIf { it >= 0 } ?: intents.lastIndex
                if (index > 0) {
                    Log.w(BATTERY_TAG, "Battery optimization settings not available, opening general settings")
                }
                try {
                    batteryOptimization.launch(intents[index])
                } catch (e: Exception) {
                    Log.e(BATTERY_TAG, "Error requesting battery optimization disable", e)
                    val prefix = when (index) {
                        0 -> "Unable to open battery optimization settings"
                        1 -> "Unable to open settings"
                        else -> "Unable to open app settings"
                    }
                    onAction(OnboardingAction.BatteryLaunchFailed("$prefix: ${e.message}"))
                }
            }
            is OnboardingEvent.RequestPermissions -> permissions.launch(event.permissions.toTypedArray())
            is OnboardingEvent.RequestBackgroundLocation -> backgroundLocation.launch(event.permission)
            OnboardingEvent.OpenAppSettings -> openAppSettings(context)
        }
    }

    when (uiState.step) {
        // Onboarding has not picked its step yet, or has finished: a spinner,
        // so first launch and Try again do not show a blank screen (F8).
        OnboardingStep.CHECKING,
        OnboardingStep.PERMISSION_REQUESTING -> InitializingScreen(modifier)
        OnboardingStep.BLUETOOTH -> BluetoothCheckScreen(modifier, uiState, onAction)
        OnboardingStep.LOCATION -> LocationCheckScreen(modifier, uiState, onAction)
        OnboardingStep.BATTERY_OPTIMIZATION -> BatteryOptimizationScreen(modifier, uiState, onAction)
        OnboardingStep.PERMISSION_EXPLANATION -> PermissionExplanationScreen(modifier, uiState, onAction)
        OnboardingStep.BACKGROUND_LOCATION -> BackgroundLocationPermissionScreen(modifier, uiState, onAction)
        OnboardingStep.ERROR -> InitializationErrorScreen(modifier, uiState, onAction)
    }
}

/**
 * The screens that turn battery optimization off for this app, best first: the
 * direct request, the list of optimized apps, then the app's own settings page.
 */
fun batteryOptimizationIntents(context: Context): List<Intent> = listOf(
    Intent().apply {
        action = Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
        data = Uri.parse("package:${context.packageName}")
    },
    Intent().apply {
        action = Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
    },
    Intent().apply {
        action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
        data = Uri.fromParts("package", context.packageName, null)
    },
)

/**
 * Collects the view model's events while the route is at least started, on
 * Main.immediate so an event sent from a tap launches before the next frame.
 * Events sent while stopped wait in the view model's channel.
 */
@Composable
private fun <T> ObserveEvents(events: Flow<T>, onEvent: (T) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnEvent by rememberUpdatedState(onEvent)
    LaunchedEffect(events, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            withContext(Dispatchers.Main.immediate) {
                events.collect { currentOnEvent(it) }
            }
        }
    }
}

private fun isBluetoothEnabled(context: Context): Boolean = try {
    (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.isEnabled == true
} catch (e: Exception) {
    Log.w(BLUETOOTH_TAG, "Error checking Bluetooth enabled state: ${e.message}")
    false
}

/** The system-wide location setting, not the location permission. */
private fun isLocationEnabled(context: Context): Boolean = try {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        lm.isLocationEnabled
    } else {
        // Older devices - Check individual providers
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
} catch (e: Exception) {
    Log.w(LOCATION_TAG, "Error checking location enabled state: ${e.message}")
    false
}

/**
 * Open app settings for manual permission management
 */
private fun openAppSettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        Log.d(SETTINGS_TAG, "Opened app settings for manual permission management")
    } catch (e: Exception) {
        Log.e(SETTINGS_TAG, "Failed to open app settings", e)
    }
}
