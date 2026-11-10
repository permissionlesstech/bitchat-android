package com.bitchat.android.onboarding

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.ui.debug.DebugPreferenceManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doNothing
import org.mockito.Mockito.spy
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InternetOnlyOnboardingTest {
    private lateinit var application: Application
    private lateinit var permissions: PermissionManager
    private lateinit var coordinator: OnboardingCoordinator
    private var completed = 0
    private var backgroundRequests = 0
    private var failures = 0

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        application.getSharedPreferences("bitchat_permissions", Context.MODE_PRIVATE)
            .edit().clear().commit()
        DebugPreferenceManager.init(application)
        DebugPreferenceManager.setBleEnabled(true)
        DebugPreferenceManager.setWifiAwareEnabled(false)
        BackgroundLocationPreferenceManager.setSkipped(application, false)
        permissions = spy(PermissionManager(application))
        // JVM tests do not load app string resources. Keep real permission state
        // and onboarding behavior; only resource-dependent diagnostic logging is stubbed.
        doNothing().`when`(permissions).logPermissionStatus()
        shadowOf(application).denyPermissions(
            *permissions.getRequiredPermissions().toTypedArray(),
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
        val controller = Robolectric.buildActivity(ComponentActivity::class.java)
        coordinator = OnboardingCoordinator(
            controller.get(), permissions,
            onOnboardingComplete = { completed++ },
            onBackgroundLocationRequired = { backgroundRequests++ },
            onOnboardingFailed = { failures++ }
        )
        controller.setup()
    }

    private fun results(result: Map<String, Boolean>) {
        OnboardingCoordinator::class.java.getDeclaredMethod("handlePermissionResults", Map::class.java)
            .apply { isAccessible = true }
            .invoke(coordinator, result)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
    }

    @Test
    fun `denying all local permissions completes without requesting background location`() {
        results(permissions.getRequiredPermissions().associateWith { false })
        assertEquals(1, completed)
        assertEquals(0, backgroundRequests)
        assertEquals(0, failures)
        assertFalse(permissions.isFirstTimeLaunch())
        assertFalse(permissions.areRequiredPermissionsGranted())
    }

    @Test
    fun `skipping disables transports and does not repeat the notification prompt`() {
        coordinator.skipLocalPermissions()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
        assertEquals(1, completed)
        assertEquals(0, backgroundRequests)
        assertEquals(0, failures)
        assertTrue(permissions.getUnrequestedOptionalPermissions().isEmpty())
        assertFalse(permissions.areRequiredPermissionsGranted())
        assertFalse(DebugPreferenceManager.getBleEnabled())
        assertFalse(DebugPreferenceManager.getWifiAwareEnabled())
    }

    @Test
    fun `partial Bluetooth grant does not trigger background location request`() {
        shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
        results(mapOf(Manifest.permission.ACCESS_FINE_LOCATION to false))
        assertEquals(1, completed)
        assertEquals(0, backgroundRequests)
        assertEquals(0, failures)
    }

    @Test
    fun `full foreground grant preserves optional background location flow`() {
        shadowOf(application).grantPermissions(*permissions.getRequiredPermissions().toTypedArray())
        results(permissions.getRequiredPermissions().associateWith { true })
        assertEquals(0, completed)
        assertEquals(1, backgroundRequests)
        assertEquals(0, failures)
    }

    @Test
    fun `notification denial after previous local grants is not local denial`() {
        shadowOf(application).grantPermissions(*permissions.getRequiredPermissions().toTypedArray())
        BackgroundLocationPreferenceManager.setSkipped(application, true)
        results(mapOf(Manifest.permission.POST_NOTIFICATIONS to false))
        assertEquals(1, completed)
        assertEquals(0, backgroundRequests)
        assertEquals(0, failures)
    }
}
