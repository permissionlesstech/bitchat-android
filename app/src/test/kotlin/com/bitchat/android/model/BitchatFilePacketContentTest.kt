package com.bitchat.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * A FILE_TRANSFER payload arrives from any verified peer, and the sender chooses how many CONTENT
 * TLVs it is split into. Reassembly therefore has to be linear in the payload, not in the chunk
 * count times the payload.
 */
class BitchatFilePacketContentTest {

    private fun shortTlv(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(
            tag.toByte(),
            ((value.size ushr 8) and 0xFF).toByte(),
            (value.size and 0xFF).toByte()
        ) + value

    /** CONTENT carries a 4-byte length, unlike the other tags. */
    private fun contentTlv(value: ByteArray): ByteArray =
        byteArrayOf(
            0x04,
            ((value.size ushr 24) and 0xFF).toByte(),
            ((value.size ushr 16) and 0xFF).toByte(),
            ((value.size ushr 8) and 0xFF).toByte(),
            (value.size and 0xFF).toByte()
        ) + value

    private fun payload(contentChunks: List<ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(shortTlv(0x01, "photo.jpg".toByteArray()))
        out.write(shortTlv(0x03, "image/jpeg".toByteArray()))
        contentChunks.forEach { out.write(contentTlv(it)) }
        return out.toByteArray()
    }

    @Test
    fun `a split content tlv is reassembled in order`() {
        val chunks = listOf("abc".toByteArray(), "de".toByteArray(), "fgh".toByteArray())

        val decoded = BitchatFilePacket.decode(payload(chunks))

        assertNotNull(decoded)
        assertArrayEquals("abcdefgh".toByteArray(), decoded!!.content)
        assertEquals("photo.jpg", decoded.fileName)
        assertEquals("image/jpeg", decoded.mimeType)
        // No FILE_SIZE TLV, so the size falls back to the reassembled length.
        assertEquals(8L, decoded.fileSize)
    }

    @Test
    fun `a single content tlv round trips through encode`() {
        val original = BitchatFilePacket(
            fileName = "note.txt",
            fileSize = 5L,
            mimeType = "text/plain",
            content = "hello".toByteArray()
        )

        val decoded = BitchatFilePacket.decode(original.encode()!!)

        assertNotNull(decoded)
        assertArrayEquals(original.content, decoded!!.content)
        assertEquals(original.fileName, decoded.fileName)
        assertEquals(original.fileSize, decoded.fileSize)
    }

    @Test
    fun `an empty content tlv still decodes to an empty file`() {
        val decoded = BitchatFilePacket.decode(payload(listOf(ByteArray(0))))

        assertNotNull(decoded)
        assertEquals(0, decoded!!.content.size)
    }

    @Test
    fun `a payload with no content tlv is rejected`() {
        assertNull(BitchatFilePacket.decode(payload(emptyList())))
    }

    @Test
    fun `heavily split content reassembles correctly at scale`() {
        // A CONTENT TLV costs 5 header bytes, so one content byte per chunk is the sender's
        // cheapest way to maximise the chunk count. 200k chunks is ~1.2 MB on the wire, the
        // scale BLE reassembly already lets through.
        //
        // No timing assertion here on purpose: a wall-clock threshold wide enough to stay stable
        // on slow CI is too wide to fail on the quadratic path, so it would only add flake. The
        // cost was measured directly instead and is recorded in the pull request.
        val chunkCount = 200_000
        val data = payload(List(chunkCount) { byteArrayOf(it.toByte()) })

        val decoded = BitchatFilePacket.decode(data)

        assertNotNull(decoded)
        assertEquals(chunkCount, decoded!!.content.size)
        assertTrue(
            "every chunk must land at its own offset",
            decoded.content.withIndex().all { (i, b) -> b == i.toByte() }
        )
    }
}
