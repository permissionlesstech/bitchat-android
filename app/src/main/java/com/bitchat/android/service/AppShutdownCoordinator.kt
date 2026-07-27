package com.bitchat.android.service

import android.app.Application
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.TorMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

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
    private val shutdownGate = ShutdownGate()

    fun isShutdownCommitted(): Boolean = shutdownGate.isCommitted()

    fun requestFullShutdownAndKill(
        app: Application,
        mesh: MeshService?,
        notificationManager: NotificationManagerCompat,
        stopForeground: () -> Unit,
        stopService: () -> Unit
    ) {
        if (!shutdownGate.commit()) return

        val terminated = AtomicBoolean(false)
        val terminateProcess = {
            if (terminated.compareAndSet(false, true)) {
                try { stopService() } catch (_: Exception) { }
                try { Process.killProcess(Process.myPid()) } catch (_: Exception) { }
                try { System.exit(0) } catch (_: Exception) { }
            }
        }
        scope.launch {
            delay(5_000)
            terminateProcess()
        }
        scope.launch {
            try {
                // Quit is an account-lifetime boundary, not a transient service
                // pause: no queued plaintext or relay event may survive it.
                val accountReset = runCatching {
                    com.bitchat.android.nostr.AccountResetCoordinator.begin(
                        application = app,
                        terminal = true
                    )
                }.getOrNull()
                try {
                    com.bitchat.android.nostr.NdrNostrService
                        .getInstance(app)
                        .shutdownForProcessExit()
                } catch (_: Exception) { }
                if (accountReset != null) {
                    runCatching {
                        com.bitchat.android.nostr.AccountResetCoordinator
                            .discardRelay(accountReset)
                    }
                }

                // Signal UI to finish gracefully before we kill the process
                try {
                    val intent = android.content.Intent(com.bitchat.android.util.AppConstants.UI.ACTION_FORCE_FINISH)
                        .setPackage(app.packageName)
                    app.sendBroadcast(intent, com.bitchat.android.util.AppConstants.UI.PERMISSION_FORCE_FINISH)
                } catch (_: Exception) { }

                // Stop mesh (best-effort)
                try { mesh?.stopServices() } catch (_: Exception) { }
                try {
                    com.bitchat.android.mesh.PowerManager
                        .getInstance(app)
                        .shutdown()
                } catch (_: Exception) { }

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

                withTimeoutOrNull(5_000) {
                    try { torStop.await() } catch (_: Exception) { }
                    delay(100)
                }
            } finally {
                terminateProcess()
            }
        }
    }
}
