package com.bitchat.android.features.video

import kotlin.math.ceil

/** One encoded access unit handed to the packetizer. */
class LiveMediaUnit(
    val track: LiveVideoTrack,
    val ptsMs: Long,
    val keyframe: Boolean,
    val data: ByteArray
)

/** How much Reed-Solomon parity accompanies each block of source shards. */
data class LiveVideoFecPolicy(
    val parityRatio: Double,
    val keyframeParityRatio: Double,
    val maxSourceShards: Int = 12,
    val shardFlushMs: Long = 150L,
    val blockWindowMs: Long = 400L
) {
    fun parityCount(sourceCount: Int, containsKeyframe: Boolean): Int {
        val ratio = if (containsKeyframe) keyframeParityRatio else parityRatio
        if (ratio <= 0.0) return 0
        // Two parity shards from four sources up: one lost delta frame freezes the picture
        // until the next keyframe, so double losses are worth absorbing.
        val floor = if (sourceCount >= 4) 2 else 1
        return maxOf(ceil(sourceCount * ratio).toInt(), floor)
            .coerceIn(1, VideoBurstPacket.MAX_BLOCK_PARITY_SHARDS)
    }
}

/** FEC symbol framing shared by sender and receiver: `[regionLength: UInt16][region][zero pad]`. */
internal object LiveVideoSymbols {
    fun symbol(region: ByteArray, length: Int): ByteArray = ByteArray(length).also { symbol ->
        symbol[0] = ((region.size ushr 8) and 0xFF).toByte()
        symbol[1] = (region.size and 0xFF).toByte()
        region.copyInto(symbol, destinationOffset = 2)
    }

    fun region(symbol: ByteArray): ByteArray? {
        if (symbol.size < 2) return null
        val length = symbol.u16(0)
        if (length == 0 || length > symbol.size - 2) return null
        if ((2 + length until symbol.size).any { symbol[it].toInt() != 0 }) return null
        return symbol.copyOfRange(2, 2 + length)
    }
}

/**
 * Turns encoded audio/video units into wire packets.
 *
 * Small units are aggregated into one shard, large ones fragmented across shards (in the
 * spirit of RTP STAP-A/FU-A), so every packet is independently parseable and a lost packet
 * only damages the units it carried. Consecutive source shards form a block that is closed
 * by size, by time, or before a keyframe, and then protected by Reed-Solomon parity. Source
 * shards are emitted immediately; parity only costs latency when a shard is actually lost.
 *
 * Not thread-safe: one sender thread owns it.
 */
