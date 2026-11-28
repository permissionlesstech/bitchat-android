package com.bitchat.android.model

import java.util.Date

/**
 * Public mesh `MESSAGE` payloads stay raw UTF-8. A channel payload is the
 * canonical `BitchatMessage` binary (flag bit `0x40` set, channel present)
 * and nothing else. A payload that is not that canonical form is public text.
 *
 * The channel field is one length byte, so a name longer than 255 UTF-8 bytes
 * is not representable. Encoding fails closed instead of truncating the name
 * or falling back to raw public text when the 4096-byte serializer buffer
 * cannot hold the envelope. Receivers key the visible message by the outer
 * packet id, not the id inside this payload.
 */
object ChannelMessageWire {
    const val MAX_CHANNEL_UTF8_BYTES = 255

    fun channelNameSupported(channel: String): Boolean =
        channel.toByteArray(Charsets.UTF_8).size <= MAX_CHANNEL_UTF8_BYTES

    fun encode(
        content: String,
        mentions: List<String>,
        channel: String?,
        sender: String,
        senderPeerID: String,
        messageId: String,
        timestampMillis: Long
    ): ByteArray? {
        if (channel.isNullOrEmpty()) {
            return content.toByteArray(Charsets.UTF_8)
        }
        if (!channelNameSupported(channel)) return null
        val message = BitchatMessage(
            id = messageId,
            sender = sender,
            content = content,
            timestamp = Date(timestampMillis),
            senderPeerID = senderPeerID,
            mentions = mentions.takeIf { it.isNotEmpty() },
            channel = channel
        )
        return message.toBinaryPayload()
    }

    /**
     * Returns a channel message only when the payload is a canonical binary
     * message with a channel. Plain public text must not match.
     */
    fun decode(payload: ByteArray): BitchatMessage? {
        val parsed = BitchatMessage.fromBinaryPayload(payload) ?: return null
        val channel = parsed.channel ?: return null
        if (!channel.startsWith("#") || channel.length < 2) return null
        if (parsed.sender.isEmpty() || parsed.id.isEmpty()) return null
        val canonical = parsed.toBinaryPayload() ?: return null
        if (!canonical.contentEquals(payload)) return null
        return parsed
    }
}
