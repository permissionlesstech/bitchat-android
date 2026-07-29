package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.PrivateMessageArrivalOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Date

class ConversationSummaryTest {
    @After
    fun tearDown() {
        PrivateMessageArrivalOrder.clear()
    }

    @Test
    fun `read offline conversation remains present`() {
        val message = incoming("message", "alice", 100L)
        PrivateMessageArrivalOrder.record(message.id)

        val conversations = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf("alice-peer" to listOf(message)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true }
        )

        assertEquals(1, conversations.size)
        assertEquals("alice-peer", conversations.single().conversationID)
        assertEquals(0, conversations.single().unreadCount)
        assertFalse(conversations.single().isConnected)
    }

    @Test
    fun `canonical aliases yield one conversation with unique messages`() {
        val first = incoming("first", "alice", 300L)
        val second = incoming("second", "alice", 100L)
        PrivateMessageArrivalOrder.record(first.id)
        PrivateMessageArrivalOrder.record(second.id)

        val conversations = buildConversationSummaries(
            unreadConversationIDs = setOf("mesh-alias"),
            privateChats = mapOf(
                "mesh-alias" to listOf(first),
                "nostr_alias" to listOf(first, second)
            ),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { "contact_alice" },
            isMessageRead = { false }
        )

        assertEquals(1, conversations.size)
        assertEquals(2, conversations.single().unreadCount)
        assertEquals(
            setOf("mesh-alias", "nostr_alias", "contact_alice"),
            conversations.single().identityAliases
        )
        assertEquals("alice", conversations.single().displayName)
        assertEquals(DirectMessageTransport.NOSTR, conversations.single().transport)
    }

    @Test
    fun `online conversations sort before newer offline conversations`() {
        val offline = summary(
            id = "offline",
            isConnected = false,
            unreadCount = 3,
            activity = 300L
        )
        val onlineRead = summary(
            id = "online-read",
            isConnected = true,
            unreadCount = 0,
            activity = 100L
        )
        val onlineUnread = summary(
            id = "online-unread",
            isConnected = true,
            unreadCount = 1,
            activity = 50L
        )

        assertEquals(
            listOf("online-unread", "online-read", "offline"),
            sortConversationSummaries(listOf(offline, onlineRead, onlineUnread))
                .map { it.conversationID }
        )
    }

    @Test
    fun `preview follows local arrival order instead of remote timestamp`() {
        val first = incoming(
            id = "first",
            sender = "alice",
            timestamp = 900L,
            content = "older arrival"
        )
        val second = incoming(
            id = "second",
            sender = "alice",
            timestamp = 100L,
            content = "  latest\nmessage  "
        )
        PrivateMessageArrivalOrder.record(first.id)
        PrivateMessageArrivalOrder.record(second.id)

        val conversation = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf("alice-peer" to listOf(first, second)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true }
        ).single()

        assertEquals("latest message", conversation.latestMessagePreview)
        assertEquals(BitchatMessageType.Message, conversation.latestMessageType)
    }

    @Test
    fun `file preview contains filename without local path`() {
        val message = incoming(
            id = "file",
            sender = "alice",
            timestamp = 100L,
            content = "/private/conversations/quarterly report.pdf",
            type = BitchatMessageType.File
        )

        val conversation = buildConversationSummaries(
            unreadConversationIDs = emptySet(),
            privateChats = mapOf("alice-peer" to listOf(message)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { true }
        ).single()

        assertEquals("quarterly report.pdf", conversation.latestMessagePreview)
        assertEquals(BitchatMessageType.File, conversation.latestMessageType)
    }

    private fun incoming(
        id: String,
        sender: String,
        timestamp: Long,
        content: String = "hello",
        type: BitchatMessageType = BitchatMessageType.Message
    ) = BitchatMessage(
        id = id,
        sender = sender,
        content = content,
        type = type,
        timestamp = Date(timestamp),
        isPrivate = true
    )

    private fun summary(
        id: String,
        isConnected: Boolean,
        unreadCount: Int,
        activity: Long
    ) = ConversationSummary(
        conversationID = id,
        displayName = id,
        unreadCount = unreadCount,
        latestMessageAt = activity,
        latestActivityOrder = activity,
        latestMessageType = BitchatMessageType.Message,
        latestMessagePreview = "hello",
        transport = DirectMessageTransport.MESH,
        nostrPubkey = null,
        identityAliases = setOf(id),
        isConnected = isConnected
    )
}
