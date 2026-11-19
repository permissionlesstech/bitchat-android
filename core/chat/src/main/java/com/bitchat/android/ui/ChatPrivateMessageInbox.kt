package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.nostr.PrivateMessageInbox

/** A chat session's private conversations, as the Nostr layer delivers into them. */
class ChatPrivateMessageInbox(
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
) : PrivateMessageInbox {

    override val myNickname: String
        get() = state.getNicknameValue()

    override val selectedConversationID: String?
        get() = state.getSelectedPrivateChatPeerValue()

    override fun contains(conversationID: String, messageID: String): Boolean =
        state.getPrivateChatsValue()[conversationID].orEmpty().any { it.id == messageID }

    override suspend fun admit(message: BitchatMessage, suppressUnread: Boolean): Boolean =
        privateChatManager.handleIncomingPrivateMessageDurably(
            message = message,
            suppressUnread = suppressUnread,
            origin = PrivateMessageOrigin.NOSTR,
        )
}
