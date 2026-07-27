package com.bitchat.android.nostr

internal data class NdrApplicationMessage(
    val content: String,
    val timestampMs: Long
)

internal object NdrApplicationMessageDecoder {
    private const val PROTOCOL_TAG = "ndr-protocol"
    private const val PROTOCOL_VALUE = "pairwise-rumor"
    private const val VERSION_TAG = "ndr-version"
    private const val VERSION_VALUE = "1"

    fun decode(
        message: NdrDecryptedMessage,
        fallbackTimestampMs: Long = System.currentTimeMillis()
    ): NdrApplicationMessage? {
        val plaintext = message.content.trim()
        if (!NdrInputPolicy.isWithinEncodedEventLimit(plaintext) ||
            !NdrInputPolicy.isPubkeyHex(message.senderPubkeyHex) ||
            message.senderDevicePubkeyHex?.let(NdrInputPolicy::isPubkeyHex) == false ||
            message.conversationOwnerPubkeyHex?.let(NdrInputPolicy::isPubkeyHex) == false ||
            message.eventId?.let(NdrInputPolicy::isEventIdHex) == false
        ) return null

        // Compatibility with the earliest BitChat NDR prototype, which sent
        // the embedded packet directly instead of the v1 pairwise rumor.
        if (plaintext.startsWith("bitchat1:")) {
            return NdrApplicationMessage(plaintext, fallbackTimestampMs)
        }

        val event = NostrEvent.fromJsonString(plaintext) ?: return null
        if (event.kind != NostrKind.DIRECT_MESSAGE) return null
        if (!NdrInputPolicy.isPubkeyHex(event.pubkey)) return null
        if (!event.pubkey.equals(message.senderPubkeyHex, ignoreCase = true)) return null
        if (event.createdAt <= 0 || event.id.isBlank()) return null
        if (!event.id.equals(event.computeEventIdHex(), ignoreCase = true)) return null
        if (!NdrInputPolicy.hasBoundedTags(event)) return null
        if (!event.hasTag(PROTOCOL_TAG, PROTOCOL_VALUE)) return null
        if (!event.hasTag(VERSION_TAG, VERSION_VALUE)) return null

        return NdrApplicationMessage(
            content = event.content,
            timestampMs = event.createdAt.toLong() * 1000L
        )
    }

    private fun NostrEvent.hasTag(name: String, value: String): Boolean {
        return tags.any { tag ->
            tag.size >= 2 && tag[0] == name && tag[1] == value
        }
    }
}
