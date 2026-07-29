package com.bitchat.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R

internal enum class PeerAvailabilityAction {
    NONE,
    SHOW,
    CLEAR
}

/**
 * Tracks mesh availability epochs. A notification is eligible only for a background
 * transition from no peers to at least one peer. Returning to zero starts a new epoch.
 */
internal class PeerAvailabilityTracker {
    private var previousPeerCount = 0

    fun update(peerCount: Int, isAppInBackground: Boolean): PeerAvailabilityAction {
        require(peerCount >= 0) { "peerCount must not be negative" }

        val action = when {
            peerCount == 0 -> PeerAvailabilityAction.CLEAR
            previousPeerCount == 0 && isAppInBackground -> PeerAvailabilityAction.SHOW
            else -> PeerAvailabilityAction.NONE
        }

        previousPeerCount = peerCount
        return action
    }
}

internal interface PeerAvailabilityTextProvider {
    fun title(): String
    fun body(peerCount: Int): String
}

private class AndroidPeerAvailabilityTextProvider(
    private val context: Context
) : PeerAvailabilityTextProvider {
    override fun title(): String {
        return context.getString(R.string.notification_active_peers_title)
    }

    override fun body(peerCount: Int): String {
        return if (peerCount == 1) {
            context.getString(R.string.notification_active_peers_one)
        } else {
            context.getString(R.string.notification_active_peers_many, peerCount)
        }
    }
}

/**
 * Owns the user-visible "bitchatters nearby" notification independently of the UI delegate.
 */
internal class PeerAvailabilityNotifier(
    private val context: Context,
    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context),
    private val tracker: PeerAvailabilityTracker = PeerAvailabilityTracker(),
    private val textProvider: PeerAvailabilityTextProvider =
        AndroidPeerAvailabilityTextProvider(context),
    private val canPostNotifications: () -> Boolean = {
        notificationManager.areNotificationsEnabled() &&
            (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                )
    }
) {
    companion object {
        internal const val CHANNEL_ID = "bitchat_peer_availability_notifications"
        internal const val NOTIFICATION_ID = 997
        private const val TAG = "PeerAvailability"
    }

    init {
        createNotificationChannel()
    }

    fun onPeerCountChanged(peerCount: Int, isAppInBackground: Boolean) {
        when (tracker.update(peerCount, isAppInBackground)) {
            PeerAvailabilityAction.NONE -> Unit
            PeerAvailabilityAction.CLEAR -> notificationManager.cancel(NOTIFICATION_ID)
            PeerAvailabilityAction.SHOW -> showNotification(peerCount)
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            textProvider.title(),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            enableVibration(true)
            setShowBadge(false)
        }
        val systemManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        systemManager.createNotificationChannel(channel)
    }

    @SuppressLint("MissingPermission")
    private fun showNotification(peerCount: Int) {
        if (!canPostNotifications()) {
            Log.i(TAG, "Skipping peer availability notification because notifications are disabled")
            return
        }

        val openAppIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            NOTIFICATION_ID,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(textProvider.title())
            .setContentText(textProvider.body(peerCount))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            Log.i(TAG, "Posted peer availability notification for $peerCount peer(s)")
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission changed before peer alert was posted", error)
        }
    }
}
