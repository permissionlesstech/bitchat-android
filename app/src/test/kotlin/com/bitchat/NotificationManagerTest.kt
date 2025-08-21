package com.bitchat

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.util.NotificationIntervalManager
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.Mockito.times
import org.mockito.MockitoAnnotations
import org.mockito.Spy
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NotificationManagerTest {

  private val context: Context = ApplicationProvider.getApplicationContext()
  private val notificationIntervalManager = NotificationIntervalManager()
  lateinit var notificationManager: NotificationManager

  @Spy
  val notificationManagerCompat: NotificationManagerCompat =
    Mockito.spy(NotificationManagerCompat.from(context))

  @Before
  fun setup() {
    MockitoAnnotations.openMocks(this)
    notificationManager = NotificationManager(
      context,
      notificationManagerCompat,
      notificationIntervalManager
    )
  }

  @Test
  fun `when there are no active peers, do not send active peer notification`() {
    notificationManager.setAppBackgroundState(true)
    notificationManager.showActiveUserNotification(emptyList())
    verify(notificationManagerCompat, never()).notify(any(), any())
  }

  @Test
  fun `when app is in foreground, do not send active peer notification`() {
    notificationManager.setAppBackgroundState(false)
    notificationManager.showActiveUserNotification(listOf("peer-1"))
    verify(notificationManagerCompat, never()).notify(any(), any())
  }

  @Test
  fun `when there is an active peer, send notification`() {
    notificationManager.setAppBackgroundState(true)
    notificationManager.showActiveUserNotification(listOf("peer-1"))
    verify(notificationManagerCompat, times(1)).notify(any(), any())
  }

  @Test
  fun `when there is an active peer but less than 5 minutes have passed since last notification, do not send notification`() {
    notificationManager.setAppBackgroundState(true)
    notificationManager.showActiveUserNotification(listOf("peer-1"))
    notificationManager.showActiveUserNotification(listOf("peer-2"))
    verify(notificationManagerCompat, times(1)).notify(any(), any())
  }

  @Test
  fun `when there is an active peer and more than 5 minutes have passed since last notification, send notification`() {
    notificationManager.setAppBackgroundState(true)
    notificationManager.showActiveUserNotification(listOf("peer-1"))
    notificationIntervalManager.setLastNetworkNotificationTime(System.currentTimeMillis() - 301_000L)
    notificationManager.showActiveUserNotification(listOf("peer-2"))
    verify(notificationManagerCompat, times(2)).notify(any(), any())
  }
}
