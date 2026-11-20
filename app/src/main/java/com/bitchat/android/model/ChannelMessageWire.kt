package com.bitchat.android.model

import java.util.Date

/**
 * Public mesh messages stay raw UTF-8. A channel message has to carry its
 * channel or every peer files it on the public timeline, which is invisible
 * while that peer is sitting in the channel.
 */
object ChannelMessageWire {
    fun encode(
        content: String,
        mentions: List<String>,
        channel: String?,
        sender: String,
        senderPeerID: String,
        messageId: String,
        timestampMillis: Long
    ): ByteArray {
        if (channel.isNullOrEmpty()) {
            return content.toByteArray(Charsets.UTF_8)
        }
        val message = BitchatMessage(
            id = messageId,
            sender = sender,
            content = content,
            timestamp = Date(timestampMillis),
            senderPeerID = senderPeerID,
            mentions = mentions.takeIf { it.isNotEmpty() },
            channel = channel
        )
        return message.toBinaryPayload() ?: content.toByteArray(Charsets.UTF_8)
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
