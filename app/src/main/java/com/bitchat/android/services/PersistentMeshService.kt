package com.bitchat.android.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshServiceHolder
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.NotificationManager as DMNotificationManager

/**
 * Foreground service that keeps the Bluetooth mesh alive in the background
 * and delivers PM notifications when the app UI is not active.
 */
class PersistentMeshService : Service() {

    companion object {
        private const val TAG = "PersistentMeshService"
        private const val CHANNEL_ID = "bitchat_mesh_foreground"
        private const val NOTIFICATION_ID = 1337

        fun start(context: Context) {
            val intent = Intent(context, PersistentMeshService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PersistentMeshService::class.java))
        }
    }

    private lateinit var mesh: BluetoothMeshService
    private lateinit var dmNotifications: DMNotificationManager

    // Minimal headless delegate to surface PMs as notifications when UI is not attached
    private val backgroundDelegate = object : BluetoothMeshDelegate {
        override fun didReceiveMessage(message: BitchatMessage) {
            // Buffer messages in-memory while UI is closed (no disk persistence)
            try { InMemoryMessageBuffer.add(message) } catch (_: Exception) {}

            // Only show notifications when app is in background and not focused on this PM
            if (message.isPrivate) {
                val senderPeer = message.senderPeerID ?: return
                val isBg = AppVisibilityState.isAppInBackground
                val focusedPeer = AppVisibilityState.currentPrivateChatPeer
                if (isBg || (focusedPeer != senderPeer)) {
                    val senderName = if (message.senderPeerID == message.sender) senderPeer else message.sender
                    dmNotifications.setAppBackgroundState(isBg)
                    dmNotifications.setCurrentPrivateChatPeer(focusedPeer)
                    dmNotifications.showPrivateMessageNotification(senderPeer, senderName, message.content)
                }
            }
        }

        override fun didUpdatePeerList(peers: List<String>) {}
        override fun didReceiveChannelLeave(channel: String, fromPeer: String) {}
        override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {}
        override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {}
        override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? = null
        override fun getNickname(): String? = loadNickname()
        override fun isFavorite(peerID: String): Boolean = false
    }

    override fun onCreate() {
        super.onCreate()
        dmNotifications = DMNotificationManager(applicationContext)
        mesh = MeshServiceHolder.get(applicationContext)
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildOngoingNotification())

        // Ensure mesh is running and delegate includes background notifications without
        // disrupting any existing UI delegate.
        try {
            val existing = mesh.delegate
            if (existing != null && existing !== backgroundDelegate) {
                mesh.delegate = CombinedDelegate(existing, backgroundDelegate)
            } else {
                mesh.delegate = backgroundDelegate
            }
            // App may be in background; let PowerManager manage based on state updates
            mesh.startServices()
            // Immediately broadcast with the proper nickname so others see us correctly
            mesh.sendBroadcastAnnounce()
            Log.i(TAG, "Mesh started in foreground service")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh in service", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do not stop mesh here; UI or settings control lifecycle.
        Log.i(TAG, "Foreground service destroyed")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // App task removed; ensure background delegate handles callbacks
        try {
            mesh.delegate = backgroundDelegate
            AppVisibilityState.isAppInBackground = true
            Log.i(TAG, "Task removed: reattached background delegate")
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Mesh Background",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the bitchat mesh running in the background"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildOngoingNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Mesh running in background")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    /**
     * Forwards all callbacks to two delegates.
     */
    private class CombinedDelegate(
        private val a: BluetoothMeshDelegate?,
        private val b: BluetoothMeshDelegate?
    ) : BluetoothMeshDelegate {
        override fun didReceiveMessage(message: BitchatMessage) {
            a?.didReceiveMessage(message)
            b?.didReceiveMessage(message)
        }

        override fun didUpdatePeerList(peers: List<String>) {
            a?.didUpdatePeerList(peers)
            b?.didUpdatePeerList(peers)
        }

        override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
            a?.didReceiveChannelLeave(channel, fromPeer)
            b?.didReceiveChannelLeave(channel, fromPeer)
        }

        override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
            a?.didReceiveDeliveryAck(messageID, recipientPeerID)
            b?.didReceiveDeliveryAck(messageID, recipientPeerID)
        }

        override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
            a?.didReceiveReadReceipt(messageID, recipientPeerID)
            b?.didReceiveReadReceipt(messageID, recipientPeerID)
        }

        override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
            // Prefer result from a; fall back to b
            return a?.decryptChannelMessage(encryptedContent, channel)
                ?: b?.decryptChannelMessage(encryptedContent, channel)
        }

        override fun getNickname(): String? {
            return a?.getNickname() ?: b?.getNickname()
        }

        override fun isFavorite(peerID: String): Boolean {
            return (a?.isFavorite(peerID) == true) || (b?.isFavorite(peerID) == true)
        }
    }

    private fun loadNickname(): String {
        return try {
            val prefs = applicationContext.getSharedPreferences("bitchat_prefs", Context.MODE_PRIVATE)
            prefs.getString("nickname", null) ?: mesh.myPeerID
        } catch (_: Exception) {
            mesh.myPeerID
        }
    }
}
