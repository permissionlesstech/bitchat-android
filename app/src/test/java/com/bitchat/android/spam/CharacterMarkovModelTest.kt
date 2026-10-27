package com.bitchat.android.spam

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CharacterMarkovModelTest {
    private fun bytes(threshold: Float = 2f, secondPoint: Int = 'a'.code, lastPair: Int = 2): ByteArray =
        ByteArrayOutputStream().also { output ->
            DataOutputStream(output).use {
                it.writeInt(0x42434d31)
                it.writeShort(2)
                it.writeFloat(threshold)
                it.writeShort(40960)
                it.writeShort(4096)
                it.writeInt(2)
                listOf(' '.code, secondPoint).forEach { point ->
                    it.writeInt(point)
                    it.writeShort(4096)
                    it.writeShort(0)
                }
                it.writeInt(1)
                it.writeShort(8192)
                it.writeInt(lastPair)
                it.writeShort(4096)
            }
        }.toByteArray()

    private fun digest(raw: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(raw)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun compressed(raw: ByteArray): String {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(raw) }
        return Base64.getEncoder().encodeToString(output.toByteArray())
    }

    @Test
    fun `observed transitions backoff unknown characters and single letters have explicit costs`() {
        val raw = bytes()
        val model = CharacterMarkovModel.decode(digest(raw), compressed(raw))
        assertEquals(2.0, model.threshold, 0.0)
        assertEquals(2.0, model.score(intArrayOf('a'.code)), 0.0)
        assertEquals(1.0, model.score(intArrayOf('a'.code, 'a'.code)), 0.0)
        assertEquals(10.0, model.score(intArrayOf('x'.code)), 0.0)
    }

    @Test
    fun `tampered truncated and structurally invalid packs are rejected`() {
        val raw = bytes()
        assertThrows(IllegalArgumentException::class.java) {
            CharacterMarkovModel.decode("00".repeat(32), compressed(raw))
        }
        listOf(bytes(Float.NaN), bytes(secondPoint = ' '.code), bytes(lastPair = 1), raw + byteArrayOf(0),
            raw.copyOf(raw.size - 1)).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) {
                CharacterMarkovModel.decode(digest(invalid), compressed(invalid))
            }
        }
    }

    @Test
    fun `decompression has a hard upper bound even before checksum verification`() {
        val oversized = ByteArray(1_048_577)
        assertThrows(IllegalArgumentException::class.java) {
            CharacterMarkovModel.decode(digest(oversized), compressed(oversized))
        }
    }
}
