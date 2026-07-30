package com.bitchat.android.service

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

class PeerAvailabilityTrackerTest {

    @Test
    fun `repeated flapping produces only one alert during cooldown`() {
        val clock = MutableClock()
        val tracker = tracker(clock = clock)
        var showCount = 0

        if (tracker.update(1, isAppInBackground = true) == PeerAvailabilityAction.SHOW) {
            showCount++
            tracker.markAlertShown()
        }

        repeat(5) {
            clock.advance(1_000L)
            assertEquals(
                PeerAvailabilityAction.CLEAR,
                tracker.update(0, isAppInBackground = true)
            )
            clock.advance(PeerAvailabilityTracker.EMPTY_REARM_DELAY_MS)
            if (tracker.update(1, isAppInBackground = true) == PeerAvailabilityAction.SHOW) {
                showCount++
                tracker.markAlertShown()
            }
        }

        assertEquals(1, showCount)
    }

    @Test
    fun `mesh must remain empty before notification re-arms`() {
        val clock = MutableClock()
        val tracker = tracker(
            clock = clock,
            alertCooldownMs = 0L
        )

        assertEquals(PeerAvailabilityAction.SHOW, tracker.update(1, isAppInBackground = true))
        tracker.markAlertShown()

        assertEquals(PeerAvailabilityAction.CLEAR, tracker.update(0, isAppInBackground = true))
        clock.advance(PeerAvailabilityTracker.EMPTY_REARM_DELAY_MS - 1)
        assertEquals(PeerAvailabilityAction.NONE, tracker.update(1, isAppInBackground = true))

        assertEquals(PeerAvailabilityAction.CLEAR, tracker.update(0, isAppInBackground = true))
        clock.advance(PeerAvailabilityTracker.EMPTY_REARM_DELAY_MS)
        assertEquals(PeerAvailabilityAction.SHOW, tracker.update(1, isAppInBackground = true))
    }

    @Test
    fun `cooldown survives tracker recreation`() {
        val clock = MutableClock()
        val history = InMemoryAlertHistory()
        val originalTracker = tracker(clock = clock, history = history)

        assertEquals(
            PeerAvailabilityAction.SHOW,
            originalTracker.update(1, isAppInBackground = true)
        )
        originalTracker.markAlertShown()

        clock.advance(PeerAvailabilityTracker.ALERT_COOLDOWN_MS - 1)
        val restartedDuringCooldown = tracker(clock = clock, history = history)
        assertEquals(
            PeerAvailabilityAction.NONE,
            restartedDuringCooldown.update(1, isAppInBackground = true)
        )

        clock.advance(1)
        val restartedAfterCooldown = tracker(clock = clock, history = history)
        assertEquals(
            PeerAvailabilityAction.SHOW,
            restartedAfterCooldown.update(1, isAppInBackground = true)
        )
    }

    @Test
    fun `foreground discovery is not replayed after app enters background`() {
        val tracker = tracker()

        assertEquals(PeerAvailabilityAction.NONE, tracker.update(1, isAppInBackground = false))
        assertEquals(PeerAvailabilityAction.NONE, tracker.update(1, isAppInBackground = true))
        assertEquals(PeerAvailabilityAction.CLEAR, tracker.update(0, isAppInBackground = true))
        assertEquals(PeerAvailabilityAction.SHOW, tracker.update(1, isAppInBackground = true))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative peer count is rejected`() {
        tracker().update(-1, isAppInBackground = true)
    }

    private fun tracker(
        clock: MutableClock = MutableClock(),
        history: InMemoryAlertHistory = InMemoryAlertHistory(),
        alertCooldownMs: Long = PeerAvailabilityTracker.ALERT_COOLDOWN_MS
    ): PeerAvailabilityTracker {
        return PeerAvailabilityTracker(
            alertHistory = history,
            nowMillis = clock::now,
            alertCooldownMs = alertCooldownMs
        )
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
        context.getSharedPreferences(
            SharedPreferencesPeerAvailabilityAlertHistory.PREFERENCES_NAME,
            Context.MODE_PRIVATE
        ).edit().clear().commit()
    }

    @Test
    fun `background availability posts on dedicated channel`() {
        val textProvider = testTextProvider()
        val notifier = PeerAvailabilityNotifier(
            context = context,
            tracker = tracker(),
            textProvider = textProvider,
            canPostNotifications = { true }
        )

        notifier.onPeerCountChanged(0, isAppInBackground = true)
        notifier.onPeerCountChanged(2, isAppInBackground = true)

        val notification =
            shadowOf(systemNotificationManager).getNotification(
                PeerAvailabilityNotifier.NOTIFICATION_ID
            )
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
        assertTrue(notification.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        assertNotNull(
            systemNotificationManager.getNotificationChannel(PeerAvailabilityNotifier.CHANNEL_ID)
        )
    }

    @Test
    fun `returning to zero cancels availability notification`() {
        val notifier = PeerAvailabilityNotifier(
            context = context,
            tracker = tracker(),
            textProvider = testTextProvider(),
            canPostNotifications = { true }
        )

        notifier.onPeerCountChanged(1, isAppInBackground = true)
        notifier.onPeerCountChanged(0, isAppInBackground = true)

        assertNull(
            shadowOf(systemNotificationManager).getNotification(
                PeerAvailabilityNotifier.NOTIFICATION_ID
            )
        )
    }

    @Test
    fun `explicit clear cancels availability notification`() {
        val notifier = PeerAvailabilityNotifier(
            context = context,
            tracker = tracker(),
            textProvider = testTextProvider(),
            canPostNotifications = { true }
        )

        notifier.onPeerCountChanged(1, isAppInBackground = true)
        notifier.clear()

        assertNull(
            shadowOf(systemNotificationManager).getNotification(
                PeerAvailabilityNotifier.NOTIFICATION_ID
            )
        )
    }

    @Test
    fun `disabled notifications do not post`() {
        val history = InMemoryAlertHistory()
        val notifier = PeerAvailabilityNotifier(
            context = context,
            tracker = PeerAvailabilityTracker(history),
            textProvider = testTextProvider(),
            canPostNotifications = { false }
        )

        notifier.onPeerCountChanged(1, isAppInBackground = true)

        assertNull(
            shadowOf(systemNotificationManager).getNotification(
                PeerAvailabilityNotifier.NOTIFICATION_ID
            )
        )
        assertNull(history.lastAlertAtMillis)
    }

    @Test
    fun `shared preferences history persists last alert time`() {
        val history = SharedPreferencesPeerAvailabilityAlertHistory(context)
        history.lastAlertAtMillis = 42L

        assertEquals(
            42L,
            SharedPreferencesPeerAvailabilityAlertHistory(context).lastAlertAtMillis ?: -1L
        )
    }

    private fun tracker(): PeerAvailabilityTracker {
        return PeerAvailabilityTracker(InMemoryAlertHistory())
    }

    private fun testTextProvider(): PeerAvailabilityTextProvider {
        return object : PeerAvailabilityTextProvider {
            override fun title(): String = "Bitchatters nearby"

            override fun body(peerCount: Int): String = "$peerCount people around"
        }
    }
}

private class InMemoryAlertHistory(
    override var lastAlertAtMillis: Long? = null
) : PeerAvailabilityAlertHistory

private class MutableClock(
    private var currentMillis: Long = 1_000L
) {
    fun now(): Long = currentMillis

    fun advance(durationMillis: Long) {
        currentMillis += durationMillis
    }
}
