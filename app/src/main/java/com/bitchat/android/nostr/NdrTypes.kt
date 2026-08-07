package com.bitchat.android.nostr

data class NdrPubSubEvent(
    val kind: String,
    val actionId: String = kind,
    val subid: String? = null,
    val filterJson: String? = null,
    val eventJson: String? = null,
    val peerPubkeyHex: String? = null,
    val sessionId: String? = null,
    val senderPubkeyHex: String? = null,
    val content: String? = null,
    val eventId: String? = null,
    val expiresAtSeconds: ULong? = null
)

data class NdrDecryptedMessage(
    val content: String,
    val senderPubkeyHex: String,
    val eventId: String,
    val actionId: String,
    val expiresAtSeconds: ULong? = null
)

enum class NdrDeliveryResult {
    CONSUMED,
    DUPLICATE,
    REJECTED,
    RETRY;

    val shouldAcknowledge: Boolean
        get() = this != RETRY
}

enum class NdrSendResult {
    SENT,
    NO_SESSION,
    FAILED
}

data class NdrOutOfBandPayload(
    val actionId: String,
    val eventJson: String,
    val peerPubkeyHex: String,
    internal val runtimeEpoch: Long? = null,
    internal val runtime: NdrPairwiseRuntime? = null
)

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

    fun hasBoundedTags(event: NostrEvent): Boolean = runCatching {
        event.tags.size <= MAX_EVENT_TAGS &&
            event.tags.all { tag ->
                tag.size <= MAX_EVENT_TAG_VALUES &&
                    tag.all { value ->
                        value.length <= MAX_EVENT_TAG_VALUE_BYTES &&
                            value.toByteArray(Charsets.UTF_8).size <= MAX_EVENT_TAG_VALUE_BYTES
                    }
        }
    }.getOrDefault(false)
}

data class NdrAcceptInviteResult(
    val peerPubkeyHex: String,
    val createdNewSession: Boolean
)

data class NdrOutOfBandProcessResult(
    val outboundPayloads: List<NdrOutOfBandPayload>,
    val sessionLookupPubkeyHex: String? = null
)

class NdrSessionNotReadyException(
    message: String?,
    cause: Throwable? = null
) : Exception(message, cause)

interface NdrRelayManager {
    fun subscribe(filter: NostrFilter, id: String, handler: (NostrEvent) -> Boolean)
    fun unsubscribe(id: String)
    fun sendEventConfirmed(event: NostrEvent, completion: (accepted: Boolean) -> Unit)
    fun cancelConfirmedEvent(eventId: String)
    fun setOnConnectionAvailable(handler: () -> Unit)
}

fun interface NdrRetryCancellation {
    fun cancel()
}

fun interface NdrRetryScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): NdrRetryCancellation
}

data class NdrPairwiseSessionInfo(
    val sendReady: Boolean,
    val receiveReady: Boolean,
    val trackedSenderPubkeys: List<String>
) {
    val isActive: Boolean
        get() = sendReady || receiveReady
}

data class NdrPairwiseSendResult(
    val innerEventId: String,
    val outerEventId: String
)

interface NdrPairwiseRuntime {
    fun currentInviteEventJson(): String?
    fun currentInviteUrl(root: String): String?
    fun acceptInviteFromEventJson(
        eventJson: String,
        expectedPeerPubkeyHex: String
    ): NdrAcceptInviteResult
    fun acceptInviteFromUrl(
        inviteUrl: String,
        expectedPeerPubkeyHex: String
    ): NdrAcceptInviteResult
    fun processEvent(eventJson: String)
    fun processOutOfBandResponse(eventJson: String, expectedPeerPubkeyHex: String)
    fun pendingActions(nowSeconds: ULong): List<NdrPubSubEvent>
    fun ackActions(actionIds: List<String>)
    fun sessionInfo(peerPubkeyHex: String): NdrPairwiseSessionInfo?
    fun knownPeerPubkeys(): List<String>
    fun retirePeer(peerPubkeyHex: String): Boolean
    fun sendText(
        recipientPubkeyHex: String,
        text: String,
        expiresAtSeconds: ULong? = null
    ): NdrPairwiseSendResult
    fun getOurPubkeyHex(): String
    fun getTotalSessions(): ULong
    fun destroy()
}

interface NdrPairwiseRuntimeFactory {
    fun newWithStoragePath(
        ourPubkeyHex: String,
        ourIdentityPrivkeyHex: String,
        storagePath: String
    ): NdrPairwiseRuntime
}
