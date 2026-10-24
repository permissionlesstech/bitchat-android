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
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Where the app is between launch and chat. */
enum class AppPhase {
    /** Deciding whether onboarding is needed. Chat shows meanwhile. */
    CHECKING,

    /** Onboarding shows; it decides its own step. */
    ONBOARDING,

    /** The app is starting (P6). Chat shows meanwhile. */
    INITIALIZING,

    READY,

    /** P6 failed; onboarding shows [AppStartupGate.initError] as its error step. */
    INIT_FAILED,
}

/**
 * The one place that decides between onboarding and chat. Retained across
 * configuration changes and cleared with the Activity.
 *
 * Init runs once per entry into [AppPhase.INITIALIZING]: whoever runs it takes
 * the request with [takeInitRequest], which says yes once per entry. When init
 * finishes the gate re-reads readiness, so a Bluetooth or location drop during
 * init ends in onboarding instead of being overwritten by READY (F1).
 */
@ActivityRetainedScoped
class AppStartupGate @Inject constructor(
    private val readiness: DeviceReadiness,
    private val skip: BluetoothSkipSession,
    @AppStartupScope private val scope: CoroutineScope,
) : OnboardingHost {

    private companion object {
        const val TAG = "AppStartupGate"
    }

    private val _phase = MutableStateFlow(AppPhase.CHECKING)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val _initError = MutableStateFlow<String?>(null)
    override val initError: StateFlow<String?> = _initError.asStateFlow()

    private var check: Job? = null
    private var initRequested = false

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
        }
    }

    /**
     * True once per entry into INITIALIZING, for whoever runs init. A recreated
     * Activity that finds the gate already initializing gets false.
     */
    fun takeInitRequest(): Boolean {
        if (_phase.value != AppPhase.INITIALIZING || !initRequested) return false
        initRequested = false
        return true
    }

    /** Onboarding is done, or an init error was moved on from: init runs. Ignored otherwise. */
    override fun onboardingFinished() {
        if (_phase.value == AppPhase.ONBOARDING || _phase.value == AppPhase.INIT_FAILED) {
            enterInitializing()
        }
    }

    /** Init finished. READY, unless Bluetooth or location dropped meanwhile. */
    fun initSucceeded() {
        if (_phase.value != AppPhase.INITIALIZING) return
        _phase.value = if (dropped(readiness.snapshot())) AppPhase.ONBOARDING else AppPhase.READY
    }

    fun initFailed(message: String) {
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
            SystemEvent.BluetoothOff -> _phase.value = AppPhase.ONBOARDING
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

    /** Try again on an error step: back to CHECKING, then the checks (T40). */
    override fun retry() {
        if (_phase.value != AppPhase.ONBOARDING && _phase.value != AppPhase.INIT_FAILED) return
        _initError.value = null
        _phase.value = AppPhase.CHECKING
        start()
    }

    /** Puts the gate in [phase] directly, for device tests that cross between onboarding and chat. */
    @VisibleForTesting
    internal fun forcePhase(phase: AppPhase) {
        _phase.value = phase
    }

    private fun enterInitializing() {
        _initError.value = null
        initRequested = true
        _phase.value = AppPhase.INITIALIZING
    }

    // The onResume re-check's rule: Bluetooth counts unless skipped or switched
    // off in the debug settings; location services always count.
    private fun bluetoothDropped(r: Readiness) =
        r.bleRequired && r.bluetooth != BluetoothStatus.ENABLED && !skip.skipped.value

    private fun dropped(r: Readiness) = bluetoothDropped(r) || r.location != LocationStatus.ENABLED
}
