package com.bitchat.android.sync

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.util.Hashing
import com.bitchat.android.util.toHexString

/**
 * Deterministic packet ID helper for sync purposes.
 * Uses SHA-256 over a canonical subset of packet fields:
 * [type | senderID | timestamp | payload] to generate a stable ID.
 * Returns a 16-byte (128-bit) truncated hash for compactness.
 */
object PacketIdUtil {
    fun computeIdBytes(packet: BitchatPacket): ByteArray {
        val data = ByteArray(1 + packet.senderID.size + 8 + packet.payload.size)
        var offset = 0
        data[offset++] = packet.type.toByte()
        packet.senderID.copyInto(data, destinationOffset = offset)
        offset += packet.senderID.size

        val ts = packet.timestamp.toLong()
        for (i in 7 downTo 0) {
            data[offset++] = ((ts ushr (i * 8)) and 0xFF).toByte()
        }
        packet.payload.copyInto(data, destinationOffset = offset)

        return Hashing.sha256(data).copyOf(16) // 128-bit ID
    }

    fun computeIdHex(packet: BitchatPacket): String {
        return computeIdBytes(packet).toHexString()
    }
}
