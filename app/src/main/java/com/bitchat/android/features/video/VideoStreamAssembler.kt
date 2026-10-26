package com.bitchat.android.features.video

import java.util.TreeMap

/** Output of [VideoStreamAssembler], in decode order per track. */
sealed interface LiveVideoOutput {
    data class Config(val config: LiveVideoConfig) : LiveVideoOutput

    /** A decodable video access unit: a keyframe, or a delta frame whose references all arrived. */
    class Video(val sequence: Long, val ptsMs: Long, val keyframe: Boolean, val data: ByteArray) : LiveVideoOutput

    class Audio(val sequence: Long, val ptsMs: Long, val data: ByteArray) : LiveVideoOutput

    /** Video units that were lost (or undecodable after a loss) and skipped. */
    data class VideoSkipped(val units: Int) : LiveVideoOutput

    data class Ended(val durationMs: Long) : LiveVideoOutput
    data object Canceled : LiveVideoOutput
}

data class LiveVideoReceiveStats(
    val packets: Int,
    val bytes: Long,
    val recoveredShards: Int,
    val videoUnits: Int,
    val audioUnits: Int,
    val skippedVideoUnits: Int,
    /** Packet loss over the most recent sequence window, 0.0..1.0. */
    val recentLoss: Double
)

/**
 * Receiver state machine for one live video stream. Pure logic; the caller supplies time.
 *
 * Packets may arrive duplicated, reordered, or not at all. Source shards are parsed the moment
 * they arrive; parity rebuilds lost shards once any `k` symbols of a block are present. Units
 * are released per track in sequence order. A gap is skipped after [gapMs]; a skipped video
 * unit breaks the reference chain, so delta frames are withheld until the next keyframe (the
 * player freezes on the last good picture instead of showing corruption).
 */
