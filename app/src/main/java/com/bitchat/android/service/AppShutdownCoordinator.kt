package com.bitchat.android.service

import android.app.Application
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates a full application shutdown:
 * - Stop mesh cleanly
 * - Stop Tor without changing persistent setting
 * - Clear in-memory AppState
 * - Stop foreground service/notification
 * - Kill the process after completion or after a 5s timeout
 */
object AppShutdownCoordinator {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val shutdownToken = AtomicLong(0L)
    @Volatile
    private var shutdownJob: Job? = null

    fun cancelPendingShutdown() {
        shutdownToken.incrementAndGet()
        shutdownJob?.cancel()
        shutdownJob = null
    }

    fun requestFullShutdownAndKill(
        app: Application,
        mesh: BluetoothMeshService?,
        notificationManager: NotificationManagerCompat,
        stopForeground: () -> Unit,
        stopService: () -> Unit
    ) {
        val token = shutdownToken.incrementAndGet()
        shutdownJob?.cancel()
        val job = scope.launch {
            // Signal UI to finish gracefully before we kill the process
            try {
                val intent = android.content.Intent(com.bitchat.android.util.AppConstants.UI.ACTION_FORCE_FINISH)
                    .setPackage(app.packageName)
                app.sendBroadcast(intent, com.bitchat.android.util.AppConstants.UI.PERMISSION_FORCE_FINISH)
            } catch (_: Exception) { }

            // Stop mesh (best-effort)
            try { mesh?.stopServices() } catch (_: Exception) { }

            // Stop Tor temporarily (do not change user setting)
            val torProvider = ArtiTorManager.getInstance()
            val torStop = async {
                try { torProvider.applyMode(app, TorMode.OFF) } catch (_: Exception) { }
            }

            // Clear AppState in-memory store
            try { com.bitchat.android.services.AppStateStore.clear() } catch (_: Exception) { }

            // Stop foreground and clear notification
            try { stopForeground() } catch (_: Exception) { }
            try { notificationManager.cancel(10001) } catch (_: Exception) { }

            // Wait up to 5 seconds for shutdown tasks
            withTimeoutOrNull(5000) {
                try { torStop.await() } catch (_: Exception) { }
                delay(100)
            }

            // Stop the service itself
            if (!isActive || shutdownToken.get() != token) return@launch
            try { stopService() } catch (_: Exception) { }

            // Hard kill the app process
            if (!isActive || shutdownToken.get() != token) return@launch
            try { Process.killProcess(Process.myPid()) } catch (_: Exception) { }
            try { System.exit(0) } catch (_: Exception) { }
        }
        shutdownJob = job
        job.invokeOnCompletion {
            if (shutdownJob === job) {
                shutdownJob = null
            }
        }
    }
}
