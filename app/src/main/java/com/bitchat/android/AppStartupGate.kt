package com.bitchat.android

import android.util.Log
import androidx.annotation.VisibleForTesting
import com.bitchat.android.di.AppStartupScope
import com.bitchat.android.onboarding.BluetoothSkipSession
import com.bitchat.android.onboarding.BluetoothStatus
import com.bitchat.android.onboarding.DeviceReadiness
import com.bitchat.android.onboarding.LocationStatus
import com.bitchat.android.onboarding.OnboardingHost
import com.bitchat.android.onboarding.OnboardingPhase
import com.bitchat.android.onboarding.Readiness
import com.bitchat.android.onboarding.SystemEvent
import com.bitchat.android.onboarding.nextStep
import com.bitchat.android.wifiaware.WifiAwareController
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the app is between launch and chat. */
enum class AppPhase {
    /**
     * Deciding whether onboarding is needed. Chat shows meanwhile for a
     * returning user; onboarding's spinner on first launch and after Try again.
     */
    CHECKING,

    /** Onboarding shows; it decides its own step. */
    ONBOARDING,

    /** The app is starting (P6, run by the gate). Chat shows meanwhile. */
    INITIALIZING,

    READY,

    /** P6 failed; onboarding shows [AppStartupGate.initError] as its error step. */
    INIT_FAILED,
}

/**
 * The one place that decides between onboarding and chat. Retained across
 * configuration changes and cleared with the Activity.
 *
 * Init (P6) runs once per entry into [AppPhase.INITIALIZING], on the gate's own
 * scope, so recreating the Activity mid-init neither cancels nor repeats it
 * (F2). When init finishes the gate re-reads readiness, so a Bluetooth or
 * location drop during init ends in onboarding instead of being overwritten by
 * READY (F1).
 */
