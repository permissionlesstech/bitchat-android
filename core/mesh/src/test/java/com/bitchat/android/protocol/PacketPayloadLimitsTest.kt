package com.bitchat.android.protocol

import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Per-type payload caps at decode: a payload may reach its type's cap and no
 * further, whether it inflates from a few bytes on the wire or arrives
 * uncompressed (as a reassembled fragment stream does).
 */
class PacketPayloadLimitsTest {

    private val senderID = ByteArray(8) { (it + 1).toByte() }
    private val fixedTimestamp = 1709600000000uL

    private val cappedTypes: List<UByte> = listOf(
        MessageType.MESSAGE.value,
        MessageType.FRAGMENT.value,
        MessageType.ANNOUNCE.value,
        MessageType.LEAVE.value,
        MessageType.NOISE_HANDSHAKE.value,
        MessageType.REQUEST_SYNC.value,
        MessageType.VOICE_FRAME.value,
        0x04u, // courier envelope (iOS only)
        0x23u, // board post (iOS only)
        0x25u, // group message (iOS only)
        0x28u, // Nostr carrier (iOS only)
        0x7Fu // unassigned: future types keep a v1 frame's worth
    )

    @Test
    fun `caps match the iOS table`() {
        assertEquals(128 * 1024, PacketPayloadLimits.maxPayloadBytes(MessageType.MESSAGE.value))
        assertEquals(1_280, PacketPayloadLimits.maxPayloadBytes(MessageType.FRAGMENT.value))
        assertEquals(4 * 1024, PacketPayloadLimits.maxPayloadBytes(MessageType.ANNOUNCE.value))
        assertEquals(8 * 1024, PacketPayloadLimits.maxPayloadBytes(MessageType.REQUEST_SYNC.value))
        assertEquals(64 * 1024, PacketPayloadLimits.maxPayloadBytes(0x04u))
        assertEquals(64 * 1024, PacketPayloadLimits.maxPayloadBytes(0x28u))
        assertEquals(4 * 1024, PacketPayloadLimits.maxPayloadBytes(0x26u))
        assertEquals(128 * 1024, PacketPayloadLimits.maxPayloadBytes(0x7Fu))
    }

    @Test
    fun `media types keep the framed-file ceiling`() {
        val max = AppConstants.Protocol.MAX_PAYLOAD_LENGTH
        assertEquals(max, PacketPayloadLimits.maxPayloadBytes(MessageType.FILE_TRANSFER.value))
        assertEquals(max, PacketPayloadLimits.maxPayloadBytes(MessageType.NOISE_ENCRYPTED.value))
    }

    @Test
    fun `compressed payload inflates to its type cap and no further`() {
        for (type in cappedTypes) {
            val cap = PacketPayloadLimits.maxPayloadBytes(type)
            assertTrue(cap < AppConstants.Protocol.MAX_PAYLOAD_LENGTH)

            assertEquals(
                "type $type at cap",
                cap,
                decodeCompressed(type, originalSize = cap)?.payload?.size
            )
            var calls = 0
            val pastCap = BinaryProtocol.decodeForTesting(compressedFrame(type, cap + 1)) { _, size ->
                calls += 1
                ByteArray(size)
            }
            assertNull("type $type past cap", pastCap)
            assertEquals("type $type past cap must not reach the decompressor", 0, calls)
        }
    }

    @Test
    fun `uncompressed payload reaches its type cap and no further`() {
        for (type in cappedTypes) {
            val cap = PacketPayloadLimits.maxPayloadBytes(type)

            val atCap = BinaryProtocol.decode(uncompressedFrame(type, cap))
            assertNotNull("type $type at cap", atCap)
            assertEquals(cap, atCap!!.payload.size)

            assertNull("type $type past cap", BinaryProtocol.decode(uncompressedFrame(type, cap + 1)))
        }
    }

    @Test
    fun `media payload above every control cap still decodes`() {
        val size = 256 * 1024
        for (type in listOf(MessageType.FILE_TRANSFER.value, MessageType.NOISE_ENCRYPTED.value)) {
            assertEquals(size, BinaryProtocol.decode(uncompressedFrame(type, size))?.payload?.size)
            assertEquals(size, decodeCompressed(type, originalSize = size)?.payload?.size)
        }
    }

    @Test
    fun `declared expansion beyond the DEFLATE bound never reaches the decompressor`() {
        val compressedSize = 16
        val type = MessageType.FILE_TRANSFER.value
        val atBound = compressedSize * PacketPayloadLimits.MAX_DEFLATE_RATIO

        assertEquals(
            atBound,
            decodeCompressed(type, originalSize = atBound, compressedSize = compressedSize)?.payload?.size
        )

        var calls = 0
        val pastBound = BinaryProtocol.decodeForTesting(
            compressedFrame(type, atBound + 1, compressedSize)
        ) { _, size ->
            calls += 1
            ByteArray(size)
        }
        assertNull(pastBound)
        assertEquals(0, calls)
    }

    private fun decodeCompressed(
        type: UByte,
        originalSize: Int,
        compressedSize: Int = minimumCompressedSize(originalSize)
    ): BitchatPacket? =
        BinaryProtocol.decodeForTesting(compressedFrame(type, originalSize, compressedSize)) { _, size ->
            ByteArray(size)
        }

    private fun minimumCompressedSize(originalSize: Int): Int =
        (originalSize + PacketPayloadLimits.MAX_DEFLATE_RATIO - 1) / PacketPayloadLimits.MAX_DEFLATE_RATIO

    private fun compressedFrame(
        type: UByte,
        originalSize: Int,
        compressedSize: Int = minimumCompressedSize(originalSize)
    ): ByteArray {
        val payloadLength = 4 + compressedSize
        return ByteBuffer.allocate(16 + 8 + payloadLength).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(2.toByte())
            put(type.toByte())
            put(5.toByte())
            putLong(fixedTimestamp.toLong())
            put(BinaryProtocol.Flags.IS_COMPRESSED.toByte())
            putInt(payloadLength)
            put(senderID)
            putInt(originalSize)
            put(ByteArray(compressedSize) { 0x55 })
        }.array()
    }

    private fun uncompressedFrame(type: UByte, payloadSize: Int): ByteArray =
        ByteBuffer.allocate(16 + 8 + payloadSize).apply {
            order(ByteOrder.BIG_ENDIAN)
            put(2.toByte())
            put(type.toByte())
            put(5.toByte())
            putLong(fixedTimestamp.toLong())
            put(0.toByte())
            putInt(payloadSize)
            put(senderID)
            put(ByteArray(payloadSize) { 0x2A })
        }.array()
}
