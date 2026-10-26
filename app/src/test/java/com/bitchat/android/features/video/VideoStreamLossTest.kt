package com.bitchat.android.features.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Packetizer -> lossy, reordering channel -> assembler, with synthetic units and a fake clock. */
class VideoStreamLossTest {
    private val config = LiveVideoConfig(
        LiveVideoCodec.H264_ANNEX_B,
        144,
        192,
        8,
        24,
        LiveVideoAudioCodec.AAC_LC_16K_MONO
    )

    private data class SentUnit(val track: LiveVideoTrack, val keyframe: Boolean, val data: ByteArray)

    private class Result(
        val outputs: List<LiveVideoOutput>,
        val sentVideo: List<SentUnit>,
        val sentAudio: List<SentUnit>,
        val assembler: VideoStreamAssembler,
        val packetizer: VideoStreamPacketizer
    ) {
        val video get() = outputs.filterIsInstance<LiveVideoOutput.Video>()
        val audio get() = outputs.filterIsInstance<LiveVideoOutput.Audio>()
        val ended get() = outputs.any { it is LiveVideoOutput.Ended }
    }

    /**
     * 10 s stream: 8 fps video with a 2 s GOP (keyframes ~2.4 KB, deltas 120-260 B) and
     * 64 ms audio frames (~96 B). [lose] decides per packet whether the channel drops it.
     */
    private fun run(
        seed: Int,
        fec: LiveVideoFecPolicy = LiveVideoFecPolicy(parityRatio = 0.25, keyframeParityRatio = 0.5),
        reorderMs: Long = 0L,
        lose: (Int, ByteArray) -> Boolean
    ): Result {
        val random = Random(seed)
        val packetizer = VideoStreamPacketizer(ByteArray(8) { 9 }, config, fec)
        val assembler = VideoStreamAssembler()
        val sentVideo = mutableListOf<SentUnit>()
        val sentAudio = mutableListOf<SentUnit>()
        val inFlight = mutableListOf<Pair<Long, ByteArray>>()
        val outputs = mutableListOf<LiveVideoOutput>()
        var packetIndex = 0

        fun send(now: Long, packets: List<ByteArray>) {
            packets.forEach { packet ->
                if (!lose(packetIndex++, packet)) {
                    inFlight += (now + if (reorderMs > 0) random.nextLong(0, reorderMs) else 0L) to packet
                }
            }
        }

        fun deliver(now: Long) {
            val due = inFlight.filter { it.first <= now }.sortedBy { it.first }
            inFlight.removeAll(due)
            due.forEach { (_, bytes) ->
                outputs += assembler.onPacket(VideoBurstPacket.decode(bytes)!!, bytes.size, now)
            }
            outputs += assembler.onTick(now)
        }

        send(0, packetizer.start())
        var nextVideo = 0L
        var nextAudio = 0L
        var frame = 0
        for (now in 0L..10_000L step 4L) {
            if (now >= nextVideo) {
                val key = frame % 16 == 0
                val size = if (key) 2_400 else random.nextInt(120, 260)
                val data = ByteArray(size).also(random::nextBytes)
                sentVideo += SentUnit(LiveVideoTrack.VIDEO, key, data)
                send(now, packetizer.add(LiveMediaUnit(LiveVideoTrack.VIDEO, now, key, data), now))
                frame++
                nextVideo += 125
            }
            if (now >= nextAudio) {
                val data = ByteArray(random.nextInt(80, 110)).also(random::nextBytes)
                sentAudio += SentUnit(LiveVideoTrack.AUDIO, false, data)
                send(now, packetizer.add(LiveMediaUnit(LiveVideoTrack.AUDIO, now, false, data), now))
                nextAudio += 64
            }
            send(now, packetizer.tick(now))
            deliver(now)
        }
        send(10_000L, packetizer.finish(10_000L, 10_000L))
        for (now in 10_000L..13_000L step 10L) deliver(now)
        if (!assembler.isFinished) outputs += assembler.flush()
        return Result(outputs, sentVideo, sentAudio, assembler, packetizer)
    }

