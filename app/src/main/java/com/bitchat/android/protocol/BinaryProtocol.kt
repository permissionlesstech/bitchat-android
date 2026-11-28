package com.bitchat.android.protocol

import android.os.Parcelable
import kotlinx.parcelize.IgnoredOnParcel
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

/**
 * Message types - exact same as iOS version with Noise Protocol support
 */
enum class MessageType(val value: UByte) {
    ANNOUNCE(0x01u),
    MESSAGE(0x02u),  // All user messages (private and broadcast)
    LEAVE(0x03u),
    NOISE_HANDSHAKE(0x10u),  // Noise handshake
    NOISE_ENCRYPTED(0x11u),  // Noise encrypted transport message
    FRAGMENT(0x20u), // Fragmentation for large packets
    REQUEST_SYNC(0x21u), // GCS-based sync request
    FILE_TRANSFER(0x22u), // New: File transfer packet (BLE voice notes, etc.)
    VOICE_FRAME(0x29u); // Ephemeral live push-to-talk frame; never added to gossip sync

    companion object {
        fun fromValue(value: UByte): MessageType? = when (value) {
            0x01u.toUByte() -> ANNOUNCE
            0x02u.toUByte() -> MESSAGE
            0x03u.toUByte() -> LEAVE
            0x10u.toUByte() -> NOISE_HANDSHAKE
            0x11u.toUByte() -> NOISE_ENCRYPTED
            0x20u.toUByte() -> FRAGMENT
            0x21u.toUByte() -> REQUEST_SYNC
            0x22u.toUByte() -> FILE_TRANSFER
            0x29u.toUByte() -> VOICE_FRAME
            else -> null
        }
    }
}

/**
 * Special recipient IDs - exact same as iOS version
 */
object SpecialRecipients {
    val BROADCAST = ByteArray(8) { 0xFF.toByte() }  // All 0xFF = broadcast
}

/**
 * Payload as it arrived on the wire, set by [BinaryProtocol.decode].
 *
 * Signatures cover a re-encoding of the packet, and verification re-encodes too.
 * DEFLATE output is not canonical and clients use different encoders
 * (java.util.zip.Deflater here, Apple's compression_encode_buffer on iOS), so
 * re-compressing can change the preimage and reject a valid packet. Reusing these
 * bytes also stops a relay, which re-encodes on TTL decrement, from substituting
 * its own encoding.
 *
 * [forPayload] ties the bytes to the payload they decode to: replace the payload
 * and the encoder compresses instead.
 *
 * Not a data class: generated equals/hashCode over ByteArray compares references.
 */
class WirePayload(
    val bytes: ByteArray,
    val compressed: Boolean,
    val forPayload: ByteArray
)

/**
 * Binary packet format - 100% backward compatible with iOS version
 *
 * Header (14 bytes for v1, 16 bytes for v2):
 * - Version: 1 byte
 * - Type: 1 byte
 * - TTL: 1 byte
 * - Timestamp: 8 bytes (UInt64, big-endian)
 * - Flags: 1 byte (bit 0: hasRecipient, bit 1: hasSignature, bit 2: isCompressed)
 * - PayloadLength: 2 bytes (v1) / 4 bytes (v2) (big-endian)
 *
 * Variable sections:
 * - SenderID: 8 bytes (fixed)
 * - RecipientID: 8 bytes (if hasRecipient flag set)
 * - Payload: Variable length (includes original size if compressed)
 * - Signature: 64 bytes (if hasSignature flag set)
 */
