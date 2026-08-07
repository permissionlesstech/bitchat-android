package com.bitchat.android.nostr

internal data class NdrApplicationMessage(
    val content: String,
    val timestampMs: Long,
    val expiresAtSeconds: Long?
) {
    fun isExpiredAt(nowSeconds: Long): Boolean =
        expiresAtSeconds?.let { it <= nowSeconds } == true
}

internal object NdrApplicationMessageDecoder {
    private const val PROTOCOL_TAG = "ndr-protocol"
    private const val PROTOCOL_VALUE = "pairwise-rumor"
    private const val VERSION_TAG = "ndr-version"
    private const val VERSION_VALUE = "1"
    private const val MILLISECOND_TIMESTAMP_TAG = "ms"
    private const val EXPIRATION_TAG = "expiration"
    private val UNSIGNED_DECIMAL = Regex("^[0-9]+$")

    fun decode(message: NdrDecryptedMessage): NdrApplicationMessage? =
        runCatching { decodeStrict(message) }.getOrNull()

    private fun decodeStrict(message: NdrDecryptedMessage): NdrApplicationMessage? {
        val plaintext = message.content.trim()
        if (!NdrInputPolicy.isWithinEncodedEventLimit(plaintext) ||
            !NdrInputPolicy.isPubkeyHex(message.senderPubkeyHex) ||
            !NdrInputPolicy.isEventIdHex(message.eventId)
        ) return null

        val event = NostrEvent.fromJsonString(plaintext) ?: return null
        if (event.kind != NostrKind.DIRECT_MESSAGE) return null
        if (event.sig != null) return null
        if (!NdrInputPolicy.isPubkeyHex(event.pubkey)) return null
        if (!event.pubkey.equals(message.senderPubkeyHex, ignoreCase = true)) return null
        if (event.createdAt <= 0 || event.id.isBlank()) return null
        if (!event.id.equals(event.computeEventIdHex(), ignoreCase = true)) return null
        if (!message.eventId.equals(event.id, ignoreCase = true)) return null
        if (!NdrInputPolicy.hasBoundedTags(event)) return null
        if (!event.hasExactlyOneTag(PROTOCOL_TAG, PROTOCOL_VALUE)) return null
        if (!event.hasExactlyOneTag(VERSION_TAG, VERSION_VALUE)) return null
        val timestampMs = event.requiredMillisecondTimestamp() ?: return null
        val expiresAtSeconds = event.optionalExpirationSeconds() ?: run {
            if (event.tags.any { it.firstOrNull() == EXPIRATION_TAG }) return null
            null
        }
        val actionExpiresAtSeconds = message.expiresAtSeconds?.let {
            if (it > Long.MAX_VALUE.toULong()) return null
            it.toLong()
        }
        if (actionExpiresAtSeconds != expiresAtSeconds) return null

        return NdrApplicationMessage(
            content = event.content,
            timestampMs = timestampMs,
            expiresAtSeconds = expiresAtSeconds
        )
    }

    private fun NostrEvent.hasExactlyOneTag(name: String, value: String): Boolean {
        val matches = tags.filter { it.firstOrNull() == name }
        return matches.size == 1 &&
            matches.single().size == 2 &&
            matches.single()[1] == value
    }

    private fun NostrEvent.optionalExpirationSeconds(): Long? {
        val matches = tags.filter { it.firstOrNull() == EXPIRATION_TAG }
        if (matches.isEmpty()) return null
        if (matches.size != 1) return null
        val tag = matches.single()
        if (tag.size != 2 || !UNSIGNED_DECIMAL.matches(tag[1])) return null
        return tag[1]
            .toULongOrNull()
            ?.takeIf { it <= Long.MAX_VALUE.toULong() }
            ?.toLong()
    }

    private fun NostrEvent.requiredMillisecondTimestamp(): Long? {
        val matches = tags.filter { it.firstOrNull() == MILLISECOND_TIMESTAMP_TAG }
        if (matches.size != 1) return null
        val tag = matches.single()
        if (tag.size != 2 || !UNSIGNED_DECIMAL.matches(tag[1])) return null
        return tag[1]
            .toULongOrNull()
            ?.takeIf { it <= Long.MAX_VALUE.toULong() }
            ?.toLong()
    }
}
