package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class ConversationRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private lateinit var repository: ConversationRepository
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "conversation-repository-test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        if (::repository.isInitialized) repository.closeForTest()
        dispatcher.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun `reload restores persisted history after initial process restore`() {
        repository = ConversationRepository(
            context = context,
            dispatcher = dispatcher,
            databaseName = databaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        val message = BitchatMessage(
            id = "persisted-message",
            sender = "alice",
            content = "survives restart",
            timestamp = Date(100L),
            isPrivate = true
        )
        repository.upsertMessage(
            conversationID = "peer-alice",
            aliases = setOf("peer-alice"),
            displayName = "alice",
            message = message,
            isRead = true
        )
        runBlocking { repository.awaitPendingWrites() }

        val initialSnapshot = AtomicReference<PersistedConversationSnapshot>()
        repository.initialize(initialSnapshot::set)
        runBlocking { repository.awaitPendingWrites() }
        assertEquals(
            listOf(message),
            initialSnapshot.get().chats.getValue("peer-alice")
        )

        val reloadedSnapshot = AtomicReference<PersistedConversationSnapshot>()
        repository.reload(reloadedSnapshot::set)
        runBlocking { repository.awaitPendingWrites() }
        assertEquals(
            listOf(message),
            reloadedSnapshot.get().chats.getValue("peer-alice")
        )
    }

    @Test
    fun `panic clear drains queued writes and leaves database empty`() {
        repository = ConversationRepository(
            context = context,
            dispatcher = dispatcher,
            databaseName = databaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        repository.upsertMessage(
            conversationID = "peer-alice",
            aliases = setOf("peer-alice"),
            displayName = "alice",
            message = BitchatMessage(
                id = "queued-before-panic",
                sender = "alice",
                content = "must be erased",
                timestamp = Date(100L),
                isPrivate = true
            ),
            isRead = true
        )

        assertTrue(runBlocking { repository.clearAllAndWait() })

        val snapshot = AtomicReference<PersistedConversationSnapshot>()
        repository.reload(snapshot::set)
        runBlocking { repository.awaitPendingWrites() }
        assertTrue(snapshot.get().chats.isEmpty())
        assertTrue(snapshot.get().readMessageIDs.isEmpty())
        assertTrue(snapshot.get().deletedMessageIDs.isEmpty())
    }
}
