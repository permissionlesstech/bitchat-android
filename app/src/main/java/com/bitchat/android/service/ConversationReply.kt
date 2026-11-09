package com.bitchat.android.service

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.MessageRouter
import java.util.Date
import java.util.UUID

/**
 * Sends a reply from a notification, with what one identity gave the receiver when the reply came in.
 *
 * The objects are read once on the main thread, so they all belong to the identity that was current
 * then, and a panic in between cannot leave the reply half on the next identity. A reply that lands
 * after that identity ended is dropped: it answers a conversation that no longer exists, and the new
 * identity must not send it. During a wipe the old store's closed gate refuses it.
 */
internal class ConversationReply(
    private val identityIsCurrent: () -> Boolean,
    private val appStateStore: AppStateStore,
    private val mesh: () -> MeshService,
    private val router: (MeshService) -> MessageRouter,
) {
    /** Returns true if the reply was sent and the conversation is marked read. */
    suspend fun send(conversationID: String, reply: String, recipientNickname: String?): Boolean {
        if (!identityIsCurrent()) return false
        val mesh = try {
            mesh()
        } catch (_: IllegalStateException) {
            return false
        }
        val message = BitchatMessage(
            id = UUID.randomUUID().toString().uppercase(),
            sender = mesh.myPeerID,
            content = reply,
            timestamp = Date(),
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = mesh.myPeerID,
            deliveryStatus = DeliveryStatus.Sending,
        )
        val persisted = appStateStore.addPrivateMessageDurably(
            peerID = conversationID,
            msg = message,
            forceRead = true,
        )
        if (!persisted || !identityIsCurrent()) return false
        router(mesh).sendPrivate(
            content = reply,
            toPeerID = conversationID,
            recipientNickname = message.recipientNickname.orEmpty(),
            messageID = message.id,
        )
        return appStateStore.setPrivateConversationRead(conversationID, true)
    }
}