@Parcelize
data class BitchatPacket(
    val version: UByte = 1u,
    val type: UByte,
    val senderID: ByteArray,
    val recipientID: ByteArray? = null,
    val timestamp: ULong,
    val payload: ByteArray,
    var signature: ByteArray? = null,  // Changed from val to var for packet signing
    var ttl: UByte,
    var route: List<ByteArray>? = null, // Optional source route: ordered list of peerIDs (8 bytes each), not including sender and final recipient
    // Set by BinaryProtocol.decode. Not part of packet identity, so it stays out of
    // the parcel, equals and hashCode. Losing it only costs a re-compression.
    @IgnoredOnParcel val wirePayload: WirePayload? = null
) : Parcelable {

    // Transient per-instance verification cache to avoid redundant Ed25519 verifications
    // when SecurityManager validates a packet and MessageHandler immediately consumes it.
    // Validated against exact field references so any copy() or mutation invalidates it.
    @IgnoredOnParcel
    @Transient
    internal var cachedVerifiedAnnouncement: VerifiedAnnouncementCacheEntry? = null

    internal class VerifiedAnnouncementCacheEntry(
        val peerID: String,
        val payloadRef: ByteArray,
        val signatureRef: ByteArray?,
        val senderIDRef: ByteArray,
        val announcement: Any
    )

    constructor(
        type: UByte,
        ttl: UByte,
        senderID: String,
        payload: ByteArray
    ) : this(
        version = 1u,
        type = type,
        senderID = hexStringToByteArray(senderID),
        recipientID = null,
        timestamp = (System.currentTimeMillis()).toULong(),
        payload = payload,
        signature = null,
        ttl = ttl
    )

    fun toBinaryData(padding: Boolean = true): ByteArray? {
        return BinaryProtocol.encode(this, padding = padding)
    }

    /**
     * Create binary representation for signing (without signature and TTL fields)
     * TTL is excluded because it changes during packet relay operations
     */
    fun toBinaryDataForSigning(): ByteArray? {
        // Create a copy without signature and with fixed TTL for signing
        // TTL must be excluded because it changes during relay
        val unsignedPacket = BitchatPacket(
            version = version,
            type = type,
            senderID = senderID,
            recipientID = recipientID,
            timestamp = timestamp,
            payload = payload,
            signature = null, // Remove signature for signing
            route = route,
            ttl = com.bitchat.android.util.AppConstants.SYNC_TTL_HOPS, // Use fixed TTL=0 for signing to ensure relay compatibility
            wirePayload = wirePayload // preimage must use the originator's bytes
        )
        return BinaryProtocol.encode(unsignedPacket)
    }

    companion object {
        fun fromBinaryData(data: ByteArray): BitchatPacket? {
            return BinaryProtocol.decode(data)
        }

        /**
         * Convert hex string peer ID to binary data (8 bytes) - exactly same as iOS
         */
        private fun hexStringToByteArray(hexString: String): ByteArray =
            com.bitchat.android.mesh.MeshPacketUtils.hexStringToByteArray(hexString)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as BitchatPacket

        if (version != other.version) return false
        if (type != other.type) return false
        if (!senderID.contentEquals(other.senderID)) return false
        if (recipientID != null) {
            if (other.recipientID == null) return false
            if (!recipientID.contentEquals(other.recipientID)) return false
        } else if (other.recipientID != null) return false
        if (timestamp != other.timestamp) return false
        if (!payload.contentEquals(other.payload)) return false
        if (signature != null) {
            if (other.signature == null) return false
            if (!signature.contentEquals(other.signature)) return false
        } else if (other.signature != null) return false
        if (ttl != other.ttl) return false
        if (route != null || other.route != null) {
            val a = route?.map { it.toList() } ?: emptyList()
            val b = other.route?.map { it.toList() } ?: emptyList()
            if (a != b) return false
        }

        return true
    }

    override fun hashCode(): Int {
        var result = version.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + senderID.contentHashCode()
        result = 31 * result + (recipientID?.contentHashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + (signature?.contentHashCode() ?: 0)
        result = 31 * result + ttl.hashCode()
        result = 31 * result + (route?.fold(1) { acc, bytes -> 31 * acc + bytes.contentHashCode() } ?: 0)
        return result
    }
}

/**
 * Binary Protocol implementation - supports v1 and v2, backward compatible
 */
object BinaryProtocol {
    private const val HEADER_SIZE_V1 = 14
    private const val HEADER_SIZE_V2 = 16
    private const val SENDER_ID_SIZE = 8
    private const val RECIPIENT_ID_SIZE = 8
    private const val SIGNATURE_SIZE = 64
    object Flags {
        const val HAS_RECIPIENT: UByte = 0x01u
        const val HAS_SIGNATURE: UByte = 0x02u
        const val IS_COMPRESSED: UByte = 0x04u
        const val HAS_ROUTE: UByte = 0x08u
    }

    private fun getHeaderSize(version: UByte): Int {
        return when (version) {
            1u.toUByte() -> HEADER_SIZE_V1
            else -> HEADER_SIZE_V2  // v2+ will use 4-byte payload length
        }
    }
    
    fun encode(packet: BitchatPacket, padding: Boolean = true): ByteArray? {
        try {
            val maxPayloadLength = com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH
            if (packet.payload.size > maxPayloadLength) {
                Log.w(
                    "BinaryProtocol",
                    "Cannot encode payload ${packet.payload.size} above receiver limit $maxPayloadLength"
                )
                return null
            }

            // Try to compress payload if beneficial
            var payload = packet.payload
            var originalPayloadSize: Int? = null
            var isCompressed = false
            
            // Re-encode of a decoded packet: reuse the originator's bytes (see WirePayload).
            val wire = packet.wirePayload
            if (wire != null && wire.forPayload.contentEquals(packet.payload)) {
                if (wire.compressed) {
                    payload = wire.bytes
                    originalPayloadSize = packet.payload.size
                    isCompressed = true
                }
                // Uncompressed on the wire: keep it that way. shouldCompress agrees
                // across clients, but the "only if smaller" check need not.
            } else if (CompressionUtil.shouldCompress(payload)) {
                CompressionUtil.compress(payload)?.let { compressedPayload ->
                    originalPayloadSize = payload.size
                    payload = compressedPayload
                    isCompressed = true
                }
            }
            
            // Compute exact unpadded frame size and final padded size to allocate once.
            val isV2 = packet.version >= 2u.toUByte()
            val headerSize = getHeaderSize(packet.version)
            val recipientID = packet.recipientID
            val signature = packet.signature
            val routeList = if (isV2) packet.route?.takeIf { it.isNotEmpty() } else null
            val routeCount = routeList?.size?.coerceAtMost(255) ?: 0
            val recipientBytes = if (recipientID != null) RECIPIENT_ID_SIZE else 0
            val signatureBytes = if (signature != null) minOf(signature.size, SIGNATURE_SIZE) else 0
            val sizeFieldBytes = if (isCompressed) (if (isV2) 4 else 2) else 0
            val payloadDataSize = payload.size + sizeFieldBytes
            if (!isV2 && (payloadDataSize > 0xFFFF || (originalPayloadSize ?: 0) > 0xFFFF)) {
                Log.w("BinaryProtocol", "Cannot encode oversized v1 packet payload: $payloadDataSize bytes")
                return null
            }
            val routeBytes = if (routeCount > 0) 1 + (routeCount * SENDER_ID_SIZE) else 0
            val unpaddedSize = headerSize + SENDER_ID_SIZE + recipientBytes + routeBytes + payloadDataSize + signatureBytes

            val finalSize = if (padding) {
                val optimalSize = MessagePadding.optimalBlockSize(unpaddedSize)
                val padNeeded = optimalSize - unpaddedSize
                if (padNeeded in 1..255) optimalSize else unpaddedSize
            } else {
                unpaddedSize
            }

            val out = ByteArray(finalSize)
            var pos = 0

            // Header
            out[pos++] = packet.version.toByte()
            out[pos++] = packet.type.toByte()
            out[pos++] = packet.ttl.toByte()

            // Timestamp (8 bytes, big-endian)
            val ts = packet.timestamp.toLong()
            out[pos++] = (ts ushr 56).toByte()
            out[pos++] = (ts ushr 48).toByte()
            out[pos++] = (ts ushr 40).toByte()
            out[pos++] = (ts ushr 32).toByte()
            out[pos++] = (ts ushr 24).toByte()
            out[pos++] = (ts ushr 16).toByte()
            out[pos++] = (ts ushr 8).toByte()
            out[pos++] = ts.toByte()

            // Flags
            var flags: UByte = 0u
            if (recipientID != null) flags = flags or Flags.HAS_RECIPIENT
            if (signature != null) flags = flags or Flags.HAS_SIGNATURE
            if (isCompressed) flags = flags or Flags.IS_COMPRESSED
            if (routeCount > 0) flags = flags or Flags.HAS_ROUTE
            out[pos++] = flags.toByte()

            // Payload length (2 or 4 bytes, big-endian) - includes original size if compressed
            if (isV2) {
                out[pos++] = (payloadDataSize ushr 24).toByte()
                out[pos++] = (payloadDataSize ushr 16).toByte()
                out[pos++] = (payloadDataSize ushr 8).toByte()
                out[pos++] = payloadDataSize.toByte()
            } else {
                out[pos++] = (payloadDataSize ushr 8).toByte()
                out[pos++] = payloadDataSize.toByte()
            }

            // SenderID (exactly 8 bytes, zero-padded if shorter)
            val senderCopy = minOf(packet.senderID.size, SENDER_ID_SIZE)
            System.arraycopy(packet.senderID, 0, out, pos, senderCopy)
            pos += SENDER_ID_SIZE

            // RecipientID (if present, exactly 8 bytes, zero-padded if shorter)
            if (recipientID != null) {
                val recCopy = minOf(recipientID.size, RECIPIENT_ID_SIZE)
                System.arraycopy(recipientID, 0, out, pos, recCopy)
                pos += RECIPIENT_ID_SIZE
            }

            // Route (optional, v2+ only): 1 byte count + N*8 bytes
            if (routeCount > 0 && routeList != null) {
                out[pos++] = routeCount.toByte()
                for (i in 0 until routeCount) {
                    val hop = routeList[i]
                    val hopCopy = minOf(hop.size, SENDER_ID_SIZE)
                    System.arraycopy(hop, 0, out, pos, hopCopy)
                    pos += SENDER_ID_SIZE
                }
            }

            // Payload (with original size prepended if compressed)
            if (isCompressed) {
                val originalSize = originalPayloadSize
                if (originalSize != null) {
                    if (isV2) {
                        out[pos++] = (originalSize ushr 24).toByte()
                        out[pos++] = (originalSize ushr 16).toByte()
                        out[pos++] = (originalSize ushr 8).toByte()
                        out[pos++] = originalSize.toByte()
                    } else {
                        out[pos++] = (originalSize ushr 8).toByte()
                        out[pos++] = originalSize.toByte()
                    }
                }
            }
            System.arraycopy(payload, 0, out, pos, payload.size)
            pos += payload.size

            // Signature (if present)
            if (signature != null && signatureBytes > 0) {
                System.arraycopy(signature, 0, out, pos, signatureBytes)
                pos += signatureBytes
            }

            // Apply PKCS#7 padding in-place (zero extra copy)
            if (finalSize > pos) {
                val padByte = (finalSize - pos).toByte()
                out.fill(padByte, pos, finalSize)
            }

            return out
            
        } catch (e: Exception) {
            Log.e("BinaryProtocol", "Error encoding packet type ${packet.type}: ${e.message}")
            return null
        }
    }
    
    fun decode(data: ByteArray): BitchatPacket? =
        decode(data, CompressionUtil::decompressWithResourcesReserved)

    /** Test seam used to prove rejected expansion sizes never reach inflation. */
    internal fun decodeForTesting(
        data: ByteArray,
        decompress: (ByteArray, Int) -> ByteArray?
    ): BitchatPacket? = decode(data, decompress)

    private fun decode(
        data: ByteArray,
        decompress: (ByteArray, Int) -> ByteArray?
    ): BitchatPacket? {
        // Try decode as-is first (robust when padding wasn't applied) - iOS fix
        decodeCore(data, decompress)?.let { return it }
        
        // If that fails, try after removing padding
        val unpadded = MessagePadding.unpad(data)
        if (unpadded === data) return null // No padding was removed, already failed
        
        return decodeCore(unpadded, decompress)
    }
    
    /**
     * Core decoding implementation used by decode() with and without padding removal - iOS fix
     */
    private fun decodeCore(
        raw: ByteArray,
        decompress: (ByteArray, Int) -> ByteArray?
    ): BitchatPacket? {
        try {
            if (raw.size < HEADER_SIZE_V1 + SENDER_ID_SIZE) return null

            val buffer = ByteBuffer.wrap(raw).apply { order(ByteOrder.BIG_ENDIAN) }

            // Header
            val version = buffer.get().toUByte()
            if (version.toUInt() != 1u && version.toUInt() != 2u) return null  // Support v1 and v2

            val headerSize = getHeaderSize(version)

            val type = buffer.get().toUByte()
            val ttl = buffer.get().toUByte()

            // Timestamp
            val timestamp = buffer.getLong().toULong()

            // Flags
            val flags = buffer.get().toUByte()
            val hasRecipient = (flags and Flags.HAS_RECIPIENT) != 0u.toUByte()
            val hasSignature = (flags and Flags.HAS_SIGNATURE) != 0u.toUByte()
            val isCompressed = (flags and Flags.IS_COMPRESSED) != 0u.toUByte()
            // HAS_ROUTE is only valid for v2+ packets; ignore the flag for v1
            val hasRoute = (version >= 2u.toUByte()) && (flags and Flags.HAS_ROUTE) != 0u.toUByte()

            // Payload length - version-dependent (2 or 4 bytes)
            val payloadLength = if (version >= 2u.toUByte()) {
                buffer.getInt().toUInt()  // 4 bytes for v2+
            } else {
                buffer.getShort().toUShort().toUInt()  // 2 bytes for v1, convert to UInt
            }

            if (payloadLength > com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH.toUInt()) {
                Log.w("BinaryProtocol", "Payload length ${payloadLength} exceeds maximum allowed (${com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH})")
                return null
            }

            // Per-type ceiling on the decoded payload, compressed or not, checked
            // before any allocation: see PacketPayloadLimits for why one
            // frame-wide cap is not enough.
            val maxPayloadSize = PacketPayloadLimits.maxPayloadBytes(type)

            var expectedSize = headerSize + SENDER_ID_SIZE + payloadLength.toInt()
            if (hasRecipient) expectedSize += RECIPIENT_ID_SIZE
            var routeCount = 0
            if (hasRoute) {
                // Peek count (1 byte) without consuming buffer for now
                // The buffer is currently positioned at the start of SenderID (after fixed header)
                // We must skip SenderID and RecipientID (if present) to find the route count
                val currentPos = buffer.position()
                var routeOffset = currentPos + SENDER_ID_SIZE
                if (hasRecipient) {
                    routeOffset += RECIPIENT_ID_SIZE
                }

                if (raw.size >= routeOffset + 1) {
                    routeCount = raw[routeOffset].toUByte().toInt()
                }
                expectedSize += 1 + (routeCount * SENDER_ID_SIZE)
            }
            if (hasSignature) expectedSize += SIGNATURE_SIZE

            if (raw.size < expectedSize) return null
            
            // SenderID
            val senderID = ByteArray(SENDER_ID_SIZE)
            buffer.get(senderID)
            
            // RecipientID
            val recipientID = if (hasRecipient) {
                val recipientBytes = ByteArray(RECIPIENT_ID_SIZE)
                buffer.get(recipientBytes)
                recipientBytes
            } else null
            
            // Route (optional)
            val route: List<ByteArray>? = if (hasRoute) {
                val count = buffer.get().toUByte().toInt()
                if (count == 0) {
                    null // Treat empty route list as null to enforce canonical representation
                } else {
                    val hops = mutableListOf<ByteArray>()
                    repeat(count) {
                        val hop = ByteArray(SENDER_ID_SIZE)
                        buffer.get(hop)
                        hops.add(hop)
                    }
                    hops
                }
            } else null

            // Payload
            // Kept so the packet can be re-encoded byte-identically (see WirePayload).
            var receivedCompressed: ByteArray? = null
            val payload = if (isCompressed) {
                val lengthFieldBytes = if (version >= 2u.toUByte()) 4 else 2
                if (payloadLength.toInt() < lengthFieldBytes) return null
                
                val originalSize = if (version >= 2u.toUByte()) {
                    buffer.getInt()
                } else {
                    buffer.getShort().toUShort().toInt()
                }

                if (originalSize <= 0 || originalSize > maxPayloadSize) {
                    Log.w(
                        "BinaryProtocol",
                        "Expanded payload size $originalSize for type $type is outside the allowed range 1..$maxPayloadSize"
                    )
                    return null
                }
                
                // Compressed payload
                val compressedSize = payloadLength.toInt() - lengthFieldBytes
                if (compressedSize == 0) {
                    Log.w("BinaryProtocol", "Compressed payload has no deflate bytes")
                    return null
                }
                // Security check: Compression bomb protection
                if (originalSize.toLong() > compressedSize.toLong() * PacketPayloadLimits.MAX_DEFLATE_RATIO) {
                    Log.w("BinaryProtocol", "🚫 Impossible compression ratio: $originalSize bytes from $compressedSize")
                    return null
                }
                
                // Reserve the compressed copy plus expanded output before either allocation.
                // Small packets share the memory pool; packets wait only while its budget is full.
                val resourceBytes = compressedSize.toLong() + originalSize.toLong()
                val expandedPayload = CompressionUtil.withDecompressionResources(resourceBytes) {
                    val compressedPayload = ByteArray(compressedSize)
                    buffer.get(compressedPayload)
                    // Captured here so the allocation stays inside the reservation.
                    receivedCompressed = compressedPayload
                    decompress(compressedPayload, originalSize)
                } ?: return null
                if (expandedPayload.size != originalSize) {
                    Log.w(
                        "BinaryProtocol",
                        "Expanded payload size ${expandedPayload.size} did not match declared size $originalSize"
                    )
                    return null
                }
                expandedPayload
            } else {
                // Uncompressed payloads get the same ceiling, or a large frame
                // could arrive as small compressed fragments and be reassembled.
                if (payloadLength > maxPayloadSize.toUInt()) {
                    Log.w("BinaryProtocol", "Payload for type $type is $payloadLength bytes (cap $maxPayloadSize)")
                    return null
                }
                val payloadBytes = ByteArray(payloadLength.toInt())
                buffer.get(payloadBytes)
                payloadBytes
            }
            
            // Signature
            val signature = if (hasSignature) {
                val signatureBytes = ByteArray(SIGNATURE_SIZE)
                buffer.get(signatureBytes)
                signatureBytes
            } else null
            
            return BitchatPacket(
                version = version,
                type = type,
                senderID = senderID,
                recipientID = recipientID,
                timestamp = timestamp,
                payload = payload,
                signature = signature,
                ttl = ttl,
                route = route,
                wirePayload = WirePayload(
                    bytes = receivedCompressed ?: payload,
                    compressed = receivedCompressed != null,
                    forPayload = payload
                )
            )
            
        } catch (e: Exception) {
            Log.e("BinaryProtocol", "Error decoding packet: ${e.message}")
            return null
        }
    }
}
