package com.bitchat.android.sync

import com.bitchat.android.protocol.BitchatPacket
import java.security.MessageDigest

/**
 * Deterministic packet ID helper for sync purposes.
 * Uses SHA-256 over a canonical subset of packet fields:
 * [type | senderID | timestamp | payload] to generate a stable ID.
 * Returns a 16-byte (128-bit) truncated hash for compactness.
 */
object PacketIdUtil {
    fun computeIdBytes(packet: BitchatPacket): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(packet.type.toByte())
        md.update(packet.senderID)
        // Timestamp as 8 bytes big-endian
        val ts = packet.timestamp.toLong()
        for (i in 7 downTo 0) {
            md.update(((ts ushr (i * 8)) and 0xFF).toByte())
        }
        md.update(packet.payload)
        val digest = md.digest()
        return digest.copyOf(16) // 128-bit ID
    }

    fun computeIdHex(packet: BitchatPacket): String {
        return computeIdBytes(packet).joinToString("") { b -> "%02x".format(b) }
    }
}

