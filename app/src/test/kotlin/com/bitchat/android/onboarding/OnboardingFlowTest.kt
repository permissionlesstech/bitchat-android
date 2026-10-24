package com.bitchat.android.onboarding

import com.bitchat.android.MainViewModel
import com.bitchat.android.onboarding.FakeOnboardingSystem.Companion.BACKGROUND_LOCATION
import com.bitchat.android.onboarding.FakeOnboardingSystem.Companion.BLUETOOTH_CONNECT
import com.bitchat.android.onboarding.FakeOnboardingSystem.Companion.BLUETOOTH_SCAN
import com.bitchat.android.onboarding.FakeOnboardingSystem.Companion.COARSE_LOCATION
import com.bitchat.android.onboarding.FakeOnboardingSystem.Companion.FINE_LOCATION
import com.bitchat.android.onboarding.FakeOnboardingSystem.Companion.POST_NOTIFICATIONS
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization tests: they lock what onboarding does today, ids from the
 * Phase 9 inventory (T = event-driven transition, P = decision procedure, C =
 * completeOnboarding). Names ending in `_currentBehaviour` lock a path that a
 * later fix changes on purpose.
 *
 * Skipped (spec Decision 4, removed by the restructure, not reproduced): F1 (T11's
 * double chain and repeated init), F3 (T45, recreation during a delay) and F11
 * (T12 during the permission phase).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingFlowTest {

    private val vm = MainViewModel()
    private val system = FakeOnboardingSystem()

    private fun TestScope.flow() = OnboardingFlow(this, vm, system)

    /** Runs everything due up to and including [ms] from now. */
    private fun TestScope.advance(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    private val state get() = vm.onboardingState.value

    // ---- T1 ----

    @Test
    fun t1_newViewModel_startsCheckingWithDefaults() {
        assertEquals(OnboardingState.CHECKING, state)
        assertEquals(BluetoothStatus.ENABLED, vm.bluetoothStatus.value)
        assertEquals(LocationStatus.ENABLED, vm.locationStatus.value)
        assertEquals(BatteryOptimizationStatus.ENABLED, vm.batteryOptimizationStatus.value)
        assertEquals("", vm.errorMessage.value)
        assertFalse(vm.isBluetoothLoading.value)
        assertFalse(vm.isLocationLoading.value)
        assertFalse(vm.isBatteryOptimizationLoading.value)
        assertFalse(vm.isBluetoothCheckSkipped.value)
    }

    // ---- T2, T3 ----

    @Test
    fun t2_noBluetoothAdapterAtStart_errorWithMessage_currentBehaviour() = runTest {
        system.bluetooth = BluetoothStatus.NOT_SUPPORTED
        vm.updateBluetoothLoading(true)

        flow().dispatch(OnboardingAction.BluetoothNotSupportedAtStart)

        assertEquals(OnboardingState.ERROR, state)
        assertEquals(
            "This device doesn't support Bluetooth, which is required for bitchat to function.",
            vm.errorMessage.value,
        )
        assertEquals(BluetoothStatus.NOT_SUPPORTED, vm.bluetoothStatus.value)
        assertFalse(vm.isBluetoothLoading.value)
    }

    @Test
    fun t3_checking_runsChecksAfter500ms() = runTest {
        flow().dispatch(OnboardingAction.Start)

        advance(499)
        assertEquals(OnboardingState.CHECKING, state)
        assertTrue(system.effects.isEmpty())

        advance(1)
        // P1 -> P2 -> P3 -> P4, which waits 200 ms before it decides.
        assertEquals(OnboardingState.CHECKING, state)
        advance(200)
        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("initializeApp"), system.effects)
    }

    @Test
    fun t3_notChecking_startDoesNothing() = runTest {
        vm.updateOnboardingState(OnboardingState.ERROR)

        flow().dispatch(OnboardingAction.Start)
        advance(10_000)

        assertEquals(OnboardingState.ERROR, state)
        assertTrue(system.effects.isEmpty())
    }

    // ---- P1 checkBluetoothAndProceed ----

    @Test
    fun p1_bluetoothCheckSkipped_runsLocationCheck() = runTest {
        vm.skipBluetoothCheck()
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.Start)
        advance(500)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        // Bluetooth was not read.
        assertEquals(BluetoothStatus.ENABLED, vm.bluetoothStatus.value)
    }

    @Test
    fun p1_firstTimeLaunch_showsPermissionExplanation() = runTest {
        system.firstTimeLaunch = true
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(OnboardingAction.Start)
        advance(500)
        assertEquals(OnboardingState.CHECKING, state)
        advance(200)

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, state)
        assertEquals(BluetoothStatus.ENABLED, vm.bluetoothStatus.value)
    }

    @Test
    fun p1_bleSwitchedOffInDebug_writesStatusAndRunsLocationCheck() = runTest {
        system.bleRequired = false
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.Start)
        advance(500)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertEquals(BluetoothStatus.DISABLED, vm.bluetoothStatus.value)
    }

    @Test
    fun p1_bluetoothEnabled_runsLocationCheck() = runTest {
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.Start)
        advance(500)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
    }

    @Test
    fun p1_returningUserBluetoothDisabled_showsBluetoothCheck() = runTest {
        system.bluetooth = BluetoothStatus.DISABLED
        vm.updateBluetoothLoading(true)

        flow().dispatch(OnboardingAction.Start)
        advance(500)

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertEquals(BluetoothStatus.DISABLED, vm.bluetoothStatus.value)
        assertFalse(vm.isBluetoothLoading.value)
    }

    @Test
    fun p1_bluetoothNotSupported_showsBluetoothCheck() = runTest {
        system.bluetooth = BluetoothStatus.NOT_SUPPORTED
        vm.updateBluetoothLoading(true)

        flow().dispatch(OnboardingAction.Start)
        advance(500)

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertEquals(BluetoothStatus.NOT_SUPPORTED, vm.bluetoothStatus.value)
        assertFalse(vm.isBluetoothLoading.value)
    }

    // ---- P2 checkLocationAndProceed ----

    @Test
    fun p2_firstTimeLaunch_showsPermissionExplanation() = runTest {
        system.firstTimeLaunch = true
        system.location = LocationStatus.DISABLED
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)

        flow().dispatch(OnboardingAction.LocationCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, state)
        // Location was not read.
        assertEquals(LocationStatus.ENABLED, vm.locationStatus.value)
    }

    @Test
    fun p2_locationEnabled_runsBatteryCheck() = runTest {
        system.battery = BatteryOptimizationStatus.ENABLED
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)

        flow().dispatch(OnboardingAction.LocationCheckAgainTapped)

        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
    }

    @Test
    fun p2_locationDisabled_showsLocationCheck() = runTest {
        system.location = LocationStatus.DISABLED
        vm.updateLocationLoading(true)

        flow().dispatch(OnboardingAction.LocationCheckAgainTapped)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertEquals(LocationStatus.DISABLED, vm.locationStatus.value)
        assertFalse(vm.isLocationLoading.value)
    }

    @Test
    fun p2_locationNotAvailable_showsLocationCheck() = runTest {
        system.location = LocationStatus.NOT_AVAILABLE
        vm.updateLocationLoading(true)

        flow().dispatch(OnboardingAction.LocationCheckAgainTapped)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertEquals(LocationStatus.NOT_AVAILABLE, vm.locationStatus.value)
        assertFalse(vm.isLocationLoading.value)
    }

    // ---- P3 checkBatteryOptimizationAndProceed ----

    @Test
    fun p3_firstTimeLaunch_showsPermissionExplanation() = runTest {
        system.firstTimeLaunch = true
        system.battery = BatteryOptimizationStatus.ENABLED
        vm.updateBatteryOptimizationStatus(BatteryOptimizationStatus.NOT_SUPPORTED)

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, state)
        // Battery was not read.
        assertEquals(BatteryOptimizationStatus.NOT_SUPPORTED, vm.batteryOptimizationStatus.value)
    }

    @Test
    fun p3_batterySkipped_runsPermissionCheck() = runTest {
        system.batterySkipped = true
        system.battery = BatteryOptimizationStatus.ENABLED
        vm.updateBatteryOptimizationStatus(BatteryOptimizationStatus.NOT_SUPPORTED)

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(BatteryOptimizationStatus.NOT_SUPPORTED, vm.batteryOptimizationStatus.value)
    }

    @Test
    fun p3_batteryOptimizationDisabled_runsPermissionCheck() = runTest {
        system.battery = BatteryOptimizationStatus.DISABLED

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(BatteryOptimizationStatus.DISABLED, vm.batteryOptimizationStatus.value)
    }

    @Test
    fun p3_batteryNotSupported_runsPermissionCheck() = runTest {
        system.battery = BatteryOptimizationStatus.NOT_SUPPORTED

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(BatteryOptimizationStatus.NOT_SUPPORTED, vm.batteryOptimizationStatus.value)
    }

    @Test
    fun p3_batteryOptimizationEnabled_showsBatteryCheck() = runTest {
        system.battery = BatteryOptimizationStatus.ENABLED
        vm.updateBatteryOptimizationLoading(true)

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)

        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
        assertEquals(BatteryOptimizationStatus.ENABLED, vm.batteryOptimizationStatus.value)
        assertFalse(vm.isBatteryOptimizationLoading.value)
    }

    // ---- P4 proceedWithPermissionCheck (reached here through P3 with battery already off) ----

    @Test
    fun p4_firstTimeLaunch_showsPermissionExplanationAfter200ms() = runTest {
        system.firstTimeLaunch = true

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(199)
        assertEquals(OnboardingState.CHECKING, state)
        advance(1)

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, state)
    }

    @Test
    fun p4_optionalPermissionUnrequested_showsPermissionExplanation() = runTest {
        system.unrequestedOptional = listOf(POST_NOTIFICATIONS)

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, state)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun p4_backgroundLocationNeeded_showsBackgroundLocationExplanation() = runTest {
        system.backgroundLocationGranted = false

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.BACKGROUND_LOCATION_EXPLANATION, state)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun p4_backgroundLocationSkipped_initializes() = runTest {
        system.backgroundLocationGranted = false
        system.backgroundLocationSkipped = true

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("initializeApp"), system.effects)
    }

    @Test
    fun p4_backgroundLocationNotNeeded_initializes() = runTest {
        system.needsBackgroundLocation = false
        system.backgroundLocationGranted = false

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("initializeApp"), system.effects)
    }

    @Test
    fun p4_requiredGranted_initializes() = runTest {
        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("initializeApp"), system.effects)
    }

    @Test
    fun p4_requiredMissing_showsPermissionExplanation() = runTest {
        system.granted -= FINE_LOCATION

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, state)
        assertTrue(system.effects.isEmpty())
    }

    // ---- C completeOnboarding ----

    @Test
    fun c_complete_marksFlagThenRechecksAfter100ms() = runTest {
        vm.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)

        flow().dispatch(OnboardingAction.BackgroundResult(granted = true))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(99)
        assertEquals(OnboardingState.BACKGROUND_LOCATION_EXPLANATION, state)
        advance(1)
        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("markOnboardingComplete", "initializeApp"), system.effects)
    }

    // ---- P5 handleOnboardingComplete (reached through C) ----

    @Test
    fun p5_bluetoothOffEvenIfSkipped_showsBluetoothCheck() = runTest {
        vm.skipBluetoothCheck()
        vm.updateBluetoothLoading(true)
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertEquals(BluetoothStatus.DISABLED, vm.bluetoothStatus.value)
        assertFalse(vm.isBluetoothLoading.value)
    }

    @Test
    fun p5_bleSwitchedOffInDebug_ignoresBluetooth() = runTest {
        system.bleRequired = false
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(BluetoothStatus.ENABLED, vm.bluetoothStatus.value)
    }

    @Test
    fun p5_locationOff_showsLocationCheck() = runTest {
        vm.updateLocationLoading(true)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertEquals(LocationStatus.DISABLED, vm.locationStatus.value)
        assertFalse(vm.isLocationLoading.value)
    }

    @Test
    fun p5_batteryOptimizedEvenIfSkipped_showsBatteryCheck() = runTest {
        vm.updateBatteryOptimizationLoading(true)
        vm.updateBatteryOptimizationStatus(BatteryOptimizationStatus.DISABLED)
        system.batterySkipped = true
        system.battery = BatteryOptimizationStatus.ENABLED

        flow().dispatch(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
        assertEquals(BatteryOptimizationStatus.ENABLED, vm.batteryOptimizationStatus.value)
        assertFalse(vm.isBatteryOptimizationLoading.value)
    }

    @Test
    fun p5_allReady_initializes() = runTest {
        flow().dispatch(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("markOnboardingComplete", "initializeApp"), system.effects)
    }

    // ---- P6 initializeApp hand-offs (the body stays in MainActivity, behind system.initializeApp) ----

    @Test
    fun p6_permissionsRevoked_errorWithMessage() = runTest {
        vm.updateOnboardingState(OnboardingState.INITIALIZING)

        flow().onInitFailed("Some permissions were revoked. Please grant all permissions to continue.")

        assertEquals(OnboardingState.ERROR, state)
        assertEquals(
            "Some permissions were revoked. Please grant all permissions to continue.",
            vm.errorMessage.value,
        )
    }

    @Test
    fun p6_initFinished_complete() = runTest {
        vm.updateOnboardingState(OnboardingState.INITIALIZING)

        flow().onInitComplete()

        assertEquals(OnboardingState.COMPLETE, state)
    }

    @Test
    fun p6_initThrew_errorWithMessage() = runTest {
        vm.updateOnboardingState(OnboardingState.INITIALIZING)

        flow().onInitFailed("Failed to initialize the app: synthetic failure")

        assertEquals(OnboardingState.ERROR, state)
        assertEquals("Failed to initialize the app: synthetic failure", vm.errorMessage.value)
    }

    // ---- T4-T12 Bluetooth ----

    @Test
    fun t4_bluetoothOnWhileOnBluetoothCheck_rerunsBluetoothCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.BluetoothTurnedOn)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
    }

    @Test
    fun t4_bluetoothOnOnAnotherStep_ignored() = runTest {
        vm.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.BluetoothTurnedOn)
        advance(10_000)

        assertEquals(OnboardingState.PERMISSION_EXPLANATION, state)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t5_enableTapped_setsLoadingAndLaunchesDialog() = runTest {
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)

        flow().dispatch(OnboardingAction.BluetoothEnableTapped)

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertTrue(vm.isBluetoothLoading.value)
        assertEquals(listOf("launchBluetoothEnable"), system.effects)
    }

    @Test
    fun t6_bluetoothEnabledResult_runsLocationCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
        vm.updateBluetoothLoading(true)
        vm.updateBluetoothStatus(BluetoothStatus.DISABLED)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.BluetoothResult(enabled = true))

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertEquals(BluetoothStatus.ENABLED, vm.bluetoothStatus.value)
        assertFalse(vm.isBluetoothLoading.value)
    }

    @Test
    fun t7_bluetoothDeclined_staysOnBluetoothCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
        vm.updateBluetoothLoading(true)
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(OnboardingAction.BluetoothResult(enabled = false))

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertEquals(BluetoothStatus.DISABLED, vm.bluetoothStatus.value)
        assertFalse(vm.isBluetoothLoading.value)
        assertEquals("", vm.errorMessage.value)
    }

    @Test
    fun t7_bluetoothDeclinedAndNotSupported_errorWithMessage() = runTest {
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
        system.bluetooth = BluetoothStatus.NOT_SUPPORTED

        flow().dispatch(OnboardingAction.BluetoothResult(enabled = false))

        assertEquals(OnboardingState.ERROR, state)
        assertEquals(
            "Bluetooth is required for bitchat to discover and connect to nearby users. Please enable Bluetooth to continue.",
            vm.errorMessage.value,
        )
    }

    @Test
    fun t8_securityExceptionOnLaunch_staysOnBluetoothCheck() = runTest {
        // The message says "permissions" in lower case, so the "Permission" branches never match (Q3).
        system.firstTimeLaunch = true
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
        vm.updateBluetoothLoading(true)
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(
            OnboardingAction.BluetoothLaunchFailed(
                "Bluetooth permissions are required before enabling Bluetooth. Please grant permissions first."
            )
        )
        advance(10_000)

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertFalse(vm.isBluetoothLoading.value)
    }

    @Test
    fun t8_otherLaunchFailure_staysOnBluetoothCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
        vm.updateBluetoothLoading(true)
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(OnboardingAction.BluetoothLaunchFailed("Failed to request Bluetooth enable: synthetic failure"))

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertFalse(vm.isBluetoothLoading.value)
    }

    @Test
    fun t9_skipTapped_marksSkippedAndRunsLocationCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.BluetoothSkipTapped)

        assertTrue(vm.isBluetoothCheckSkipped.value)
        assertEquals(OnboardingState.LOCATION_CHECK, state)
    }

    @Test
    fun t10_checkAgainTapped_rerunsBluetoothCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.BLUETOOTH_CHECK)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.BluetoothCheckAgainTapped)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
    }

    @Test
    fun t12_bluetoothTurnedOffWhileComplete_showsBluetoothCheck() = runTest {
        // Ignores both the session skip and the debug BLE switch.
        vm.updateOnboardingState(OnboardingState.COMPLETE)
        vm.skipBluetoothCheck()
        system.bleRequired = false
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(OnboardingAction.BluetoothTurnedOff)

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertEquals(BluetoothStatus.DISABLED, vm.bluetoothStatus.value)
    }

    // ---- T13-T19 Location ----

    @Test
    fun t13_openSettingsTapped_setsLoadingAndLaunchesSettings() = runTest {
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)

        flow().dispatch(OnboardingAction.LocationOpenSettingsTapped)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertTrue(vm.isLocationLoading.value)
        assertEquals(listOf("launchLocationSettings"), system.effects)
    }

    @Test
    fun t14_locationEnabledResult_startsWifiAwareAndRunsBatteryCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)
        vm.updateLocationLoading(true)
        vm.updateLocationStatus(LocationStatus.DISABLED)
        system.battery = BatteryOptimizationStatus.ENABLED

        flow().dispatch(OnboardingAction.LocationResult(enabled = true))

        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
        assertEquals(LocationStatus.ENABLED, vm.locationStatus.value)
        assertFalse(vm.isLocationLoading.value)
        assertEquals(listOf("startWifiAwareIfPossible"), system.effects)
    }

    @Test
    fun t15_locationDeclined_staysOnLocationCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)
        vm.updateLocationLoading(true)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.LocationResult(enabled = false))

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertEquals(LocationStatus.DISABLED, vm.locationStatus.value)
        assertFalse(vm.isLocationLoading.value)
        assertEquals("", vm.errorMessage.value)
    }

    @Test
    fun t15_locationDeclinedAndNotAvailable_errorWithMessage() = runTest {
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)
        system.location = LocationStatus.NOT_AVAILABLE

        flow().dispatch(OnboardingAction.LocationResult(enabled = false))

        assertEquals(OnboardingState.ERROR, state)
        assertEquals(
            "Location services are required for Bluetooth scanning on Android. Please enable location services to continue.",
            vm.errorMessage.value,
        )
    }

    @Test
    fun t16_launchFailed_staysOnLocationCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)
        vm.updateLocationLoading(true)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.LocationLaunchFailed("Failed to open location settings: synthetic failure"))

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertFalse(vm.isLocationLoading.value)
    }

    @Test
    fun t17_checkAgainTapped_rerunsLocationCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)
        system.battery = BatteryOptimizationStatus.ENABLED

        flow().dispatch(OnboardingAction.LocationCheckAgainTapped)

        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
        assertEquals(LocationStatus.ENABLED, vm.locationStatus.value)
    }

    @Test
    fun t18_locationTurnedOn_startsWifiAwareAndRunsBatteryCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.LOCATION_CHECK)
        vm.updateLocationStatus(LocationStatus.DISABLED)
        system.battery = BatteryOptimizationStatus.ENABLED

        flow().dispatch(OnboardingAction.LocationChanged(enabled = true))

        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
        assertEquals(LocationStatus.ENABLED, vm.locationStatus.value)
        assertEquals(listOf("startWifiAwareIfPossible"), system.effects)
    }

    @Test
    fun t19_locationTurnedOffWhileComplete_showsLocationCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.COMPLETE)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.LocationChanged(enabled = false))

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertEquals(LocationStatus.DISABLED, vm.locationStatus.value)
    }

    // ---- T20-T25 Battery optimization (T23 is unreachable at minSdk 26) ----

    @Test
    fun t20_disableTapped_setsLoadingAndLaunchesDialog() = runTest {
        vm.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)

        flow().dispatch(OnboardingAction.BatteryDisableTapped)

        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
        assertTrue(vm.isBatteryOptimizationLoading.value)
        assertEquals(listOf("launchBatteryOptimization"), system.effects)
    }

    @Test
    fun t21_dialogResultWhileStillOptimized_proceeds_currentBehaviour() = runTest {
        vm.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
        vm.updateBatteryOptimizationLoading(true)
        system.battery = BatteryOptimizationStatus.ENABLED

        flow().dispatch(OnboardingAction.BatteryResult)

        assertEquals(BatteryOptimizationStatus.DISABLED, vm.batteryOptimizationStatus.value)
        assertFalse(vm.isBatteryOptimizationLoading.value)
        advance(200)
        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("initializeApp"), system.effects)
    }

    @Test
    fun t22_launchFailed_staysOnBatteryCheckWithFreshStatus() = runTest {
        vm.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
        vm.updateBatteryOptimizationLoading(true)
        vm.updateBatteryOptimizationStatus(BatteryOptimizationStatus.NOT_SUPPORTED)
        system.battery = BatteryOptimizationStatus.ENABLED

        flow().dispatch(OnboardingAction.BatteryLaunchFailed("synthetic failure"))

        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
        assertEquals(BatteryOptimizationStatus.ENABLED, vm.batteryOptimizationStatus.value)
        assertFalse(vm.isBatteryOptimizationLoading.value)
    }

    @Test
    fun t24_checkAgainTapped_rerunsBatteryCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
        system.battery = BatteryOptimizationStatus.DISABLED

        flow().dispatch(OnboardingAction.BatteryCheckAgainTapped)
        advance(200)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(BatteryOptimizationStatus.DISABLED, vm.batteryOptimizationStatus.value)
    }

    @Test
    fun t25_skipTapped_writesSkipAndRunsPermissionCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.BATTERY_OPTIMIZATION_CHECK)
        system.battery = BatteryOptimizationStatus.ENABLED

        flow().dispatch(OnboardingAction.BatterySkipTapped)

        assertEquals(listOf("setBatterySkipped"), system.effects)
        assertEquals(OnboardingState.BATTERY_OPTIMIZATION_CHECK, state)
        advance(200)
        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("setBatterySkipped", "initializeApp"), system.effects)
    }

    // ---- T26-T34 Runtime permissions ----

    @Test
    fun t26_grantTapped_showsRequestingAndRequests() = runTest {
        vm.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
        system.granted.clear()

        flow().dispatch(OnboardingAction.GrantPermissionsTapped)

        assertEquals(OnboardingState.PERMISSION_REQUESTING, state)
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
        vm.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
        system.backgroundLocationGranted = false

        flow().dispatch(OnboardingAction.GrantPermissionsTapped)
        advance(10_000)

        assertEquals(OnboardingState.BACKGROUND_LOCATION_EXPLANATION, state)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t28_nothingMissing_completes() = runTest {
        vm.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)

        flow().dispatch(OnboardingAction.GrantPermissionsTapped)

        assertEquals(OnboardingState.PERMISSION_REQUESTING, state)
        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertEquals(OnboardingState.INITIALIZING, state)
    }

    @Test
    fun t29_somethingMissing_marksOptionalRequestedBeforeLaunch() = runTest {
        vm.updateOnboardingState(OnboardingState.PERMISSION_EXPLANATION)
        system.granted -= FINE_LOCATION
        system.unrequestedOptional = listOf(POST_NOTIFICATIONS, FINE_LOCATION)

        flow().dispatch(OnboardingAction.GrantPermissionsTapped)
        advance(10_000)

        assertEquals(OnboardingState.PERMISSION_REQUESTING, state)
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
        vm.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
        system.backgroundLocationGranted = false

        flow().dispatch(OnboardingAction.PermissionResults(system.required.associateWith { true }))
        advance(10_000)

        assertEquals(OnboardingState.BACKGROUND_LOCATION_EXPLANATION, state)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t31_allGranted_completes() = runTest {
        vm.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)

        flow().dispatch(OnboardingAction.PermissionResults(system.required.associateWith { true }))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertEquals(OnboardingState.INITIALIZING, state)
    }

    @Test
    fun t32_optionalDenied_completesWithoutUi() = runTest {
        vm.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
        val results = system.required.associateWith { true } + (POST_NOTIFICATIONS to false)

        flow().dispatch(OnboardingAction.PermissionResults(results))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        assertEquals("", vm.errorMessage.value)
        advance(100)
        assertEquals(OnboardingState.INITIALIZING, state)
    }

    @Test
    fun t32_resultMapLacksRequired_checksPackageState() = runTest {
        // A returning user asked only for notifications: required ones are absent from the map.
        vm.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)

        flow().dispatch(OnboardingAction.PermissionResults(mapOf(POST_NOTIFICATIONS to false)))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertEquals(OnboardingState.INITIALIZING, state)
    }

    @Test
    fun t33_criticalDenied_errorWithNames() = runTest {
        vm.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
        system.granted -= BLUETOOTH_SCAN
        system.granted -= FINE_LOCATION
        val results = mapOf(
            BLUETOOTH_SCAN to false,
            BLUETOOTH_CONNECT to true,
            COARSE_LOCATION to true,
            FINE_LOCATION to false,
            POST_NOTIFICATIONS to false,
        )

        flow().dispatch(OnboardingAction.PermissionResults(results))
        advance(10_000)

        assertEquals(OnboardingState.ERROR, state)
        assertEquals(
            "Critical permissions were denied. bitchat requires these permissions to function:\n" +
                "- Bluetooth/Nearby Devices\n" +
                "- Location (for Bluetooth scanning)\n" +
                "\nPlease grant these permissions in Settings to use bitchat.",
            vm.errorMessage.value,
        )
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t34_requiredMissingButNotDeniedInResults_writesFlagAndCompletes_currentBehaviour() = runTest {
        vm.updateOnboardingState(OnboardingState.PERMISSION_REQUESTING)
        system.granted -= FINE_LOCATION

        flow().dispatch(OnboardingAction.PermissionResults(emptyMap()))

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("markOnboardingComplete", "initializeApp"), system.effects)
    }

    // ---- T35-T39 Background location ----

    @Test
    fun t35_noBackgroundPermissionBelowApi29_completes() = runTest {
        vm.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
        system.backgroundPermission = null

        flow().dispatch(OnboardingAction.BackgroundGrantTapped)

        assertEquals(listOf("markOnboardingComplete"), system.effects)
        advance(100)
        assertEquals(OnboardingState.INITIALIZING, state)
    }

    @Test
    fun t36_grantTapped_launchesBackgroundRequest() = runTest {
        vm.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
        system.backgroundLocationGranted = false

        flow().dispatch(OnboardingAction.BackgroundGrantTapped)
        advance(10_000)

        assertEquals(OnboardingState.BACKGROUND_LOCATION_EXPLANATION, state)
        assertEquals(listOf("launchBackgroundLocation:$BACKGROUND_LOCATION"), system.effects)
    }

    @Test
    fun t37_backgroundGranted_completes() = runTest {
        vm.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)

        flow().dispatch(OnboardingAction.BackgroundResult(granted = true))
        advance(100)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("markOnboardingComplete", "initializeApp"), system.effects)
    }

    @Test
    fun t37_backgroundDenied_completes() = runTest {
        vm.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
        system.backgroundLocationGranted = false

        flow().dispatch(OnboardingAction.BackgroundResult(granted = false))
        advance(100)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("markOnboardingComplete", "initializeApp"), system.effects)
    }

    @Test
    fun t38_checkAgainNoLongerNeeded_completes() = runTest {
        vm.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)

        flow().dispatch(OnboardingAction.BackgroundCheckAgainTapped)
        advance(100)

        assertEquals(OnboardingState.INITIALIZING, state)
        assertEquals(listOf("markOnboardingComplete", "initializeApp"), system.effects)
    }

    @Test
    fun t38_checkAgainStillNeeded_doesNothing() = runTest {
        vm.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
        system.backgroundLocationGranted = false

        flow().dispatch(OnboardingAction.BackgroundCheckAgainTapped)
        advance(10_000)

        assertEquals(OnboardingState.BACKGROUND_LOCATION_EXPLANATION, state)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t39_skipTapped_writesSkipAndCompletes() = runTest {
        vm.updateOnboardingState(OnboardingState.BACKGROUND_LOCATION_EXPLANATION)
        system.backgroundLocationGranted = false

        flow().dispatch(OnboardingAction.BackgroundSkipTapped)

        assertEquals(listOf("setBackgroundLocationSkipped", "markOnboardingComplete"), system.effects)
        advance(100)
        assertEquals(OnboardingState.INITIALIZING, state)
    }

    // ---- T40-T44 Error screen, resume, init ----

    @Test
    fun t40_tryAgain_checkingThenChecksAfter500ms() = runTest {
        vm.updateOnboardingState(OnboardingState.ERROR)
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(OnboardingAction.TryAgainTapped)

        assertEquals(OnboardingState.CHECKING, state)
        advance(499)
        assertEquals(OnboardingState.CHECKING, state)
        advance(1)
        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
    }

    @Test
    fun t41_openSettingsTapped_opensAppSettingsAndStays() = runTest {
        vm.updateOnboardingState(OnboardingState.ERROR)

        flow().dispatch(OnboardingAction.OpenSettingsTapped)
        advance(10_000)

        assertEquals(OnboardingState.ERROR, state)
        assertEquals(listOf("openAppSettings"), system.effects)
    }

    @Test
    fun t42_resumedWithBluetoothOff_showsBluetoothCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.COMPLETE)
        vm.updateBluetoothLoading(true)
        system.bluetooth = BluetoothStatus.DISABLED
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.ResumedWhileComplete)

        assertEquals(OnboardingState.BLUETOOTH_CHECK, state)
        assertEquals(BluetoothStatus.DISABLED, vm.bluetoothStatus.value)
        assertFalse(vm.isBluetoothLoading.value)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t42_resumedWithBluetoothOffButSkipped_ignoresBluetooth() = runTest {
        vm.updateOnboardingState(OnboardingState.COMPLETE)
        vm.skipBluetoothCheck()
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(OnboardingAction.ResumedWhileComplete)

        assertEquals(OnboardingState.COMPLETE, state)
        assertEquals(listOf("startWifiAwareIfPossible"), system.effects)
    }

    @Test
    fun t42_resumedWithBluetoothOffBleSwitchedOff_ignoresBluetooth() = runTest {
        vm.updateOnboardingState(OnboardingState.COMPLETE)
        system.bleRequired = false
        system.bluetooth = BluetoothStatus.DISABLED

        flow().dispatch(OnboardingAction.ResumedWhileComplete)

        assertEquals(OnboardingState.COMPLETE, state)
        assertEquals(listOf("startWifiAwareIfPossible"), system.effects)
    }

    @Test
    fun t43_resumedWithLocationOff_showsLocationCheck() = runTest {
        vm.updateOnboardingState(OnboardingState.COMPLETE)
        vm.updateLocationLoading(true)
        system.location = LocationStatus.DISABLED

        flow().dispatch(OnboardingAction.ResumedWhileComplete)

        assertEquals(OnboardingState.LOCATION_CHECK, state)
        assertEquals(LocationStatus.DISABLED, vm.locationStatus.value)
        assertFalse(vm.isLocationLoading.value)
        assertTrue(system.effects.isEmpty())
    }

    @Test
    fun t43_resumedAllReady_startsWifiAwareAndStaysComplete() = runTest {
        vm.updateOnboardingState(OnboardingState.COMPLETE)

        flow().dispatch(OnboardingAction.ResumedWhileComplete)

        assertEquals(OnboardingState.COMPLETE, state)
        assertEquals(listOf("startWifiAwareIfPossible"), system.effects)
    }

    @Test
    fun t44_initCancelled_errorWithCancellationMessage_currentBehaviour() = runTest {
        // initializeApp's catch (e: Exception) also catches the cancellation and reports it here.
        vm.updateOnboardingState(OnboardingState.INITIALIZING)

        flow().onInitFailed("Failed to initialize the app: Job was cancelled")

        assertEquals(OnboardingState.ERROR, state)
        assertEquals("Failed to initialize the app: Job was cancelled", vm.errorMessage.value)
    }
}
