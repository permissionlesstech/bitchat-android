package com.bitchat.watch.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.MainActivity
import com.bitchat.watch.R
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.notification.WearNotificationCoordinator
import com.bitchat.watch.notification.WearNotificationPolicy
import com.bitchat.watch.ui.WearChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps the BLE mesh (scan + advertise + GATT) alive while the app is backgrounded or the watch
 * goes ambient. Bluetooth mesh only; no internet connectivity is used or declared.
 */
class WearMeshForegroundService : Service() {

    companion object {
        const val CHANNEL_ID = "bitchat_mesh"
        const val NOTIFICATION_ID = 1
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManagerCompat
    private lateinit var notificationCoordinator: WearNotificationCoordinator
    private lateinit var mesh: WearMeshService
    private var peerCountJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        notificationCoordinator = WearNotificationCoordinator.getInstance(applicationContext)
        mesh = WearMeshService.getOrCreate(applicationContext)
        createChannel()
        startForeground(WearNotificationPolicy.activePeerCount(AppStateStore.peers.value))
        observePeerCount()
        mesh.onPrivateMessage = ::handlePrivateMessage
        mesh.startServices()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        updateForegroundNotification(
            WearNotificationPolicy.activePeerCount(AppStateStore.peers.value)
        )
        return START_STICKY
    }

    override fun onDestroy() {
        peerCountJob?.cancel()
        peerCountJob = null
        if (::mesh.isInitialized) {
            mesh.onPrivateMessage = null
            mesh.stopServices()
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.mesh_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.mesh_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startForeground(activePeers: Int) {
        val notification = buildNotification(activePeers)
        startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
    }

    private fun observePeerCount() {
        peerCountJob = serviceScope.launch {
            AppStateStore.peers
                .map(WearNotificationPolicy::activePeerCount)
                .distinctUntilChanged()
                .collect(::updateForegroundNotification)
        }
    }

    private fun updateForegroundNotification(activePeers: Int) {
        if (!canPostNotifications()) return
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(activePeers))
        } catch (_: SecurityException) {
            // Permission can be revoked between the preflight check and notify().
        }
    }

    private fun handlePrivateMessage(message: BitchatMessage) {
        val senderPeerID = message.senderPeerID ?: return
        if (message.sender == "system") return

        WearChatState.onPrivateMessageArrived(senderPeerID)
        val senderNickname = mesh.getPeerNickname(senderPeerID)
            ?: message.sender.takeIf { it.isNotBlank() && it != senderPeerID }
            ?: senderPeerID.take(8)
        notificationCoordinator.onPrivateMessage(
            message = message,
            senderPeerID = senderPeerID,
            senderNickname = senderNickname
        )
    }

    private fun buildNotification(activePeers: Int): Notification {
        val launchIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                if (activePeers == 0) {
                    getString(R.string.mesh_notification_no_peers)
                } else {
                    resources.getQuantityString(
                        R.plurals.mesh_notification_text,
                        activePeers,
                        activePeers
                    )
                }
            )
            .setContentIntent(launchIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun canPostNotifications(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }
}
