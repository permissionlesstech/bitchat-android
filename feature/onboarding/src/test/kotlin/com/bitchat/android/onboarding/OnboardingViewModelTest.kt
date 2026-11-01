package com.bitchat.android.onboarding

import com.bitchat.android.onboarding.FakeOnboardingReadiness.Companion.BACKGROUND_LOCATION
import com.bitchat.android.onboarding.FakeOnboardingReadiness.Companion.BLUETOOTH_CONNECT
import com.bitchat.android.onboarding.FakeOnboardingReadiness.Companion.BLUETOOTH_SCAN
import com.bitchat.android.onboarding.FakeOnboardingReadiness.Companion.COARSE_LOCATION
import com.bitchat.android.onboarding.FakeOnboardingReadiness.Companion.FINE_LOCATION
import com.bitchat.android.onboarding.FakeOnboardingReadiness.Companion.POST_NOTIFICATIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The onboarding view model, ids from the Phase 9 inventory (T = event-driven
 * transition, P = decision procedure, C = completeOnboarding). Names ending in
 * `_currentBehaviour` lock a path that a later fix changes on purpose.
 *
 * Ported from OnboardingFlowTest. Where that test wrote a step into the old
 * view model before acting, this one opens the view model on that step from
 * readiness and then sets the device up. "No step" is [OnboardingStep.CHECKING].
 *
 * Whether onboarding shows at all is the app gate's (AppStartupGateTest).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingViewModelTest {

    private val system = FakeOnboardingReadiness()
    private val skip = BluetoothSkipSession()
    private val host = FakeOnboardingHost(system.effects)
    private val cards = object : PermissionCards {
        override fun categories() = CARDS
    }

    private lateinit var vm: OnboardingViewModel

    private val state get() = vm.uiState.value
    private val step get() = state.step

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A view model opening on what the device says. Its effects join [FakeOnboardingReadiness.effects]. */
    private fun TestScope.newViewModel() {
        vm = OnboardingViewModel(system, skip, host, cards)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            vm.events.collect { system.effects += describe(it) }
        }
    }

    private fun describe(event: OnboardingEvent) = when (event) {
        OnboardingEvent.LaunchBluetoothEnable -> "launchBluetoothEnable"
        OnboardingEvent.LaunchLocationSettings -> "launchLocationSettings"
        OnboardingEvent.LaunchBatteryOptimization -> "launchBatteryOptimization"
        is OnboardingEvent.RequestPermissions -> "launchPermissions:${event.permissions}"
        is OnboardingEvent.RequestBackgroundLocation -> "launchBackgroundLocation:${event.permission}"
        OnboardingEvent.OpenAppSettings -> "openAppSettings"
    }

    private fun act(action: OnboardingAction) = vm.onAction(action)

    private suspend fun radio(event: SystemEvent) = system.events.emit(event)

    /** Runs everything due up to and including [ms] from now. */
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    /** Onboarding finished: no step left, the host told once. */
    private fun assertFinished() {
        assertEquals(OnboardingStep.CHECKING, step)
        assertEquals(1, host.finished)
    }

    // ---- Opening on a step. Each puts back the device field it changed. ----

    private fun TestScope.onBluetoothStep() {
        system.bluetooth = BluetoothStatus.DISABLED
        newViewModel()
        assertEquals(OnboardingStep.BLUETOOTH, step)
        system.bluetooth = BluetoothStatus.ENABLED
    }

    private fun TestScope.onLocationStep() {
        system.location = LocationStatus.DISABLED
        newViewModel()
        assertEquals(OnboardingStep.LOCATION, step)
        system.location = LocationStatus.ENABLED
    }

    private fun TestScope.onBatteryStep() {
        system.battery = BatteryOptimizationStatus.ENABLED
        newViewModel()
        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        system.battery = BatteryOptimizationStatus.DISABLED
    }

    private fun TestScope.onPermissionExplanation() {
        system.firstTimeLaunch = true
        newViewModel()
        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        system.firstTimeLaunch = false
    }

    /** The permission dialog is up. */
    private fun TestScope.onPermissionRequesting() {
        onPermissionExplanation()
        system.granted -= FINE_LOCATION
        act(OnboardingAction.GrantPermissionsTapped)
        assertEquals(OnboardingStep.PERMISSION_REQUESTING, step)
        system.granted += FINE_LOCATION
        system.effects.clear()
    }

    private fun TestScope.onBackgroundStep() {
        system.backgroundLocationGranted = false
        newViewModel()
        assertEquals(OnboardingStep.BACKGROUND_LOCATION, step)
        system.backgroundLocationGranted = true
    }

    /** The app's init failed, which onboarding shows as its error step. */
    private fun TestScope.onErrorStep() {
        host.initError.value = "synthetic init failure"
        newViewModel()
        assertEquals(OnboardingStep.ERROR, step)
        assertEquals("synthetic init failure", state.errorMessage)
    }

    private fun startBluetoothLoading() {
        act(OnboardingAction.BluetoothEnableTapped)
        system.effects.clear()
    }

    private fun startLocationLoading() {
        act(OnboardingAction.LocationOpenSettingsTapped)
        system.effects.clear()
    }

    private fun startBatteryLoading() {
        act(OnboardingAction.BatteryDisableTapped)
        system.effects.clear()
    }

    // ---- T1, T2 ----

    @Test
    fun t1_newViewModel_readyDevice_finishesWithDefaults() = runTest {
        newViewModel()

        assertEquals(OnboardingUiState(), state)
        assertEquals(1, host.finished)
        assertFalse(skip.skipped.value)
    }

    @Test
    fun bluetoothNotSupported_showsBluetoothStepNotError() = runTest {
        system.bluetooth = BluetoothStatus.NOT_SUPPORTED

        newViewModel()

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.NOT_SUPPORTED, state.bluetooth)
        assertEquals("", state.errorMessage)
        assertFalse(state.bluetoothLoading)
    }

    // ---- Opening: the step readiness implies ----

    @Test
    fun open_firstLaunch_showsPermissionExplanationAtOnce() = runTest {
        system.firstTimeLaunch = true
        system.bluetooth = BluetoothStatus.DISABLED

        newViewModel()

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        assertEquals(CARDS, state.permissionCards)
    }

    @Test
    fun open_returningBatteryOptimized_showsBatteryCheck() = runTest {
        system.battery = BatteryOptimizationStatus.ENABLED

        newViewModel()

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertEquals(BatteryOptimizationStatus.ENABLED, state.battery)
        assertFalse(state.batteryLoading)
    }

    @Test
    fun open_returningBackgroundLocationNeeded_showsBackgroundExplanation() = runTest {
        system.backgroundLocationGranted = false

        newViewModel()

        assertEquals(OnboardingStep.BACKGROUND_LOCATION, step)
    }

    @Test
    fun open_bluetoothSkipped_showsLocationCheck() = runTest {
        skip.skip()
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        newViewModel()

        assertEquals(OnboardingStep.LOCATION, step)
    }

    @Test
    fun open_nothingToDo_finishesOnboarding() = runTest {
        newViewModel()

        assertFinished()
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun newViewModelMidPermissionPhase_recomputesStep() = runTest {
        // The permission dialog came back granted and the flag is written; the
        // process dies during the 100 ms before the re-check (F3).
        onPermissionRequesting()
        act(OnboardingAction.PermissionResults(system.required.associateWith { true }))
        assertEquals(listOf("markOnboardingComplete"), system.effects)
        system.bluetooth = BluetoothStatus.DISABLED

        newViewModel()

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
    }

    // ---- P1 checkBluetoothAndProceed (run directly by the Bluetooth step's check again) ----

    @Test
    fun p1_bluetoothCheckSkipped_runsLocationCheck() = runTest {
        onBluetoothStep()
        skip.skip()
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BluetoothCheckAgainTapped)
        runCurrent()

        assertEquals(OnboardingStep.LOCATION, step)
        // Bluetooth was not read: the device says ENABLED now.
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
    }

    @Test
    fun p1_firstTimeLaunch_showsPermissionExplanation() = runTest {
        onBluetoothStep()
        system.firstTimeLaunch = true

        act(OnboardingAction.BluetoothCheckAgainTapped)
        runCurrent()
        assertEquals(OnboardingStep.BLUETOOTH, step)
        advance(200)

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        // Bluetooth was not read: the device says ENABLED now.
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
    }

    @Test
    fun p1_bleSwitchedOffInDebug_writesStatusAndRunsLocationCheck() = runTest {
        onBluetoothStep()
        system.bleRequired = false
        system.bluetooth = BluetoothStatus.NOT_SUPPORTED
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BluetoothCheckAgainTapped)
        runCurrent()

        assertEquals(OnboardingStep.LOCATION, step)
        assertEquals(BluetoothStatus.NOT_SUPPORTED, state.bluetooth)
    }

    @Test
    fun p1_bluetoothEnabled_runsLocationCheck() = runTest {
        onBluetoothStep()
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BluetoothCheckAgainTapped)
        runCurrent()

        assertEquals(OnboardingStep.LOCATION, step)
    }

    @Test
    fun p1_returningUserBluetoothDisabled_showsBluetoothCheck() = runTest {
        onBluetoothStep()
        startBluetoothLoading()
        system.bluetooth = BluetoothStatus.DISABLED

        act(OnboardingAction.BluetoothCheckAgainTapped)
        runCurrent()

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
        assertFalse(state.bluetoothLoading)
    }

    @Test
    fun p1_bluetoothNotSupported_showsBluetoothCheck() = runTest {
        onBluetoothStep()
        startBluetoothLoading()
        system.bluetooth = BluetoothStatus.NOT_SUPPORTED

        act(OnboardingAction.BluetoothCheckAgainTapped)
        runCurrent()

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.NOT_SUPPORTED, state.bluetooth)
        assertFalse(state.bluetoothLoading)
    }

    // ---- P2 checkLocationAndProceed ----

    @Test
    fun p2_firstTimeLaunch_showsPermissionExplanation() = runTest {
        onLocationStep()
        system.firstTimeLaunch = true

        act(OnboardingAction.LocationCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        // Location was not read: the device says ENABLED now.
        assertEquals(LocationStatus.DISABLED, state.location)
    }

    @Test
    fun p2_locationEnabled_runsBatteryCheck() = runTest {
        onLocationStep()
        system.battery = BatteryOptimizationStatus.ENABLED

        act(OnboardingAction.LocationCheckAgainTapped)

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
    }

    @Test
    fun p2_locationDisabled_showsLocationCheck() = runTest {
        onLocationStep()
        startLocationLoading()
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.LocationCheckAgainTapped)

        assertEquals(OnboardingStep.LOCATION, step)
        assertEquals(LocationStatus.DISABLED, state.location)
        assertFalse(state.locationLoading)
    }

    @Test
    fun p2_locationNotAvailable_showsLocationCheck() = runTest {
        onLocationStep()
        startLocationLoading()
        system.location = LocationStatus.NOT_AVAILABLE

        act(OnboardingAction.LocationCheckAgainTapped)

        assertEquals(OnboardingStep.LOCATION, step)
        assertEquals(LocationStatus.NOT_AVAILABLE, state.location)
        assertFalse(state.locationLoading)
    }

    // ---- P3 checkBatteryOptimizationAndProceed ----

    @Test
    fun p3_firstTimeLaunch_showsPermissionExplanation() = runTest {
        onBatteryStep()
        system.firstTimeLaunch = true
        system.battery = BatteryOptimizationStatus.NOT_SUPPORTED

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        // Battery was not read.
        assertEquals(BatteryOptimizationStatus.ENABLED, state.battery)
    }

    @Test
    fun p3_batterySkipped_runsPermissionCheck() = runTest {
        onBatteryStep()
        system.batterySkipped = true
        system.battery = BatteryOptimizationStatus.NOT_SUPPORTED

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertFinished()
        // Battery was not read.
        assertEquals(BatteryOptimizationStatus.ENABLED, state.battery)
    }

    @Test
    fun p3_batteryOptimizationDisabled_runsPermissionCheck() = runTest {
        onBatteryStep()
        system.battery = BatteryOptimizationStatus.DISABLED

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertFinished()
        assertEquals(BatteryOptimizationStatus.DISABLED, state.battery)
    }

    @Test
    fun p3_batteryNotSupported_runsPermissionCheck() = runTest {
        onBatteryStep()
        system.battery = BatteryOptimizationStatus.NOT_SUPPORTED

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertFinished()
        assertEquals(BatteryOptimizationStatus.NOT_SUPPORTED, state.battery)
    }

    @Test
    fun p3_batteryOptimizationEnabled_showsBatteryCheck() = runTest {
        onBatteryStep()
        startBatteryLoading()
        system.battery = BatteryOptimizationStatus.ENABLED

        act(OnboardingAction.BatteryCheckAgainTapped)

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertEquals(BatteryOptimizationStatus.ENABLED, state.battery)
        assertFalse(state.batteryLoading)
    }

    // ---- P4 proceedWithPermissionCheck (reached here through P3 with battery already off) ----

    @Test
    fun p4_firstTimeLaunch_showsPermissionExplanationAfter200ms() = runTest {
        onBatteryStep()
        system.firstTimeLaunch = true

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(199)
        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        advance(1)

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
    }

    @Test
    fun p4_optionalPermissionUnrequested_showsPermissionExplanation() = runTest {
        onBatteryStep()
        system.unrequestedOptional = listOf(POST_NOTIFICATIONS)

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun p4_backgroundLocationNeeded_showsBackgroundLocationExplanation() = runTest {
        onBatteryStep()
        system.backgroundLocationGranted = false

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingStep.BACKGROUND_LOCATION, step)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun p4_backgroundLocationSkipped_initializes() = runTest {
        onBatteryStep()
        system.backgroundLocationGranted = false
        system.backgroundLocationSkipped = true

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertFinished()
        assertEquals(emptyList<String>(), system.effects)
    }

    @Test
    fun p4_backgroundLocationNotNeeded_initializes() = runTest {
        onBatteryStep()
        system.needsBackgroundLocation = false
        system.backgroundLocationGranted = false

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertFinished()
        assertEquals(emptyList<String>(), system.effects)
    }

    @Test
    fun p4_requiredGranted_initializes() = runTest {
        onBatteryStep()

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertFinished()
        assertEquals(emptyList<String>(), system.effects)
    }

    @Test
    fun p4_requiredMissing_showsPermissionExplanation() = runTest {
        onBatteryStep()
        system.granted -= FINE_LOCATION

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        assertTrue(system.effects.isEmpty())
    }

    // ---- C completeOnboarding ----

    @Test
    fun c_complete_marksFlagThenRechecksAfter100ms() = runTest {
        onBackgroundStep()

        act(OnboardingAction.BackgroundResult(granted = true))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(99)
        assertEquals(OnboardingStep.BACKGROUND_LOCATION, step)
        advance(1)
        assertFinished()
        assertEquals(listOf("markOnboardingComplete"), system.effects)
    }

    // ---- P5 handleOnboardingComplete (reached through C); both skips honoured (F6) ----

    @Test
    fun p5_bluetoothOff_showsBluetoothCheck() = runTest {
        onBackgroundStep()
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
        assertFalse(state.bluetoothLoading)
    }

    @Test
    fun p5_bluetoothOffButSkipped_showsLocationCheck() = runTest {
        // Before F6 this showed the Bluetooth step.
        onBackgroundStep()
        skip.skip()
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingStep.LOCATION, step)
    }

    @Test
    fun p5_bleSwitchedOffInDebug_ignoresBluetooth() = runTest {
        onBackgroundStep()
        system.bleRequired = false
        system.bluetooth = BluetoothStatus.DISABLED

        act(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertFinished()
        assertEquals(BluetoothStatus.ENABLED, state.bluetooth)
    }

    @Test
    fun p5_locationOff_showsLocationCheck() = runTest {
        onBackgroundStep()
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingStep.LOCATION, step)
        assertEquals(LocationStatus.DISABLED, state.location)
        assertFalse(state.locationLoading)
    }

    @Test
    fun p5_batteryOptimized_showsBatteryCheck() = runTest {
        onBackgroundStep()
        system.battery = BatteryOptimizationStatus.ENABLED

        act(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertEquals(BatteryOptimizationStatus.ENABLED, state.battery)
        assertFalse(state.batteryLoading)
    }

    @Test
    fun afterPermissions_honoursBatterySkip() = runTest {
        // A returning user asked for a new optional permission, battery skipped
        // before. Before F6 the battery step came back after the dialog.
        system.battery = BatteryOptimizationStatus.ENABLED
        system.batterySkipped = true
        system.unrequestedOptional = listOf(POST_NOTIFICATIONS)
        newViewModel()
        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)

        act(OnboardingAction.GrantPermissionsTapped)
        act(OnboardingAction.PermissionResults(mapOf(POST_NOTIFICATIONS to true)))
        advance(100)

        assertFinished()
    }

    @Test
    fun p5_allReady_initializes() = runTest {
        onBackgroundStep()

        act(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertFinished()
        assertEquals(listOf("markOnboardingComplete"), system.effects)
    }

    // ---- T4-T12 Bluetooth ----

    @Test
    fun t4_bluetoothOnWhileOnBluetoothCheck_rerunsBluetoothCheck() = runTest {
        onBluetoothStep()
        system.location = LocationStatus.DISABLED

        radio(SystemEvent.BluetoothOn)

        assertEquals(OnboardingStep.LOCATION, step)
    }

    @Test
    fun t4_bluetoothOnOnAnotherStep_ignored() = runTest {
        onPermissionExplanation()
        system.location = LocationStatus.DISABLED

        radio(SystemEvent.BluetoothOn)
        advance(10_000)

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t5_enableTapped_setsLoadingAndLaunchesDialog() = runTest {
        onBluetoothStep()

        act(OnboardingAction.BluetoothEnableTapped)

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertTrue(state.bluetoothLoading)
        assertEquals(listOf("launchBluetoothEnable"), system.effects)
    }

    @Test
    fun t6_bluetoothEnabledResult_runsLocationCheck() = runTest {
        onBluetoothStep()
        startBluetoothLoading()
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BluetoothResult(enabled = true))

        assertEquals(OnboardingStep.LOCATION, step)
        assertEquals(BluetoothStatus.ENABLED, state.bluetooth)
        assertFalse(state.bluetoothLoading)
    }

    @Test
    fun t7_bluetoothDeclined_staysOnBluetoothCheck() = runTest {
        onBluetoothStep()
        startBluetoothLoading()
        system.bluetooth = BluetoothStatus.DISABLED

        act(OnboardingAction.BluetoothResult(enabled = false))

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
        assertFalse(state.bluetoothLoading)
        assertEquals("", state.errorMessage)
    }

    @Test
    fun t7_bluetoothDeclinedAndNotSupported_errorWithMessage() = runTest {
        onBluetoothStep()
        system.bluetooth = BluetoothStatus.NOT_SUPPORTED

        act(OnboardingAction.BluetoothResult(enabled = false))

        assertEquals(OnboardingStep.ERROR, step)
        assertEquals(
            "Bluetooth is required for bitchat to discover and connect to nearby users. Please enable Bluetooth to continue.",
            state.errorMessage,
        )
    }

    @Test
    fun t8_securityExceptionOnLaunch_staysOnBluetoothCheck() = runTest {
        onBluetoothStep()
        system.firstTimeLaunch = true
        startBluetoothLoading()
        system.bluetooth = BluetoothStatus.DISABLED

        act(
            OnboardingAction.BluetoothLaunchFailed(
                "Bluetooth permissions are required before enabling Bluetooth. Please grant permissions first."
            )
        )
        advance(10_000)

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertFalse(state.bluetoothLoading)
    }

    @Test
    fun t8_otherLaunchFailure_staysOnBluetoothCheck() = runTest {
        onBluetoothStep()
        startBluetoothLoading()
        system.bluetooth = BluetoothStatus.DISABLED

        act(OnboardingAction.BluetoothLaunchFailed("Failed to request Bluetooth enable: synthetic failure"))

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertFalse(state.bluetoothLoading)
    }

    @Test
    fun t9_skipTapped_marksSkippedAndRunsLocationCheck() = runTest {
        onBluetoothStep()
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BluetoothSkipTapped)

        assertTrue(skip.skipped.value)
        assertEquals(OnboardingStep.LOCATION, step)
    }

    @Test
    fun t10_checkAgainTapped_rerunsBluetoothCheck() = runTest {
        onBluetoothStep()
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.BluetoothCheckAgainTapped)

        assertEquals(OnboardingStep.LOCATION, step)
    }

    @Test
    fun t12_bluetoothTurnedOffWhileReady_showsBluetoothCheck() = runTest {
        // Ignores both the session skip and the debug BLE switch.
        host.bluetoothTurnedOff = true
        skip.skip()
        system.bleRequired = false
        system.bluetooth = BluetoothStatus.DISABLED

        newViewModel()

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
    }

    @Test
    fun bluetoothOffThenOnBeforeOpen_doesNotStrandOnBluetoothStep() = runTest {
        // Off while ready, back on while the app was in the background: the
        // flag is stale by the time the view model opens.
        host.bluetoothTurnedOff = true
        system.bluetooth = BluetoothStatus.ENABLED

        newViewModel()

        assertFinished()
        assertFalse(host.bluetoothTurnedOff)
    }

    @Test
    fun t12_bluetoothTurnedOffDuringOnboarding_showsBluetoothCheck() = runTest {
        onLocationStep()
        system.bluetooth = BluetoothStatus.DISABLED

        radio(SystemEvent.BluetoothOff)

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
    }

    @Test
    fun bluetoothOffDuringPermissionPhase_appliedAfterPhase() = runTest {
        // F11: the dialog is not yanked away; the Bluetooth step follows it.
        onPermissionRequesting()
        system.bluetooth = BluetoothStatus.DISABLED

        radio(SystemEvent.BluetoothOff)
        advance(10_000)

        assertEquals(OnboardingStep.PERMISSION_REQUESTING, step)
        assertEquals(BluetoothStatus.ENABLED, state.bluetooth)

        act(OnboardingAction.PermissionResults(system.required.associateWith { true }))
        advance(100)

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
    }

    // ---- T13-T19 Location ----

    @Test
    fun t13_openSettingsTapped_setsLoadingAndLaunchesSettings() = runTest {
        onLocationStep()

        act(OnboardingAction.LocationOpenSettingsTapped)

        assertEquals(OnboardingStep.LOCATION, step)
        assertTrue(state.locationLoading)
        assertEquals(listOf("launchLocationSettings"), system.effects)
    }

    @Test
    fun t14_locationEnabledResult_startsWifiAwareAndRunsBatteryCheck() = runTest {
        onLocationStep()
        startLocationLoading()
        system.battery = BatteryOptimizationStatus.ENABLED

        act(OnboardingAction.LocationResult(enabled = true))

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertEquals(LocationStatus.ENABLED, state.location)
        assertFalse(state.locationLoading)
        assertEquals(listOf("startWifiAwareIfPossible"), system.effects)
    }

    @Test
    fun t15_locationDeclined_staysOnLocationCheck() = runTest {
        onLocationStep()
        startLocationLoading()
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.LocationResult(enabled = false))

        assertEquals(OnboardingStep.LOCATION, step)
        assertEquals(LocationStatus.DISABLED, state.location)
        assertFalse(state.locationLoading)
        assertEquals("", state.errorMessage)
    }

    @Test
    fun t15_locationDeclinedAndNotAvailable_errorWithMessage() = runTest {
        onLocationStep()
        system.location = LocationStatus.NOT_AVAILABLE

        act(OnboardingAction.LocationResult(enabled = false))

        assertEquals(OnboardingStep.ERROR, step)
        assertEquals(
            "Location services are required for Bluetooth scanning on Android. Please enable location services to continue.",
            state.errorMessage,
        )
    }

    @Test
    fun t16_launchFailed_staysOnLocationCheck() = runTest {
        onLocationStep()
        startLocationLoading()
        system.location = LocationStatus.DISABLED

        act(OnboardingAction.LocationLaunchFailed("Failed to open location settings: synthetic failure"))

        assertEquals(OnboardingStep.LOCATION, step)
        assertFalse(state.locationLoading)
    }

    @Test
    fun t17_checkAgainTapped_rerunsLocationCheck() = runTest {
        onLocationStep()
        system.battery = BatteryOptimizationStatus.ENABLED

        act(OnboardingAction.LocationCheckAgainTapped)

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertEquals(LocationStatus.ENABLED, state.location)
    }

    @Test
    fun t18_locationTurnedOn_startsWifiAwareAndRunsBatteryCheck() = runTest {
        onLocationStep()
        system.battery = BatteryOptimizationStatus.ENABLED

        radio(SystemEvent.LocationChanged(enabled = true))

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertEquals(LocationStatus.ENABLED, state.location)
        assertEquals(listOf("startWifiAwareIfPossible"), system.effects)
    }

    @Test
    fun t19_locationTurnedOffWhileReady_showsLocationCheck() = runTest {
        // The gate went back to onboarding; it opens on the location step.
        system.location = LocationStatus.DISABLED

        newViewModel()

        assertEquals(OnboardingStep.LOCATION, step)
        assertEquals(LocationStatus.DISABLED, state.location)
    }

    // ---- T20-T25 Battery optimization (T23 is unreachable at minSdk 26) ----

    @Test
    fun t20_disableTapped_setsLoadingAndLaunchesDialog() = runTest {
        onBatteryStep()

        act(OnboardingAction.BatteryDisableTapped)

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertTrue(state.batteryLoading)
        assertEquals(listOf("launchBatteryOptimization"), system.effects)
    }

    @Test
    fun batteryResultStillOptimized_staysOnBatteryStep() = runTest {
        onBatteryStep()
        startBatteryLoading()
        system.battery = BatteryOptimizationStatus.ENABLED

        act(OnboardingAction.BatteryResult)
        advance(10_000)

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertEquals(BatteryOptimizationStatus.ENABLED, state.battery)
        assertFalse(state.batteryLoading)
        assertEquals(0, host.finished)
        assertEquals(emptyList<String>(), system.effects)
    }

    @Test
    fun batteryResultIgnored_movesOn() = runTest {
        onBatteryStep()
        startBatteryLoading()
        system.battery = BatteryOptimizationStatus.DISABLED

        act(OnboardingAction.BatteryResult)

        assertEquals(BatteryOptimizationStatus.DISABLED, state.battery)
        assertFalse(state.batteryLoading)
        advance(200)
        assertFinished()
        assertEquals(emptyList<String>(), system.effects)
    }

    @Test
    fun t22_launchFailed_staysOnBatteryCheckWithFreshStatus() = runTest {
        onBatteryStep()
        startBatteryLoading()
        system.battery = BatteryOptimizationStatus.NOT_SUPPORTED

        act(OnboardingAction.BatteryLaunchFailed("synthetic failure"))

        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        assertEquals(BatteryOptimizationStatus.NOT_SUPPORTED, state.battery)
        assertFalse(state.batteryLoading)
    }

    @Test
    fun t24_checkAgainTapped_rerunsBatteryCheck() = runTest {
        onBatteryStep()
        system.battery = BatteryOptimizationStatus.DISABLED

        act(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertFinished()
        assertEquals(BatteryOptimizationStatus.DISABLED, state.battery)
    }

    @Test
    fun t25_skipTapped_writesSkipAndRunsPermissionCheck() = runTest {
        onBatteryStep()
        system.battery = BatteryOptimizationStatus.ENABLED

        act(OnboardingAction.BatterySkipTapped)

        assertEquals(listOf("setBatterySkipped"), system.effects)
        assertEquals(OnboardingStep.BATTERY_OPTIMIZATION, step)
        advance(200)
        assertFinished()
        assertEquals(listOf("setBatterySkipped"), system.effects)
    }

    // ---- T26-T34 Runtime permissions ----

    @Test
    fun t26_grantTapped_showsRequestingAndRequests() = runTest {
        onPermissionExplanation()
        system.granted.clear()

        act(OnboardingAction.GrantPermissionsTapped)

        assertEquals(OnboardingStep.PERMISSION_REQUESTING, step)
        assertEquals(
            listOf(
                "markOptionalPermissionsRequested:[]",
                "launchPermissions:[$BLUETOOTH_SCAN, $BLUETOOTH_CONNECT, $COARSE_LOCATION, $FINE_LOCATION]",
            ),
            system.effects,
        )
    }

    @Test
    fun t27_nothingMissingBackgroundNeeded_showsBackgroundExplanation() = runTest {
        onPermissionExplanation()
        system.backgroundLocationGranted = false

        act(OnboardingAction.GrantPermissionsTapped)
        advance(10_000)

        assertEquals(OnboardingStep.BACKGROUND_LOCATION, step)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t28_nothingMissing_completes() = runTest {
        onPermissionExplanation()

        act(OnboardingAction.GrantPermissionsTapped)

        assertEquals(OnboardingStep.PERMISSION_REQUESTING, step)
        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertFinished()
    }

    @Test
    fun t29_somethingMissing_marksOptionalRequestedBeforeLaunch() = runTest {
        onPermissionExplanation()
        system.granted -= FINE_LOCATION
        system.unrequestedOptional = listOf(POST_NOTIFICATIONS, FINE_LOCATION)

        act(OnboardingAction.GrantPermissionsTapped)
        advance(10_000)

        assertEquals(OnboardingStep.PERMISSION_REQUESTING, step)
        assertEquals(
            listOf(
                "markOptionalPermissionsRequested:[$POST_NOTIFICATIONS, $FINE_LOCATION]",
                // Missing required first, then optional, without duplicates.
                "launchPermissions:[$FINE_LOCATION, $POST_NOTIFICATIONS]",
            ),
            system.effects,
        )
    }

    @Test
    fun t30_requiredGrantedBackgroundNeeded_showsBackgroundExplanationWithoutFlag() = runTest {
        onPermissionRequesting()
        system.backgroundLocationGranted = false

        act(OnboardingAction.PermissionResults(system.required.associateWith { true }))
        advance(10_000)

        assertEquals(OnboardingStep.BACKGROUND_LOCATION, step)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t31_allGranted_completes() = runTest {
        onPermissionRequesting()

        act(OnboardingAction.PermissionResults(system.required.associateWith { true }))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertFinished()
    }

    @Test
    fun t32_optionalDenied_completesWithoutUi() = runTest {
        onPermissionRequesting()
        val results = system.required.associateWith { true } + (POST_NOTIFICATIONS to false)

        act(OnboardingAction.PermissionResults(results))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        assertEquals("", state.errorMessage)
        advance(100)
        assertFinished()
    }

    @Test
    fun t32_resultMapLacksRequired_checksPackageState() = runTest {
        // A returning user asked only for notifications: required ones are absent from the map.
        onPermissionRequesting()

        act(OnboardingAction.PermissionResults(mapOf(POST_NOTIFICATIONS to false)))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertFinished()
    }

    @Test
    fun t33_criticalDenied_errorWithNames() = runTest {
        onPermissionRequesting()
        system.granted -= BLUETOOTH_SCAN
        system.granted -= FINE_LOCATION
        val results = mapOf(
            BLUETOOTH_SCAN to false,
            BLUETOOTH_CONNECT to true,
            COARSE_LOCATION to true,
            FINE_LOCATION to false,
            POST_NOTIFICATIONS to false,
        )

        act(OnboardingAction.PermissionResults(results))
        advance(10_000)

        assertEquals(OnboardingStep.ERROR, step)
        assertEquals(
            "Critical permissions were denied. bitchat requires these permissions to function:\n" +
                "- Bluetooth/Nearby Devices\n" +
                "- Location (for Bluetooth scanning)\n" +
                "\nPlease grant these permissions in Settings to use bitchat.",
            state.errorMessage,
        )
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun permissionResultsMissingRequired_doesNotMarkComplete() = runTest {
        onPermissionRequesting()
        system.granted -= FINE_LOCATION

        act(OnboardingAction.PermissionResults(emptyMap()))
        advance(10_000)

        assertEquals(OnboardingStep.PERMISSION_EXPLANATION, step)
        assertEquals(emptyList<String>(), system.effects)
        assertEquals(0, host.finished)
    }

    // ---- T35-T39 Background location ----

    @Test
    fun t35_noBackgroundPermissionBelowApi29_completes() = runTest {
        onBackgroundStep()
        system.backgroundPermission = null

        act(OnboardingAction.BackgroundGrantTapped)

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertFinished()
    }

    @Test
    fun t36_grantTapped_launchesBackgroundRequest() = runTest {
        onBackgroundStep()
        system.backgroundLocationGranted = false

        act(OnboardingAction.BackgroundGrantTapped)
        advance(10_000)

        assertEquals(OnboardingStep.BACKGROUND_LOCATION, step)
        assertEquals(listOf("launchBackgroundLocation:$BACKGROUND_LOCATION"), system.effects)
    }

    @Test
    fun t37_backgroundGranted_completes() = runTest {
        onBackgroundStep()

        act(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertFinished()
        assertEquals(listOf("markOnboardingComplete"), system.effects)
    }

    @Test
    fun t37_backgroundDenied_completes() = runTest {
        onBackgroundStep()
        system.backgroundLocationGranted = false

        act(OnboardingAction.BackgroundResult(granted = false))
        advance(100)

        assertFinished()
        assertEquals(listOf("markOnboardingComplete"), system.effects)
    }

    @Test
    fun t38_checkAgainNoLongerNeeded_completes() = runTest {
        onBackgroundStep()

        act(OnboardingAction.BackgroundCheckAgainTapped)
        advance(100)

        assertFinished()
        assertEquals(listOf("markOnboardingComplete"), system.effects)
    }

    @Test
    fun t38_checkAgainStillNeeded_doesNothing() = runTest {
        onBackgroundStep()
        system.backgroundLocationGranted = false

        act(OnboardingAction.BackgroundCheckAgainTapped)
        advance(10_000)

        assertEquals(OnboardingStep.BACKGROUND_LOCATION, step)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t39_skipTapped_writesSkipAndCompletes() = runTest {
        onBackgroundStep()
        system.backgroundLocationGranted = false

        act(OnboardingAction.BackgroundSkipTapped)

        assertEquals(listOf("setBackgroundLocationSkipped", "markOnboardingComplete"), system.effects)
        advance(100)
        assertFinished()
    }

    // ---- T40-T43 Error screen, reopening ----

    @Test
    fun t40_tryAgain_clearsStepAndRetriesThroughTheGate() = runTest {
        onErrorStep()

        act(OnboardingAction.TryAgainTapped)
        advance(10_000)

        // Back to checking; the gate's checks found nothing for onboarding, so the app starts.
        assertEquals(OnboardingStep.CHECKING, step)
        assertEquals(1, host.retried)
        assertEquals(0, host.finished)
    }

    @Test
    fun tryAgain_checksStillNeedOnboarding_reopensOnTheirStep() = runTest {
        // F8: onboarding stays on screen through Try again, so it reopens itself
        // on the step the gate's checks found, with the error cleared.
        onErrorStep()
        system.bluetooth = BluetoothStatus.DISABLED
        host.retryShowsOnboarding = true

        act(OnboardingAction.TryAgainTapped)
        advance(10_000)

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals("", state.errorMessage)
        assertEquals(1, host.retried)
    }

    @Test
    fun t41_openSettingsTapped_opensAppSettingsAndStays() = runTest {
        onErrorStep()

        act(OnboardingAction.OpenSettingsTapped)
        advance(10_000)

        assertEquals(OnboardingStep.ERROR, step)
        assertEquals(listOf("openAppSettings"), system.effects)
    }

    @Test
    fun t42_openWithBluetoothOff_showsBluetoothCheck() = runTest {
        // The gate found Bluetooth off on resume (T42) and opened onboarding.
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        newViewModel()

        assertEquals(OnboardingStep.BLUETOOTH, step)
        assertEquals(BluetoothStatus.DISABLED, state.bluetooth)
        assertFalse(state.bluetoothLoading)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t43_openWithLocationOff_showsLocationCheck() = runTest {
        // The gate found location off on resume (T43) and opened onboarding.
        system.location = LocationStatus.DISABLED

        newViewModel()

        assertEquals(OnboardingStep.LOCATION, step)
        assertEquals(LocationStatus.DISABLED, state.location)
        assertFalse(state.locationLoading)
        assertTrue(system.effects.isEmpty())
    }

    private companion object {
        val CARDS = listOf(
            PermissionCategory(
                type = PermissionType.NEARBY_DEVICES,
                description = "synthetic description",
                permissions = listOf(BLUETOOTH_SCAN),
                isGranted = false,
                systemDescription = "synthetic system description",
            )
        )
    }
}
