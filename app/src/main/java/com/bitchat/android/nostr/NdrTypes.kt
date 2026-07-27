package com.bitchat.android.nostr

data class NdrPubSubEvent(
    val kind: String,
    val subid: String? = null,
    val filterJson: String? = null,
    val eventJson: String? = null,
    val senderPubkeyHex: String? = null,
    val senderDevicePubkeyHex: String? = null,
    val conversationOwnerPubkeyHex: String? = null,
    val content: String? = null,
    val eventId: String? = null
)

data class NdrDecryptedMessage(
    val content: String,
    val senderPubkeyHex: String,
    val senderDevicePubkeyHex: String? = null,
    val conversationOwnerPubkeyHex: String? = null,
    val eventId: String? = null
) {
    /**
     * Iris sets [conversationOwnerPubkeyHex] on a local-sibling copy. The
     * authenticated author remains [senderPubkeyHex], while app routing must
     * use the remote conversation owner.
     */
    val conversationPubkeyHex: String
        get() = conversationOwnerPubkeyHex ?: senderPubkeyHex

    val isLocalSiblingCopy: Boolean
        get() = conversationOwnerPubkeyHex != null

    fun isAttributedToLocalAccount(localAccountPubkeyHex: String): Boolean =
        !isLocalSiblingCopy ||
            senderPubkeyHex.equals(localAccountPubkeyHex, ignoreCase = true)
}

internal object NdrInputPolicy {
    const val MAX_ENCODED_EVENT_BYTES = 64 * 1024
    private const val MAX_EVENT_TAGS = 64
    private const val MAX_EVENT_TAG_VALUES = 16
    private const val MAX_EVENT_TAG_VALUE_BYTES = 1024
    private val HEX_32 = Regex("^[0-9a-fA-F]{64}$")

    fun isPubkeyHex(value: String): Boolean = HEX_32.matches(value)

    fun isEventIdHex(value: String): Boolean = HEX_32.matches(value)

    fun isWithinEncodedEventLimit(value: String): Boolean =
        value.length <= MAX_ENCODED_EVENT_BYTES &&
            value.toByteArray(Charsets.UTF_8).size <= MAX_ENCODED_EVENT_BYTES

    fun hasBoundedTags(event: NostrEvent): Boolean {
        if (event.tags.size > MAX_EVENT_TAGS) return false
        return event.tags.all { tag ->
            tag.size <= MAX_EVENT_TAG_VALUES &&
                tag.all { value ->
                    value.length <= MAX_EVENT_TAG_VALUE_BYTES &&
                        value.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_TAG_VALUE_BYTES
                }
        }
    }
}

data class NdrAcceptInviteResult(
    val ownerPubkeyHex: String,
    val inviterDevicePubkeyHex: String,
    val deviceId: String,
    val createdNewSession: Boolean
)

data class NdrOutOfBandProcessResult(
    val outboundPayloads: List<String>,
    val sessionLookupPubkeyHex: String? = null
)

class NdrSessionNotReadyException(
    message: String?,
    cause: Throwable? = null
) : Exception(message, cause)

interface NdrRelayManager {
    fun subscribe(filter: NostrFilter, id: String, handler: (NostrEvent) -> Unit)
    fun unsubscribe(id: String)
    fun sendEvent(event: NostrEvent)
}

interface NdrSessionManager {
    fun init()
    fun acceptInviteFromEventJson(eventJson: String, ownerPubkeyHintHex: String?): NdrAcceptInviteResult
    fun acceptInviteFromUrl(inviteUrl: String, ownerPubkeyHintHex: String?): NdrAcceptInviteResult
    fun processEvent(eventJson: String)
    fun processOutOfBandResponse(eventJson: String, expectedOwnerPubkeyHex: String)
    fun drainEvents(): List<NdrPubSubEvent>
    fun getActiveSessionState(peerPubkeyHex: String): String?
    fun sendText(recipientPubkeyHex: String, text: String, expiresAtSeconds: ULong? = null): List<String>
    fun getOurPubkeyHex(): String
    fun getTotalSessions(): ULong
    fun destroy()
}

interface NdrSessionManagerFactory {
    fun newWithStoragePath(
        ourPubkeyHex: String,
        ourIdentityPrivkeyHex: String,
        deviceId: String,
        storagePath: String,
        ownerPubkeyHex: String?
    ): NdrSessionManager
}
