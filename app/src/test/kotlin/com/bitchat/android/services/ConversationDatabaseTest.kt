package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class ConversationDatabaseTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ConversationDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "conversation-test-${UUID.randomUUID()}.db"
        database = ConversationDatabase(context, databaseName)
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `message fields read state and delivery status survive reopen`() {
        val message = BitchatMessage(
            id = "round-trip",
            sender = "alice",
            content = "photo path",
            type = BitchatMessageType.Image,
            timestamp = Date(123_456L),
            isRelay = true,
            originalSender = "alice-original",
            isPrivate = true,
            recipientNickname = "me",
            senderPeerID = "0123456789abcdef",
            mentions = listOf("me", "bob"),
            channel = "private",
            encryptedContent = byteArrayOf(1, 2, 3),
            isEncrypted = true,
            deliveryStatus = DeliveryStatus.Delivered("me", Date(123_999L)),
            senderNostrPubkey = "a".repeat(64)
        )

        database.upsertMessage(
            conversationID = "contact_alice",
            aliases = setOf("contact_alice", "0123456789abcdef"),
            displayName = "alice",
            message = message,
            isRead = false
        )
        database.markRead(message.id)
        database.updateDeliveryStatus(
            message.id,
            DeliveryStatus.Read("me", Date(124_000L))
        )
        database.close()

        database = ConversationDatabase(context, databaseName)
        val restored = database.loadSnapshot()
        val restoredMessage = restored.chats.getValue("contact_alice").single()

        assertEquals(message.id, restoredMessage.id)
        assertEquals(message.sender, restoredMessage.sender)
        assertEquals(message.content, restoredMessage.content)
        assertEquals(message.type, restoredMessage.type)
        assertEquals(message.timestamp, restoredMessage.timestamp)
        assertEquals(message.mentions, restoredMessage.mentions)
        assertEquals(message.senderNostrPubkey, restoredMessage.senderNostrPubkey)
        assertArrayEquals(message.encryptedContent, restoredMessage.encryptedContent)
        assertEquals(
            DeliveryStatus.Read("me", Date(124_000L)),
            restoredMessage.deliveryStatus
        )
        assertEquals(setOf(message.id), restored.readMessageIDs)
        assertEquals(listOf(message.id), restored.arrivalOrder)
    }

    @Test
    fun `aliases merge into one conversation and duplicate transport delivery stays unique`() {
        val first = message("first", "alice", 100L)
        val second = message("second", "alice", 200L)

        database.upsertMessage("mesh-alias", setOf("mesh-alias"), "alice", first, false)
        database.upsertMessage("nostr_alias", setOf("nostr_alias"), "alice", second, false)
        database.mergeAliases(
            targetConversationID = "contact_alice",
            aliases = setOf("mesh-alias", "nostr_alias", "contact_alice")
        )
        database.upsertMessage(
            "contact_alice",
            setOf("mesh-alias", "nostr_alias"),
            "alice",
            first,
            false
        )

        val restored = database.loadSnapshot()
        assertEquals(setOf("contact_alice"), restored.chats.keys)
        assertEquals(listOf("first", "second"), restored.chats.getValue("contact_alice").map { it.id })
    }

    @Test
    fun `conversation deletion cascades messages while a later message starts fresh`() {
        database.upsertMessage(
            "contact_alice",
            setOf("mesh-alias", "contact_alice"),
            "alice",
            message("old", "alice", 100L),
            false
        )

        database.deleteConversation(
            "contact_alice",
            setOf("mesh-alias", "contact_alice")
        )
        assertTrue(database.loadSnapshot().chats.isEmpty())

        database.close()
        database = ConversationDatabase(context, databaseName)
        database.upsertMessage(
            "contact_alice",
            setOf("mesh-alias", "contact_alice"),
            "alice",
            message("old", "alice", 100L),
            false
        )
        assertTrue(database.loadSnapshot().chats.isEmpty())

        database.upsertMessage(
            "contact_alice",
            setOf("mesh-alias", "contact_alice"),
            "alice",
            message("new", "alice", 200L),
            false
        )
        val restored = database.loadSnapshot()
        assertEquals(listOf("new"), restored.chats.getValue("contact_alice").map { it.id })
        assertFalse(restored.readMessageIDs.contains("old"))
        assertTrue(restored.deletedMessageIDs.contains("old"))
    }

    @Test
    fun `per conversation retention keeps the newest bounded history`() {
        val total = ConversationDatabase.MAX_MESSAGES_PER_CONVERSATION + 5
        repeat(total) { index ->
            database.upsertMessage(
                conversationID = "contact_alice",
                aliases = setOf("contact_alice"),
                displayName = "alice",
                message = message("message-$index", "alice", index.toLong()),
                isRead = true
            )
        }

        val snapshot = database.loadSnapshot()
        val messages = snapshot.chats.getValue("contact_alice")
        assertEquals(ConversationDatabase.MAX_MESSAGES_PER_CONVERSATION, messages.size)
        assertEquals("message-5", messages.first().id)
        assertEquals("message-${total - 1}", messages.last().id)
        assertEquals(
            (0 until 5).mapTo(mutableSetOf()) { "message-$it" },
            snapshot.deletedMessageIDs
        )
    }

    @Test
    fun `global retention is hard bounded when every conversation has one message`() {
        database.close()
        context.deleteDatabase(databaseName)
        database = ConversationDatabase(
            context = context,
            databaseName = databaseName,
            maxMessagesPerConversation = 10,
            maxMessagesTotal = 3,
            maxPayloadBytes = Long.MAX_VALUE
        )
        repeat(4) { index ->
            database.upsertMessage(
                conversationID = "peer-$index",
                aliases = setOf("peer-$index"),
                displayName = "peer $index",
                message = message("single-$index", "peer $index", index.toLong()),
                isRead = true
            )
        }

        database.pruneToRetentionLimits()

        val snapshot = database.loadSnapshot()
        assertEquals(3, snapshot.chats.values.sumOf { it.size })
        assertFalse(snapshot.chats.containsKey("peer-0"))
        assertTrue(snapshot.deletedMessageIDs.contains("single-0"))
    }

    private fun message(id: String, sender: String, timestamp: Long) = BitchatMessage(
        id = id,
        sender = sender,
        content = "hello-$id",
        timestamp = Date(timestamp),
        isPrivate = true,
        senderPeerID = "0123456789abcdef"
    )
}
