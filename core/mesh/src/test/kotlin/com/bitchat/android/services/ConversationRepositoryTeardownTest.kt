package com.bitchat.android.services

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.identity.scope.IdentityTeardown
import com.bitchat.android.model.BitchatMessage
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
class ConversationRepositoryTeardownTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private val executor = Executors.newSingleThreadExecutor()
    private val dispatcher = executor.asCoroutineDispatcher()
    // One key for the file, as the Keystore gives every repository that opens it.
    private val cipher = InMemoryConversationStorageCipher()
    private var clock = 0L
    private val repositories = mutableListOf<ConversationRepository>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "conversation-repository-teardown-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        repositories.forEach(ConversationRepository::close)
        dispatcher.close()
        context.deleteDatabase(databaseName)
    }

    private fun open(teardown: IdentityTeardown? = null): ConversationRepository =
        ConversationRepository(
            context = context,
            dispatcher = dispatcher,
            databaseName = databaseName,
            storageCipher = cipher,
        ).also {
            repositories += it
            teardown?.register("ConversationRepository", it::close)
        }

    private fun message(id: String) = BitchatMessage(
        id = id,
        sender = "alice",
        content = "synthetic $id",
        timestamp = Date(1_700_000_000_000L + clock++),
        isPrivate = true,
    )

    private fun ConversationRepository.write(id: String, peer: String = "peer-alice"): Boolean = runBlocking {
        upsertMessageAndWait(peer, setOf(peer), peer, message(id), isRead = true)
    }

    @Test
    fun `closing the repository closes its helper and refuses later writes`() {
        val teardown = IdentityTeardown()
        val repository = open(teardown)
        assertTrue(repository.write("before"))

        assertEquals(emptyList<String>(), teardown.runAll())

        assertFalse(repository.write("after"))
        assertTrue(repository.storeState.value is ConversationStoreState.Error)
        // Queued work after the end is dropped, not run against a reopened file.
        repository.upsertMessage("peer-alice", setOf("peer-alice"), "alice", message("queued"), isRead = true)
        runBlocking { repository.awaitPendingWrites() }
        repository.close() // closing twice is safe
    }

    @Test
    fun `a new repository opens the same file after the old one closed`() {
        val old = open()
        assertTrue(old.write("kept", "peer-alice"))
        old.close()

        val replacement = open()
                assertTrue(replacement.write("next", "peer-bob"))

        val snapshot = AtomicReference<PersistedConversationSnapshot>()
        replacement.reload(snapshot::set)
        runBlocking { replacement.awaitPendingWrites() }
        val chats = snapshot.get().chats
        assertEquals(listOf("kept"), chats.getValue("peer-alice").map { it.id })
        assertEquals(listOf("next"), chats.getValue("peer-bob").map { it.id })
    }
}
