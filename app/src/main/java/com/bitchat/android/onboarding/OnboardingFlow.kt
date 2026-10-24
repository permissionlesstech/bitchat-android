package com.bitchat.android.onboarding

import android.util.Log
import com.bitchat.android.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The onboarding state machine, lifted out of MainActivity and
 * OnboardingCoordinator unchanged: same checks, same order, same delays and
 * the same log lines. It writes its state into [state] and reaches the device
 * only through [system].
 */
class OnboardingFlow(
    private val scope: CoroutineScope,
    private val state: MainViewModel,
    private val system: OnboardingSystem,
) {

    companion object {
        // The tags the code logged under before it moved here.
        private const val ACTIVITY_TAG = "MainActivity"
        private const val COORDINATOR_TAG = "OnboardingCoordinator"

        // The messages each event carried into the disabled handlers.
        const val BLUETOOTH_DECLINED_MESSAGE =
            "Bluetooth is required for bitchat to discover and connect to nearby users. Please enable Bluetooth to continue."
        const val BLUETOOTH_TURNED_OFF_MESSAGE = "User has turned off their Blue"
        const val BLUETOOTH_NOT_SUPPORTED_MESSAGE =
            "This device doesn't support Bluetooth, which is required for bitchat to function."
        const val LOCATION_DECLINED_MESSAGE =
            "Location services are required for Bluetooth scanning on Android. Please enable location services to continue."
        const val LOCATION_TURNED_OFF_MESSAGE = "Location services have been disabled."
    }

    fun dispatch(action: OnboardingAction) {
        when (action) {
            OnboardingAction.Start -> {
                // Only start onboarding process if we're in the initial CHECKING state
                // This prevents restarting onboarding on configuration changes
                if (state.onboardingState.value == OnboardingState.CHECKING) {
                    checkOnboardingStatus()
                }
            }

            OnboardingAction.BluetoothEnableTapped -> {
                state.updateBluetoothLoading(true)
                system.launchBluetoothEnable()
            }
            OnboardingAction.BluetoothSkipTapped -> {
                state.skipBluetoothCheck()
                checkLocationAndProceed()
            }
            OnboardingAction.BluetoothCheckAgainTapped -> checkBluetoothAndProceed()
            is OnboardingAction.BluetoothResult -> {
                if (action.enabled) {
                    handleBluetoothEnabled()
                } else {
                    handleBluetoothDisabled(BLUETOOTH_DECLINED_MESSAGE)
                }
            }
            is OnboardingAction.BluetoothLaunchFailed -> handleBluetoothDisabled(action.message)
            OnboardingAction.BluetoothTurnedOn -> {
                if (state.onboardingState.value == OnboardingState.BLUETOOTH_CHECK) {
                    checkBluetoothAndProceed()
                }
            }
            OnboardingAction.BluetoothTurnedOff -> handleBluetoothDisabled(BLUETOOTH_TURNED_OFF_MESSAGE)
            OnboardingAction.BluetoothNotSupportedAtStart -> handleBluetoothDisabled(BLUETOOTH_NOT_SUPPORTED_MESSAGE)

            OnboardingAction.LocationOpenSettingsTapped -> {
                state.updateLocationLoading(true)
                system.launchLocationSettings()
            }
            OnboardingAction.LocationCheckAgainTapped -> checkLocationAndProceed()
            is OnboardingAction.LocationResult -> {
                if (action.enabled) {
                    handleLocationEnabled()
                } else {
                    handleLocationDisabled(LOCATION_DECLINED_MESSAGE)
                }
            }
            is OnboardingAction.LocationLaunchFailed -> handleLocationDisabled(action.message)
            is OnboardingAction.LocationChanged -> {
                if (action.enabled) {
                    handleLocationEnabled()
                } else {
                    handleLocationDisabled(LOCATION_TURNED_OFF_MESSAGE)
                }
            }

            OnboardingAction.BatteryDisableTapped -> {
                state.updateBatteryOptimizationLoading(true)
                system.launchBatteryOptimization()
            }
            OnboardingAction.BatteryCheckAgainTapped -> checkBatteryOptimizationAndProceed()
            OnboardingAction.BatterySkipTapped -> {
                // Skip battery optimization and proceed
                system.setBatterySkipped()
                proceedWithPermissionCheck()
            }
            OnboardingAction.BatteryResult -> handleBatteryOptimizationDisabled()
            is OnboardingAction.BatteryLaunchFailed -> handleBatteryOptimizationFailed(action.message)

            OnboardingAction.GrantPermissionsTapped -> {
                state.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
                requestPermissions()
            }
            is OnboardingAction.PermissionResults -> handlePermissionResults(action.results)
            OnboardingAction.BackgroundGrantTapped -> requestBackgroundLocation()
            is OnboardingAction.BackgroundResult -> handleBackgroundLocationResult(action.granted)
            OnboardingAction.BackgroundCheckAgainTapped -> checkBackgroundLocationAndProceed()
            OnboardingAction.BackgroundSkipTapped -> skipBackgroundLocation()

            OnboardingAction.TryAgainTapped -> {
                state.updateOnboardingState(OnboardingState.CHECKING)
                checkOnboardingStatus()
            }
            OnboardingAction.OpenSettingsTapped -> system.openAppSettings()

            OnboardingAction.ResumedWhileComplete -> recheckAfterResume()
        }
    }

    /** P6 failed: the permission re-check or an exception during initialization. */
    fun onInitFailed(message: String) {
        handleOnboardingFailed(message)
    }

    /** P6 finished. */
    fun onInitComplete() {
        state.updateOnboardingState(OnboardingState.COMPLETE)
    }

    private fun checkOnboardingStatus() {
        scope.launch {
            // Small delay to show the checking state
            delay(500)

            // First check Bluetooth status (always required)
            checkBluetoothAndProceed()
        }
    }

    /**
     * Check Bluetooth status and proceed with onboarding flow
     */
    private fun checkBluetoothAndProceed() {
        // Check if user has skipped Bluetooth check for this session
        if (state.isBluetoothCheckSkipped.value) {
            checkLocationAndProceed()
            return
        }

        // For first-time users, skip Bluetooth check and go straight to permissions
        // We'll check Bluetooth after permissions are granted
        if (system.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }

        // For existing users, check Bluetooth status first
        system.logBluetoothDiagnostics()
        state.updateBluetoothStatus(system.bluetoothStatus())

        val bleRequired = system.bleRequired()
        if (!bleRequired) {
            // Skip BLE checks entirely when BLE is disabled in debug settings
            checkLocationAndProceed()
            return
        }
        when (state.bluetoothStatus.value) {
            BluetoothStatus.ENABLED -> {
                // Bluetooth is enabled, check location services next
                checkLocationAndProceed()
            }
            BluetoothStatus.DISABLED -> {
                // Show Bluetooth enable screen (should have permissions as existing user)
                state.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                state.updateBluetoothLoading(false)
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                // Device doesn't support Bluetooth
                Log.e(ACTIVITY_TAG, "Bluetooth not supported")
                state.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                state.updateBluetoothLoading(false)
            }
        }
    }

    /**
     * Proceed with permission checking
     */
    private fun proceedWithPermissionCheck() {
        scope.launch {
            delay(200) // Small delay for smooth transition

            if (system.isFirstTimeLaunch()) {
                state.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (system.unrequestedOptionalPermissions().isNotEmpty()) {
                state.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            } else if (system.areRequiredPermissionsGranted()) {
                if (system.needsBackgroundLocation() &&
                    !system.isBackgroundLocationGranted() &&
                    !system.isBackgroundLocationSkipped()
                ) {
                    state.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
                } else {
                    state.updateOnboardingState(OnboardingState.INITIALIZING)
                    system.initializeApp()
                }
            } else {
                state.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
        }
    }

    /**
     * Handle Bluetooth enabled callback
     */
    private fun handleBluetoothEnabled() {
        state.updateBluetoothLoading(false)
        state.updateBluetoothStatus(BluetoothStatus.ENABLED)
        checkLocationAndProceed()
    }

    /**
     * Check Location services status and proceed with onboarding flow
     */
    private fun checkLocationAndProceed() {
        // For first-time users, skip location check and go straight to permissions
        // We'll check location after permissions are granted
        if (system.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }

        // For existing users, check location status
        system.logLocationDiagnostics()
        state.updateLocationStatus(system.locationStatus())

        when (state.locationStatus.value) {
            LocationStatus.ENABLED -> {
                // Location services enabled, check battery optimization next
                checkBatteryOptimizationAndProceed()
            }
            LocationStatus.DISABLED -> {
                // Show location enable screen (should have permissions as existing user)
                state.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                state.updateLocationLoading(false)
            }
            LocationStatus.NOT_AVAILABLE -> {
                // Device doesn't support location services (very unusual)
                Log.e(ACTIVITY_TAG, "Location services not available")
                state.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                state.updateLocationLoading(false)
            }
        }
    }

    /**
     * Handle Location enabled callback
     */
    private fun handleLocationEnabled() {
        state.updateLocationLoading(false)
        state.updateLocationStatus(LocationStatus.ENABLED)
        // Ensure Wi-Fi Aware starts now that location is enabled
        system.startWifiAwareIfPossible()
        checkBatteryOptimizationAndProceed()
    }

    /**
     * Handle Location disabled callback
     */
    private fun handleLocationDisabled(message: String) {
        Log.w(ACTIVITY_TAG, "Location services disabled or failed: $message")
        state.updateLocationLoading(false)
        state.updateLocationStatus(system.locationStatus())

        when {
            state.locationStatus.value == LocationStatus.NOT_AVAILABLE -> {
                // Show permanent error for devices without location services
                state.updateErrorMessage(message)
                state.updateOnboardingState(OnboardingState.ERROR)
            }
            else -> {
                // Stay on location check screen for retry
                state.updateOnboardingState(OnboardingState.LOCATION_CHECK)
            }
        }
    }

    /**
     * Handle Bluetooth disabled callback
     */
    private fun handleBluetoothDisabled(message: String) {
        Log.w(ACTIVITY_TAG, "Bluetooth disabled or failed: $message")
        state.updateBluetoothLoading(false)
        state.updateBluetoothStatus(system.bluetoothStatus())

        when {
            state.bluetoothStatus.value == BluetoothStatus.NOT_SUPPORTED -> {
                // Show permanent error for unsupported devices
                state.updateErrorMessage(message)
                state.updateOnboardingState(OnboardingState.ERROR)
            }
            message.contains("Permission") && system.isFirstTimeLaunch() -> {
                // During first-time onboarding, if Bluetooth enable fails due to permissions,
                // proceed to permission explanation screen where user will grant permissions first
                proceedWithPermissionCheck()
            }
            message.contains("Permission") -> {
                // For existing users, redirect to permission explanation to grant missing permissions
                state.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
            }
            else -> {
                // Stay on Bluetooth check screen for retry
                state.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
            }
        }
    }

    private fun handleOnboardingComplete() {
        // After permissions are granted, re-check Bluetooth, Location, and Battery Optimization status
        val currentBluetoothStatus = system.bluetoothStatus()
        val currentLocationStatus = system.locationStatus()
        val currentBatteryOptimizationStatus = system.batteryStatus()

        val bleRequired2 = system.bleRequired()
        when {
            bleRequired2 && currentBluetoothStatus != BluetoothStatus.ENABLED -> {
                // Bluetooth still disabled, but now we have permissions to enable it
                state.updateBluetoothStatus(currentBluetoothStatus)
                state.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
                state.updateBluetoothLoading(false)
            }
            currentLocationStatus != LocationStatus.ENABLED -> {
                // Location services still disabled, but now we have permissions to enable it
                state.updateLocationStatus(currentLocationStatus)
                state.updateOnboardingState(OnboardingState.LOCATION_CHECK)
                state.updateLocationLoading(false)
            }
            currentBatteryOptimizationStatus == BatteryOptimizationStatus.ENABLED -> {
                // Battery optimization still enabled, show battery optimization screen
                state.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)
                state.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                state.updateBatteryOptimizationLoading(false)
            }
            else -> {
                // Both are enabled, proceed to app initialization
                state.updateOnboardingState(OnboardingState.INITIALIZING)
                system.initializeApp()
            }
        }
    }

    private fun handleOnboardingFailed(message: String) {
        Log.e(ACTIVITY_TAG, "Onboarding failed: $message")
        state.updateErrorMessage(message)
        state.updateOnboardingState(OnboardingState.ERROR)
    }

    /**
     * Check Battery Optimization status and proceed with onboarding flow
     */
    private fun checkBatteryOptimizationAndProceed() {
        // For first-time users, skip battery optimization check and go straight to permissions
        // We'll check battery optimization after permissions are granted
        if (system.isFirstTimeLaunch()) {
            proceedWithPermissionCheck()
            return
        }

        // Check if user has previously skipped battery optimization
        if (system.isBatterySkipped()) {
            proceedWithPermissionCheck()
            return
        }

        // For existing users, check battery optimization status
        system.logBatteryDiagnostics()
        val currentBatteryOptimizationStatus = system.batteryStatus()
        state.updateBatteryOptimizationStatus(currentBatteryOptimizationStatus)

        when (currentBatteryOptimizationStatus) {
            BatteryOptimizationStatus.DISABLED, BatteryOptimizationStatus.NOT_SUPPORTED -> {
                // Battery optimization is disabled or not supported, proceed with permission check
                proceedWithPermissionCheck()
            }
            BatteryOptimizationStatus.ENABLED -> {
                // Show battery optimization disable screen
                state.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
                state.updateBatteryOptimizationLoading(false)
            }
        }
    }

    /**
     * Handle Battery Optimization disabled callback
     */
    private fun handleBatteryOptimizationDisabled() {
        state.updateBatteryOptimizationLoading(false)
        state.updateBatteryOptimizationStatus(BatteryOptimizationStatus.DISABLED)
        proceedWithPermissionCheck()
    }

    /**
     * Handle Battery Optimization failed callback
     */
    private fun handleBatteryOptimizationFailed(message: String) {
        Log.w(ACTIVITY_TAG, "Battery optimization disable failed: $message")
        state.updateBatteryOptimizationLoading(false)
        val currentStatus = system.batteryStatus()
        state.updateBatteryOptimizationStatus(currentStatus)

        // Stay on battery optimization check screen for retry
        state.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
    }

    /**
     * onResume while COMPLETE: back to a check screen if Bluetooth or location
     * services were turned off while the app was in the background.
     */
    private fun recheckAfterResume() {
        // Check if Bluetooth was disabled while app was backgrounded
        val currentBluetoothStatus = system.bluetoothStatus()
        val bleRequired = system.bleRequired()
        if (bleRequired && currentBluetoothStatus != BluetoothStatus.ENABLED && !state.isBluetoothCheckSkipped.value) {
            Log.w(ACTIVITY_TAG, "Bluetooth disabled while app was backgrounded")
            state.updateBluetoothStatus(currentBluetoothStatus)
            state.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
            state.updateBluetoothLoading(false)
            return
        }

        // Check if location services were disabled while app was backgrounded
        val currentLocationStatus = system.locationStatus()
        if (currentLocationStatus != LocationStatus.ENABLED) {
            Log.w(ACTIVITY_TAG, "Location services disabled while app was backgrounded")
            state.updateLocationStatus(currentLocationStatus)
            state.updateOnboardingState(OnboardingState.LOCATION_CHECK)
            state.updateLocationLoading(false)
        } else {
            // If location is enabled, ensure Wi-Fi Aware starts if it was blocked by location earlier
            system.startWifiAwareIfPossible()
        }
    }

    // ---- Formerly OnboardingCoordinator ----

    /**
     * Called when user accepts the permission explanation
     */
    private fun requestPermissions() {
        Log.d(COORDINATOR_TAG, "User accepted permission explanation, requesting permissions")

        // Required permissions
        val missingRequired = system.missingPermissions()

        // Optional permissions (ask, but do not block if denied)
        val optionalToRequest = system.unrequestedOptionalPermissions()

        val missingPermissions = (missingRequired + optionalToRequest).distinct()

        if (missingPermissions.isEmpty()) {
            if (shouldRequestBackgroundLocation()) {
                onBackgroundLocationRequired()
            } else {
                completeOnboarding()
            }
            return
        }

        Log.d(COORDINATOR_TAG, "Requesting ${missingPermissions.size} permissions")
        system.markOptionalPermissionsRequested(optionalToRequest)
        system.launchPermissions(missingPermissions)
    }

    /**
     * Handle permission request results
     */
    private fun handlePermissionResults(permissions: Map<String, Boolean>) {
        Log.d(COORDINATOR_TAG, "Received permission results:")
        permissions.forEach { (permission, granted) ->
            Log.d(COORDINATOR_TAG, "  $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }

        val allGranted = permissions.values.all { it }
        val criticalPermissions = getCriticalPermissions()
        // The launcher result only contains permissions requested in this round. Returning
        // users may be asked for POST_NOTIFICATIONS alone, so re-check required permissions
        // against package state instead of treating absent result-map entries as denials.
        val criticalGranted = criticalPermissions.all(system::isPermissionGranted)

        when {
            criticalGranted -> {
                if (shouldRequestBackgroundLocation()) {
                    Log.d(COORDINATOR_TAG, "Foreground permissions granted; requesting background location next")
                    onBackgroundLocationRequired()
                    return
                }
                if (allGranted) {
                    Log.d(COORDINATOR_TAG, "All permissions granted successfully")
                    completeOnboarding()
                } else {
                    Log.d(COORDINATOR_TAG, "Critical permissions granted, can proceed with limited functionality")
                    showPartialPermissionWarning(permissions)
                }
            }
            else -> {
                Log.d(COORDINATOR_TAG, "Critical permissions denied")
                handlePermissionDenial(permissions)
            }
        }
    }

    private fun requestBackgroundLocation() {
        val permission = system.backgroundLocationPermission()
        if (permission == null) {
            completeOnboarding()
            return
        }
        Log.d(COORDINATOR_TAG, "Requesting background location permission")
        system.launchBackgroundLocation(permission)
    }

    private fun handleBackgroundLocationResult(granted: Boolean) {
        if (granted) {
            Log.d(COORDINATOR_TAG, "Background location permission granted")
        } else {
            Log.w(COORDINATOR_TAG, "Background location permission denied; continuing without it")
        }
        completeOnboarding()
    }

    private fun skipBackgroundLocation() {
        Log.d(COORDINATOR_TAG, "User skipped background location permission")
        system.setBackgroundLocationSkipped()
        completeOnboarding()
    }

    private fun checkBackgroundLocationAndProceed() {
        if (!shouldRequestBackgroundLocation()) {
            completeOnboarding()
        }
    }

    private fun shouldRequestBackgroundLocation(): Boolean {
        return system.needsBackgroundLocation() &&
            !system.isBackgroundLocationGranted() &&
            !system.isBackgroundLocationSkipped()
    }

    private fun onBackgroundLocationRequired() {
        state.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
    }

    /**
     * Get the list of critical permissions that are absolutely required
     */
    private fun getCriticalPermissions(): List<String> {
        // For bitchat, Bluetooth and location permissions are critical
        // Notifications are nice-to-have but not critical and are not included in getRequiredPermissions()
        return system.requiredPermissions()
    }

    /**
     * Show warning when some permissions are granted but others are denied
     */
    private fun showPartialPermissionWarning(permissions: Map<String, Boolean>) {
        val deniedPermissions = permissions.filter { !it.value }.keys
        val message = buildString {
            append("Some permissions were denied:\n")
            deniedPermissions.forEach { permission ->
                append("- ${getPermissionDisplayName(permission)}\n")
            }
            append("\nbitchat may not work properly without all permissions.")
        }

        Log.w(COORDINATOR_TAG, "Partial permissions granted: $message")

        // For now, we'll proceed anyway and let the user experience the limitations
        // In a production app, you might want to show a dialog explaining the limitations
        completeOnboarding()
    }

    /**
     * Handle permission denial scenarios
     */
    private fun handlePermissionDenial(permissions: Map<String, Boolean>) {
        val deniedCritical = permissions.filter { !it.value && getCriticalPermissions().contains(it.key) }

        if (deniedCritical.isNotEmpty()) {
            val message = buildString {
                append("Critical permissions were denied. bitchat requires these permissions to function:\n")
                deniedCritical.keys.forEach { permission ->
                    append("- ${getPermissionDisplayName(permission)}\n")
                }
                append("\nPlease grant these permissions in Settings to use bitchat.")
            }

            Log.e(COORDINATOR_TAG, "Critical permissions denied: $deniedCritical")
            handleOnboardingFailed(message)
        } else {
            // Shouldn't happen given our logic above, but handle gracefully
            completeOnboarding()
        }
    }

    /**
     * Complete the onboarding process and initialize the app
     */
    private fun completeOnboarding() {
        Log.d(COORDINATOR_TAG, "Completing onboarding process")

        // Mark onboarding as complete
        system.markOnboardingComplete()

        // Log final permission status
        system.logPermissionDiagnostics()

        // Notify completion with a small delay to ensure everything is ready
        scope.launch {
            delay(100) // Small delay for UI state to settle
            handleOnboardingComplete()
        }
    }

    /**
     * Convert permission string to user-friendly display name
     */
    private fun getPermissionDisplayName(permission: String): String {
        return when {
            permission.contains("BLUETOOTH") -> "Bluetooth/Nearby Devices"
            permission.contains("BACKGROUND") -> "Background Location"
            permission.contains("LOCATION") -> "Location (for Bluetooth scanning)"
            permission.contains("NEARBY_WIFI") -> "Nearby Wi‑Fi Devices (for Wi‑Fi Aware)"
            permission.contains("NOTIFICATION") -> "Notifications"
            else -> permission.substringAfterLast(".")
        }
    }
}
