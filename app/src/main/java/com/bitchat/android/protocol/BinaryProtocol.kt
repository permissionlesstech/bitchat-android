package com.bitchat.android.protocol

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.util.Log

/**
 * Message types - exact same as iOS version with Noise Protocol support
 */
enum class MessageType(val value: UByte) {
    ANNOUNCE(0x01u),
    // 0x02 was legacy keyExchange - removed
    LEAVE(0x03u),
    MESSAGE(0x04u),  // All user messages (private and broadcast)
    FRAGMENT_START(0x05u),
    FRAGMENT_CONTINUE(0x06u),
    FRAGMENT_END(0x07u),
    CHANNEL_ANNOUNCE(0x08u),  // Announce password-protected channel status
    CHANNEL_RETENTION(0x09u),  // Announce channel retention status
    DELIVERY_ACK(0x0Au),  // Acknowledge message received
    DELIVERY_STATUS_REQUEST(0x0Bu),  // Request delivery status update
    READ_RECEIPT(0x0Cu),  // Message has been read/viewed
    
    // Noise Protocol messages - exact same as iOS
    NOISE_HANDSHAKE_INIT(0x10u),  // Noise handshake initiation
    NOISE_HANDSHAKE_RESP(0x11u),  // Noise handshake response
    NOISE_ENCRYPTED(0x12u),       // Noise encrypted transport message
    NOISE_IDENTITY_ANNOUNCE(0x13u),  // Announce static public key for discovery
    CHANNEL_KEY_VERIFY_REQUEST(0x14u),  // Request key verification for a channel
    CHANNEL_KEY_VERIFY_RESPONSE(0x15u), // Response to key verification request
    CHANNEL_PASSWORD_UPDATE(0x16u),     // Distribute new password to channel members
    CHANNEL_METADATA(0x17u),            // Announce channel creator and metadata
    HANDSHAKE_REQUEST(0x25u),            // Request handshake initiation for pending messages
    
    // Protocol version negotiation
    VERSION_HELLO(0x20u),               // Initial version announcement
    VERSION_ACK(0x21u);                 // Version acknowledgment

