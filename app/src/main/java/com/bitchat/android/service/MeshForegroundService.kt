package com.bitchat.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.mesh.BluetoothMeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MeshForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "bitchat_mesh_service"
        private const val NOTIFICATION_ID = 10001

        const val ACTION_START = "com.bitchat.android.service.START"
        const val ACTION_STOP = "com.bitchat.android.service.STOP"
        const val ACTION_UPDATE_NOTIFICATION = "com.bitchat.android.service.UPDATE_NOTIFICATION"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }

    private lateinit var notificationManager: NotificationManagerCompat
    private var updateJob: Job? = null
    private var meshService: BluetoothMeshService? = null
    private val scope = CoroutineScope(Dispatchers.Default + Job())

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        createChannel()

        // Adopt or create the mesh service
        meshService = MeshServiceHolder.meshService ?: MeshServiceHolder.getOrCreate(applicationContext)
        MeshServiceHolder.attach(meshService!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_NOTIFICATION -> {
                updateNotification(force = true)
            }
            else -> { /* ACTION_START or null */ }
        }

        // Ensure mesh is running (only after permissions are granted)
        ensureMeshStarted()

        // Start as foreground only if user enabled it AND we have required permissions
        if (MeshServicePreferences.isPersistentNotificationEnabled(true) && hasAllRequiredPermissions()) {
            val notification = buildNotification(meshService?.getActivePeerCount() ?: 0)
            startForeground(NOTIFICATION_ID, notification)
        }

        // Periodically refresh the notification with live network size
        if (updateJob == null) {
            updateJob = scope.launch {
                while (isActive) {
                    // Retry enabling mesh/foreground once permissions become available
                    ensureMeshStarted()
                    if (MeshServicePreferences.isPersistentNotificationEnabled(true) && hasAllRequiredPermissions()) {
                        updateNotification(force = false)
                        // If not yet in foreground (e.g., permission just granted), promote now
                        try {
                            startForeground(NOTIFICATION_ID, buildNotification(meshService?.getActivePeerCount() ?: 0))
                        } catch (_: Exception) { /* ignore, will retry */ }
                    } else {
                        // If disabled or perms missing, ensure we are not in foreground to avoid SecurityException
                        try { stopForeground(false) } catch (_: Exception) { }
                    }
                    delay(5000)
                }
            }
        }

        return START_STICKY
    }

    private fun ensureMeshStarted() {
        if (!hasBluetoothPermissions()) return
        try { meshService?.startServices() } catch (_: Exception) { }
    }

    private fun updateNotification(force: Boolean) {
        val count = meshService?.getActivePeerCount() ?: 0
        val notification = buildNotification(count)
        if (MeshServicePreferences.isPersistentNotificationEnabled(true) && hasAllRequiredPermissions()) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } else if (force) {
            // If disabled and forced, make sure to remove any prior foreground state
            stopForeground(false)
            notificationManager.cancel(NOTIFICATION_ID)
        }
    }

    private fun hasAllRequiredPermissions(): Boolean {
        // For starting FGS with connectedDevice|dataSync, we need:
        // - Foreground service permissions (declared in manifest)
        // - One of the device-related permissions (we request BL perms at runtime)
        // - On Android 13+, POST_NOTIFICATIONS to actually show notification
        return hasBluetoothPermissions() && hasNotificationPermission()
    }

    private fun hasBluetoothPermissions(): Boolean {
        val pm = androidx.core.content.ContextCompat::class
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else {
            // Prior to S, scanning requires location permissions
            val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val coarse = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
            fine || coarse
        }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun buildNotification(activeUsers: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val title = getString(R.string.app_name)
        val content = getString(R.string.mesh_service_notification_content, activeUsers)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.mesh_service_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.mesh_service_channel_desc)
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        updateJob?.cancel()
        updateJob = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
