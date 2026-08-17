package com.bitchat.android.sync

import com.bitchat.android.protocol.MessageType

/** Compact little-endian bitfield matching iOS SyncTypeFlags. */
@JvmInline
value class SyncTypeFlags private constructor(val rawValue: ULong) {
    companion object {
        private const val KNOWN_TYPE_MASK: ULong = 0xffu

        val ANNOUNCE = fromMessageTypes(MessageType.ANNOUNCE)
        val MESSAGE = fromMessageTypes(MessageType.MESSAGE)
        val FRAGMENT = fromMessageTypes(MessageType.FRAGMENT)
        val FILE_TRANSFER = fromMessageTypes(MessageType.FILE_TRANSFER)
        val PUBLIC_MESSAGES = fromMessageTypes(MessageType.ANNOUNCE, MessageType.MESSAGE)
        val FRAGMENTS_AND_FILES = fromMessageTypes(MessageType.FRAGMENT, MessageType.FILE_TRANSFER)

        fun fromRawValue(rawValue: ULong): SyncTypeFlags = SyncTypeFlags(rawValue and KNOWN_TYPE_MASK)

        fun fromMessageTypes(vararg types: MessageType): SyncTypeFlags {
            var rawValue = 0uL
            types.forEach { type ->
                bitIndex(type)?.let { bit -> rawValue = rawValue or (1uL shl bit) }
            }
            return fromRawValue(rawValue)
        }

        fun decode(data: ByteArray): SyncTypeFlags? {
            if (data.size !in 1..8) return null
            var rawValue = 0uL
            data.forEachIndexed { index, byte ->
                rawValue = rawValue or (byte.toUByte().toULong() shl (index * 8))
            }
            return fromRawValue(rawValue)
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
            MessageType.COURIER_ENVELOPE,
            MessageType.VOICE_FRAME -> null
        }
    }

    fun contains(type: MessageType): Boolean {
        val bit = bitIndex(type) ?: return false
        return (rawValue and (1uL shl bit)) != 0uL
    }

    fun union(other: SyncTypeFlags): SyncTypeFlags = fromRawValue(rawValue or other.rawValue)

    fun encode(): ByteArray? {
        if (rawValue == 0uL) return null
        var remaining = rawValue
        val bytes = ArrayList<Byte>(8)
        while (remaining != 0uL && bytes.size < 8) {
            bytes += (remaining and 0xffu).toByte()
            remaining = remaining shr 8
        }
        return bytes.toByteArray()
    }
}
