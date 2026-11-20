package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage
import java.util.Date

/** Public mesh text stays on MESSAGE. Channel envelopes use a type legacy peers ignore. */
internal fun messageTypeForPublicOrChannel(channel: String?): com.bitchat.android.protocol.MessageType =
    if (channel == null) com.bitchat.android.protocol.MessageType.MESSAGE
    else com.bitchat.android.protocol.MessageType.CHANNEL_MESSAGE

/** Encode channel routing with content; an encoding failure must not publish public text. */
internal fun encodePublicOrChannelMessage(
    content: String,
    mentions: List<String>,
    channel: String?,
    nickname: String,
    peerID: String
): ByteArray? {
    if (channel == null) return content.toByteArray(Charsets.UTF_8)
    // The wire length is one byte. Never silently truncate a channel into another one.
    if (channel.toByteArray(Charsets.UTF_8).size !in 1..255) return null
    return BitchatMessage(
        sender = nickname,
        content = content,
        timestamp = Date(),
        isRelay = false,
        senderPeerID = peerID,
        mentions = mentions.takeIf { it.isNotEmpty() },
        channel = channel
    ).toBinaryPayload()
}
