package com.bitchat.android.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.NicknameProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Handles the wave action on the "bitchatters nearby" notification: broadcasts a 👋 on the
 * public mesh without opening the app. The notification always refers to mesh peers, so the
 * wave goes to the public mesh timeline regardless of the channel selected in the UI.
 */
class PeerAvailabilityWaveReceiver : BroadcastReceiver() {
    companion object {
        private const val WAVE_CONTENT = "👋"
        private const val TAG = "PeerAvailabilityWave"

        /**
         * Echoes the wave into the public mesh timeline and broadcasts it, mirroring how the
         * composer sends a public mesh message.
         */
        internal fun sendMeshWave(
            mesh: MeshService,
            senderNickname: String,
            timestamp: Date = Date()
        ): BitchatMessage {
            val message = BitchatMessage(
                sender = senderNickname,
                content = WAVE_CONTENT,
                timestamp = timestamp,
                isRelay = false,
                senderPeerID = mesh.myPeerID
            )
            AppStateStore.addPublicMessage(message)
            mesh.sendMessage(WAVE_CONTENT, emptyList(), null)
            return message
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PeerAvailabilityNotifier.ACTION_WAVE) return
        val appContext = context.applicationContext
        // Unlike a content tap, an action tap leaves the notification in the shade.
        NotificationManagerCompat.from(appContext).cancel(PeerAvailabilityNotifier.NOTIFICATION_ID)

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                // A notification can outlive the process that posted it. Promote the mesh
                // runtime before dispatch so Android keeps the transport alive after this
                // short-lived receiver finishes.
                MeshForegroundService.start(appContext)
                val mesh = MeshServiceHolder.getUnifiedOrCreate(appContext)
                sendMeshWave(mesh, NicknameProvider.getNickname(appContext, mesh.myPeerID))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to send wave to nearby peers", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
