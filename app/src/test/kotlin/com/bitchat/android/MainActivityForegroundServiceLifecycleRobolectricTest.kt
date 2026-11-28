package com.bitchat.android

import android.Manifest
import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.Lifecycle
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServicePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 34], application = Application::class)
class MainActivityForegroundServiceLifecycleRobolectricTest {
    @Test
    fun `pending foreground request runs on initial start and subsequent restart`() {
        val app = RuntimeEnvironment.getApplication()
        MeshServicePreferences.init(app)
        MeshServicePreferences.setBackgroundEnabled(true)
        shadowOf(app).grantPermissions(
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN
        )
        mockStatic(AppCompatDelegate::class.java).use { delegates ->
            // Keep real Activity/lifecycle callbacks, without inflating AppCompat resources.
            val delegate = mock<AppCompatDelegate>()
            whenever(delegate.attachBaseContext2(any())).thenAnswer { it.getArgument<Context>(0) }
            delegates.`when`<AppCompatDelegate> {
                AppCompatDelegate.create(any<Activity>(), any())
            }.thenReturn(delegate)

            // The quit path initializes superclasses and skips transports and onboarding.
            val intent = Intent(app, MainActivity::class.java).putExtra("ACTION_QUIT_APP", true)
            val controller = Robolectric.buildActivity(MainActivity::class.java, intent).create()
            val activity = controller.get()
            Activity::class.java.getDeclaredField("mFinished").apply {
                isAccessible = true
                setBoolean(activity, false)
            }
            val request = MainActivity::class.java.getDeclaredMethod("startMeshForegroundServiceBestEffort")
                .apply { isAccessible = true }
            val pending = MainActivity::class.java.getDeclaredField("pendingMeshForegroundServiceStart")
                .apply { isAccessible = true }

            try {
                assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState)
                request.invoke(activity)
                assertTrue(pending.getBoolean(activity))

                controller.start()

                assertEquals(Lifecycle.State.STARTED, activity.lifecycle.currentState)
                assertFalse("A real lifecycle start must consume the pending retry", pending.getBoolean(activity))
                assertEquals(MeshForegroundService.ACTION_START, shadowOf(app).nextStartedService?.action)

                controller.stop()
                assertEquals(Lifecycle.State.CREATED, activity.lifecycle.currentState)
                request.invoke(activity)
                assertTrue(pending.getBoolean(activity))

                controller.restart().start()

                assertEquals(Lifecycle.State.STARTED, activity.lifecycle.currentState)
                assertFalse("A later lifecycle start must also retry", pending.getBoolean(activity))
                assertEquals(MeshForegroundService.ACTION_START, shadowOf(app).nextStartedService?.action)
                controller.stop()
            } finally {
                controller.destroy()
            }
        }
    }
}