class VideoStreamPacketizer(
    val streamID: ByteArray,
    private val config: LiveVideoConfig,
    private val fec: LiveVideoFecPolicy,
    private val maxEntriesRegion: Int = VideoBurstPacket.MAX_ENTRIES_REGION
) {
    companion object {
        /** Do not open a fragment that would carry less than this many bytes. */
        private const val MIN_FRAGMENT_BYTES = 24
    }

    private var sequence = 0
    private var block = 0
    private val openEntries = mutableListOf<LiveVideoEntry>()
    private var openSize = 0
    private var openSinceMs = 0L
    private val blockSymbols = mutableListOf<ByteArray>()
    private var blockSinceMs = 0L
    private var blockHasKeyframe = false
    private val unitSequence = IntArray(LiveVideoTrack.entries.size)
    private val unitCount = IntArray(LiveVideoTrack.entries.size)

    var packetCount: Int = 0
        private set
    var droppedUnits: Int = 0
        private set
    var parityPackets: Int = 0
        private set

    fun unitsEmitted(track: LiveVideoTrack): Int = unitCount[track.value]

    fun start(): List<ByteArray> = listOfNotNull(packet(VideoBurstPacket.Kind.Start(config)))

    fun add(unit: LiveMediaUnit, nowMs: Long): List<ByteArray> {
        val output = mutableListOf<ByteArray>()
        if (unit.data.isEmpty()) return output
        val bodyBudget = maxEntriesRegion - LiveVideoEntry.HEADER_SIZE
        if (unit.keyframe && unit.track == LiveVideoTrack.VIDEO) {
            // Give each keyframe its own, more strongly protected block, announced by a
            // repeated START so a viewer joining mid-stream can configure its decoder.
            output += flushShard(nowMs)
            output += closeBlock()
            output += start()
        }
        val firstCapacity = maxEntriesRegion - openSize - LiveVideoEntry.HEADER_SIZE
        val useOpenShard = openEntries.isNotEmpty() &&
            firstCapacity >= minOf(unit.data.size, MIN_FRAGMENT_BYTES)
        if (openEntries.isNotEmpty() && !useOpenShard) output += flushShard(nowMs)
        val firstChunk = if (useOpenShard) minOf(firstCapacity, unit.data.size) else minOf(bodyBudget, unit.data.size)
        val remaining = unit.data.size - firstChunk
        val fragmentCount = 1 + (remaining + bodyBudget - 1) / bodyBudget
        if (fragmentCount > 0xFF) {
            droppedUnits++
            return output
        }
        val seq = unitSequence[unit.track.value]
        unitSequence[unit.track.value] = (seq + 1) and 0xFFFF
        unitCount[unit.track.value] = (unitCount[unit.track.value] + 1).coerceAtMost(0xFFFF)
        val pts = unit.ptsMs.coerceIn(0L, 0xFFFF_FFFFL)
        var offset = 0
        for (fragment in 0 until fragmentCount) {
            val chunk = if (fragment == 0) firstChunk else minOf(bodyBudget, unit.data.size - offset)
            val entry = LiveVideoEntry(
                unit.track,
                unit.keyframe,
                seq,
                pts,
                fragment,
                fragmentCount,
                unit.data.copyOfRange(offset, offset + chunk)
            )
            offset += chunk
            if (openEntries.isEmpty()) openSinceMs = nowMs
            openEntries += entry
            openSize += entry.encodedSize
            if (unit.keyframe) blockHasKeyframe = true
            if (maxEntriesRegion - openSize < LiveVideoEntry.HEADER_SIZE + MIN_FRAGMENT_BYTES) {
                output += flushShard(nowMs)
            }
        }
        return output
    }

    /** Emits shards and parity whose latency deadlines have passed. */
    fun tick(nowMs: Long): List<ByteArray> {
        val output = mutableListOf<ByteArray>()
        if (openEntries.isNotEmpty() && nowMs - openSinceMs >= fec.shardFlushMs) output += flushShard(nowMs)
        if (blockSymbols.isNotEmpty() && nowMs - blockSinceMs >= fec.blockWindowMs) output += closeBlock()
        return output
    }

    /**
     * Flushes and emits END. END is repeated ([endCopies]) because a lost END would leave
     * viewers waiting out their reconnect window; receivers treat the copies as duplicates.
     */
    fun finish(durationMs: Long, nowMs: Long, endCopies: Int = 3): List<ByteArray> {
        val output = mutableListOf<ByteArray>()
        output += flushShard(nowMs)
        output += closeBlock()
        val end = VideoBurstPacket.Kind.End(
            durationMs = durationMs.coerceIn(0L, 0xFFFF_FFFFL),
            videoUnits = unitCount[LiveVideoTrack.VIDEO.value],
            audioUnits = unitCount[LiveVideoTrack.AUDIO.value],
            packets = packetCount.coerceAtMost(0xFFFF)
        )
        repeat(endCopies.coerceAtLeast(1)) { output += listOfNotNull(packet(end)) }
        return output
    }

    fun cancel(): ByteArray? = packet(VideoBurstPacket.Kind.Canceled)

    private fun flushShard(nowMs: Long): List<ByteArray> {
        if (openEntries.isEmpty()) return emptyList()
        val region = VideoBurstPacket.encodeEntries(openEntries)
        openEntries.clear()
        openSize = 0
        if (blockSymbols.isEmpty()) blockSinceMs = nowMs
        val index = blockSymbols.size
        blockSymbols += region
        val output = mutableListOf<ByteArray>()
        packet(VideoBurstPacket.Kind.Data(block, index, region))?.let(output::add)
        if (blockSymbols.size >= fec.maxSourceShards.coerceAtMost(VideoBurstPacket.MAX_BLOCK_SOURCE_SHARDS)) {
            output += closeBlock()
        }
        return output
    }

    private fun closeBlock(): List<ByteArray> {
        if (blockSymbols.isEmpty()) return emptyList()
        val k = blockSymbols.size
        val r = fec.parityCount(k, blockHasKeyframe)
        val output = mutableListOf<ByteArray>()
        if (r > 0) {
            val length = 2 + blockSymbols.maxOf { it.size }
            val symbols = blockSymbols.map { LiveVideoSymbols.symbol(it, length) }
            ReedSolomonErasure.encode(symbols, r).forEachIndexed { row, parity ->
                packet(VideoBurstPacket.Kind.Parity(block, row, k, r, parity))?.let {
                    output += it
                    parityPackets++
                }
            }
        }
        blockSymbols.clear()
        blockHasKeyframe = false
        block = (block + 1) and 0xFFFF
        return output
    }

    private fun packet(kind: VideoBurstPacket.Kind): ByteArray? {
        val encoded = VideoBurstPacket.create(streamID, sequence, kind)?.encode() ?: return null
        sequence = (sequence + 1) and 0xFFFF
        packetCount++
        return encoded
    }
}
