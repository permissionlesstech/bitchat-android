package com.bitchat.android.sync

import com.bitchat.android.protocol.MessageType

/**
 * Little-endian variable-width bitfield carried in REQUEST_SYNC TLV 0x04.
 * The bit-to-message mapping is shared with iOS.
 */
data class SyncTypeFlags(val rawValue: ULong) {
    fun contains(type: MessageType): Boolean {
        val bit = bitIndex(type) ?: return false
        return (rawValue and (1uL shl bit)) != 0uL
    }

    fun union(other: SyncTypeFlags): SyncTypeFlags =
        SyncTypeFlags((rawValue or other.rawValue) and KNOWN_TYPE_MASK)

    fun encode(): ByteArray? {
        if (rawValue == 0uL) return null
        var value = rawValue
        val output = ArrayList<Byte>()
        while (value != 0uL && output.size < 8) {
            output += (value and 0xFFuL).toByte()
            value = value shr 8
        }
        return output.toByteArray().takeIf { it.isNotEmpty() }
    }

    companion object {
        val ANNOUNCE = of(MessageType.ANNOUNCE)
        val MESSAGE = of(MessageType.MESSAGE)
        val BOARD = of(MessageType.BOARD_POST)
        val PUBLIC_MESSAGES = of(MessageType.ANNOUNCE, MessageType.MESSAGE)

        private val KNOWN_TYPE_MASK: ULong = MessageType.entries.fold(0uL) { mask, type ->
            val bit = bitIndex(type)
            if (bit == null) mask else mask or (1uL shl bit)
        }

        fun of(vararg types: MessageType): SyncTypeFlags =
            SyncTypeFlags(types.fold(0uL) { mask, type ->
                val bit = bitIndex(type)
                if (bit == null) mask else mask or (1uL shl bit)
            })

        fun decode(data: ByteArray): SyncTypeFlags? {
            if (data.size !in 1..8) return null
            var value = 0uL
            data.forEachIndexed { index, byte ->
                value = value or ((byte.toULong() and 0xFFuL) shl (index * 8))
            }
            return SyncTypeFlags(value and KNOWN_TYPE_MASK)
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
            MessageType.BOARD_POST -> 8
        }
    }
}
