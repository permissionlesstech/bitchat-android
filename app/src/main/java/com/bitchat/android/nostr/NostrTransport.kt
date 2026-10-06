package com.bitchat.android.nostr

import android.content.Context
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.PrivateDeliveryCoordinator
import com.bitchat.android.services.PrivateDeliveryJob
import com.google.gson.Gson

/** Bitchat wire adapter. Persistence, retry and delivery state belong to the coordinator. */
class NostrTransport(private val context: Context, var senderPeerID: String = "") {
    companion object {
        @Volatile private var instance: NostrTransport? = null
        fun getInstance(context: Context): NostrTransport = instance ?: synchronized(this) {
            instance ?: NostrTransport(context.applicationContext).also { instance = it }
        }
    }
    val myPeerID: String get() = senderPeerID
    private val gson = Gson()
    private val coordinator get() = PrivateDeliveryCoordinator.getInstance(context)

    fun sendPrivateMessage(content: String, to: String, recipientNickname: String, messageID: String) {
        coordinator.enqueue(PrivateDeliveryJob("message:$messageID", ContactDirectory.canonicalConversationId(to), messageID,
            PrivateDeliveryJob.Kind.MESSAGE, content, recipientNickname))
    }

    fun sendFavoriteNotification(to: String, isFavorite: Boolean) {
        // The relationship repository persists the latest intent. The worker recovers it even
        // if this wake-up is lost to process death or precedes key exchange.
        coordinator.wake()
    }

    fun sendReadReceipt(receipt: ReadReceipt, to: String) = queueReceipt(receipt.originalMessageID, to, true)
    fun sendDeliveryAck(messageID: String, to: String) = queueReceipt(messageID, to, false)

    private fun queueReceipt(messageID: String, to: String, read: Boolean) {
        val conversation = ContactDirectory.canonicalConversationId(to)
        val kind = if (read) PrivateDeliveryJob.Kind.READ else PrivateDeliveryJob.Kind.DELIVERED
        val geo = GeohashConversationRegistry.get(to)
        coordinator.enqueue(PrivateDeliveryJob("${kind.name}:$conversation:$messageID", conversation, messageID, kind,
            recipientPubkey = if (geo != null) GeohashAliasRegistry.get(to) else null, sourceGeohash = geo))
    }

    fun sendDeliveryAckGeohash(messageID: String, toRecipientHex: String, fromIdentity: NostrIdentity, sourceGeohash: String? = null) =
        queueReceiptToKey(messageID, toRecipientHex, fromIdentity, false, sourceGeohash)

    fun sendReadReceiptGeohash(messageID: String, toRecipientHex: String, fromIdentity: NostrIdentity, sourceGeohash: String? = null) =
        queueReceiptToKey(messageID, toRecipientHex, fromIdentity, true, sourceGeohash)

    private fun queueReceiptToKey(messageID: String, pubkey: String, identity: NostrIdentity, read: Boolean, geohash: String?) {
        val account = NostrIdentityBridge.getCurrentNostrIdentity(context) ?: return
        require(geohash != null || identity.publicKeyHex == account.publicKeyHex) { "Missing receipt identity scope" }
        val alias = requireNotNull(ContactIdentityResolver.nostrAliasForPubkey(pubkey))
        val conversation = ContactDirectory.canonicalConversationId(alias)
        val kind = if (read) PrivateDeliveryJob.Kind.READ else PrivateDeliveryJob.Kind.DELIVERED
        coordinator.enqueue(PrivateDeliveryJob("${kind.name}:$conversation:$messageID", conversation, messageID, kind,
            recipientPubkey = pubkey, sourceGeohash = geohash))
    }

    fun sendPrivateMessageGeohash(content: String, toRecipientHex: String, messageID: String, sourceGeohash: String? = null) {
        val alias = requireNotNull(ContactIdentityResolver.nostrAliasForPubkey(toRecipientHex))
        val geohash = sourceGeohash ?: GeohashConversationRegistry.get(alias) ?: return
        coordinator.enqueue(PrivateDeliveryJob("message:$messageID", alias, messageID, PrivateDeliveryJob.Kind.MESSAGE,
            content = content, recipientPubkey = toRecipientHex, sourceGeohash = geohash))
    }

    internal fun prepare(job: PrivateDeliveryJob): PrivateDeliveryJob {
        val identity = job.sourceGeohash?.let { NostrIdentityBridge.deriveIdentity(it, context) }
            ?: requireNotNull(NostrIdentityBridge.getCurrentNostrIdentity(context)) { "Identity unavailable" }
        require(job.sourceIdentityPubkey == null || job.sourceIdentityPubkey == identity.publicKeyHex) { "Delivery identity changed" }
        val contact = ContactDirectory.resolve(job.conversationID)
        val recipient = requireNotNull(ContactIdentityResolver.nostrPubkeyHex(
            (if (job.sourceGeohash == null) contact.nostrPubkey else job.recipientPubkey) ?: job.recipientPubkey ?: error("Recipient key unavailable")
        )) { "Invalid recipient key" }
        if (job.eventJson != null && job.recipientPubkey == recipient && job.sourceIdentityPubkey == identity.publicKeyHex) return job
        val sender = senderPeerID.takeIf(ContactIdentityResolver::isMeshPeerId) ?: "0000000000000000"
        // The authenticated Nostr recipient is authoritative. Account PMs retain their legacy
        // recipient field for older Bitchat peers; ephemeral mesh IDs are never used as identity.
        val embedded = when (job.kind) {
            PrivateDeliveryJob.Kind.MESSAGE, PrivateDeliveryJob.Kind.FAVORITE -> {
                if (job.sourceGeohash == null && contact.noiseKeyHex != null) {
                    NostrEmbeddedBitChat.encodePMForNostr(job.content, job.messageID, contact.noiseKeyHex!!, sender, job.createdAt)
                } else NostrEmbeddedBitChat.encodePMForNostrNoRecipient(job.content, job.messageID, sender, job.createdAt)
            }
            PrivateDeliveryJob.Kind.DELIVERED, PrivateDeliveryJob.Kind.READ ->
                NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(
                    if (job.kind == PrivateDeliveryJob.Kind.READ) NoisePayloadType.READ_RECEIPT else NoisePayloadType.DELIVERED,
                    job.messageID, sender)
        } ?: error("Message encoding failed")
        val event = NostrProtocol.createPrivateMessage(embedded, recipient, identity, job.createdAt).single()
        return job.copy(eventJson = gson.toJson(event), recipientPubkey = recipient, sourceIdentityPubkey = identity.publicKeyHex)
    }

    internal suspend fun publish(job: PrivateDeliveryJob): Boolean {
        val event = gson.fromJson(requireNotNull(job.eventJson), NostrEvent::class.java)
        return NostrRelayManager.getInstance(context).publishConfirmed(event)
    }

    fun cleanup() = coordinator.stop()
}
