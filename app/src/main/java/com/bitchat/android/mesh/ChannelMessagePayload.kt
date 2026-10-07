package com.bitchat.android.mesh

import com.bitchat.android.model.BitchatMessage
import java.util.Date

/** Encode channel routing with content; an encoding failure must not publish public text. */
internal fun encodePublicOrChannelMessage(
    content: String,
    mentions: List<String>,
    channel: String?,
    nickname: String,
    peerID: String
): ByteArray? {
    if (channel == null) return content.toByteArray(Charsets.UTF_8)
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
