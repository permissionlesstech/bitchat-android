package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.NostrTransport

/** UI facade. A route is not a delivery result; all private sends enter the durable worker. */
class MessageRouter private constructor(private val context: Context, private var mesh: MeshService) {
    enum class RouteResult { MESH, NOSTR, QUEUED, DROPPED }
    companion object {
        @Volatile private var instance: MessageRouter? = null
        fun tryGetInstance(): MessageRouter? = instance

        fun deliveryJob(message: com.bitchat.android.model.BitchatMessage, to: String): PrivateDeliveryJob {
            val conversation = ContactDirectory.canonicalConversationId(to)
            val geo = GeohashConversationRegistry.get(to) ?: GeohashConversationRegistry.get(conversation)
            return PrivateDeliveryJob("message:${message.id}", conversation, message.id, PrivateDeliveryJob.Kind.MESSAGE,
                content = message.content, nickname = message.recipientNickname.orEmpty(), createdAt = message.timestamp.time,
                recipientPubkey = if (geo != null) GeohashAliasRegistry.get(to) ?: GeohashAliasRegistry.get(conversation) else null,
                sourceGeohash = geo)
        }
        fun getInstance(context: Context, mesh: MeshService): MessageRouter {
            val router = instance ?: synchronized(this) {
                instance ?: MessageRouter(context.applicationContext, mesh).also { instance = it }
            }
            router.mesh = mesh
            PrivateDeliveryCoordinator.getInstance(context).bindMesh(mesh)
            return router
        }
    }
    private val coordinator get() = PrivateDeliveryCoordinator.getInstance(context)
    private val nostr get() = NostrTransport.getInstance(context)

    fun sendPrivate(content: String, toPeerID: String, recipientNickname: String, messageID: String): RouteResult {
        val conversation = ContactDirectory.canonicalConversationId(toPeerID)
        val geohash = GeohashConversationRegistry.get(toPeerID)
        val key = if (geohash != null) GeohashAliasRegistry.get(toPeerID) else null
        coordinator.enqueue(PrivateDeliveryJob(
            "message:$messageID", conversation, messageID, PrivateDeliveryJob.Kind.MESSAGE,
            content, recipientNickname, key, geohash
        ))
        return RouteResult.QUEUED
    }

    fun queueReadReceipt(message: com.bitchat.android.model.BitchatMessage, conversation: String) {
        val wireID = message.wireMessageID ?: message.id
        val geo = GeohashConversationRegistry.get(conversation)
        val job = PrivateDeliveryJob("READ:$conversation:$wireID", conversation, wireID, PrivateDeliveryJob.Kind.READ,
            recipientPubkey = message.senderNostrPubkey, sourceGeohash = geo, localMessageID = message.id)
        AppStateStore.markPrivateMessageRead(message.id, job)
        coordinator.wake()
    }

    fun sendReadReceipt(receipt: ReadReceipt, toPeerID: String) = nostr.sendReadReceipt(receipt, toPeerID)
    fun sendDeliveryAck(messageID: String, toPeerID: String) = nostr.sendDeliveryAck(messageID, toPeerID)
    fun sendFavoriteNotification(toPeerID: String, isFavorite: Boolean) = nostr.sendFavoriteNotification(toPeerID, isFavorite)
    fun flushOutboxFor(peerID: String) = coordinator.wake()
    fun flushAllOutbox() = coordinator.wake()
    fun onPeersUpdated(peers: List<String>) = coordinator.wake()
    fun onSessionEstablished(peerID: String) = coordinator.wake()
    fun clearAll() = coordinator.stop()
    fun stopOutboxScheduler() = coordinator.stop()
}
