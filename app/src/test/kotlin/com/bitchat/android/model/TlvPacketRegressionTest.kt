package com.bitchat.android.model

import com.bitchat.android.protocol.TlvLengthSize
import com.bitchat.android.protocol.TlvWriter
import com.bitchat.android.services.meshgraph.GossipTLV
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TlvPacketRegressionTest {

    @Test
    fun `identity announcement skips unknown tlv fields`() {
        val noiseKey = ByteArray(32) { 0x11 }
        val signingKey = ByteArray(32) { 0x22 }
        val encoded = TlvWriter()
            .put(0x7f, byteArrayOf(0x55), TlvLengthSize.ONE_BYTE)
            .put(0x01, "alice".toByteArray(), TlvLengthSize.ONE_BYTE)
            .put(0x02, noiseKey, TlvLengthSize.ONE_BYTE)
            .put(0x03, signingKey, TlvLengthSize.ONE_BYTE)
            .toByteArray()

        val decoded = IdentityAnnouncement.decode(encoded)

        assertNotNull(decoded)
        assertEquals("alice", decoded!!.nickname)
        assertArrayEquals(noiseKey, decoded.noisePublicKey)
        assertArrayEquals(signingKey, decoded.signingPublicKey)
    }

    @Test
    fun `private message rejects unknown tlv fields`() {
        val encoded = TlvWriter()
            .put(0x00, "msg-1".toByteArray(), TlvLengthSize.ONE_BYTE)
            .put(0x7f, byteArrayOf(0x55), TlvLengthSize.ONE_BYTE)
            .put(0x01, "hello".toByteArray(), TlvLengthSize.ONE_BYTE)
            .toByteArray()

        assertNull(PrivateMessagePacket.decode(encoded))
    }

    @Test
    fun `request sync skips unknown tlv fields and enforces payload cap`() {
        val encoded = TlvWriter()
            .put(0x7f, byteArrayOf(0x55), TlvLengthSize.TWO_BYTES)
            .put(0x01, byteArrayOf(4), TlvLengthSize.TWO_BYTES)
            .put(0x02, byteArrayOf(0, 0, 0, 16), TlvLengthSize.TWO_BYTES)
            .put(0x03, byteArrayOf(1, 2, 3), TlvLengthSize.TWO_BYTES)
            .toByteArray()
        val tooLarge = TlvWriter()
            .put(0x01, byteArrayOf(4), TlvLengthSize.TWO_BYTES)
            .put(0x02, byteArrayOf(0, 0, 0, 16), TlvLengthSize.TWO_BYTES)
            .put(0x03, ByteArray(RequestSyncPacket.MAX_ACCEPT_FILTER_BYTES + 1), TlvLengthSize.TWO_BYTES)
            .toByteArray()

        val decoded = RequestSyncPacket.decode(encoded)

        assertNotNull(decoded)
        assertEquals(4, decoded!!.p)
        assertEquals(16L, decoded.m)
        assertArrayEquals(byteArrayOf(1, 2, 3), decoded.data)
        assertNull(RequestSyncPacket.decode(tooLarge))
    }

    @Test
    fun `file packet uses four byte content length for large payloads`() {
        val content = ByteArray(70_000) { (it % 251).toByte() }
        val packet = BitchatFilePacket(
            fileName = "large.bin",
            fileSize = content.size.toLong(),
            mimeType = "application/octet-stream",
            content = content
        )

        val encoded = packet.encode()
        val decoded = BitchatFilePacket.decode(encoded!!)
        val contentTypeOffset = encoded.indexOf(0x04.toByte())
        val contentLength =
            ((encoded[contentTypeOffset + 1].toInt() and 0xFF) shl 24) or
                ((encoded[contentTypeOffset + 2].toInt() and 0xFF) shl 16) or
                ((encoded[contentTypeOffset + 3].toInt() and 0xFF) shl 8) or
                (encoded[contentTypeOffset + 4].toInt() and 0xFF)

        assertEquals(content.size, contentLength)
        assertNotNull(decoded)
        assertArrayEquals(content, decoded!!.content)
    }

    @Test
    fun `file packet rejects unknown tlv fields`() {
        val encoded = TlvWriter()
            .put(0x7f, byteArrayOf(0x55), TlvLengthSize.TWO_BYTES)
            .put(0x01, "x.bin".toByteArray(), TlvLengthSize.TWO_BYTES)
            .put(0x02, byteArrayOf(0, 0, 0, 1), TlvLengthSize.TWO_BYTES)
            .put(0x03, "application/octet-stream".toByteArray(), TlvLengthSize.TWO_BYTES)
            .put(0x04, byteArrayOf(1), TlvLengthSize.FOUR_BYTES)
            .toByteArray()

        assertNull(BitchatFilePacket.decode(encoded))
    }

    @Test
    fun `gossip tlv uses shared peer id and tlv helpers`() {
        val encoded = GossipTLV.encodeNeighbors(listOf("0102030405060708", "aabbcc"))

        assertEquals(listOf("0102030405060708", "aabbcc0000000000"), GossipTLV.decodeNeighborsFromAnnouncementPayload(encoded))
    }
}
