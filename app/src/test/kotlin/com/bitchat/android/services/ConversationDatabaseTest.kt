package com.bitchat.android.services

import android.content.ContentValues
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ConversationDatabaseTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var database: ConversationDatabase
    private lateinit var storageCipher: InMemoryConversationStorageCipher

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "conversation-test-${UUID.randomUUID()}.db"
        storageCipher = InMemoryConversationStorageCipher()
        database = ConversationDatabase(context, databaseName, storageCipher = storageCipher)
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

        database = ConversationDatabase(context, databaseName, storageCipher = storageCipher)
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
        assertEquals("alice", restored.displayNames.getValue("contact_alice"))
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
        val duplicate = database.upsertMessage(
            "contact_alice",
            setOf("mesh-alias", "nostr_alias"),
            null,
            first,
            false
        )

        val restored = database.loadSnapshot()
        assertFalse(duplicate.inserted)
        assertEquals(setOf("contact_alice"), restored.chats.keys)
        assertEquals(listOf("first", "second"), restored.chats.getValue("contact_alice").map { it.id })
        assertEquals("alice", restored.displayNames.getValue("contact_alice"))
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
        database = ConversationDatabase(context, databaseName, storageCipher = storageCipher)
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
            maxPayloadBytes = Long.MAX_VALUE,
            storageCipher = storageCipher
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

    @Test
    fun `initial snapshot decrypts only latest row while preserving exact unread count`() {
        repeat(3) { index ->
            database.upsertMessage(
                conversationID = "contact_alice",
                aliases = setOf("contact_alice"),
                displayName = "alice",
                message = message("summary-$index", "alice", index.toLong()),
                isRead = index == 2
            )
        }

        val initial = database.loadInitialSnapshot()

        assertEquals(listOf("summary-2"), initial.chats.getValue("contact_alice").map { it.id })
        assertEquals(2, initial.unreadCounts.getValue("contact_alice"))
        assertEquals(setOf("summary-2"), initial.readMessageIDs)
        assertEquals(3, database.loadConversation("contact_alice").arrivalOrder.size)
    }

    @Test
    fun `nickname metadata updates without requiring a new message`() {
        database.upsertMessage(
            conversationID = "mesh-alias",
            aliases = setOf("mesh-alias"),
            displayName = "Alice Old",
            message = message("rename-message", "Alice Old", 1L),
            isRead = true
        )

        database.updateConversationIdentity(
            conversationID = "contact_alice",
            aliases = setOf("mesh-alias", "contact_alice"),
            displayName = "Alice New"
        )
        database.close()
        database = ConversationDatabase(context, databaseName, storageCipher = storageCipher)

        val restored = database.loadInitialSnapshot()

        assertEquals(setOf("contact_alice"), restored.chats.keys)
        assertEquals("Alice New", restored.displayNames.getValue("contact_alice"))
        assertEquals("Alice Old", restored.chats.getValue("contact_alice").single().sender)
    }

    @Test
    fun `sensitive message and display fields are encrypted and legacy columns are scrubbed`() {
        val secret = "unique-secret-${UUID.randomUUID()}"
        database.upsertMessage(
            conversationID = "contact_alice",
            aliases = setOf("contact_alice"),
            displayName = "display-$secret",
            message = BitchatMessage(
                id = "encrypted-row",
                sender = "sender-$secret",
                content = "content-$secret",
                timestamp = Date(42L),
                isPrivate = true,
                recipientNickname = "recipient-$secret"
            ),
            isRead = false
        )

        database.readableDatabase.query(
            "private_messages",
            arrayOf("sender", "content", "recipient_nickname", "payload_ciphertext"),
            "message_id = ?",
            arrayOf("encrypted-row"),
            null,
            null,
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertNull(cursor.getString(2))
            val envelope = cursor.getBlob(3)
            assertFalse(envelope.toString(Charsets.ISO_8859_1).contains(secret))
            val plaintext = storageCipher.decrypt(
                envelope,
                "message:encrypted-row".toByteArray()
            ).toString(Charsets.UTF_8)
            assertTrue(plaintext.contains(secret))
        }
        database.readableDatabase.query(
            "conversations",
            arrayOf("display_name", "display_name_ciphertext"),
            "conversation_id = ?",
            arrayOf("contact_alice"),
            null,
            null,
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNull(cursor.getString(0))
            assertNotNull(cursor.getBlob(1))
        }
    }

    @Test
    fun `media byte retention reports only orphaned app-owned attachments`() {
        database.close()
        context.deleteDatabase(databaseName)
        database = ConversationDatabase(
            context = context,
            databaseName = databaseName,
            maxMessagesPerConversation = 10,
            maxMessagesTotal = 10,
            maxPayloadBytes = Long.MAX_VALUE,
            maxMediaBytes = 4,
            storageCipher = storageCipher
        )
        val first = File(context.filesDir, "conversation-first-${UUID.randomUUID()}.bin")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val second = File(context.filesDir, "conversation-second-${UUID.randomUUID()}.bin")
            .apply { writeBytes(byteArrayOf(5, 6, 7, 8)) }
        try {
            database.upsertMessage(
                "contact_alice",
                setOf("contact_alice"),
                "alice",
                message("media-1", "alice", 1L).copy(
                    type = BitchatMessageType.File,
                    content = first.absolutePath
                ),
                true
            )
            database.upsertMessage(
                "contact_alice",
                setOf("contact_alice"),
                "alice",
                message("media-2", "alice", 2L).copy(
                    type = BitchatMessageType.File,
                    content = second.absolutePath
                ),
                true
            )

            val orphaned = database.pruneToRetentionLimits()

            assertEquals(setOf(first.canonicalPath), orphaned)
            assertEquals(listOf("media-2"), database.loadSnapshot().arrivalOrder)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun `shared attachment path is counted once and retained while referenced`() {
        database.close()
        context.deleteDatabase(databaseName)
        database = ConversationDatabase(
            context = context,
            databaseName = databaseName,
            maxMessagesPerConversation = 10,
            maxMessagesTotal = 10,
            maxPayloadBytes = Long.MAX_VALUE,
            maxMediaBytes = 4,
            storageCipher = storageCipher
        )
        val shared = File(context.filesDir, "conversation-shared-${UUID.randomUUID()}.bin")
            .apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        try {
            repeat(2) { index ->
                database.upsertMessage(
                    "contact_alice",
                    setOf("contact_alice"),
                    "alice",
                    message("shared-$index", "alice", index.toLong()).copy(
                        type = BitchatMessageType.File,
                        content = shared.absolutePath
                    ),
                    true
                )
            }

            assertTrue(database.pruneToRetentionLimits().isEmpty())
            assertEquals(2, database.loadSnapshot().arrivalOrder.size)
        } finally {
            shared.delete()
        }
    }

    @Test
    fun `panic clear rotates the storage key before erasing rows`() {
        database.upsertMessage(
            "contact_alice",
            setOf("contact_alice"),
            "alice",
            message("before-panic", "alice", 1L),
            true
        )
        val envelope = database.readableDatabase.query(
            "private_messages",
            arrayOf("payload_ciphertext"),
            "message_id = ?",
            arrayOf("before-panic"),
            null,
            null,
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getBlob(0)
        }

        database.clearAll()

        assertTrue(database.loadSnapshot().chats.isEmpty())
        assertThrows(Exception::class.java) {
            storageCipher.decrypt(envelope, "message:before-panic".toByteArray())
        }
    }

    @Test
    fun `version one plaintext database migrates without losing history`() {
        database.close()
        context.deleteDatabase(databaseName)
        createVersionOneDatabase(
            conversationID = "legacy-contact",
            messageID = "legacy-message",
            sender = "legacy-alice",
            content = "legacy-content"
        )

        database = ConversationDatabase(context, databaseName, storageCipher = storageCipher)
        val restored = database.loadSnapshot()

        assertEquals("legacy-content", restored.chats.getValue("legacy-contact").single().content)
        database.readableDatabase.query(
            "private_messages",
            arrayOf("sender", "content", "payload_ciphertext", "received_at"),
            "message_id = ?",
            arrayOf("legacy-message"),
            null,
            null,
            null
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("", cursor.getString(0))
            assertEquals("", cursor.getString(1))
            assertNotNull(cursor.getBlob(2))
            assertEquals(123L, cursor.getLong(3))
        }
    }

    private fun createVersionOneDatabase(
        conversationID: String,
        messageID: String,
        sender: String,
        content: String
    ) {
        context.openOrCreateDatabase(databaseName, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                """
                CREATE TABLE conversations (
                    conversation_id TEXT COLLATE NOCASE PRIMARY KEY NOT NULL,
                    display_name TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE conversation_aliases (
                    alias TEXT COLLATE NOCASE PRIMARY KEY NOT NULL,
                    conversation_id TEXT COLLATE NOCASE NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL(
                """
                CREATE TABLE private_messages (
                    arrival_sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                    message_id TEXT UNIQUE NOT NULL,
                    conversation_id TEXT COLLATE NOCASE NOT NULL,
                    sender TEXT NOT NULL,
                    content TEXT NOT NULL,
                    message_type INTEGER NOT NULL,
                    sent_at INTEGER NOT NULL,
                    is_relay INTEGER NOT NULL,
                    original_sender TEXT,
                    is_private INTEGER NOT NULL,
                    recipient_nickname TEXT,
                    sender_peer_id TEXT,
                    mentions_json TEXT,
                    channel_name TEXT,
                    encrypted_content BLOB,
                    is_encrypted INTEGER NOT NULL,
                    delivery_type INTEGER NOT NULL,
                    delivery_text TEXT,
                    delivery_at INTEGER,
                    delivery_reached INTEGER,
                    delivery_total INTEGER,
                    sender_nostr_pubkey TEXT,
                    is_read INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                "CREATE TABLE deleted_private_messages (" +
                    "message_id TEXT PRIMARY KEY NOT NULL, deleted_at INTEGER NOT NULL)"
            )
            db.insertOrThrow(
                "conversations",
                null,
                ContentValues().apply {
                    put("conversation_id", conversationID)
                    put("display_name", sender)
                    put("created_at", 1L)
                    put("updated_at", 1L)
                }
            )
            db.insertOrThrow(
                "conversation_aliases",
                null,
                ContentValues().apply {
                    put("alias", conversationID)
                    put("conversation_id", conversationID)
                }
            )
            db.insertOrThrow(
                "private_messages",
                null,
                ContentValues().apply {
                    put("message_id", messageID)
                    put("conversation_id", conversationID)
                    put("sender", sender)
                    put("content", content)
                    put("message_type", BitchatMessageType.Message.ordinal)
                    put("sent_at", 123L)
                    put("is_relay", 0)
                    put("is_private", 1)
                    put("is_encrypted", 0)
                    put("delivery_type", 2)
                    put("is_read", 0)
                }
            )
            db.version = 1
        }
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