@ActivityRetainedScoped
class AppStartupGate @Inject constructor(
    private val readiness: DeviceReadiness,
    private val skip: BluetoothSkipSession,
    private val initializer: AppInitializer,
    @AppStartupScope private val scope: CoroutineScope,
    private val wifiAwareController: WifiAwareController,
) : OnboardingHost {

    private companion object {
        const val TAG = "AppStartupGate"
    }

    private val _phase = MutableStateFlow(AppPhase.CHECKING)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    override val initError: StateFlow<String?> = _initError.asStateFlow()

    // True while CHECKING after Try again, so onboarding stays on screen (F8).
    private val _retrying = MutableStateFlow(false)
    val retrying: StateFlow<Boolean> = _retrying.asStateFlow()

    private var check: Job? = null
    private var init: Job? = null
    private var initCompleted = false

    // Bluetooth turned off while READY, for onboarding to open on its step (T12).
    private var bluetoothTurnedOff = false

    /** From CHECKING, after 500 ms, decides between onboarding and init. Once per check. */
    fun start() {
        if (_phase.value != AppPhase.CHECKING || check?.isActive == true) return
        check = scope.launch {
            // Small delay to show the checking state
            delay(500)
            val r = readiness.snapshot()
            val phase = if (r.firstLaunch) OnboardingPhase.FIRST_LAUNCH else OnboardingPhase.RETURNING
            if (nextStep(r, phase, skip.skipped.value) == null) {
                enterInitializing()
            } else {
                _phase.value = AppPhase.ONBOARDING
            }
            _retrying.value = false
        }
    }

    /**
     * True once per init that ended in READY, for the Activity's part of
     * finishing it (the launch intent). A recreated Activity that finds the gate
     * already READY gets false.
     */
    fun takeInitCompleted(): Boolean {
        if (_phase.value != AppPhase.READY || !initCompleted) return false
        initCompleted = false
        return true
    }

    /** Onboarding is done, or an init error was moved on from: init runs. Ignored otherwise. */
    override fun onboardingFinished() {
        if (_phase.value == AppPhase.ONBOARDING || _phase.value == AppPhase.INIT_FAILED) {
            enterInitializing()
        }
    }

    /** Init finished. READY, unless Bluetooth or location dropped meanwhile. */
    private fun initSucceeded() {
        if (_phase.value != AppPhase.INITIALIZING) return
        if (dropped(readiness.snapshot())) {
            _phase.value = AppPhase.ONBOARDING
        } else {
            initCompleted = true
            _phase.value = AppPhase.READY
        }
    }

    private fun initFailed(message: String) {
        if (_phase.value != AppPhase.INITIALIZING) return
        Log.e(TAG, "Onboarding failed: $message")
        _initError.value = message
        _phase.value = AppPhase.INIT_FAILED
    }

    /**
     * A radio turned off while READY sends the app back to onboarding (T12/T19).
     * As before, Bluetooth off does so even when the Bluetooth step was skipped
     * this session or BLE is switched off in the debug settings, unlike
     * [onResumed]. Turning a radio on while READY changes nothing.
     */
    fun onSystemEvent(e: SystemEvent) {
        if (_phase.value != AppPhase.READY) return
        when (e) {
            SystemEvent.BluetoothOff -> {
                bluetoothTurnedOff = true
                _phase.value = AppPhase.ONBOARDING
            }
            is SystemEvent.LocationChanged -> if (!e.enabled) _phase.value = AppPhase.ONBOARDING
            SystemEvent.BluetoothOn -> Unit
        }
    }

    /** The Activity resumed while READY: back to onboarding if a radio went off meanwhile (T42/T43). */
    fun onResumed() {
        if (_phase.value != AppPhase.READY) return
        val r = readiness.snapshot()
        if (dropped(r)) {
            if (bluetoothDropped(r)) {
                Log.w(TAG, "Bluetooth disabled while app was backgrounded")
            } else {
                Log.w(TAG, "Location services disabled while app was backgrounded")
            }
            _phase.value = AppPhase.ONBOARDING
        }
    }

    override fun takeBluetoothTurnedOff(): Boolean {
        val turnedOff = bluetoothTurnedOff
        bluetoothTurnedOff = false
        return turnedOff
    }

    // Wi-Fi Aware waits on location services.
    override fun locationServicesEnabled() = wifiAwareController.startIfPossible()

    /** Try again on an error step: back to CHECKING, then the checks (T40). */
    override suspend fun retry(): Boolean {
        if (_phase.value != AppPhase.ONBOARDING && _phase.value != AppPhase.INIT_FAILED) return false
        _initError.value = null
        _retrying.value = true
        _phase.value = AppPhase.CHECKING
        start()
        check?.join()
        return _phase.value == AppPhase.ONBOARDING
    }

    /** Puts the gate in [phase] directly, for device tests that cross between onboarding and chat. */
    @VisibleForTesting
    internal fun forcePhase(phase: AppPhase) {
        _phase.value = phase
    }

    private fun enterInitializing() {
        _initError.value = null
        bluetoothTurnedOff = false
        initCompleted = false
        _phase.value = AppPhase.INITIALIZING
        init?.cancel()
        init = scope.launch { initializeApp() }
    }

    // P6, the part that needs no Activity. The Activity attaches the mesh
    // delegate on INITIALIZING and handles the launch intent on READY.
    private suspend fun initializeApp() {
        try {
            // Initialize the app with a proper delay to ensure Bluetooth stack is ready
            // This solves the issue where app needs restart to work on first install
            delay(1000) // Give the system time to process permission grants

            initializer.initializeLibraries()

            // Ensure all permissions are still granted (user might have revoked in settings)
            val r = readiness.snapshot()
            if (!r.requiredPermissionsGranted) {
                Log.w(TAG, "Permissions revoked during initialization: ${r.missingPermissions}")
                initFailed("Some permissions were revoked. Please grant all permissions to continue.")
                return
            }

            // Start enabled transports
            initializer.startServices()

            // Small delay to ensure mesh service is fully initialized
            delay(500)
            Log.i(TAG, "App initialization complete")
            initSucceeded()
        } catch (e: CancellationException) {
            // The retained scope was cleared: not an init failure (T44)
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize app", e)
            initFailed("Failed to initialize the app: ${e.message}")
        }
    }

    // The onResume re-check's rule: Bluetooth counts unless skipped or switched
    // off in the debug settings; location services always count.
    private fun bluetoothDropped(r: Readiness) =
        r.bleRequired && r.bluetooth != BluetoothStatus.ENABLED && !skip.skipped.value

    private fun dropped(r: Readiness) = bluetoothDropped(r) || r.location != LocationStatus.ENABLED
}
