package com.bitchat.android.features.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceBurstPacketTest {
    private val burstID = ByteArray(8) { (it + 1).toByte() }

    @Test
    fun wirePacketsRoundTripWithIosLayout() {
        val packets = listOf(
            VoiceBurstPacket.create(
                burstID,
                0,
                VoiceBurstPacket.Kind.Start(VoiceBurstCodec.AAC_LC_16K_MONO)
            )!!,
            VoiceBurstPacket.create(
                burstID,
                7,
                VoiceBurstPacket.Kind.Frames(
                    listOf(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), ByteArray(130) { 0x42 })
                )
            )!!,
            VoiceBurstPacket.create(burstID, 42, VoiceBurstPacket.Kind.End(41, 2_688))!!,
            VoiceBurstPacket.create(burstID, 3, VoiceBurstPacket.Kind.Canceled)!!
        )

        packets.forEach { expected ->
            val actual = VoiceBurstPacket.decode(expected.encode())
            assertNotNull(actual)
            assertArrayEquals(expected.burstID, actual!!.burstID)
            assertEquals(expected.sequence, actual.sequence)
            assertEquals(expected.kind, actual.kind)
        }
    }

    @Test
    fun encodedGoldenVectorsMatchIos() {
        val start = VoiceBurstPacket.create(
            burstID,
            0,
            VoiceBurstPacket.Kind.Start(VoiceBurstCodec.AAC_LC_16K_MONO)
        )!!.encode()
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 0, 0, 1, 1),
            start
        )

        val end = VoiceBurstPacket.create(
            burstID,
            42,
            VoiceBurstPacket.Kind.End(41, 2_688)
        )!!.encode()
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 0, 42, 2, 0, 41, 0, 0, 10, 0x80.toByte()),
            end
        )
    }

    @Test
    fun rejectsMalformedPacketsAndConstruction() {
        assertNull(VoiceBurstPacket.decode(byteArrayOf()))
        assertNull(VoiceBurstPacket.decode(ByteArray(10)))
        assertNull(VoiceBurstPacket.decode(burstID + byteArrayOf(0, 1, 0xFF.toByte())))
        assertNull(VoiceBurstPacket.decode(burstID + byteArrayOf(0, 1, 0)))
        assertNull(VoiceBurstPacket.decode(burstID + byteArrayOf(0, 1, 0, 0, 16, 0xAB.toByte())))
        assertNull(VoiceBurstPacket.decode(burstID + byteArrayOf(0, 0, 1, 0x7F)))
        assertNull(VoiceBurstPacket.create(byteArrayOf(1, 2), 0, VoiceBurstPacket.Kind.Canceled))
        assertNull(VoiceBurstPacket.create(burstID, 1, VoiceBurstPacket.Kind.Frames(emptyList())))
        assertNull(VoiceBurstPacket.create(burstID, 1, VoiceBurstPacket.Kind.Frames(listOf(byteArrayOf()))))
    }

    @Test
    fun packetizerRespectsBudgetAndCounters() {
        val packetizer = VoiceBurstPacketizer(burstID, budget = 210)
        val frame = ByteArray(130) { 0x55 }

        assertTrue(packetizer.add(frame).isEmpty())
        val first = packetizer.add(frame).single()
        assertEquals(1, VoiceBurstPacket.decode(first)!!.sequence)
        val final = packetizer.flush().single()
        assertEquals(2, VoiceBurstPacket.decode(final)!!.sequence)
        assertEquals(2, packetizer.dataPacketCount)
        assertEquals(3, packetizer.nextSequence)
        assertTrue(packetizer.flush().isEmpty())
        assertTrue(first.size + 1 + 16 <= 256)
    }

    @Test
    fun packetizerBatchesEightOrBudgetAndAdtsHeaderIsValid() {
        val packetizer = VoiceBurstPacketizer(burstID, budget = 210)
        repeat(4) { assertTrue(packetizer.add(ByteArray(40) { 0x11 }).isEmpty()) }
        val decoded = VoiceBurstPacket.decode(packetizer.flush().single())!!
        assertEquals(4, (decoded.kind as VoiceBurstPacket.Kind.Frames).frames.size)

        val framed = AdtsFramer.frame(byteArrayOf(1, 2, 3))
        assertEquals(10, framed.size)
        assertEquals(0xFF, framed[0].toInt() and 0xFF)
        assertEquals(0xF1, framed[1].toInt() and 0xFF)
        assertArrayEquals(byteArrayOf(1, 2, 3), framed.copyOfRange(7, framed.size))
    }

    @Test
    fun packetizerReportsFramesThatCannotFitTheWireBudget() {
        val packetizer = VoiceBurstPacketizer(burstID, budget = 210)

        assertTrue(packetizer.add(ByteArray(198)).isEmpty())
        assertEquals(1, packetizer.droppedFrameCount)
        assertEquals(0, packetizer.dataPacketCount)
        assertEquals(1, packetizer.nextSequence)
    }
}
