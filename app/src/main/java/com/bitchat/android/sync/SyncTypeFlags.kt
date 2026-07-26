package com.bitchat.android.sync

import com.bitchat.android.protocol.MessageType

/**
 * Packet types covered by one REQUEST_SYNC filter.
 *
 * The wire format matches iOS: a minimal little-endian bitfield. Unknown bits
 * are discarded because they do not map to a packet type this build can serve.
 */
class SyncTypeFlags private constructor(val rawValue: Long) {
    fun contains(type: MessageType): Boolean {
        val bit = bitIndex(type) ?: return false
        return (rawValue and (1L shl bit)) != 0L
    }

    fun union(other: SyncTypeFlags): SyncTypeFlags =
        fromRawValue(rawValue or other.rawValue)

    fun encoded(): ByteArray? {
        if (rawValue == 0L) return null
        var remaining = rawValue
        val bytes = mutableListOf<Byte>()
        while (remaining != 0L && bytes.size < Long.SIZE_BYTES) {
            bytes += remaining.toByte()
            remaining = remaining ushr 8
        }
        return bytes.toByteArray()
    }

    override fun equals(other: Any?): Boolean =
        other is SyncTypeFlags && rawValue == other.rawValue

    override fun hashCode(): Int = rawValue.hashCode()

    companion object {
        val PUBLIC_MESSAGES = of(MessageType.ANNOUNCE, MessageType.MESSAGE)
        val GROUP_MESSAGE = of(MessageType.GROUP_MESSAGE)

        fun of(vararg types: MessageType): SyncTypeFlags {
            var raw = 0L
            types.forEach { type ->
                bitIndex(type)?.let { raw = raw or (1L shl it) }
            }
            return fromRawValue(raw)
        }

        fun decode(data: ByteArray): SyncTypeFlags? {
            if (data.size !in 1..Long.SIZE_BYTES) return null
            var raw = 0L
            data.forEachIndexed { index, byte ->
                raw = raw or ((byte.toLong() and 0xffL) shl (index * 8))
            }
            return fromRawValue(raw)
        }

        private fun fromRawValue(rawValue: Long): SyncTypeFlags {
            var knownMask = 0L
            MessageType.entries.forEach { type ->
                bitIndex(type)?.let { knownMask = knownMask or (1L shl it) }
            }
            return SyncTypeFlags(rawValue and knownMask)
        }

        private fun bitIndex(type: MessageType): Int? = when (type) {
            MessageType.ANNOUNCE -> 0
            MessageType.MESSAGE -> 1
            MessageType.LEAVE -> 2
            MessageType.NOISE_HANDSHAKE -> 3
            MessageType.NOISE_ENCRYPTED -> 4
            MessageType.FRAGMENT -> 5
            MessageType.REQUEST_SYNC -> 6
            MessageType.FILE_TRANSFER -> 7
            MessageType.GROUP_MESSAGE -> 10
        }
    }
}
