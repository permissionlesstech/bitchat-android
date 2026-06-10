package com.bitchat.android.services.meshgraph

import android.util.Log
import com.bitchat.android.protocol.TlvLengthSize
import com.bitchat.android.protocol.TlvReader
import com.bitchat.android.protocol.TlvWriter
import com.bitchat.android.protocol.UnknownTlvPolicy
import com.bitchat.android.util.PeerId

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
        val valueBytes = unique.flatMap { id -> PeerId.toBytes(id).toList() }.toByteArray()
        if (valueBytes.size > 255) {
            // Safety check, though 10*8 = 80 bytes, so well under 255
            Log.w("GossipTLV", "Neighbors value exceeds 255, truncating")
        }
        return TlvWriter()
            .put(DIRECT_NEIGHBORS_TYPE.toInt(), valueBytes, TlvLengthSize.ONE_BYTE)
            .toByteArray()
    }

    /**
     * Scan a TLV-encoded announce payload and extract neighbor peerIDs.
     * Returns null if the TLV is not present at all; returns an empty list if present with length 0.
     */
    fun decodeNeighborsFromAnnouncementPayload(payload: ByteArray): List<String>? {
        val result = mutableListOf<String>()
        val fields = TlvReader.decode(
            data = payload,
            defaultLengthSize = TlvLengthSize.ONE_BYTE,
            unknownPolicy = UnknownTlvPolicy.SKIP,
            knownTypes = setOf(DIRECT_NEIGHBORS_TYPE.toInt())
        ) ?: return null

        for (field in fields) {
            if (field.type == DIRECT_NEIGHBORS_TYPE.toInt()) {
                // Value is N*8 bytes of peer IDs
                var pos = 0
                while (pos + PeerId.BYTE_LENGTH <= field.value.size) {
                    val idBytes = field.value.sliceArray(pos until pos + PeerId.BYTE_LENGTH)
                    result.add(PeerId.fromBytes(idBytes))
                    pos += PeerId.BYTE_LENGTH
                }
                return result // present (possibly empty)
            }
        }
        // Not present
        return null
    }
}
