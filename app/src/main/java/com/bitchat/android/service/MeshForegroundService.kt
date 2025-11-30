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
        const val ACTION_QUIT = "com.bitchat.android.service.QUIT"
        const val ACTION_UPDATE_NOTIFICATION = "com.bitchat.android.service.UPDATE_NOTIFICATION"
        const val ACTION_NOTIFICATION_PERMISSION_GRANTED = "com.bitchat.android.action.NOTIFICATION_PERMISSION_GRANTED"

        fun start(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_START }

            // On API >= 26, avoid background-service start restrictions by using startForegroundService
            // only when we can actually post a notification (Android 13+ requires runtime notif permission)
            val bgEnabled = MeshServicePreferences.isBackgroundEnabled(true)
            val hasNotifPerm = hasNotificationPermissionStatic(context)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (bgEnabled && hasNotifPerm) {
                    context.startForegroundService(intent)
                } else {
                    // Do not attempt to start a background service from headless context without notif permission
                    // or when background is disabled, to avoid BackgroundServiceStartNotAllowedException.
                    android.util.Log.i(
                        "MeshForegroundService",
                        "Not starting service on API>=26 (bgEnabled=$bgEnabled, hasNotifPerm=$hasNotifPerm)"
                    )
                }
            } else {
                if (bgEnabled) {
                    context.startService(intent)
                } else {
                    android.util.Log.i("MeshForegroundService", "Background disabled; not starting service (pre-O)")
                }
            }
        }

        /**
         * Helper to be invoked right after POST_NOTIFICATIONS is granted to try
         * promoting/starting the foreground service immediately without polling.
         */
        fun onNotificationPermissionGranted(context: Context) {
            // If background is enabled and permission now granted, start/promo service
            val hasNotifPerm = hasNotificationPermissionStatic(context)
            if (!MeshServicePreferences.isBackgroundEnabled(true) || !hasNotifPerm) return

            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_UPDATE_NOTIFICATION }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Safe now that we can show a notification
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, MeshForegroundService::class.java).apply { action = ACTION_STOP }
            context.startService(intent)
        }

        private fun shouldStartAsForeground(context: Context): Boolean {
            return MeshServicePreferences.isBackgroundEnabled(true) &&
                    hasBluetoothPermissionsStatic(context) &&
                    hasNotificationPermissionStatic(context)
        }

        private fun hasBluetoothPermissionsStatic(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_ADVERTISE) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_CONNECT) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.BLUETOOTH_SCAN) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                val fine = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val coarse = androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED
                fine || coarse
            }
        }

        private fun hasNotificationPermissionStatic(ctx: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= 33) {
                androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            } else true
        }
    }

    private lateinit var notificationManager: NotificationManagerCompat
    private var updateJob: Job? = null
    private var meshService: BluetoothMeshService? = null
    private val serviceJob = Job()
    private val scope = CoroutineScope(Dispatchers.Default + serviceJob)
    private var isInForeground: Boolean = false

    override fun onCreate() {
        super.onCreate()
        notificationManager = NotificationManagerCompat.from(this)
        createChannel()

        // Adopt or create the mesh service
        val existing = MeshServiceHolder.meshService
        meshService = existing ?: MeshServiceHolder.getOrCreate(applicationContext)
        if (existing != null) {
            android.util.Log.d("MeshForegroundService", "Adopted existing BluetoothMeshService from holder")
        } else {
            android.util.Log.i("MeshForegroundService", "Created/adopted new BluetoothMeshService via holder")
        }
        MeshServiceHolder.attach(meshService!!)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                // Stop FGS and mesh cleanly
                try { meshService?.stopServices() } catch (_: Exception) { }
                try { MeshServiceHolder.clear() } catch (_: Exception) { }
                try { stopForeground(true) } catch (_: Exception) { }
                notificationManager.cancel(NOTIFICATION_ID)
                isInForeground = false
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_QUIT -> {
                // Fully stop all background activity, stop Tor (without changing setting), then kill the app
                AppShutdownCoordinator.requestFullShutdownAndKill(
                    app = application,
                    mesh = meshService,
                    notificationManager = notificationManager,
                    stopForeground = {
                        try { stopForeground(true) } catch (_: Exception) { }
                        isInForeground = false
                    },
                    stopService = { stopSelf() }
                )
                return START_NOT_STICKY
            }
            ACTION_UPDATE_NOTIFICATION -> {
                // If we became eligible and are not in foreground yet, promote once
                if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions() && !isInForeground) {
                    val n = buildNotification(meshService?.getActivePeerCount() ?: 0)
                    startForeground(NOTIFICATION_ID, n)
                    isInForeground = true
                } else {
                    updateNotification(force = true)
                }
            }
            else -> { /* ACTION_START or null */ }
        }

        // Ensure mesh is running (only after permissions are granted)
        ensureMeshStarted()

        // Promote exactly once when eligible, otherwise stay background (or stop)
        if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions() && !isInForeground) {
            val notification = buildNotification(meshService?.getActivePeerCount() ?: 0)
            startForeground(NOTIFICATION_ID, notification)
            isInForeground = true
        }

        // Periodically refresh the notification with live network size
        if (updateJob == null) {
            updateJob = scope.launch {
                while (isActive) {
                    // Retry enabling mesh/foreground once permissions become available
                    ensureMeshStarted()
                    val eligible = MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions()
                    if (eligible) {
                        // Only update the notification; do not re-call startForeground()
                        updateNotification(force = false)
                    } else {
                        // If disabled or perms missing, ensure we are not in foreground and clear notif
                        if (isInForeground) {
                            try { stopForeground(false) } catch (_: Exception) { }
                            isInForeground = false
                        }
                        notificationManager.cancel(NOTIFICATION_ID)
                    }
                    delay(5000)
                }
            }
        }

        return START_STICKY
    }

    private fun ensureMeshStarted() {
        if (!hasBluetoothPermissions()) return
        try {
            android.util.Log.d("MeshForegroundService", "Ensuring mesh service is started")
            meshService?.startServices()
        } catch (e: Exception) {
            android.util.Log.e("MeshForegroundService", "Failed to start mesh service: ${e.message}")
        }
    }

    private fun updateNotification(force: Boolean) {
        val count = meshService?.getActivePeerCount() ?: 0
        val notification = buildNotification(count)
        if (MeshServicePreferences.isBackgroundEnabled(true) && hasAllRequiredPermissions()) {
            notificationManager.notify(NOTIFICATION_ID, notification)
        } else if (force) {
            // If disabled and forced, make sure to remove any prior foreground state
            try { stopForeground(false) } catch (_: Exception) { }
            notificationManager.cancel(NOTIFICATION_ID)
            isInForeground = false
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
        return if (Build.VERSION.SDK_INT >= 33) {
            androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun buildNotification(activeUsers: Int): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        // Action: Quit Bitchat
        val quitIntent = Intent(this, MeshForegroundService::class.java).apply { action = ACTION_QUIT }
        val quitPendingIntent = PendingIntent.getService(
            this, 1, quitIntent,
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
            // Add an action button that appears when notification is expanded
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_action_quit_bitchat),
                quitPendingIntent
            )
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
        // Cancel the service coroutine scope to prevent leaks
        try { serviceJob.cancel() } catch (_: Exception) { }
        // Best-effort ensure we are not marked foreground
        if (isInForeground) {
            try { stopForeground(true) } catch (_: Exception) { }
            isInForeground = false
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
