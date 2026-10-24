package com.bitchat.android.onboarding

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The location manager's dialog routing and messages. The Bluetooth and location
 * status reads are DeviceReadiness's now and are tested in :core:readiness.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SystemStateManagerContractTest {
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
        controller.destroy()
    }
}
