package com.bitchat.android.services

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PrivateMediaOutboxStoreTest {
    private val id = "media-00112233445566778899aabbccddeeff"

    @Test
    fun `restart retains original payload and attempt count and wipe removes it`() {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "private-media-outbox").deleteRecursively()
        val cipher = InMemoryConversationStorageCipher()
        val store = PrivateMediaOutboxStore(context, cipher)
        val entry = PrivateMediaOutboxStore.Entry(id, "conversation", "0011223344556677",
            "synthetic-payload", 100, attempts = 4, lastAttemptAt = 500)
        store.save(entry)
        assertEquals(listOf(entry), PrivateMediaOutboxStore(context, cipher).load())
        assertFalse(File(context.filesDir, "private-media-outbox/$id").readText().contains("synthetic-payload"))
        store.wipe()
        assertTrue(PrivateMediaOutboxStore(context, cipher).load().isEmpty())
    }

    @Test
    fun `corrupt record is never retried or silently overwritten on load`() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(context.filesDir, "private-media-outbox").apply { mkdirs() }
        val file = File(directory, id).apply { writeText("corrupt") }
        assertTrue(PrivateMediaOutboxStore(context, InMemoryConversationStorageCipher()).load().isEmpty())
        assertTrue(file.exists())
        file.delete()
    }
}
