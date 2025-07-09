package com.bitchat.android.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage

object NotificationHandler {

    fun showChatNotification(context: Context, bitChatMessage: BitchatMessage) {
        val channelId = "chat_channel"

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(bitChatMessage.recipientNickname ?: bitChatMessage.sender)
            .setContentText(bitChatMessage.content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        with(NotificationManagerCompat.from(context)) {
            try {
                notify(generateNotificationId(), builder.build())
            } catch (e: SecurityException) {
                // TODO Handle the exception
            }
        }
    }

    fun createNotificationChannels(context: Context) {
        val chatChannel = NotificationChannel(
            "chat_channel",
            "Chat Notifications",
            NotificationManager.IMPORTANCE_HIGH
        )
        chatChannel.description = "Notifications for new messages"

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(chatChannel)
    }

    private fun generateNotificationId(): Int {
        return (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
    }
}