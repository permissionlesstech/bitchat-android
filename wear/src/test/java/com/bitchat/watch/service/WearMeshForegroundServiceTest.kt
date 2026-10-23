package com.bitchat.watch.service

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.wear.ongoing.OngoingActivity
import com.bitchat.watch.MainActivity
import com.bitchat.watch.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.spy
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class, manifest = Config.NONE)
class WearMeshForegroundServiceTest {
    private lateinit var service: WearMeshForegroundService
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        // Attach without onCreate: notification tests must never start Bluetooth services.
        service = spy(Robolectric.buildService(WearMeshForegroundService::class.java).get())
        // Keep shared phone tests independent of Wear's minSdk/manifest/resources.
        val resources = spy(service.resources)
        doReturn(resources).`when`(service).resources
        doReturn("Bitchat").`when`(resources).getString(R.string.app_name)
        doReturn("Mesh running").`when`(resources).getString(R.string.mesh_notification_no_peers)
        for (count in listOf(1, 3)) {
            doReturn("$count peers").`when`(resources)
                .getQuantityString(R.plurals.mesh_notification_text, count, count)
        }
        shadowOf(service.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        manager = service.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            WearMeshForegroundService.CHANNEL_ID, "Mesh", NotificationManager.IMPORTANCE_LOW
        ))
    }

    private fun postInitialNotification() {
        service.startForeground(WearMeshForegroundService.NOTIFICATION_ID, service.buildNotification(0))
    }

    @Test
    fun `mesh notification exposes ongoing activity and immutable return action`() {
        postInitialNotification()
        val activity = OngoingActivity.recoverOngoingActivity(service)!!
        assertEquals(WearMeshForegroundService.NOTIFICATION_ID, activity.notificationId)
        assertEquals(R.drawable.ic_notification, activity.staticIcon.resId)
        assertEquals(service.getString(R.string.mesh_notification_no_peers),
            activity.status!!.getText(service, 0).toString())
        assertTrue(activity.touchIntent.isImmutable)
        assertEquals(MainActivity::class.java.name,
            shadowOf(activity.touchIntent).savedIntent.component!!.className)
        val notification = manager.activeNotifications.single().notification
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(notification.contentIntent, activity.touchIntent)
    }

    @Test
    fun `peer changes preserve the session and update both status surfaces`() {
        postInitialNotification()
        val original = OngoingActivity.recoverOngoingActivity(service)!!
        for (count in listOf(1, 3, 0)) {
            service.updateForegroundNotification(count)
            val updated = OngoingActivity.recoverOngoingActivity(service)!!
            assertEquals(original.timestamp, updated.timestamp)
            assertEquals(original.touchIntent, updated.touchIntent)
            val notification = manager.activeNotifications.single().notification
            val expected = if (count == 0) service.getString(R.string.mesh_notification_no_peers)
                else service.resources.getQuantityString(R.plurals.mesh_notification_text, count, count)
            assertEquals(expected, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertEquals(expected, updated.status!!.getText(service, 0).toString())
        }
    }

    @Test
    fun `stopping the mesh service removes the ongoing activity`() {
        postInitialNotification()
        assertNotNull(OngoingActivity.recoverOngoingActivity(service))
        service.onDestroy()
        assertNull(OngoingActivity.recoverOngoingActivity(service))
    }

    @Test
    fun `revoked notification permission skips updates without crashing`() {
        postInitialNotification()
        shadowOf(service.application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        service.updateForegroundNotification(3)
        assertEquals("Mesh running", OngoingActivity.recoverOngoingActivity(service)!!
            .status!!.getText(service, 0).toString())
    }
}
