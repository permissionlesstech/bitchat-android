package com.bitchat.android.onboarding

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemDeviceReadinessTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
            .edit().clear().commit()
        shadowOf(application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun readiness() = SystemDeviceReadiness(application) { false }

    // Bluetooth and location status (moved from SystemStateManagerContractTest)

    @Test
    @Config(sdk = [35])
    fun bluetoothDisabledAndEnabledStatesAreObservable() {
        val adapter = application.getSystemService(BluetoothManager::class.java).adapter
        val readiness = readiness()

        shadowOf(adapter).setEnabled(false)
        assertEquals(BluetoothStatus.DISABLED, readiness.snapshot().bluetooth)
        shadowOf(adapter).setEnabled(true)
        assertEquals(BluetoothStatus.ENABLED, readiness.snapshot().bluetooth)
    }

    @Test
    @Config(sdk = [35])
    fun locationDisabledAndEnabledStatesAreObservable() {
        val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val readiness = readiness()

        shadowOf(locationManager).setLocationEnabled(false)
        assertEquals(LocationStatus.DISABLED, readiness.snapshot().location)
        shadowOf(locationManager).setLocationEnabled(true)
        assertEquals(LocationStatus.ENABLED, readiness.snapshot().location)
    }

    // Optional permissions (moved from OptionalPermissionRequestTest)

    @Test
    fun missingNotificationPermissionIsOfferedOnce() {
        val readiness = readiness()

        assertEquals(
            listOf(Manifest.permission.POST_NOTIFICATIONS),
            readiness.snapshot().unrequestedOptionalPermissions
        )

        readiness.markOptionalPermissionsRequested(listOf(Manifest.permission.POST_NOTIFICATIONS))

        assertTrue(readiness.snapshot().unrequestedOptionalPermissions.isEmpty())
    }

    @Test
    fun grantedNotificationPermissionIsNotRequested() {
        shadowOf(application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        assertTrue(readiness().snapshot().unrequestedOptionalPermissions.isEmpty())
    }

    // Permission lists

    @Test
    fun requiredPermissionsAreBluetoothAndLocationWhileWifiAwareIsOff() {
        val readiness = SystemDeviceReadiness(application) { true }

        assertEquals(
            listOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ),
            readiness.requiredPermissions()
        )
        assertEquals(listOf(Manifest.permission.NEARBY_WIFI_DEVICES), readiness.wifiAwarePermissions())
    }

    @Test
    fun missingPermissionsAreTheUngrantedRequiredOnes() {
        shadowOf(application).grantPermissions(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        )
        shadowOf(application).denyPermissions(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

        val snapshot = readiness().snapshot()

        assertEquals(
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            snapshot.missingPermissions
        )
        assertEquals(false, snapshot.requiredPermissionsGranted)
    }

    @Test
    fun backgroundLocationIsNeededUntilGranted() {
        val readiness = readiness()
        assertEquals(Manifest.permission.ACCESS_BACKGROUND_LOCATION, readiness.backgroundLocationPermission())

        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(BackgroundLocation.NEEDED, readiness.snapshot().backgroundLocation)

        shadowOf(application).grantPermissions(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        assertEquals(BackgroundLocation.GRANTED, readiness.snapshot().backgroundLocation)
    }

    // System events

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun receiversTurnBroadcastsIntoEventsAndUnregisterWhenCollectionStops() = runTest {
        val locationManager = application.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val events = mutableListOf<SystemEvent>()
        val receiversBefore = shadowOf(application).registeredReceivers.size

        val collection = launch(UnconfinedTestDispatcher(testScheduler)) {
            readiness().systemEvents.collect { events += it }
        }
        assertEquals(receiversBefore + 2, shadowOf(application).registeredReceivers.size)

        broadcastBluetoothState(BluetoothAdapter.STATE_TURNING_ON)
        broadcastBluetoothState(BluetoothAdapter.STATE_ON)
        broadcastBluetoothState(BluetoothAdapter.STATE_TURNING_OFF)
        broadcastBluetoothState(BluetoothAdapter.STATE_OFF)
        // Robolectric, like a device, sends both MODE_CHANGED and PROVIDERS_CHANGED
        // for one toggle, and each becomes an event carrying the state read then.
        shadowOf(locationManager).setLocationEnabled(false)
        shadowOf(Looper.getMainLooper()).idle()
        shadowOf(locationManager).setLocationEnabled(true)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(SystemEvent.BluetoothOn, SystemEvent.BluetoothOff), events.take(2))
        val location = events.drop(2)
        assertTrue(location.isNotEmpty() && location.all { it is SystemEvent.LocationChanged })
        assertEquals(
            listOf(false, true),
            location.map { (it as SystemEvent.LocationChanged).enabled }.distinct()
        )

        collection.cancel()
        assertEquals(receiversBefore, shadowOf(application).registeredReceivers.size)
    }

    private fun broadcastBluetoothState(state: Int) {
        application.sendBroadcast(
            Intent(BluetoothAdapter.ACTION_STATE_CHANGED).putExtra(BluetoothAdapter.EXTRA_STATE, state)
        )
        shadowOf(Looper.getMainLooper()).idle()
    }
}
