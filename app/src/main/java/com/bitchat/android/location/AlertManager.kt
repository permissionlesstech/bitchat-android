package com.bitchat.android.location

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.nostr.*

class AlertManager(private val context: Context) {
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val CHANNEL_ID = "nigeria_alerts"

    init {
        createChannel()
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "Nigerian Location Alerts",
                NotificationManager.IMPORTANCE_HIGH
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun handleAlertEvent(event: NostrEvent, currentLocation: NigeriaLocation) {
        if (event.kind != 30006) return

        // Check if event tags match current location
        val state = event.tags.find { it[0] == "ng_state" }?.get(1)
        val lga = event.tags.find { it[0] == "ng_lga" }?.get(1)
        val ward = event.tags.find { it[0] == "ng_ward" }?.get(1)
        val scope = event.tags.find { it[0] == "ng_scope" }?.get(1)

        val matches = when (scope) {
            "state" -> state == currentLocation.state
            "lga" -> state == currentLocation.state && lga == currentLocation.lga
            "ward" -> state == currentLocation.state && lga == currentLocation.lga && ward == currentLocation.ward
            else -> false
        }

        if (matches) {
            showNotification(event.content)
        }
    }

    private fun showNotification(content: String) {
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(context, System.currentTimeMillis().toInt(), intent, PendingIntent.FLAG_IMMUTABLE)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Location Alert")
            .setContentText(content)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}
