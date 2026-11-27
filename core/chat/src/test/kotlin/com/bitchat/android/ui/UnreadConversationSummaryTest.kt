package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class UnreadConversationSummaryTest {
    @Test
    fun `unread conversations survive missing presence and sort by latest unread`() {
        val older = incoming(
            id = "older",
            sender = "alice",
            timestamp = 100
        )
        val newer = incoming(
            id = "newer",
            sender = "bob",
            timestamp = 200
        )

        val rows = buildUnreadConversationSummaries(
            unreadConversationIDs = setOf("alice-peer", "bob-peer"),
            privateChats = mapOf(
                "alice-peer" to listOf(older),
                "bob-peer" to listOf(newer)
            ),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { false }
        )

        assertEquals(listOf("bob-peer", "alice-peer"), rows.map { it.conversationID })
        assertEquals(listOf("bob", "alice"), rows.map { it.displayName })
    }

    @Test
    fun `canonical aliases produce one unread conversation row`() {
        val message = incoming(
            id = "message",
            sender = "alice",
            timestamp = 100
        )

        val rows = buildUnreadConversationSummaries(
            unreadConversationIDs = setOf("mesh-alias", "nostr_alias"),
            privateChats = mapOf(
                "mesh-alias" to listOf(message),
                "nostr_alias" to listOf(message)
            ),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { "contact_alice" },
            isMessageRead = { false }
        )

        assertEquals(1, rows.size)
        assertEquals("contact_alice", rows.single().conversationID)
        assertEquals(DirectMessageTransport.NOSTR, rows.single().transport)
        assertEquals(
            setOf("mesh-alias", "nostr_alias", "contact_alice"),
            rows.single().identityAliases
        )
    }

    @Test
    fun `only unseen incoming messages contribute to unread count`() {
        val read = incoming(
            id = "read",
            sender = "alice",
            timestamp = 100
        )
        val unread = incoming(
            id = "unread",
            sender = "alice",
            timestamp = 200
        )
        val outgoing = incoming(
            id = "outgoing",
            sender = "me",
            timestamp = 300
        )

        val row = buildUnreadConversationSummaries(
            unreadConversationIDs = setOf("alice-peer"),
            privateChats = mapOf("alice-peer" to listOf(read, unread, outgoing)),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { it.id == "read" }
        ).single()

        assertEquals(1, row.unreadCount)
        assertEquals(200, row.latestMessageAt)
    }

    @Test
    fun `unread key without hydrated messages still produces a row`() {
        val row = buildUnreadConversationSummaries(
            unreadConversationIDs = setOf("orphan-peer"),
            privateChats = emptyMap(),
            currentUserIdentifiers = setOf("me"),
            canonicalize = { it },
            isMessageRead = { false }
        ).single()

        assertEquals("orphan-peer", row.conversationID)
        assertEquals(1, row.unreadCount)
        assertTrue(row.displayName.isNotBlank())
    }

    @Test
    fun `canonical unread lookup returns every matching source alias`() {
        val aliases = matchingUnreadAliases(
            unreadConversationIDs = setOf("mesh-alias", "nostr_alias", "other-contact"),
            canonicalConversationID = "contact_alice",
            canonicalize = { unreadID ->
                if (unreadID == "other-contact") unreadID else "contact_alice"
            }
        )

        assertEquals(
            setOf("mesh-alias", "nostr_alias", "contact_alice"),
            aliases
        )
    }

    private fun incoming(
        id: String,
        sender: String,
        timestamp: Long
    ) = BitchatMessage(
        id = id,
        sender = sender,
        content = "hello",
        timestamp = Date(timestamp)
    )
}
