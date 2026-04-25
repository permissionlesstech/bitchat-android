package com.bitchat.android.nostr

data class NdrPubSubEvent(
    val kind: String,
    val subid: String? = null,
    val filterJson: String? = null,
    val eventJson: String? = null,
    val senderPubkeyHex: String? = null,
    val content: String? = null,
    val eventId: String? = null
)

data class NdrDecryptedMessage(
    val content: String,
    val senderPubkeyHex: String,
    val eventId: String? = null,
    val innerEventJson: String? = null
)

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
