package com.bitchat.android.features.voice

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

/** iOS-compatible codec identifier carried by a live push-to-talk START packet. */
enum class VoiceBurstCodec(val value: UByte) {
    AAC_LC_16K_MONO(0x01u);

    companion object {
        fun fromValue(value: UByte): VoiceBurstCodec? = entries.firstOrNull { it.value == value }
    }
}

/**
 * One live push-to-talk packet.
 *
 * Wire format (shared with iOS):
 * `[burstID: 8][seq: UInt16 BE][flags: UInt8][payload...]`.
 */
class VoiceBurstPacket private constructor(
    val burstID: ByteArray,
    val sequence: Int,
    val kind: Kind
) {
    sealed interface Kind {
        data class Start(val codec: VoiceBurstCodec) : Kind
        class Frames(val frames: List<ByteArray>) : Kind {
            override fun equals(other: Any?): Boolean =
                other is Frames && frames.size == other.frames.size &&
                    frames.indices.all { frames[it].contentEquals(other.frames[it]) }

            override fun hashCode(): Int = frames.fold(1) { acc, frame -> 31 * acc + frame.contentHashCode() }
        }
        data class End(val totalDataPackets: Int, val durationMs: Long) : Kind
        data object Canceled : Kind
    }

    fun encode(): ByteArray {
        val output = ByteArrayOutputStream(HEADER_SIZE + 16)
        output.write(burstID)
        output.write((sequence ushr 8) and 0xFF)
        output.write(sequence and 0xFF)
        when (val packetKind = kind) {
            is Kind.Start -> {
                output.write(FLAG_START)
                output.write(packetKind.codec.value.toInt())
            }
            is Kind.Frames -> {
                output.write(0)
                packetKind.frames.forEach { frame ->
                    output.write((frame.size ushr 8) and 0xFF)
                    output.write(frame.size and 0xFF)
                    output.write(frame)
                }
            }
            is Kind.End -> {
                output.write(FLAG_END)
                output.write((packetKind.totalDataPackets ushr 8) and 0xFF)
                output.write(packetKind.totalDataPackets and 0xFF)
                output.write(((packetKind.durationMs ushr 24) and 0xFF).toInt())
                output.write(((packetKind.durationMs ushr 16) and 0xFF).toInt())
                output.write(((packetKind.durationMs ushr 8) and 0xFF).toInt())
                output.write((packetKind.durationMs and 0xFF).toInt())
            }
            Kind.Canceled -> output.write(FLAG_CANCELED)
        }
        return output.toByteArray()
    }

    companion object {
        const val BURST_ID_SIZE = 8
        const val HEADER_SIZE = BURST_ID_SIZE + 2 + 1
        const val MAX_FRAMES_PER_PACKET = 8
        const val MAX_CONTENT_BYTES = 210
        private const val FLAG_START = 0x01
        private const val FLAG_END = 0x02
        private const val FLAG_CANCELED = 0x04
        private val random = SecureRandom()

        fun create(burstID: ByteArray, sequence: Int, kind: Kind): VoiceBurstPacket? {
            if (burstID.size != BURST_ID_SIZE || sequence !in 0..0xFFFF) return null
            when (kind) {
                is Kind.Frames -> if (
                    kind.frames.isEmpty() ||
                    kind.frames.size > MAX_FRAMES_PER_PACKET ||
                    kind.frames.any { it.isEmpty() || it.size > 0xFFFF }
                ) return null
                is Kind.End -> if (
                    kind.totalDataPackets !in 0..0xFFFF ||
                    kind.durationMs !in 0..0xFFFF_FFFFL
                ) return null
                else -> Unit
            }
            return VoiceBurstPacket(burstID.copyOf(), sequence, kind)
        }

        fun decode(data: ByteArray): VoiceBurstPacket? {
            if (data.size < HEADER_SIZE) return null
            val burstID = data.copyOfRange(0, BURST_ID_SIZE)
            val sequence = ((data[BURST_ID_SIZE].toInt() and 0xFF) shl 8) or
                (data[BURST_ID_SIZE + 1].toInt() and 0xFF)
            val flags = data[BURST_ID_SIZE + 2].toInt() and 0xFF
            val offset = HEADER_SIZE
            val kind: Kind = when (flags) {
                FLAG_START -> {
                    if (offset >= data.size) return null
                    val codec = VoiceBurstCodec.fromValue(data[offset].toUByte()) ?: return null
                    Kind.Start(codec)
                }
                FLAG_END -> {
                    if (data.size - offset < 6) return null
                    val total = ((data[offset].toInt() and 0xFF) shl 8) or
                        (data[offset + 1].toInt() and 0xFF)
                    var duration = 0L
                    repeat(4) { index ->
                        duration = (duration shl 8) or (data[offset + 2 + index].toLong() and 0xFF)
                    }
                    Kind.End(total, duration)
                }
                FLAG_CANCELED -> Kind.Canceled
                0 -> {
                    val frames = mutableListOf<ByteArray>()
                    var cursor = offset
                    while (cursor < data.size) {
                        if (data.size - cursor < 2 || frames.size >= MAX_FRAMES_PER_PACKET) return null
                        val length = ((data[cursor].toInt() and 0xFF) shl 8) or
                            (data[cursor + 1].toInt() and 0xFF)
                        cursor += 2
                        if (length <= 0 || data.size - cursor < length) {
                            return null
                        }
                        frames += data.copyOfRange(cursor, cursor + length)
                        cursor += length
                    }
                    if (frames.isEmpty()) return null
                    Kind.Frames(frames)
                }
                else -> return null
            }
            return create(burstID, sequence, kind)
        }

        fun makeBurstID(): ByteArray = ByteArray(BURST_ID_SIZE).also(random::nextBytes)

        fun burstIDHex(burstID: ByteArray): String =
            burstID.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xFF) }
    }
}

