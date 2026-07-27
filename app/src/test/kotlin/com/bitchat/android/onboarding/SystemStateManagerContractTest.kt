package com.bitchat.android.onboarding

import android.app.Application
import android.bluetooth.BluetoothManager
import android.content.Context
import android.location.LocationManager
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SystemStateManagerContractTest {
    @Test
    fun `Bluetooth disabled and enabled states are observable without throwing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val adapter = app.getSystemService(BluetoothManager::class.java).adapter
        val manager = BluetoothStatusManager(
            activity = controller.get(),
            context = app,
            onBluetoothEnabled = {},
            onBluetoothDisabled = {}
        )

        shadowOf(adapter).setEnabled(false)
        assertEquals(BluetoothStatus.DISABLED, manager.checkBluetoothStatus())
        shadowOf(adapter).setEnabled(true)
        assertEquals(BluetoothStatus.ENABLED, manager.checkBluetoothStatus())
        controller.destroy()
    }

    @Test
    fun `location disabled and enabled states are observable and receiver is cleaned up`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        val locationManager = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val manager = LocationStatusManager(
            activity = controller.get(),
            context = app,
            onLocationEnabled = {},
            onLocationDisabled = {}
        )

        shadowOf(locationManager).setLocationEnabled(false)
        assertEquals(LocationStatus.DISABLED, manager.checkLocationStatus())
        shadowOf(locationManager).setLocationEnabled(true)
        assertEquals(LocationStatus.ENABLED, manager.checkLocationStatus())

        manager.cleanup()
        manager.cleanup()
        controller.destroy()
    }

    @Test
    fun `location status routing and recovery messages remain exact`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).create()
        var enabled = 0
        val disabled = mutableListOf<String>()
        val manager = LocationStatusManager(
            activity = controller.get(),
            context = app,
            onLocationEnabled = { enabled++ },
            onLocationDisabled = disabled::add
        )

        manager.handleLocationStatus(LocationStatus.ENABLED)
        manager.handleLocationStatus(LocationStatus.NOT_AVAILABLE)

        assertEquals(1, enabled)
        assertEquals(
            listOf("Location services are not available on this device."),
            disabled
        )
        assertTrue(manager.getStatusMessage(LocationStatus.DISABLED).contains("Bluetooth scanning"))
        manager.cleanup()
        controller.destroy()
    }
}
