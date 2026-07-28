package com.bitchat.watch.ui

import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import java.io.File
import java.util.Date

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

internal fun sendPrivateMessage(
    mesh: WearMeshService,
    peerID: String,
    recipientNickname: String,
    content: String
) {
    mesh.sendPrivateMessage(content, peerID, recipientNickname)
    AppStateStore.addPrivateMessage(
        peerID,
        BitchatMessage(
            sender = mesh.nickname,
            content = content,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = mesh.myPeerID,
            deliveryStatus = DeliveryStatus.Sent
        )
    )
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
