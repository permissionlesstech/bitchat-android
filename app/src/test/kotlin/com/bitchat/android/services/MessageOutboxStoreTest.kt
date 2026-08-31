package com.bitchat.android.services

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageOutboxStoreTest {
    @Test
    fun `sealed outbox round trips and wrong key fails closed`() {
        val context = RuntimeEnvironment.getApplication()
        val file = File(context.filesDir, "message-outbox.sealed")
        file.delete()
        val store = MessageOutboxStore(context, TestCipher(0x33))
        val entry = MessageOutboxStore.Entry("secret", "peer", "message", 100)
        entry.depositedCourierKeys += "courier"
        store.save(mapOf("conversation" to listOf(entry)))

        assertFalse(file.readBytes().toString(Charsets.UTF_8).contains("secret"))
        assertEquals("secret", MessageOutboxStore(context, TestCipher(0x33)).load()["conversation"]?.single()?.content)
        assertTrue(MessageOutboxStore(context, TestCipher(0x44)).load().isEmpty())
    }

    @Test
    fun `wipe removes file and destroys key`() {
        val context = RuntimeEnvironment.getApplication()
        val cipher = TestCipher(0x33)
        val store = MessageOutboxStore(context, cipher)
        store.save(mapOf("conversation" to listOf(MessageOutboxStore.Entry("secret", "peer", "message", 100))))
        store.wipe()

        assertFalse(File(context.filesDir, "message-outbox.sealed").exists())
        assertTrue(cipher.destroyed)
    }

    private class TestCipher(private val mask: Int) : ConversationStorageCipher {
        var destroyed = false
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = byteArrayOf(mask.toByte()) +
            plaintext.map { (it.toInt() xor mask).toByte() }.toByteArray()
        override fun decrypt(envelope: ByteArray, associatedData: ByteArray): ByteArray {
            require(envelope.firstOrNull() == mask.toByte())
            return envelope.drop(1).map { (it.toInt() xor mask).toByte() }.toByteArray()
        }
        override fun destroyKey() { destroyed = true }
    }
}