    companion object {
        fun fromValue(value: UByte): MessageType? {
            return values().find { it.value == value }
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
 * Binary packet format - 100% compatible with iOS version
 * 
 * Header (Fixed 13 bytes):
 * - Version: 1 byte
 * - Type: 1 byte  
 * - TTL: 1 byte
 * - Timestamp: 8 bytes (UInt64, big-endian)
 * - Flags: 1 byte (bit 0: hasRecipient, bit 1: hasSignature, bit 2: isCompressed)
 * - PayloadLength: 2 bytes (UInt16, big-endian)
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
    val signature: ByteArray? = null,
    var ttl: UByte
) : Parcelable {

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

    fun toBinaryData(): ByteArray? {
        return BinaryProtocol.encode(this)
    }

    companion object {
        fun fromBinaryData(data: ByteArray): BitchatPacket? {
            return BinaryProtocol.decode(data)
        }
        
        /**
         * Convert hex string peer ID to binary data (8 bytes) - exactly same as iOS
         */
        private fun hexStringToByteArray(hexString: String): ByteArray {
            val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
            var tempID = hexString
            var index = 0
            
            while (tempID.length >= 2 && index < 8) {
                val hexByte = tempID.substring(0, 2)
                val byte = hexByte.toIntOrNull(16)?.toByte()
                if (byte != null) {
                    result[index] = byte
                }
                tempID = tempID.substring(2)
                index++
            }
            
            return result
        }
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
        return result
    }
}

/**
 * Binary Protocol implementation - exact same format as iOS version
 */
object BinaryProtocol {
    private const val HEADER_SIZE = 13
    private const val SENDER_ID_SIZE = 8
    private const val RECIPIENT_ID_SIZE = 8
    private const val SIGNATURE_SIZE = 64
    
    object Flags {
        const val HAS_RECIPIENT: UByte = 0x01u
        const val HAS_SIGNATURE: UByte = 0x02u
        const val IS_COMPRESSED: UByte = 0x04u
    }
    
    fun encode(packet: BitchatPacket): ByteArray? {
        try {
            // Try to compress payload if beneficial
            var payload = packet.payload
            var originalPayloadSize: UShort? = null
            var isCompressed = false
            
            if (CompressionUtil.shouldCompress(payload)) {
                CompressionUtil.compress(payload)?.let { compressedPayload ->
                    originalPayloadSize = payload.size.toUShort()
                    payload = compressedPayload
                    isCompressed = true
                }
            }
            
            val buffer = ByteBuffer.allocate(4096).apply { order(ByteOrder.BIG_ENDIAN) }
            
            // Header
            buffer.put(packet.version.toByte())
            buffer.put(packet.type.toByte())
            buffer.put(packet.ttl.toByte())
            
            // Timestamp (8 bytes, big-endian)
            buffer.putLong(packet.timestamp.toLong())
            
            // Flags
            var flags: UByte = 0u
            if (packet.recipientID != null) {
                flags = flags or Flags.HAS_RECIPIENT
            }
            if (packet.signature != null) {
                flags = flags or Flags.HAS_SIGNATURE
            }
            if (isCompressed) {
                flags = flags or Flags.IS_COMPRESSED
            }
            buffer.put(flags.toByte())
            
            // Payload length (2 bytes, big-endian) - includes original size if compressed
            val payloadDataSize = payload.size + if (isCompressed) 2 else 0
            buffer.putShort(payloadDataSize.toShort())
            
            // SenderID (exactly 8 bytes)
            val senderBytes = packet.senderID.take(SENDER_ID_SIZE).toByteArray()
            buffer.put(senderBytes)
            if (senderBytes.size < SENDER_ID_SIZE) {
                buffer.put(ByteArray(SENDER_ID_SIZE - senderBytes.size))
            }
            
            // RecipientID (if present)
            packet.recipientID?.let { recipientID ->
                val recipientBytes = recipientID.take(RECIPIENT_ID_SIZE).toByteArray()
                buffer.put(recipientBytes)
                if (recipientBytes.size < RECIPIENT_ID_SIZE) {
                    buffer.put(ByteArray(RECIPIENT_ID_SIZE - recipientBytes.size))
                }
            }
            
            // Payload (with original size prepended if compressed)
            if (isCompressed) {
                val originalSize = originalPayloadSize
                if (originalSize != null) {
                    buffer.putShort(originalSize.toShort())
                }
            }
            buffer.put(payload)
            
            // Signature (if present)
            packet.signature?.let { signature ->
                buffer.put(signature.take(SIGNATURE_SIZE).toByteArray())
            }
            
            val result = ByteArray(buffer.position())
            buffer.rewind()
            buffer.get(result)
            
            // Apply padding to standard block sizes for traffic analysis resistance
            val optimalSize = MessagePadding.optimalBlockSize(result.size)
            val paddedData = MessagePadding.pad(result, optimalSize)
            
            return paddedData
            
        } catch (e: Exception) {
            Log.e("BinaryProtocol", "Error encoding packet type ${packet.type}: ${e.message}")
            return null
        }
    }
    
    fun decode(data: ByteArray): BitchatPacket? {
        try {
            // Remove padding first - exactly same as iOS
            val unpaddedData = MessagePadding.unpad(data)
            
            if (unpaddedData.size < HEADER_SIZE + SENDER_ID_SIZE) return null
            
            val buffer = ByteBuffer.wrap(unpaddedData).apply { order(ByteOrder.BIG_ENDIAN) }
            
            // Header
            val version = buffer.get().toUByte()
            if (version != 1u.toUByte()) return null
            
            val type = buffer.get().toUByte()
            val ttl = buffer.get().toUByte()
            
            // Timestamp
            val timestamp = buffer.getLong().toULong()
            
            // Flags
            val flags = buffer.get().toUByte()
            val hasRecipient = (flags and Flags.HAS_RECIPIENT) != 0u.toUByte()
            val hasSignature = (flags and Flags.HAS_SIGNATURE) != 0u.toUByte()
            val isCompressed = (flags and Flags.IS_COMPRESSED) != 0u.toUByte()
            
            // Payload length
            val payloadLength = buffer.getShort().toUShort()
            
            // Calculate expected total size
            var expectedSize = HEADER_SIZE + SENDER_ID_SIZE + payloadLength.toInt()
            if (hasRecipient) expectedSize += RECIPIENT_ID_SIZE
            if (hasSignature) expectedSize += SIGNATURE_SIZE
            
            if (unpaddedData.size < expectedSize) return null
            
            // SenderID
            val senderID = ByteArray(SENDER_ID_SIZE)
            buffer.get(senderID)
            
            // RecipientID
            val recipientID = if (hasRecipient) {
                val recipientBytes = ByteArray(RECIPIENT_ID_SIZE)
                buffer.get(recipientBytes)
                recipientBytes
            } else null
            
            // Payload
            val payload = if (isCompressed) {
                // First 2 bytes are original size
                if (payloadLength.toInt() < 2) return null
                val originalSize = buffer.getShort().toInt()
                
                // Compressed payload
                val compressedPayload = ByteArray(payloadLength.toInt() - 2)
                buffer.get(compressedPayload)
                
                // Decompress
                CompressionUtil.decompress(compressedPayload, originalSize) ?: return null
            } else {
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
                ttl = ttl
            )
            
        } catch (e: Exception) {
            return null
        }
    }
}
