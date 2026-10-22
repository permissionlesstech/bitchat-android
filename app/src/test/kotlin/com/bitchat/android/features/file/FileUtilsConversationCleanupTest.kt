package com.bitchat.android.features.file

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.Date
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class FileUtilsConversationCleanupTest {

    @Test
    fun `deleting conversation removes only unreferenced app-owned media`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val testDirectory = File(
            context.cacheDir,
            "conversation-cleanup-${UUID.randomUUID()}"
        ).apply { mkdirs() }
        val deletedOnly = File(testDirectory, "deleted.jpg").apply {
            writeBytes(byteArrayOf(1))
        }
        val shared = File(testDirectory, "shared.jpg").apply {
            writeBytes(byteArrayOf(2))
        }
        val outsideAppStorage = File.createTempFile(
            "conversation-cleanup-",
            ".jpg"
        ).apply {
            writeBytes(byteArrayOf(3))
        }

        try {
            FileUtils.deleteConversationMedia(
                context = context,
                deletedMessages = listOf(
                    mediaMessage("deleted", deletedOnly),
                    mediaMessage("shared-deleted", shared),
                    mediaMessage("outside", outsideAppStorage)
                ),
                retainedMessages = listOf(mediaMessage("shared-retained", shared))
            )

            assertFalse(deletedOnly.exists())
            assertTrue(shared.exists())
            assertTrue(outsideAppStorage.exists())
        } finally {
            testDirectory.deleteRecursively()
            outsideAppStorage.delete()
        }
    }

    private fun mediaMessage(id: String, file: File) = BitchatMessage(
        id = id,
        sender = "alice",
        content = file.absolutePath,
        type = BitchatMessageType.Image,
        timestamp = Date(1L),
        isPrivate = true
    )
}