class VideoStreamAssembler(
    /** Longer than the widest FEC block window plus relay jitter, so parity can still land. */
    private val gapMs: Long = 900L,
    private val maxUnitBytes: Int = 256 * 1024
) {
    companion object {
        private const val MAX_TRACKED_BLOCKS = 48
        private const val MAX_PENDING_UNITS = 192
        private const val MAX_READY_UNITS = 192
        private const val LOSS_WINDOW = 128
    }

    private class BlockState {
        val sources = HashMap<Int, ByteArray>()
        val parities = HashMap<Int, ByteArray>()
        var sourceCount: Int? = null
        var parityCount: Int? = null
        var complete = false
    }

    private class PendingUnit(val keyframe: Boolean, val ptsMs: Long, val count: Int) {
        val fragments = arrayOfNulls<ByteArray>(count)
        var received = 0
        var bytes = 0
    }

    private class ReadyUnit(val ptsMs: Long, val keyframe: Boolean, val data: ByteArray)

    private class TrackState {
        val pending = LinkedHashMap<Long, PendingUnit>()
        val ready = TreeMap<Long, ReadyUnit>()
        var next: Long? = null
        var lastReference: Long? = null
        var gapSinceMs: Long? = null
        var released = 0
    }

    private val blocks = LinkedHashMap<Int, BlockState>()
    private val tracks = LiveVideoTrack.entries.associateWith { TrackState() }
    private var lastConfig: LiveVideoConfig? = null
    private var videoChainBroken = true
    private var expectedUnits: Map<LiveVideoTrack, Int>? = null
    private var endDurationMs: Long? = null
    private var finished = false

    private var packets = 0
    private var bytes = 0L
    private var recoveredShards = 0
    private var skippedVideo = 0
    private var highestSequence: Long? = null
    private val recentSequences = java.util.ArrayDeque<Long>()
    private val recentSet = HashSet<Long>()

    val config: LiveVideoConfig? get() = lastConfig
    val isFinished: Boolean get() = finished

    fun stats(): LiveVideoReceiveStats {
        val high = highestSequence
        val loss = if (high == null) 0.0 else {
            val window = minOf(LOSS_WINDOW.toLong(), high + 1)
            val seen = recentSet.count { it > high - window }
            (1.0 - seen.toDouble() / window.toDouble()).coerceIn(0.0, 1.0)
        }
        return LiveVideoReceiveStats(
            packets = packets,
            bytes = bytes,
            recoveredShards = recoveredShards,
            videoUnits = tracks.getValue(LiveVideoTrack.VIDEO).released,
            audioUnits = tracks.getValue(LiveVideoTrack.AUDIO).released,
            skippedVideoUnits = skippedVideo,
            recentLoss = loss
        )
    }

    fun onPacket(packet: VideoBurstPacket, sizeBytes: Int, nowMs: Long): List<LiveVideoOutput> {
        if (finished) return emptyList()
        packets++
        bytes += sizeBytes
        recordSequence(packet.sequence)
        val output = mutableListOf<LiveVideoOutput>()
        when (val kind = packet.kind) {
            is VideoBurstPacket.Kind.Start -> if (kind.config != lastConfig) {
                lastConfig = kind.config
                output += LiveVideoOutput.Config(kind.config)
            }
            is VideoBurstPacket.Kind.Data -> onData(kind, nowMs)
            is VideoBurstPacket.Kind.Parity -> onParity(kind, nowMs)
            is VideoBurstPacket.Kind.End -> {
                expectedUnits = mapOf(
                    LiveVideoTrack.VIDEO to kind.videoUnits,
                    LiveVideoTrack.AUDIO to kind.audioUnits
                )
                endDurationMs = kind.durationMs
            }
            VideoBurstPacket.Kind.Canceled -> {
                finished = true
                return listOf(LiveVideoOutput.Canceled)
            }
        }
        output += release(nowMs)
        return output
    }

    /** Skips gaps whose deadline passed; call periodically. */
    fun onTick(nowMs: Long): List<LiveVideoOutput> = if (finished) emptyList() else release(nowMs)

    /** Forces every buffered unit out (skipping all gaps); used when the stream goes idle. */
    fun flush(): List<LiveVideoOutput> {
        if (finished) return emptyList()
        val output = release(Long.MAX_VALUE)
        finished = true
        output += LiveVideoOutput.Ended(endDurationMs ?: 0L)
        return output
    }

    private fun onData(kind: VideoBurstPacket.Kind.Data, nowMs: Long) {
        val state = blockState(kind.block)
        val limit = state.sourceCount
        if (state.complete || kind.index in state.sources || (limit != null && kind.index >= limit)) return
        state.sources[kind.index] = kind.entriesRegion
        ingestRegion(kind.entriesRegion, nowMs)
        tryRecover(state, nowMs)
    }

    private fun onParity(kind: VideoBurstPacket.Kind.Parity, nowMs: Long) {
        val state = blockState(kind.block)
        if (state.complete) return
        val knownK = state.sourceCount
        if (knownK != null && (knownK != kind.sourceCount || state.parityCount != kind.parityCount)) return
        val existing = state.parities.values.firstOrNull()
        if (existing != null && existing.size != kind.symbol.size) return
        if (state.sources.keys.any { it >= kind.sourceCount }) return
        state.sourceCount = kind.sourceCount
        state.parityCount = kind.parityCount
        state.parities.putIfAbsent(kind.parityIndex, kind.symbol)
        tryRecover(state, nowMs)
    }

    private fun tryRecover(state: BlockState, nowMs: Long) {
        val k = state.sourceCount ?: return
        if (state.sources.size >= k) {
            state.complete = true
            return
        }
        if (state.sources.size + state.parities.size < k) return
        val symbolLength = state.parities.values.first().size
        if (state.sources.values.any { it.size + 2 > symbolLength }) {
            state.complete = true
            return
        }
        val slots = (0 until k).map { index ->
            state.sources[index]?.let { LiveVideoSymbols.symbol(it, symbolLength) }
        }
        val rebuilt = ReedSolomonErasure.reconstruct(slots, state.parities)
        state.complete = true
        if (rebuilt == null) return
        for (index in 0 until k) {
            if (index in state.sources) continue
            val region = LiveVideoSymbols.region(rebuilt[index]) ?: continue
            state.sources[index] = region
            recoveredShards++
            ingestRegion(region, nowMs)
        }
    }

    private fun ingestRegion(region: ByteArray, nowMs: Long) {
        val entries = LiveVideoEntry.decodeAll(region) ?: return
        entries.forEach { entry -> ingestEntry(entry, nowMs) }
    }

    private fun ingestEntry(entry: LiveVideoEntry, nowMs: Long) {
        val track = tracks.getValue(entry.track)
        val reference = track.next ?: track.lastReference ?: entry.unitSequence.toLong()
        val sequence = expand(entry.unitSequence, reference)
        val next = track.next
        if (next != null && sequence < next) return
        if (sequence in track.ready) return
        var pending = track.pending[sequence]
        if (pending == null) {
            if (track.pending.size >= MAX_PENDING_UNITS) {
                track.pending.remove(track.pending.keys.first())
            }
            pending = PendingUnit(entry.keyframe, entry.ptsMs, entry.fragmentCount)
            track.pending[sequence] = pending
        }
        if (pending.count != entry.fragmentCount || pending.keyframe != entry.keyframe) return
        if (pending.fragments[entry.fragmentIndex] != null) return
        pending.bytes += entry.bytes.size
        if (pending.bytes > maxUnitBytes) {
            track.pending.remove(sequence)
            return
        }
        pending.fragments[entry.fragmentIndex] = entry.bytes
        pending.received++
        if (pending.received < pending.count) return
        track.pending.remove(sequence)
        val data = ByteArray(pending.bytes)
        var offset = 0
        pending.fragments.forEach { fragment ->
            fragment!!.copyInto(data, offset)
            offset += fragment.size
        }
        if (track.next == null) {
            track.next = sequence
        }
        track.lastReference = sequence
        track.ready[sequence] = ReadyUnit(pending.ptsMs, pending.keyframe, data)
        if (track.ready.size > MAX_READY_UNITS) {
            // A runaway backlog means the head gap will never fill; skip to the oldest ready unit.
            skipTo(entry.track, track, track.ready.firstKey())
        }
    }

    private fun release(nowMs: Long): MutableList<LiveVideoOutput> {
        val output = mutableListOf<LiveVideoOutput>()
        tracks.forEach { (type, track) ->
            while (true) {
                val next = track.next ?: break
                val unit = track.ready.remove(next)
                if (unit != null) {
                    track.gapSinceMs = null
                    track.next = next + 1
                    track.released++
                    emit(type, next, unit, output)
                    continue
                }
                if (track.ready.isEmpty()) {
                    track.gapSinceMs = null
                    break
                }
                val since = track.gapSinceMs ?: nowMs.also { track.gapSinceMs = it }
                if (nowMs == Long.MAX_VALUE || nowMs - since >= gapMs) {
                    val target = track.ready.firstKey()
                    val skipped = skipTo(type, track, target)
                    if (type == LiveVideoTrack.VIDEO && skipped > 0) output += LiveVideoOutput.VideoSkipped(skipped)
                    track.gapSinceMs = null
                    continue
                }
                break
            }
        }
        val expected = expectedUnits
        if (expected != null && !finished) {
            val done = expected.all { (type, total) ->
                val track = tracks.getValue(type)
                total == 0 || (track.next != null && track.next!! >= expandCount(total, track.next!!))
            }
            if (done) {
                finished = true
                output += LiveVideoOutput.Ended(endDurationMs ?: 0L)
            }
        }
        return output
    }

    private fun emit(type: LiveVideoTrack, sequence: Long, unit: ReadyUnit, output: MutableList<LiveVideoOutput>) {
        when (type) {
            LiveVideoTrack.AUDIO -> output += LiveVideoOutput.Audio(sequence, unit.ptsMs, unit.data)
            LiveVideoTrack.VIDEO -> {
                if (unit.keyframe) videoChainBroken = false
                if (videoChainBroken) {
                    skippedVideo++
                    output += LiveVideoOutput.VideoSkipped(1)
                } else {
                    output += LiveVideoOutput.Video(sequence, unit.ptsMs, unit.keyframe, unit.data)
                }
            }
        }
    }

    private fun skipTo(type: LiveVideoTrack, track: TrackState, target: Long): Int {
        val next = track.next ?: return 0
        val skipped = (target - next).coerceAtLeast(0L).toInt()
        if (skipped > 0) {
            track.pending.keys.removeAll { it < target }
            if (type == LiveVideoTrack.VIDEO) {
                videoChainBroken = true
                skippedVideo += skipped
            }
        }
        track.next = target
        return skipped
    }

    private fun blockState(block: Int): BlockState {
        blocks[block]?.let { return it }
        while (blocks.size >= MAX_TRACKED_BLOCKS) blocks.remove(blocks.keys.first())
        return BlockState().also { blocks[block] = it }
    }

    private fun recordSequence(sequence: Int) {
        val expanded = expand(sequence, highestSequence ?: sequence.toLong())
        if (!recentSet.add(expanded)) return
        recentSequences.addLast(expanded)
        if (highestSequence == null || expanded > highestSequence!!) highestSequence = expanded
        while (recentSequences.size > LOSS_WINDOW) recentSet.remove(recentSequences.removeFirst())
    }

    /** Maps a 16-bit wire counter to the 64-bit value closest to [reference]. */
    private fun expand(value: Int, reference: Long): Long {
        val base = reference - (reference and 0xFFFF)
        var candidate = base + value
        if (candidate - reference > 0x8000) candidate -= 0x10000
        if (reference - candidate > 0x8000) candidate += 0x10000
        return candidate
    }

    /** END counts units (not sequence numbers); a count of N means sequences 0 until N. */
    private fun expandCount(count: Int, reference: Long): Long {
        val base = reference - (reference and 0xFFFF)
        var candidate = base + count
        if (candidate < reference - 0x8000) candidate += 0x10000
        return candidate
    }
}
