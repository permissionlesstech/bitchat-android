package com.bitchat.android.features.video

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** Codec identifiers carried by a live video START packet. */
enum class LiveVideoCodec(val value: Int) {
    /** H.264 Constrained Baseline, Annex B access units; every IDR carries SPS and PPS in-band. */
    H264_ANNEX_B(0x01);

    companion object {
        fun fromValue(value: Int): LiveVideoCodec? = entries.firstOrNull { it.value == value }
    }
}

/** Optional audio track codec carried by a live video START packet. */
enum class LiveVideoAudioCodec(val value: Int) {
    NONE(0x00),
    /** Raw (ADTS-less) AAC-LC access units, 16 kHz mono; the same encoding as live voice v1. */
    AAC_LC_16K_MONO(0x01);

    companion object {
        fun fromValue(value: Int): LiveVideoAudioCodec? = entries.firstOrNull { it.value == value }
    }
}

/** Stream parameters announced at sequence 0 and repeated before every keyframe. */
data class LiveVideoConfig(
    val videoCodec: LiveVideoCodec,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val audioCodec: LiveVideoAudioCodec
) {
    val hasAudio: Boolean get() = audioCodec != LiveVideoAudioCodec.NONE
}

/** Media track of one entry inside a data shard. */
enum class LiveVideoTrack(val value: Int) {
    VIDEO(0),
    AUDIO(1);

    companion object {
        fun fromValue(value: Int): LiveVideoTrack? = entries.firstOrNull { it.value == value }
    }
}

/**
 * One fragment (or the whole) of a media unit inside a data shard.
 *
 * `[flags: UInt8][unitSeq: UInt16][ptsMs: UInt32][fragIndex: UInt8][fragCount: UInt8][length: UInt16][bytes]`
 */
