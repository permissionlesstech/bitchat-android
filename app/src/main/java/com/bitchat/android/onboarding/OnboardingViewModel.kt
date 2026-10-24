package com.bitchat.android.onboarding

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The onboarding steps, scoped to the onboarding route. Same checks, order,
 * delays and log lines as the flow it replaces. It reads the device only
 * through [readiness], opens system dialogs through [effects], and tells [host]
 * when onboarding is done.
 *
 * - **Opening.** The step comes from readiness when the view model is created,
 *   so a recreated route never strands a step (F3).
 * - **One thing at a time.** Actions and radio changes are queued and each is
 *   handled to completion, delays included, before the next starts.
 * - **The permission phase.** While the permission explanation, its dialog or
 *   the background location step shows, radio changes change nothing. The step
 *   is read again when the phase ends (F11).
 */
@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val readiness: DeviceReadiness,
    private val skip: BluetoothSkipSession,
    private val host: OnboardingHost,
    private val cards: PermissionCards,
) : ViewModel() {

    companion object {
        // The tags the code logged under before it moved here.
        private const val ACTIVITY_TAG = "MainActivity"
        private const val COORDINATOR_TAG = "OnboardingCoordinator"

        const val BLUETOOTH_DECLINED_MESSAGE =
            "Bluetooth is required for bitchat to discover and connect to nearby users. Please enable Bluetooth to continue."
        const val BLUETOOTH_TURNED_OFF_MESSAGE = "User has turned off their Bluetooth"
        const val BLUETOOTH_NOT_SUPPORTED_MESSAGE =
            "This device doesn't support Bluetooth, which is required for bitchat to function."
        const val LOCATION_DECLINED_MESSAGE =
            "Location services are required for Bluetooth scanning on Android. Please enable location services to continue."
        const val LOCATION_TURNED_OFF_MESSAGE = "Location services have been disabled."

        private val PERMISSION_PHASE = setOf(
            OnboardingStep.PERMISSION_EXPLANATION,
            OnboardingStep.PERMISSION_REQUESTING,
            OnboardingStep.BACKGROUND_LOCATION,
        )
    }

    /** What the queue holds: a tap or dialog result, a radio change, or opening's hand-off to the host. */
    private sealed interface Work {
        data class Act(val action: OnboardingAction) : Work
        data class Event(val event: SystemEvent) : Work
        data object Finish : Work
    }

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    private val _effects = Channel<OnboardingEffect>(Channel.BUFFERED)
    val effects: Flow<OnboardingEffect> = _effects.receiveAsFlow()

    private val work = Channel<Work>(Channel.UNLIMITED)

    init {
        // Synchronously, so the route's first frame already shows the step.
        // Anything that reaches the host waits in the queue.
        open()
        // Dispatchers.Main rather than viewModelScope's immediate: the view model
        // is created during composition, and nothing should reach the host there.
        viewModelScope.launch(Dispatchers.Main) {
            for (item in work) handleWork(item)
        }
        viewModelScope.launch(Dispatchers.Main) {
            readiness.systemEvents.collect { work.send(Work.Event(it)) }
        }
    }

    fun onAction(a: OnboardingAction) {
        work.trySend(Work.Act(a))
    }

    private suspend fun handleWork(item: Work) {
        when (item) {
            is Work.Act -> handleAction(item.action)
            is Work.Event -> if (_state.value.step !in PERMISSION_PHASE) handleEvent(item.event)
            Work.Finish -> host.onboardingFinished()
        }
    }

    private suspend fun handleAction(action: OnboardingAction) {
        when (action) {
            OnboardingAction.BluetoothEnableTapped -> {
                _state.update { it.copy(bluetoothLoading = true) }
                _effects.send(OnboardingEffect.LaunchBluetoothEnable)
            }
            OnboardingAction.BluetoothSkipTapped -> {
                skip.skip()
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
            OnboardingAction.BluetoothNotSupportedAtStart -> handleBluetoothDisabled(BLUETOOTH_NOT_SUPPORTED_MESSAGE)

            OnboardingAction.LocationOpenSettingsTapped -> {
                _state.update { it.copy(locationLoading = true) }
                _effects.send(OnboardingEffect.LaunchLocationSettings)
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

            OnboardingAction.BatteryDisableTapped -> {
                _state.update { it.copy(batteryLoading = true) }
                _effects.send(OnboardingEffect.LaunchBatteryOptimization)
            }
            OnboardingAction.BatteryCheckAgainTapped -> checkBatteryOptimizationAndProceed()
            OnboardingAction.BatterySkipTapped -> {
                // Skip battery optimization and proceed
                readiness.setBatterySkipped()
                proceedWithPermissionCheck()
            }
            OnboardingAction.BatteryResult -> handleBatteryOptimizationDisabled()
            is OnboardingAction.BatteryLaunchFailed -> handleBatteryOptimizationFailed(action.message)

            OnboardingAction.GrantPermissionsTapped -> {
                setStep(OnboardingStep.PERMISSION_REQUESTING)
                requestPermissions()
            }
            is OnboardingAction.PermissionResults -> handlePermissionResults(action.results)
            OnboardingAction.BackgroundGrantTapped -> requestBackgroundLocation()
            is OnboardingAction.BackgroundResult -> handleBackgroundLocationResult(action.granted)
            OnboardingAction.BackgroundCheckAgainTapped -> checkBackgroundLocationAndProceed()
            OnboardingAction.BackgroundSkipTapped -> skipBackgroundLocation()

            OnboardingAction.TryAgainTapped -> {
                // Back to checking (T40); the gate runs the checks again and
                // reopens onboarding on the step they find.
                setStep(OnboardingStep.CHECKING)
                host.retry()
            }
            OnboardingAction.OpenSettingsTapped -> _effects.send(OnboardingEffect.OpenAppSettings)
        }
    }

    /** The radios, as onboarding has always heard them (T4, T11, T12, T18, T19). */
    private suspend fun handleEvent(event: SystemEvent) {
        when (event) {
            // Both, in this order: re-run the check if its screen is showing,
            // then take the Bluetooth-enabled path.
            SystemEvent.BluetoothOn -> {
                if (_state.value.step == OnboardingStep.BLUETOOTH) {
                    checkBluetoothAndProceed()
                }
                handleBluetoothEnabled()
            }
            SystemEvent.BluetoothOff -> handleBluetoothDisabled(BLUETOOTH_TURNED_OFF_MESSAGE)
            is SystemEvent.LocationChanged -> {
                if (event.enabled) {
                    handleLocationEnabled()
                } else {
                    handleLocationDisabled(LOCATION_TURNED_OFF_MESSAGE)
                }
            }
        }
    }

    /**
     * The step onboarding opens on. An adapter missing at start is the error
     * step (T2); so is the app's init failure. Bluetooth turned off in chat
     * opens on the Bluetooth step whatever the skips say (T12). Otherwise the
     * step readiness implies.
     */
    private fun open() {
        val r = readiness.snapshot()
        val initError = host.initError.value
        when {
            r.bluetooth == BluetoothStatus.NOT_SUPPORTED ->
                handleBluetoothDisabled(BLUETOOTH_NOT_SUPPORTED_MESSAGE)
            initError != null -> {
                _state.update { it.copy(errorMessage = initError) }
                setStep(OnboardingStep.ERROR)
            }
            host.takeBluetoothTurnedOff() -> handleBluetoothDisabled(BLUETOOTH_TURNED_OFF_MESSAGE)
            else -> showStepReadinessImplies(r)
        }
    }

    /** The step readiness implies, written the way the checks write it. */
    private fun showStepReadinessImplies(r: Readiness) {
        val phase = if (r.firstLaunch) OnboardingPhase.FIRST_LAUNCH else OnboardingPhase.RETURNING
        when (val step = nextStep(r, phase, skip.skipped.value)) {
            // Nothing to do. The host hears it from the queue, not from inside composition.
            null -> {
                setStep(OnboardingStep.CHECKING)
                work.trySend(Work.Finish)
            }
            OnboardingStep.BLUETOOTH -> {
                logBluetoothDiagnostics(r)
                showBluetoothStep(r.bluetooth)
            }
            OnboardingStep.LOCATION -> {
                logLocationDiagnostics(r)
                showLocationStep(r.location)
            }
            OnboardingStep.BATTERY_OPTIMIZATION -> {
                logBatteryDiagnostics(r)
                showBatteryStep(r.batteryOptimization)
            }
            else -> setStep(step)
        }
    }

    /** Onboarding is through: no step on screen, and the gate starts the app. */
    private fun finishOnboarding() {
        setStep(OnboardingStep.CHECKING)
        host.onboardingFinished()
    }

    private fun setStep(step: OnboardingStep) {
        _state.update {
            if (step == OnboardingStep.PERMISSION_EXPLANATION) {
                it.copy(step = step, permissionCards = cards.categories())
            } else {
                it.copy(step = step)
            }
        }
    }

    private fun showBluetoothStep(status: BluetoothStatus) {
        _state.update { it.copy(bluetooth = status) }
        setStep(OnboardingStep.BLUETOOTH)
        _state.update { it.copy(bluetoothLoading = false) }
    }

    private fun showLocationStep(status: LocationStatus) {
        _state.update { it.copy(location = status) }
        setStep(OnboardingStep.LOCATION)
        _state.update { it.copy(locationLoading = false) }
    }

    private fun showBatteryStep(status: BatteryOptimizationStatus) {
        _state.update { it.copy(battery = status) }
        setStep(OnboardingStep.BATTERY_OPTIMIZATION)
        _state.update { it.copy(batteryLoading = false) }
    }

    /**
     * Check Bluetooth status and proceed with onboarding flow
     */
    private suspend fun checkBluetoothAndProceed() {
        // Check if user has skipped Bluetooth check for this session
        if (skip.skipped.value) {
            checkLocationAndProceed()
            return
        }

        val r = readiness.snapshot()

        // For first-time users, skip Bluetooth check and go straight to permissions
        // We'll check Bluetooth after permissions are granted
        if (r.firstLaunch) {
            proceedWithPermissionCheck()
            return
        }

        // For existing users, check Bluetooth status first
        logBluetoothDiagnostics(r)
        _state.update { it.copy(bluetooth = r.bluetooth) }

        if (!r.bleRequired) {
            // Skip BLE checks entirely when BLE is disabled in debug settings
            checkLocationAndProceed()
            return
        }
        when (r.bluetooth) {
            BluetoothStatus.ENABLED -> {
                // Bluetooth is enabled, check location services next
                checkLocationAndProceed()
            }
            BluetoothStatus.DISABLED -> {
                // Show Bluetooth enable screen (should have permissions as existing user)
                showBluetoothStep(r.bluetooth)
            }
            BluetoothStatus.NOT_SUPPORTED -> {
                // Device doesn't support Bluetooth
                Log.e(ACTIVITY_TAG, "Bluetooth not supported")
                showBluetoothStep(r.bluetooth)
            }
        }
    }

    /**
     * Proceed with permission checking
     */
    private suspend fun proceedWithPermissionCheck() {
        delay(200) // Small delay for smooth transition

        val r = readiness.snapshot()
        if (r.firstLaunch) {
            setStep(OnboardingStep.PERMISSION_EXPLANATION)
        } else if (r.unrequestedOptionalPermissions.isNotEmpty()) {
            setStep(OnboardingStep.PERMISSION_EXPLANATION)
        } else if (r.requiredPermissionsGranted) {
            if (shouldRequestBackgroundLocation(r)) {
                setStep(OnboardingStep.BACKGROUND_LOCATION)
            } else {
                finishOnboarding()
            }
        } else {
            setStep(OnboardingStep.PERMISSION_EXPLANATION)
        }
    }

    /**
     * Handle Bluetooth enabled callback
     */
    private suspend fun handleBluetoothEnabled() {
        _state.update { it.copy(bluetoothLoading = false, bluetooth = BluetoothStatus.ENABLED) }
        checkLocationAndProceed()
    }

    /**
     * Check Location services status and proceed with onboarding flow
     */
    private suspend fun checkLocationAndProceed() {
        val r = readiness.snapshot()

        // For first-time users, skip location check and go straight to permissions
        // We'll check location after permissions are granted
        if (r.firstLaunch) {
            proceedWithPermissionCheck()
            return
        }

        // For existing users, check location status
        logLocationDiagnostics(r)
        _state.update { it.copy(location = r.location) }

        when (r.location) {
            LocationStatus.ENABLED -> {
                // Location services enabled, check battery optimization next
                checkBatteryOptimizationAndProceed()
            }
            LocationStatus.DISABLED -> {
                // Show location enable screen (should have permissions as existing user)
                showLocationStep(r.location)
            }
            LocationStatus.NOT_AVAILABLE -> {
                // Device doesn't support location services (very unusual)
                Log.e(ACTIVITY_TAG, "Location services not available")
                showLocationStep(r.location)
            }
        }
    }

    /**
     * Handle Location enabled callback
     */
    private suspend fun handleLocationEnabled() {
        _state.update { it.copy(locationLoading = false, location = LocationStatus.ENABLED) }
        // Ensure Wi-Fi Aware starts now that location is enabled
        host.locationServicesEnabled()
        checkBatteryOptimizationAndProceed()
    }

    /**
     * Handle Location disabled callback
     */
    private fun handleLocationDisabled(message: String) {
        Log.w(ACTIVITY_TAG, "Location services disabled or failed: $message")
        val status = readiness.snapshot().location
        _state.update { it.copy(locationLoading = false, location = status) }

        if (status == LocationStatus.NOT_AVAILABLE) {
            // Show permanent error for devices without location services
            _state.update { it.copy(errorMessage = message) }
            setStep(OnboardingStep.ERROR)
        } else {
            // Stay on location check screen for retry
            setStep(OnboardingStep.LOCATION)
        }
    }

    /**
     * Handle Bluetooth disabled callback
     */
    private fun handleBluetoothDisabled(message: String) {
        Log.w(ACTIVITY_TAG, "Bluetooth disabled or failed: $message")
        val status = readiness.snapshot().bluetooth
        _state.update { it.copy(bluetoothLoading = false, bluetooth = status) }

        // No message says "Permission": the one about permissions says "permissions" (Q3).
        if (status == BluetoothStatus.NOT_SUPPORTED) {
            // Show permanent error for unsupported devices
            _state.update { it.copy(errorMessage = message) }
            setStep(OnboardingStep.ERROR)
        } else {
            // Stay on Bluetooth check screen for retry
            setStep(OnboardingStep.BLUETOOTH)
        }
    }

    /**
     * After the permissions, the radios and battery again, with both skips
     * honoured (F6): the same order as on every other path.
     */
    private fun handleOnboardingComplete() {
        val r = readiness.snapshot()
        when (nextStep(r, OnboardingPhase.AFTER_PERMISSIONS, skip.skipped.value)) {
            // Bluetooth still disabled, but now we have permissions to enable it
            OnboardingStep.BLUETOOTH -> showBluetoothStep(r.bluetooth)
            // Location services still disabled, but now we have permissions to enable it
            OnboardingStep.LOCATION -> showLocationStep(r.location)
            // Battery optimization still enabled, show battery optimization screen
            OnboardingStep.BATTERY_OPTIMIZATION -> showBatteryStep(r.batteryOptimization)
            // Both are enabled, proceed to app initialization
            else -> finishOnboarding()
        }
    }

    private fun handleOnboardingFailed(message: String) {
        Log.e(ACTIVITY_TAG, "Onboarding failed: $message")
        _state.update { it.copy(errorMessage = message) }
        setStep(OnboardingStep.ERROR)
    }

    /**
     * Check Battery Optimization status and proceed with onboarding flow
     */
    private suspend fun checkBatteryOptimizationAndProceed() {
        val r = readiness.snapshot()

        // For first-time users, skip battery optimization check and go straight to permissions
        // We'll check battery optimization after permissions are granted
        if (r.firstLaunch) {
            proceedWithPermissionCheck()
            return
        }

        // Check if user has previously skipped battery optimization
        if (r.batterySkipped) {
            proceedWithPermissionCheck()
            return
        }

        // For existing users, check battery optimization status
        logBatteryDiagnostics(r)
        _state.update { it.copy(battery = r.batteryOptimization) }

        when (r.batteryOptimization) {
            BatteryOptimizationStatus.DISABLED, BatteryOptimizationStatus.NOT_SUPPORTED -> {
                // Battery optimization is disabled or not supported, proceed with permission check
                proceedWithPermissionCheck()
            }
            BatteryOptimizationStatus.ENABLED -> {
                // Show battery optimization disable screen
                showBatteryStep(r.batteryOptimization)
            }
        }
    }

    /**
     * Handle Battery Optimization disabled callback
     */
    private suspend fun handleBatteryOptimizationDisabled() {
        _state.update { it.copy(batteryLoading = false, battery = BatteryOptimizationStatus.DISABLED) }
        proceedWithPermissionCheck()
    }

    /**
     * Handle Battery Optimization failed callback
     */
    private fun handleBatteryOptimizationFailed(message: String) {
        Log.w(ACTIVITY_TAG, "Battery optimization disable failed: $message")
        val status = readiness.snapshot().batteryOptimization
        _state.update { it.copy(batteryLoading = false, battery = status) }

        // Stay on battery optimization check screen for retry
        setStep(OnboardingStep.BATTERY_OPTIMIZATION)
    }

    // ---- Formerly OnboardingCoordinator ----

    /**
     * Called when user accepts the permission explanation
     */
    private suspend fun requestPermissions() {
        Log.d(COORDINATOR_TAG, "User accepted permission explanation, requesting permissions")

        val r = readiness.snapshot()

        // Required permissions
        val missingRequired = r.missingPermissions

        // Optional permissions (ask, but do not block if denied)
        val optionalToRequest = r.unrequestedOptionalPermissions

        val missingPermissions = (missingRequired + optionalToRequest).distinct()

        if (missingPermissions.isEmpty()) {
            if (shouldRequestBackgroundLocation(r)) {
                onBackgroundLocationRequired()
            } else {
                completeOnboarding()
            }
            return
        }

        Log.d(COORDINATOR_TAG, "Requesting ${missingPermissions.size} permissions")
        readiness.markOptionalPermissionsRequested(optionalToRequest)
        _effects.send(OnboardingEffect.RequestPermissions(missingPermissions))
    }

    /**
     * Handle permission request results
     */
    private suspend fun handlePermissionResults(permissions: Map<String, Boolean>) {
        Log.d(COORDINATOR_TAG, "Received permission results:")
        permissions.forEach { (permission, granted) ->
            Log.d(COORDINATOR_TAG, "  $permission: ${if (granted) "GRANTED" else "DENIED"}")
        }

        val allGranted = permissions.values.all { it }
        val criticalPermissions = getCriticalPermissions()
        // The launcher result only contains permissions requested in this round. Returning
        // users may be asked for POST_NOTIFICATIONS alone, so re-check required permissions
        // against package state instead of treating absent result-map entries as denials.
        val criticalGranted = criticalPermissions.all(readiness::isPermissionGranted)

        when {
            criticalGranted -> {
                if (shouldRequestBackgroundLocation(readiness.snapshot())) {
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

    private suspend fun requestBackgroundLocation() {
        val permission = readiness.backgroundLocationPermission()
        if (permission == null) {
            completeOnboarding()
            return
        }
        Log.d(COORDINATOR_TAG, "Requesting background location permission")
        _effects.send(OnboardingEffect.RequestBackgroundLocation(permission))
    }

    private suspend fun handleBackgroundLocationResult(granted: Boolean) {
        if (granted) {
            Log.d(COORDINATOR_TAG, "Background location permission granted")
        } else {
            Log.w(COORDINATOR_TAG, "Background location permission denied; continuing without it")
        }
        completeOnboarding()
    }

    private suspend fun skipBackgroundLocation() {
        Log.d(COORDINATOR_TAG, "User skipped background location permission")
        readiness.setBackgroundLocationSkipped()
        completeOnboarding()
    }

    private suspend fun checkBackgroundLocationAndProceed() {
        if (!shouldRequestBackgroundLocation(readiness.snapshot())) {
            completeOnboarding()
        }
    }

    private fun shouldRequestBackgroundLocation(r: Readiness): Boolean =
        r.backgroundLocation == BackgroundLocation.NEEDED && !r.backgroundLocationSkipped

    private fun onBackgroundLocationRequired() {
        setStep(OnboardingStep.BACKGROUND_LOCATION)
    }

    /**
     * Get the list of critical permissions that are absolutely required
     */
    private fun getCriticalPermissions(): List<String> {
        // For bitchat, Bluetooth and location permissions are critical
        // Notifications are nice-to-have but not critical and are not included in getRequiredPermissions()
        return readiness.requiredPermissions()
    }

    /**
     * Show warning when some permissions are granted but others are denied
     */
    private suspend fun showPartialPermissionWarning(permissions: Map<String, Boolean>) {
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
    private suspend fun handlePermissionDenial(permissions: Map<String, Boolean>) {
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
    private suspend fun completeOnboarding() {
        Log.d(COORDINATOR_TAG, "Completing onboarding process")

        // Mark onboarding as complete
        readiness.markOnboardingComplete()

        // Log final permission status
        cards.logDiagnostics()

        // Notify completion with a small delay to ensure everything is ready
        delay(100) // Small delay for UI state to settle
        handleOnboardingComplete()
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

    // Diagnostics, logged where the status managers logged theirs. Logging only.
    private fun logBluetoothDiagnostics(r: Readiness) =
        Log.d("BluetoothStatusManager", "Bluetooth status: ${r.bluetooth} (BLE required: ${r.bleRequired})")

    private fun logLocationDiagnostics(r: Readiness) =
        Log.d("LocationStatusManager", "Location services status: ${r.location}")

    private fun logBatteryDiagnostics(r: Readiness) =
        Log.d("BatteryOptimizationManager", "Battery optimization status: ${r.batteryOptimization}")
}
