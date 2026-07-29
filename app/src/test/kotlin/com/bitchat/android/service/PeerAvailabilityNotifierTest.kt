package com.bitchat.android.service

import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class PeerAvailabilityTrackerTest {

    @Test
    fun `background zero to nonzero transition shows once per availability epoch`() {
        val tracker = PeerAvailabilityTracker()

        assertEquals(PeerAvailabilityAction.CLEAR, tracker.update(0, isAppInBackground = true))
        assertEquals(PeerAvailabilityAction.SHOW, tracker.update(1, isAppInBackground = true))
        assertEquals(PeerAvailabilityAction.NONE, tracker.update(2, isAppInBackground = true))
        assertEquals(PeerAvailabilityAction.CLEAR, tracker.update(0, isAppInBackground = true))
        assertEquals(PeerAvailabilityAction.SHOW, tracker.update(1, isAppInBackground = true))
    }

    @Test
    fun `foreground discovery is not replayed after app enters background`() {
        val tracker = PeerAvailabilityTracker()

        assertEquals(PeerAvailabilityAction.NONE, tracker.update(1, isAppInBackground = false))
        assertEquals(PeerAvailabilityAction.NONE, tracker.update(1, isAppInBackground = true))
        assertEquals(PeerAvailabilityAction.CLEAR, tracker.update(0, isAppInBackground = true))
        assertEquals(PeerAvailabilityAction.SHOW, tracker.update(1, isAppInBackground = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative peer count is rejected`() {
        PeerAvailabilityTracker().update(-1, isAppInBackground = true)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PeerAvailabilityNotifierTest {
    private lateinit var context: Context
    private lateinit var systemNotificationManager: NotificationManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        systemNotificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        systemNotificationManager.cancelAll()
    }

    @Test
    fun `background availability posts on dedicated channel`() {
        val textProvider = testTextProvider()
        val notifier = PeerAvailabilityNotifier(
            context = context,
            textProvider = textProvider,
            canPostNotifications = { true }
        )

        notifier.onPeerCountChanged(0, isAppInBackground = true)
        notifier.onPeerCountChanged(2, isAppInBackground = true)

        val notification =
            shadowOf(systemNotificationManager).getNotification(PeerAvailabilityNotifier.NOTIFICATION_ID)
        assertNotNull(notification)
        assertEquals(PeerAvailabilityNotifier.CHANNEL_ID, notification.channelId)
        assertEquals(
            textProvider.title(),
            notification.extras.getString("android.title")
        )
        assertEquals(
            textProvider.body(2),
            notification.extras.getString("android.text")
        )
        assertNotNull(
            systemNotificationManager.getNotificationChannel(PeerAvailabilityNotifier.CHANNEL_ID)
        )
    }

    @Test
    fun `returning to zero cancels availability notification`() {
        val notifier = PeerAvailabilityNotifier(
            context = context,
            textProvider = testTextProvider(),
            canPostNotifications = { true }
        )

        notifier.onPeerCountChanged(1, isAppInBackground = true)
        notifier.onPeerCountChanged(0, isAppInBackground = true)

        assertNull(
            shadowOf(systemNotificationManager).getNotification(PeerAvailabilityNotifier.NOTIFICATION_ID)
        )
    }

    @Test
    fun `disabled notifications do not post`() {
        val notifier = PeerAvailabilityNotifier(
            context = context,
            textProvider = testTextProvider(),
            canPostNotifications = { false }
        )

        notifier.onPeerCountChanged(1, isAppInBackground = true)

        assertNull(
            shadowOf(systemNotificationManager).getNotification(PeerAvailabilityNotifier.NOTIFICATION_ID)
        )
    }

    private fun testTextProvider(): PeerAvailabilityTextProvider {
        return object : PeerAvailabilityTextProvider {
            override fun title(): String = "Bitchatters nearby"

            override fun body(peerCount: Int): String = "$peerCount people around"
        }
    }
}
