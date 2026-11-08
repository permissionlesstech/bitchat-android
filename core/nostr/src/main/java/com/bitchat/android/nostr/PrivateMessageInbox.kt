package com.bitchat.android.nostr

import com.bitchat.android.model.BitchatMessage

/**
 * Where Nostr-delivered private messages land. The chat session implements it.
 *
 * A port rather than a stream of events, because the handler acknowledges a
 * message only once it has been kept: [admit] returns whether it was.
 */
interface PrivateMessageInbox {
    /** This device's nickname, recorded as the recipient. */
    val myNickname: String

    /** The conversation on screen, if any; a message to it is read on arrival. */
    val selectedConversationID: String?

    /** Whether [messageID] is already in [conversationID]'s history. */
    fun contains(conversationID: String, messageID: String): Boolean

    /**
     * Persists [message]. True means admitted, and only then may a delivery
     * ack be sent.
     */
    suspend fun admit(message: BitchatMessage, suppressUnread: Boolean): Boolean
}
