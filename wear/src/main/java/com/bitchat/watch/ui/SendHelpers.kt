package com.bitchat.watch.ui

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import java.io.File
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun sendPublicMessage(mesh: WearMeshService, content: String) {
    mesh.sendMessage(content)
    AppStateStore.addPublicMessage(
        BitchatMessage(
            sender = mesh.nickname,
            content = content,
            timestamp = Date(),
            senderPeerID = mesh.myPeerID,
            deliveryStatus = DeliveryStatus.Sent
        )
    )
}

/**
 * DM send with honest delivery state. MeshCore drops pre-handshake content (it only kicks
 * off the Noise handshake), so when no session exists we must not echo "Sent": the echo
 * stays "Sending" while a retry loop waits for the session and completes the send.
 *
 * The retry loop runs in the mesh service's scope because the caller's screen usually closes
 * right after sending; it ends with the 15 s deadline or when the mesh stops.
 */
internal fun sendPrivateMessage(
    mesh: WearMeshService,
    peerID: String,
    recipientNickname: String,
    content: String,
    nowMs: () -> Long = System::currentTimeMillis
) {
    val established = mesh.hasEstablishedSession(peerID)
    val messageID = java.util.UUID.randomUUID().toString()
    if (established) {
        mesh.sendPrivateMessageWithId(content, peerID, recipientNickname, messageID)
    } else {
        mesh.initiateNoiseHandshake(peerID)
    }
    // Store the echo before the retry loop starts: the loop can finish on another thread at
    // once, and a status update for a message that is not stored yet is lost.
    AppStateStore.addPrivateMessage(
        peerID,
        BitchatMessage(
            id = messageID,
            sender = mesh.nickname,
            content = content,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = mesh.myPeerID,
            deliveryStatus = if (established) DeliveryStatus.Sent else DeliveryStatus.Sending
        )
    )
    if (established) return
    mesh.serviceScope.launch {
        val deadline = nowMs() + 15_000
        while (mesh.isRunning() && nowMs() < deadline) {
            if (mesh.hasEstablishedSession(peerID)) {
                mesh.sendPrivateMessageWithId(content, peerID, recipientNickname, messageID)
                AppStateStore.updatePrivateMessageStatus(messageID, DeliveryStatus.Sent)
                return@launch
            }
            delay(400)
        }
        // Session never came up: the echo honestly stays "Sending" (AppStateStore
        // refuses status downgrades, so it cannot be marked Failed from here).
    }
}

/**
 * Send a recorded voice note. Global chat: broadcast file packet. DM thread: Noise-encrypted
 * private file transfer. Local echo renders immediately (content = local path, type = Audio).
 */
internal fun sendVoiceNote(mesh: WearMeshService, peerID: String?, path: String) {
    val file = File(path)
    if (!file.isFile) return
    val packet = BitchatFilePacket(
        fileName = file.name,
        fileSize = file.length(),
        mimeType = "audio/mp4",
        content = file.readBytes()
    )
    if (peerID == null) {
        mesh.sendFileBroadcast(packet)
    } else {
        mesh.sendFilePrivateEncrypted(peerID, packet)
    }
    val echo = BitchatMessage(
        sender = mesh.nickname,
        content = path,
        type = BitchatMessageType.Audio,
        timestamp = Date(),
        isPrivate = peerID != null,
        senderPeerID = mesh.myPeerID,
        deliveryStatus = DeliveryStatus.Sent
    )
    if (peerID == null) {
        AppStateStore.addPublicMessage(echo)
    } else {
        AppStateStore.addPrivateMessage(peerID, echo)
    }
}
