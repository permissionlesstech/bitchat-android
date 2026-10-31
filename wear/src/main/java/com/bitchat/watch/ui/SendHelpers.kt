package com.bitchat.watch.ui

import com.bitchat.android.mesh.MeshMessaging
import com.bitchat.android.mesh.MeshPeers
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import java.io.File
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal fun <M> sendPublicMessage(
    appStateStore: AppStateStore,
    mesh: M,
    nickname: String,
    content: String
) where M : MeshMessaging, M : MeshPeers {
    mesh.sendMessage(content, emptyList(), null)
    appStateStore.addPublicMessage(
        BitchatMessage(
            sender = nickname,
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
 */
internal fun <M> sendPrivateMessage(
    appStateStore: AppStateStore,
    mesh: M,
    nickname: String,
    peerID: String,
    recipientNickname: String,
    content: String,
    scope: CoroutineScope
) where M : MeshMessaging, M : MeshPeers {
    val established = mesh.hasEstablishedSession(peerID)
    val messageID = java.util.UUID.randomUUID().toString()
    if (established) {
        mesh.sendPrivateMessage(content, peerID, recipientNickname, messageID)
    } else {
        mesh.initiateNoiseHandshake(peerID)
        scope.launch {
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                if (mesh.hasEstablishedSession(peerID)) {
                    mesh.sendPrivateMessage(content, peerID, recipientNickname, messageID)
                    appStateStore.updatePrivateMessageStatus(messageID, DeliveryStatus.Sent)
                    return@launch
                }
                delay(400)
            }
            // Session never came up: the echo honestly stays "Sending" (AppStateStore
            // refuses status downgrades, so it cannot be marked Failed from here).
        }
    }
    appStateStore.addPrivateMessage(
        peerID,
        BitchatMessage(
            id = messageID,
            sender = nickname,
            content = content,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = mesh.myPeerID,
            deliveryStatus = if (established) DeliveryStatus.Sent else DeliveryStatus.Sending
        )
    )
}

/**
 * Send a recorded voice note. Global chat: broadcast file packet. DM thread: Noise-encrypted
 * private file transfer ([sendFilePrivateEncrypted], which only the watch has). Local echo renders immediately (content = local path, type = Audio).
 */
internal fun <M> sendVoiceNote(
    appStateStore: AppStateStore,
    mesh: M,
    nickname: String,
    peerID: String?,
    path: String,
    sendFilePrivateEncrypted: (recipientPeerID: String, file: BitchatFilePacket) -> Unit
) where M : MeshMessaging, M : MeshPeers {
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
        sendFilePrivateEncrypted(peerID, packet)
    }
    val echo = BitchatMessage(
        sender = nickname,
        content = path,
        type = BitchatMessageType.Audio,
        timestamp = Date(),
        isPrivate = peerID != null,
        senderPeerID = mesh.myPeerID,
        deliveryStatus = DeliveryStatus.Sent
    )
    if (peerID == null) {
        appStateStore.addPublicMessage(echo)
    } else {
        appStateStore.addPrivateMessage(peerID, echo)
    }
}