class LiveVideoEntry(
    val track: LiveVideoTrack,
    val keyframe: Boolean,
    val unitSequence: Int,
    val ptsMs: Long,
    val fragmentIndex: Int,
    val fragmentCount: Int,
    val bytes: ByteArray
) {
    val encodedSize: Int get() = HEADER_SIZE + bytes.size

    override fun equals(other: Any?): Boolean =
        other is LiveVideoEntry && track == other.track && keyframe == other.keyframe &&
            unitSequence == other.unitSequence && ptsMs == other.ptsMs &&
            fragmentIndex == other.fragmentIndex && fragmentCount == other.fragmentCount &&
            bytes.contentEquals(other.bytes)

    override fun hashCode(): Int {
        var result = track.hashCode()
        result = 31 * result + unitSequence
        result = 31 * result + fragmentIndex
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    companion object {
        const val HEADER_SIZE = 11
        private const val FLAG_TRACK_MASK = 0x03
        private const val FLAG_KEYFRAME = 0x04

        fun isValid(entry: LiveVideoEntry): Boolean =
            entry.unitSequence in 0..0xFFFF &&
                entry.ptsMs in 0..0xFFFF_FFFFL &&
                entry.fragmentCount in 1..0xFF &&
                entry.fragmentIndex in 0 until entry.fragmentCount &&
                entry.bytes.isNotEmpty() && entry.bytes.size <= 0xFFFF &&
                (!entry.keyframe || entry.track == LiveVideoTrack.VIDEO)

        internal fun encodeAll(entries: List<LiveVideoEntry>, output: ByteArrayOutputStream) {
            entries.forEach { entry ->
                val flags = entry.track.value or (if (entry.keyframe) FLAG_KEYFRAME else 0)
                output.write(flags)
                output.writeU16(entry.unitSequence)
                output.writeU32(entry.ptsMs)
                output.write(entry.fragmentIndex)
                output.write(entry.fragmentCount)
                output.writeU16(entry.bytes.size)
                output.write(entry.bytes)
            }
        }

        /** Parses an entries region; returns null when any entry is malformed. */
        fun decodeAll(data: ByteArray, offset: Int = 0, end: Int = data.size): List<LiveVideoEntry>? {
            val entries = mutableListOf<LiveVideoEntry>()
            var cursor = offset
            while (cursor < end) {
                if (end - cursor < HEADER_SIZE) return null
                val flags = data.u8(cursor)
                if (flags and (FLAG_TRACK_MASK or FLAG_KEYFRAME).inv() != 0) return null
                val track = LiveVideoTrack.fromValue(flags and FLAG_TRACK_MASK) ?: return null
                val keyframe = flags and FLAG_KEYFRAME != 0
                val sequence = data.u16(cursor + 1)
                val pts = data.u32(cursor + 3)
                val fragmentIndex = data.u8(cursor + 7)
                val fragmentCount = data.u8(cursor + 8)
                val length = data.u16(cursor + 9)
                cursor += HEADER_SIZE
                if (length == 0 || end - cursor < length) return null
                val entry = LiveVideoEntry(
                    track,
                    keyframe,
                    sequence,
                    pts,
                    fragmentIndex,
                    fragmentCount,
                    data.copyOfRange(cursor, cursor + length)
                )
                if (!isValid(entry)) return null
                entries += entry
                cursor += length
            }
            return entries.takeIf { it.isNotEmpty() }
        }
    }
}

/**
 * One live video packet.
 *
 * Wire format: `[streamID: 8][seq: UInt16 BE][kind: UInt8][body...]`. See docs/LIVE_VIDEO_V1.md.
 */
class VideoBurstPacket private constructor(
    val streamID: ByteArray,
    val sequence: Int,
    val kind: Kind
) {
    sealed interface Kind {
        data class Start(val config: LiveVideoConfig) : Kind

        /** Systematic FEC source shard: [entriesRegion] is the coded symbol body. */
        class Data(val block: Int, val index: Int, val entriesRegion: ByteArray) : Kind {
            override fun equals(other: Any?): Boolean =
                other is Data && block == other.block && index == other.index &&
                    entriesRegion.contentEquals(other.entriesRegion)

            override fun hashCode(): Int = (block * 31 + index) * 31 + entriesRegion.contentHashCode()
        }

        /** Reed-Solomon parity shard over the `k` source symbols of [block]. */
        class Parity(
            val block: Int,
            val parityIndex: Int,
            val sourceCount: Int,
            val parityCount: Int,
            val symbol: ByteArray
        ) : Kind {
            override fun equals(other: Any?): Boolean =
                other is Parity && block == other.block && parityIndex == other.parityIndex &&
                    sourceCount == other.sourceCount && parityCount == other.parityCount &&
                    symbol.contentEquals(other.symbol)

            override fun hashCode(): Int = (block * 31 + parityIndex) * 31 + symbol.contentHashCode()
        }

        data class End(
            val durationMs: Long,
            val videoUnits: Int,
            val audioUnits: Int,
            val packets: Int
        ) : Kind

        data object Canceled : Kind
    }

    fun encode(): ByteArray {
        val output = ByteArrayOutputStream(HEADER_SIZE + 64)
        output.write(streamID)
        output.writeU16(sequence)
        when (val packetKind = kind) {
            is Kind.Start -> {
                val config = packetKind.config
                output.write(KIND_START)
                output.write(VERSION)
                output.write(config.videoCodec.value)
                output.writeU16(config.width)
                output.writeU16(config.height)
                output.write(config.fps)
                output.writeU16(config.bitrateKbps)
                output.write(config.audioCodec.value)
            }
            is Kind.Data -> {
                output.write(KIND_DATA)
                output.writeU16(packetKind.block)
                output.write(packetKind.index)
                output.write(packetKind.entriesRegion)
            }
            is Kind.Parity -> {
                output.write(KIND_PARITY)
                output.writeU16(packetKind.block)
                output.write(packetKind.parityIndex)
                output.write(packetKind.sourceCount)
                output.write(packetKind.parityCount)
                output.writeU16(packetKind.symbol.size)
                output.write(packetKind.symbol)
            }
            is Kind.End -> {
                output.write(KIND_END)
                output.writeU32(packetKind.durationMs)
                output.writeU16(packetKind.videoUnits)
                output.writeU16(packetKind.audioUnits)
                output.writeU16(packetKind.packets)
            }
            Kind.Canceled -> output.write(KIND_CANCELED)
        }
        return output.toByteArray()
    }

    companion object {
        const val VERSION = 1
        const val STREAM_ID_SIZE = 8
        const val HEADER_SIZE = STREAM_ID_SIZE + 2 + 1
        const val DATA_HEADER_SIZE = HEADER_SIZE + 3
        const val PARITY_HEADER_SIZE = HEADER_SIZE + 7

        /**
         * Whole-packet ceiling. Keeps a Noise-wrapped, signed, source-routed (up to four hops)
         * live video packet under the 512-byte BLE fragmentation threshold.
         */
        const val MAX_PACKET_BYTES = 352

        /** Largest entries region: a parity symbol (`[len: UInt16][region]`) must fit too. */
        const val MAX_ENTRIES_REGION = MAX_PACKET_BYTES - PARITY_HEADER_SIZE - 2
        const val MAX_BLOCK_SOURCE_SHARDS = 32
        const val MAX_BLOCK_PARITY_SHARDS = 16
        const val MAX_DIMENSION = 1_920
        const val MAX_FPS = 60

        private const val KIND_DATA = 0x00
        private const val KIND_START = 0x01
        private const val KIND_END = 0x02
        private const val KIND_PARITY = 0x03
        private const val KIND_CANCELED = 0x04
        private val random = SecureRandom()

        fun create(streamID: ByteArray, sequence: Int, kind: Kind): VideoBurstPacket? {
            if (streamID.size != STREAM_ID_SIZE || sequence !in 0..0xFFFF) return null
            val valid = when (kind) {
                is Kind.Start -> kind.config.width in 16..MAX_DIMENSION &&
                    kind.config.height in 16..MAX_DIMENSION &&
                    kind.config.width % 2 == 0 && kind.config.height % 2 == 0 &&
                    kind.config.fps in 1..MAX_FPS &&
                    kind.config.bitrateKbps in 1..0xFFFF
                is Kind.Data -> kind.block in 0..0xFFFF &&
                    kind.index in 0 until MAX_BLOCK_SOURCE_SHARDS &&
                    kind.entriesRegion.size in 1..MAX_ENTRIES_REGION &&
                    LiveVideoEntry.decodeAll(kind.entriesRegion) != null
                is Kind.Parity -> kind.block in 0..0xFFFF &&
                    kind.sourceCount in 1..MAX_BLOCK_SOURCE_SHARDS &&
                    kind.parityCount in 1..MAX_BLOCK_PARITY_SHARDS &&
                    kind.parityIndex in 0 until kind.parityCount &&
                    kind.symbol.size in 3..(MAX_ENTRIES_REGION + 2)
                is Kind.End -> kind.durationMs in 0..0xFFFF_FFFFL &&
                    kind.videoUnits in 0..0xFFFF &&
                    kind.audioUnits in 0..0xFFFF &&
                    kind.packets in 0..0xFFFF
                Kind.Canceled -> true
            }
            if (!valid) return null
            return VideoBurstPacket(streamID.copyOf(), sequence, kind)
        }

        fun decode(data: ByteArray): VideoBurstPacket? {
            if (data.size < HEADER_SIZE || data.size > MAX_PACKET_BYTES) return null
            val streamID = data.copyOfRange(0, STREAM_ID_SIZE)
            val sequence = data.u16(STREAM_ID_SIZE)
            val body = HEADER_SIZE
            val kind: Kind = when (data.u8(STREAM_ID_SIZE + 2)) {
                KIND_START -> {
                    if (data.size != body + 10 || data.u8(body) != VERSION) return null
                    val codec = LiveVideoCodec.fromValue(data.u8(body + 1)) ?: return null
                    val audio = LiveVideoAudioCodec.fromValue(data.u8(body + 9)) ?: return null
                    Kind.Start(
                        LiveVideoConfig(
                            videoCodec = codec,
                            width = data.u16(body + 2),
                            height = data.u16(body + 4),
                            fps = data.u8(body + 6),
                            bitrateKbps = data.u16(body + 7),
                            audioCodec = audio
                        )
                    )
                }
                KIND_DATA -> {
                    if (data.size <= body + 3) return null
                    Kind.Data(data.u16(body), data.u8(body + 2), data.copyOfRange(body + 3, data.size))
                }
                KIND_PARITY -> {
                    if (data.size < body + 7) return null
                    val length = data.u16(body + 5)
                    if (data.size != body + 7 + length) return null
                    Kind.Parity(
                        block = data.u16(body),
                        parityIndex = data.u8(body + 2),
                        sourceCount = data.u8(body + 3),
                        parityCount = data.u8(body + 4),
                        symbol = data.copyOfRange(body + 7, data.size)
                    )
                }
                KIND_END -> {
                    if (data.size != body + 10) return null
                    Kind.End(
                        durationMs = data.u32(body),
                        videoUnits = data.u16(body + 4),
                        audioUnits = data.u16(body + 6),
                        packets = data.u16(body + 8)
                    )
                }
                KIND_CANCELED -> {
                    if (data.size != body) return null
                    Kind.Canceled
                }
                else -> return null
            }
            return create(streamID, sequence, kind)
        }

        fun makeStreamID(): ByteArray = ByteArray(STREAM_ID_SIZE).also(random::nextBytes)

        fun streamIDHex(streamID: ByteArray): String =
            streamID.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }

        /** Encodes an entries region; the caller guarantees it fits [MAX_ENTRIES_REGION]. */
        fun encodeEntries(entries: List<LiveVideoEntry>): ByteArray {
            val output = ByteArrayOutputStream(entries.sumOf { it.encodedSize })
            LiveVideoEntry.encodeAll(entries, output)
            return output.toByteArray()
        }
    }
}

internal fun ByteArrayOutputStream.writeU16(value: Int) {
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}

internal fun ByteArrayOutputStream.writeU32(value: Long) {
    write(((value ushr 24) and 0xFF).toInt())
    write(((value ushr 16) and 0xFF).toInt())
    write(((value ushr 8) and 0xFF).toInt())
    write((value and 0xFF).toInt())
}

internal fun ByteArray.u8(offset: Int): Int = this[offset].toInt() and 0xFF

internal fun ByteArray.u16(offset: Int): Int = (u8(offset) shl 8) or u8(offset + 1)

internal fun ByteArray.u32(offset: Int): Long =
    (u8(offset).toLong() shl 24) or (u8(offset + 1).toLong() shl 16) or
        (u8(offset + 2).toLong() shl 8) or u8(offset + 3).toLong()
