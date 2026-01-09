package com.bitchat.android.services.meshgraph

import android.util.Log

/**
 * Gossip TLV helpers for embedding direct neighbor peer IDs in ANNOUNCE payloads.
 * Uses compact TLV: [type=0x04][len=1 byte][value=N*8 bytes of peerIDs]
 */
object GossipTLV {
    // TLV type for a compact list of direct neighbor peerIDs (each 8 bytes)
    const val DIRECT_NEIGHBORS_TYPE: UByte = 0x04u

    /**
     * Encode up to 10 unique peerIDs (hex string up to 16 chars) as TLV value.
     */
    fun encodeNeighbors(peerIDs: List<String>): ByteArray {
        val unique = peerIDs.distinct().take(10)
        val valueBytes = unique.flatMap { id -> hexStringPeerIdTo8Bytes(id).toList() }.toByteArray()
        if (valueBytes.size > 255) {
            // Safety check, though 10*8 = 80 bytes, so well under 255
            Log.w("GossipTLV", "Neighbors value exceeds 255, truncating")
        }
        return byteArrayOf(DIRECT_NEIGHBORS_TYPE.toByte(), valueBytes.size.toByte()) + valueBytes
    }

    /**
     * Scan a TLV-encoded announce payload and extract neighbor peerIDs.
     * Returns null if the TLV is not present at all; returns an empty list if present with length 0.
     */
    fun decodeNeighborsFromAnnouncementPayload(payload: ByteArray): List<String>? {
        val result = mutableListOf<String>()
        var offset = 0
        while (offset + 2 <= payload.size) {
            val type = payload[offset].toUByte()
            val len = payload[offset + 1].toUByte().toInt()
            offset += 2
            if (offset + len > payload.size) break
            val value = payload.sliceArray(offset until offset + len)
            offset += len

            if (type == DIRECT_NEIGHBORS_TYPE) {
                // Value is N*8 bytes of peer IDs
                var pos = 0
                while (pos + 8 <= value.size) {
                    val idBytes = value.sliceArray(pos until pos + 8)
                    result.add(bytesToPeerIdHex(idBytes))
                    pos += 8
                }
                return result // present (possibly empty)
            }
        }
        // Not present
        return null
    }

    private fun hexStringPeerIdTo8Bytes(hexString: String): ByteArray {
        val clean = hexString.lowercase().take(16)
        val result = ByteArray(8) { 0 }
        var idx = 0
        var out = 0
        while (idx + 1 < clean.length && out < 8) {
            val byteStr = clean.substring(idx, idx + 2)
            val b = byteStr.toIntOrNull(16)?.toByte() ?: 0
            result[out++] = b
            idx += 2
        }
        return result
    }

    private fun bytesToPeerIdHex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes.take(8)) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
