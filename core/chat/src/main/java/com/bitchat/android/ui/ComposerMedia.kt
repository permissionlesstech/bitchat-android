package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.LiveVoiceTarget
import com.bitchat.android.features.voice.VoiceRecorder
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject

/**
 * Voice notes, images and files from a composer, and the voice recorder
 * that streams live when a peer can take it. Shared by the chat composer
 * and the private chat's; moved out of ChatViewModel unchanged.
 */
@ActivityRetainedScoped
class ComposerMedia @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mediaSendingManager: MediaSendingManager,
    private val sessionMesh: ChatSessionMesh,
) {

    private val mesh get() = sessionMesh.unified

    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendVoiceNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun createVoiceRecorder(toPeerIDOrNull: String?, channelOrNull: String?): VoiceRecorder {
        if (!LiveVoicePreferences.isEnabled(context)) return VoiceRecorder(context)
        val recipientPeerID = toPeerIDOrNull?.let {
            PrivateMediaRecipientResolver.resolve(it, mesh)?.meshPeerID
        }
        val liveTarget = when {
            toPeerIDOrNull != null && recipientPeerID != null && mesh.hasEstablishedSession(recipientPeerID) ->
                LiveVoiceTarget { payload -> mesh.sendVoiceFrame(recipientPeerID, payload) }
            toPeerIDOrNull == null && channelOrNull == null && mesh.getActivePeerCount() > 0 ->
                LiveVoiceTarget { payload -> mesh.sendVoiceFrame(null, payload) }
            else -> null
        }
        return VoiceRecorder(context, liveTarget)
    }

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendFileNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendImageNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun cancelMediaSend(messageId: String) {
        // Delegate to MediaSendingManager which tracks transfer IDs and cleans up UI state
        mediaSendingManager.cancelMediaSend(messageId)
    }
}
