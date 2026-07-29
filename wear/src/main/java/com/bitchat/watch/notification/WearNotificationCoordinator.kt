package com.bitchat.watch.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.NotificationTextUtils
import com.bitchat.watch.MainActivity
import com.bitchat.watch.R
import com.bitchat.watch.ui.WearChatState
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide owner for local Wear notifications.
 *
 * Mesh delivery must not depend on an Activity being alive, so the foreground service invokes this
 * coordinator directly. UI state is consulted only to suppress an alert for the exact DM that is
 * currently visible while the app is resumed.
 */
class WearNotificationCoordinator private constructor(context: Context) {

    companion object {
        const val EXTRA_OPEN_DM = "com.bitchat.watch.extra.OPEN_DM"
        const val EXTRA_PEER_ID = "com.bitchat.watch.extra.PEER_ID"

        private const val MESSAGE_CHANNEL_ID = "bitchat_watch_messages"
        private const val CONVERSATION_NOTIFICATION_ID_BASE = 10_000

        @Volatile
        private var instance: WearNotificationCoordinator? = null

        fun getInstance(context: Context): WearNotificationCoordinator {
            return instance ?: synchronized(this) {
                instance ?: WearNotificationCoordinator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private data class PendingMessage(
        val senderNickname: String,
        val preview: String,
        val timestamp: Long
    )

    private val appContext = context.applicationContext
    private val notificationManager = NotificationManagerCompat.from(appContext)
    private val pendingMessages = ConcurrentHashMap<String, MutableList<PendingMessage>>()

    init {
        createMessageChannel()
    }

    @Synchronized
    fun onPrivateMessage(
        message: BitchatMessage,
        senderPeerID: String,
        senderNickname: String
    ) {
        val shouldNotify = WearNotificationPolicy.shouldNotifyPrivateMessage(
            senderPeerID = senderPeerID,
            senderIsSystem = message.sender == "system",
            appInForeground = WearChatState.appInForeground,
            openDmPeer = WearChatState.openDmPeer
        )
        if (!shouldNotify || !canPostNotifications()) return

        pendingMessages.getOrPut(senderPeerID) { mutableListOf() }.add(
            PendingMessage(
                senderNickname = senderNickname,
                preview = NotificationTextUtils.buildPrivateMessagePreview(message),
                timestamp = message.timestamp.time
            )
        )

        postConversationNotification(senderPeerID)
    }

    @Synchronized
    fun clearConversation(peerID: String) {
        pendingMessages.remove(peerID)
        notificationManager.cancel(conversationNotificationId(peerID))
    }

    private fun postConversationNotification(peerID: String) {
        val messages = pendingMessages[peerID] ?: return
        val latest = messages.lastOrNull() ?: return
        val sender = Person.Builder()
            .setName(latest.senderNickname)
            .setKey(peerID)
            .build()
        val user = Person.Builder()
            .setName(appContext.getString(R.string.app_name))
            .build()
        val style = NotificationCompat.MessagingStyle(user)
        messages.takeLast(5).forEach { pending ->
            style.addMessage(pending.preview, pending.timestamp, sender)
        }

        val contentIntent = PendingIntent.getActivity(
            appContext,
            conversationNotificationId(peerID),
            Intent(appContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_OPEN_DM, true)
                putExtra(EXTRA_PEER_ID, peerID)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val publicVersion = NotificationCompat.Builder(appContext, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(appContext.getString(R.string.notification_new_private_message))
            .setContentText(appContext.getString(R.string.notification_private_message_hidden))
            .build()

        val notification = NotificationCompat.Builder(appContext, MESSAGE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(latest.senderNickname)
            .setContentText(latest.preview)
            .setContentIntent(contentIntent)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .setAutoCancel(true)
            .setWhen(latest.timestamp)
            .setShowWhen(true)
            .build()

        try {
            notificationManager.notify(conversationNotificationId(peerID), notification)
        } catch (_: SecurityException) {
            // Permission can be revoked between the preflight check and notify().
        }
    }

    private fun createMessageChannel() {
        val channel = NotificationChannel(
            MESSAGE_CHANNEL_ID,
            appContext.getString(R.string.message_channel_name),
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = appContext.getString(R.string.message_channel_description)
            enableVibration(true)
            setShowBadge(true)
            lockscreenVisibility = NotificationCompat.VISIBILITY_PRIVATE
        }
        appContext.getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean {
        val permissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        return permissionGranted && notificationManager.areNotificationsEnabled()
    }

    private fun conversationNotificationId(peerID: String): Int {
        return CONVERSATION_NOTIFICATION_ID_BASE + (peerID.hashCode() and 0x3FFFFFFF)
    }
}
