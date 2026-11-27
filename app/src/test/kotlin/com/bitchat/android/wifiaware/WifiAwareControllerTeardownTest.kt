package com.bitchat.android.wifiaware

import android.content.Context
import com.bitchat.android.identity.scope.IdentityTeardown
import com.bitchat.android.service.testWifiAwareController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class WifiAwareControllerTeardownTest {
    private val teardown = IdentityTeardown()
    private val controller = testWifiAwareController(teardown = teardown)

    private fun runningService(): com.bitchat.android.wifiaware.WifiAwareMeshService =
        mock<WifiAwareMeshService>().also { whenever(it.isRunning()).thenReturn(true) }

    @Test
    fun `an open controller publishes a started service`() {
        assertEquals(WifiAwareController.PublishOutcome.PUBLISHED, controller.publishStarted(runningService()))
        assertTrue(controller.running.value)
    }

    @Test
    fun `closing during a start does not publish the service`() {
        val started = mock<WifiAwareMeshService>()
        // The identity ends while startServices() is still running.
        whenever(started.startServices()).thenAnswer {
            assertTrue(teardown.runAll().isEmpty())
            whenever(started.isRunning()).thenReturn(true)
            Unit
        }
        started.startServices()

        assertEquals(WifiAwareController.PublishOutcome.ABANDONED, controller.publishStarted(started))
        assertFalse(controller.running.value)
    }

    @Test
    fun `an ended controller starts nothing and registers no receiver`() {
        teardown.runAll()
        controller.setEnabled(true)
        controller.initialize(androidx.test.core.app.ApplicationProvider.getApplicationContext<Context>(), true)
        controller.startIfPossible()

        assertFalse(controller.running.value)
    }
}