/** Greedy packetizer constrained so one Noise-wrapped frame stays out of fragmentation. */
class VoiceBurstPacketizer(
    val burstID: ByteArray,
    private val budget: Int = VoiceBurstPacket.MAX_CONTENT_BYTES
) {
    private val pendingFrames = mutableListOf<ByteArray>()
    private var pendingSize = 0

    var nextSequence: Int = 1
        private set
    var dataPacketCount: Int = 0
        private set
    var droppedFrameCount: Int = 0
        private set

    fun add(frame: ByteArray): List<ByteArray> {
        val frameCost = 2 + frame.size
        if (VoiceBurstPacket.HEADER_SIZE + frameCost > budget) {
            droppedFrameCount++
            return emptyList()
        }
        val output = mutableListOf<ByteArray>()
        if (
            pendingFrames.isNotEmpty() &&
            (VoiceBurstPacket.HEADER_SIZE + pendingSize + frameCost > budget ||
                pendingFrames.size >= VoiceBurstPacket.MAX_FRAMES_PER_PACKET)
        ) {
            output += flush()
        }
        pendingFrames += frame.copyOf()
        pendingSize += frameCost
        return output
    }

    fun flush(): List<ByteArray> {
        if (pendingFrames.isEmpty()) return emptyList()
        val packet = VoiceBurstPacket.create(
            burstID,
            nextSequence,
            VoiceBurstPacket.Kind.Frames(pendingFrames.map(ByteArray::copyOf))
        ) ?: run {
            pendingFrames.clear()
            pendingSize = 0
            return emptyList()
        }
        pendingFrames.clear()
        pendingSize = 0
        nextSequence = (nextSequence + 1) and 0xFFFF
        dataPacketCount = (dataPacketCount + 1).coerceAtMost(0xFFFF)
        return listOf(packet.encode())
    }
}

/** Adds a seven-byte ADTS header to an ADTS-less AAC-LC/16 kHz/mono access unit. */
object AdtsFramer {
    fun frame(payload: ByteArray): ByteArray {
        val frameLength = payload.size + 7
        require(frameLength <= 0x1FFF) { "AAC frame is too large for ADTS" }
        return ByteArray(frameLength).also { output ->
            output[0] = 0xFF.toByte()
            output[1] = 0xF1.toByte()
            output[2] = 0x60.toByte() // AAC-LC, 16 kHz frequency index, mono channel config high bit
            output[3] = (0x40 or ((frameLength ushr 11) and 0x03)).toByte()
            output[4] = ((frameLength ushr 3) and 0xFF).toByte()
            output[5] = (((frameLength and 0x07) shl 5) or 0x1F).toByte()
            output[6] = 0xFC.toByte()
            payload.copyInto(output, destinationOffset = 7)
        }
    }
}