    @Test
    fun losslessChannelDeliversEveryUnitInOrder() {
        val result = run(seed = 1) { _, _ -> false }
        assertEquals(result.sentVideo.size, result.video.size)
        assertEquals(result.sentAudio.size, result.audio.size)
        result.video.forEachIndexed { index, unit ->
            assertArrayEquals(result.sentVideo[index].data, unit.data)
            assertEquals(result.sentVideo[index].keyframe, unit.keyframe)
        }
        result.audio.forEachIndexed { index, unit -> assertArrayEquals(result.sentAudio[index].data, unit.data) }
        assertTrue(result.ended)
        assertEquals(0, result.assembler.stats().skippedVideoUnits)
    }

    @Test
    fun aggregationKeepsPacketsFull() {
        val result = run(seed = 2) { _, _ -> false }
        val bytes = result.sentVideo.sumOf { it.data.size } + result.sentAudio.sumOf { it.data.size }
        val sourcePackets = result.packetizer.packetCount - result.packetizer.parityPackets
        // Far fewer packets than units: small audio/delta units share shards.
        assertTrue(sourcePackets < (result.sentVideo.size + result.sentAudio.size) * 2 / 3)
        assertTrue(bytes / sourcePackets > 180)
    }

    @Test
    fun fecRepairsIndependentLossWithoutVisibleGaps() {
        val random = Random(77)
        val result = run(seed = 3, reorderMs = 120L) { _, _ -> random.nextDouble() < 0.05 }
        val stats = result.assembler.stats()
        assertTrue("recovered ${stats.recoveredShards}", stats.recoveredShards > 0)
        // 5 % random loss is mostly absorbed by parity; a rare unrecoverable block costs at
        // most the rest of its GOP.
        assertTrue("video ${result.video.size}/${result.sentVideo.size}", result.video.size >= result.sentVideo.size * 0.8)
        assertTrue(result.audio.size >= result.sentAudio.size * 0.95)
        assertTrue(result.ended)
    }

    @Test
    fun heavyBurstLossFreezesUntilNextKeyframeAndNeverEmitsBrokenDeltas() {
        // Drop 40 consecutive packets (an entire GOP's worth of shards plus its parity).
        val result = run(seed = 4) { index, _ -> index in 60..99 }
        val sentByContent = result.sentVideo.associateBy { it.data.contentHashCode() }
        var chainValid = false
        var previousSequence = -1L
        result.video.forEach { unit ->
            val sent = sentByContent.getValue(unit.data.contentHashCode())
            assertArrayEquals(sent.data, unit.data)
            if (unit.keyframe) chainValid = true
            assertTrue("delta emitted without an intact chain", chainValid)
            if (!unit.keyframe) assertEquals(previousSequence + 1, unit.sequence)
            previousSequence = unit.sequence
        }
        assertTrue(result.assembler.stats().skippedVideoUnits > 0)
        // Recovers at the next keyframe and keeps playing.
        assertTrue(result.video.size > result.sentVideo.size / 2)
    }

    @Test
    fun lateJoinerStartsAtTheFirstKeyframe() {
        val result = run(seed = 5) { index, _ -> index < 150 }
        val first = result.video.first()
        assertTrue(first.keyframe)
        assertTrue(result.outputs.any { it is LiveVideoOutput.Config })
    }

    @Test
    fun duplicatedPacketsAreIgnored() {
        val packetizer = VideoStreamPacketizer(ByteArray(8), config, LiveVideoFecPolicy(0.25, 0.5))
        val assembler = VideoStreamAssembler()
        val packets = packetizer.start() +
            packetizer.add(LiveMediaUnit(LiveVideoTrack.VIDEO, 0, true, ByteArray(900) { 3 }), 0) +
            packetizer.finish(1_000, 1_000)
        val outputs = (packets + packets).flatMap { assembler.onPacket(VideoBurstPacket.decode(it)!!, it.size, 0) }
        assertEquals(1, outputs.filterIsInstance<LiveVideoOutput.Video>().size)
        assertEquals(1, outputs.count { it is LiveVideoOutput.Ended })
    }
}
