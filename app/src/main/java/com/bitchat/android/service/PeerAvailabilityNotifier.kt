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
import androidx.core.content.edit
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class PeerAvailabilityAction {
    NONE,
    SHOW,
    CLEAR
}

internal interface PeerAvailabilityAlertHistory {
    var lastAlertAtMillis: Long?
}

internal class SharedPreferencesPeerAvailabilityAlertHistory(
    context: Context
) : PeerAvailabilityAlertHistory {
    companion object {
        internal const val PREFERENCES_NAME = "peer_availability_notifications"
        private const val KEY_LAST_ALERT_AT_MILLIS = "last_alert_at_millis"
    }

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override var lastAlertAtMillis: Long?
        get() = if (preferences.contains(KEY_LAST_ALERT_AT_MILLIS)) {
            preferences.getLong(KEY_LAST_ALERT_AT_MILLIS, 0L)
        } else {
            null
        }
        set(value) {
            preferences.edit {
                if (value == null) {
                    remove(KEY_LAST_ALERT_AT_MILLIS)
                } else {
                    putLong(KEY_LAST_ALERT_AT_MILLIS, value)
                }
            }
        }
}

/**
 * Tracks mesh availability epochs with two anti-flapping gates:
 * - no more than one alert per persisted cooldown window;
 * - after an alert, the mesh must remain empty before another epoch can re-arm.
 */
internal class PeerAvailabilityTracker(
    private val alertHistory: PeerAvailabilityAlertHistory,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val alertCooldownMs: Long = ALERT_COOLDOWN_MS,
    private val emptyRearmDelayMs: Long = EMPTY_REARM_DELAY_MS
) {
    companion object {
        internal const val ALERT_COOLDOWN_MS = 5 * 60_000L
        internal const val EMPTY_REARM_DELAY_MS = 30_000L
    }

    private var previousPeerCount = 0
    private var isArmed = true
    private var emptySinceMillis: Long? = null

    init {
        require(alertCooldownMs >= 0) { "alertCooldownMs must not be negative" }
        require(emptyRearmDelayMs >= 0) { "emptyRearmDelayMs must not be negative" }
    }

    fun update(peerCount: Int, isAppInBackground: Boolean): PeerAvailabilityAction {
        require(peerCount >= 0) { "peerCount must not be negative" }

        val now = nowMillis()
        if (peerCount == 0) {
            if (previousPeerCount > 0 || emptySinceMillis == null) {
                emptySinceMillis = now
            }
            previousPeerCount = 0
            return PeerAvailabilityAction.CLEAR
        }

        val transitionedFromEmpty = previousPeerCount == 0
        previousPeerCount = peerCount
        if (!transitionedFromEmpty) return PeerAvailabilityAction.NONE

        if (!isArmed) {
            val emptySince = emptySinceMillis
            val remainedEmptyLongEnough =
                emptySince != null && now - emptySince >= emptyRearmDelayMs
            if (!remainedEmptyLongEnough) {
                emptySinceMillis = null
                return PeerAvailabilityAction.NONE
            }
            isArmed = true
        }
        emptySinceMillis = null

        val lastAlertAt = alertHistory.lastAlertAtMillis
        val cooldownElapsed =
            lastAlertAt == null || now - lastAlertAt >= alertCooldownMs
        return if (isAppInBackground && cooldownElapsed) {
            PeerAvailabilityAction.SHOW
        } else {
            PeerAvailabilityAction.NONE
        }
    }

    /**
     * Called only after NotificationManager accepts the post. Failed or disabled posts
     * do not consume the cooldown or require the mesh to re-arm.
     */
    fun markAlertShown() {
        alertHistory.lastAlertAtMillis = nowMillis()
        isArmed = false
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
 * The first eligible peer starts a fixed aggregation window; arrivals within that window update
 * the count without postponing the alert indefinitely.
 */
internal class PeerAvailabilityNotifier(
    private val context: Context,
    private val scope: CoroutineScope,
    private val notificationManager: NotificationManagerCompat =
        NotificationManagerCompat.from(context),
    private val tracker: PeerAvailabilityTracker = PeerAvailabilityTracker(
        SharedPreferencesPeerAvailabilityAlertHistory(context)
    ),
    private val textProvider: PeerAvailabilityTextProvider =
        AndroidPeerAvailabilityTextProvider(context),
    private val isAppCurrentlyInBackground: () -> Boolean,
    private val aggregationWindowMs: Long = AGGREGATION_WINDOW_MS,
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
        internal const val AGGREGATION_WINDOW_MS = 10_000L
        private const val TAG = "PeerAvailability"
    }

    private var latestPeerCount = 0
    private var pendingNotificationJob: Job? = null

    init {
        require(aggregationWindowMs >= 0) { "aggregationWindowMs must not be negative" }
        createNotificationChannel()
    }

    fun onPeerCountChanged(peerCount: Int, isAppInBackground: Boolean) {
        latestPeerCount = peerCount
        when (tracker.update(peerCount, isAppInBackground)) {
            PeerAvailabilityAction.NONE -> Unit
            PeerAvailabilityAction.CLEAR -> clear()
            PeerAvailabilityAction.SHOW -> scheduleNotification()
        }
    }

    fun clear() {
        pendingNotificationJob?.cancel()
        pendingNotificationJob = null
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun scheduleNotification() {
        if (pendingNotificationJob?.isActive == true) return

        pendingNotificationJob = scope.launch {
            delay(aggregationWindowMs)
            pendingNotificationJob = null

            val peerCount = latestPeerCount
            if (peerCount > 0 && isAppCurrentlyInBackground()) {
                showNotification(peerCount)
            }
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
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .build()

        try {
            notificationManager.notify(NOTIFICATION_ID, notification)
            tracker.markAlertShown()
            Log.i(TAG, "Posted peer availability notification for $peerCount peer(s)")
        } catch (error: SecurityException) {
            Log.w(TAG, "Notification permission changed before peer alert was posted", error)
        }
    }
}
