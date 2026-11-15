package com.bitchat.android.features.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoBurstPacketTest {
    private val streamID = ByteArray(8) { (it + 1).toByte() }
    private val config = LiveVideoConfig(
        LiveVideoCodec.H264_ANNEX_B,
        width = 144,
        height = 192,
        fps = 8,
        bitrateKbps = 24,
        audioCodec = LiveVideoAudioCodec.AAC_LC_16K_MONO
    )

    private fun hex(value: String): ByteArray =
        value.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun everyKindRoundTrips() {
        val entries = listOf(
            LiveVideoEntry(LiveVideoTrack.VIDEO, true, 7, 1_000, 0, 2, ByteArray(40) { 1 }),
            LiveVideoEntry(LiveVideoTrack.AUDIO, false, 9, 1_064, 0, 1, byteArrayOf(5, 6, 7))
        )
        val kinds = listOf(
            VideoBurstPacket.Kind.Start(config),
            VideoBurstPacket.Kind.Data(3, 1, VideoBurstPacket.encodeEntries(entries)),
            VideoBurstPacket.Kind.Parity(3, 0, 4, 2, ByteArray(60) { it.toByte() }),
            VideoBurstPacket.Kind.End(12_345, 99, 190, 777),
            VideoBurstPacket.Kind.Canceled
        )
        kinds.forEachIndexed { sequence, kind ->
            val packet = VideoBurstPacket.create(streamID, sequence, kind)!!
            val decoded = VideoBurstPacket.decode(packet.encode())
            assertNotNull(decoded)
            assertArrayEquals(streamID, decoded!!.streamID)
            assertEquals(sequence, decoded.sequence)
            assertEquals(kind, decoded.kind)
        }
        val region = (kinds[1] as VideoBurstPacket.Kind.Data).entriesRegion
        assertEquals(entries, LiveVideoEntry.decodeAll(region))
    }

    @Test
    fun goldenVectorsMatchSpecification() {
        assertArrayEquals(
            hex("0102030405060708 0000 01 01 01 0090 00C0 08 0018 01"),
            VideoBurstPacket.create(streamID, 0, VideoBurstPacket.Kind.Start(config))!!.encode()
        )
        val entry = LiveVideoEntry(LiveVideoTrack.VIDEO, true, 1, 64, 0, 1, hex("DEADBE"))
        assertArrayEquals(
            hex("0102030405060708 0005 00 0002 00 04 0001 00000040 00 01 0003 DEADBE"),
            VideoBurstPacket.create(
                streamID,
                5,
                VideoBurstPacket.Kind.Data(2, 0, VideoBurstPacket.encodeEntries(listOf(entry)))
            )!!.encode()
        )
        assertArrayEquals(
            hex("0102030405060708 0006 03 0002 00 01 01 0003 AABBCC"),
            VideoBurstPacket.create(
                streamID,
                6,
                VideoBurstPacket.Kind.Parity(2, 0, 1, 1, hex("AABBCC"))
            )!!.encode()
        )
        assertArrayEquals(
            hex("0102030405060708 002A 02 00000A80 0010 0029 002A"),
            VideoBurstPacket.create(streamID, 42, VideoBurstPacket.Kind.End(2_688, 16, 41, 42))!!.encode()
        )
        assertArrayEquals(
            hex("0102030405060708 0003 04"),
            VideoBurstPacket.create(streamID, 3, VideoBurstPacket.Kind.Canceled)!!.encode()
        )
    }

    @Test
    fun malformedPacketsAreRejected() {
        val start = VideoBurstPacket.create(streamID, 0, VideoBurstPacket.Kind.Start(config))!!.encode()
        assertNull(VideoBurstPacket.decode(start.copyOf(start.size - 1)))
        assertNull(VideoBurstPacket.decode(start + byteArrayOf(0)))
        assertNull(VideoBurstPacket.decode(start.copyOf().also { it[11] = 0x09 }))
        assertNull(VideoBurstPacket.decode(start.copyOf().also { it[12] = 0x02 }))
        assertNull(VideoBurstPacket.decode(start.copyOf().also { it[10] = 0x07 }))
        assertNull(VideoBurstPacket.decode(ByteArray(VideoBurstPacket.MAX_PACKET_BYTES + 1)))

        val entry = LiveVideoEntry(LiveVideoTrack.AUDIO, false, 1, 0, 0, 1, byteArrayOf(1, 2))
        val data = VideoBurstPacket.create(
            streamID,
            1,
            VideoBurstPacket.Kind.Data(0, 0, VideoBurstPacket.encodeEntries(listOf(entry)))
        )!!.encode()
        // Truncated entry, unknown flag bits, keyframe audio, and zero-length entries.
        assertNull(VideoBurstPacket.decode(data.copyOf(data.size - 1)))
        assertNull(VideoBurstPacket.decode(data.copyOf().also { it[14] = 0x08 }))
        assertNull(VideoBurstPacket.decode(data.copyOf().also { it[14] = 0x05 }))
        assertNull(VideoBurstPacket.decode(data.copyOf().also { it[24] = 0; it[23] = 0 }))
        // Fragment index beyond its count.
        assertNull(VideoBurstPacket.decode(data.copyOf().also { it[21] = 1 }))
    }

    @Test
    fun maximalPacketsStayWithinBudget() {
        val fullEntry = LiveVideoEntry(
            LiveVideoTrack.VIDEO,
            false,
            1,
            0,
            0,
            1,
            ByteArray(VideoBurstPacket.MAX_ENTRIES_REGION - LiveVideoEntry.HEADER_SIZE) { 7 }
        )
        val data = VideoBurstPacket.create(
            streamID,
            1,
            VideoBurstPacket.Kind.Data(0, 0, VideoBurstPacket.encodeEntries(listOf(fullEntry)))
        )!!.encode()
        val parity = VideoBurstPacket.create(
            streamID,
            2,
            VideoBurstPacket.Kind.Parity(0, 0, 1, 1, ByteArray(VideoBurstPacket.MAX_ENTRIES_REGION + 2))
        )!!.encode()
        assertTrue(data.size <= VideoBurstPacket.MAX_PACKET_BYTES)
        assertEquals(VideoBurstPacket.MAX_PACKET_BYTES, parity.size)
        assertNull(
            VideoBurstPacket.create(
                streamID,
                2,
                VideoBurstPacket.Kind.Parity(0, 0, 1, 1, ByteArray(VideoBurstPacket.MAX_ENTRIES_REGION + 3))
            )
        )
    }
}
