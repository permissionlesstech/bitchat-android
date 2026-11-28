package com.bitchat.android

import android.app.Application
import android.app.ForegroundServiceStartNotAllowedException
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.Lifecycle
import com.bitchat.android.service.MeshServicePreferences
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class MainActivityForegroundServiceRetryRobolectricTest {
    @Before
    fun setUp() {
        MeshServicePreferences.init(RuntimeEnvironment.getApplication())
        MeshServicePreferences.setBackgroundEnabled(true)
    }

    @Test
    fun `denied foreground start stays pending until an accepted retry`() {
        assertRetryAfterDenial(ForegroundServiceStartNotAllowedException("synthetic transient denial"))
    }

    @Test
    fun `permission denial stays pending until an accepted retry`() {
        assertRetryAfterDenial(SecurityException("synthetic permission revocation"))
    }

    private fun assertRetryAfterDenial(denial: RuntimeException) {
        val caller = mock<Context>()
        whenever(caller.checkPermission(any(), any(), any())).thenReturn(PackageManager.PERMISSION_GRANTED)
        whenever(caller.startForegroundService(any()))
            .thenThrow(denial)
            .thenReturn(ComponentName("com.bitchat.droid", "com.bitchat.android.service.MeshForegroundService"))
        val lifecycle = mock<Lifecycle> {
            on { currentState } doReturn Lifecycle.State.STARTED
        }
        // Attach without onCreate: the retry contract needs no transports, UI, or user data.
        val activity = spy(Robolectric.buildActivity(MainActivity::class.java).get())
        doReturn(caller).whenever(activity).applicationContext
        doReturn(lifecycle).whenever(activity).lifecycle
        val start = MainActivity::class.java.getDeclaredMethod("startMeshForegroundServiceBestEffort")
            .apply { isAccessible = true }
        start.invoke(activity)
        val pending = MainActivity::class.java.getDeclaredField("pendingMeshForegroundServiceStart")
            .apply { isAccessible = true }
        assertTrue("Denied start must retain the activity retry", pending.getBoolean(activity))
        verify(caller).startForegroundService(any())

        start.invoke(activity)

        verify(caller, times(2)).startForegroundService(any())
        verify(caller, never()).startService(any())
        assertFalse("An accepted retry must clear the pending flag", pending.getBoolean(activity))
    }
}
