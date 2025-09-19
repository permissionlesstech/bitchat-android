package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Noise encrypted payload types and handling - 100% compatible with iOS SimplifiedBluetoothService
 * 
 * This handles all encrypted content that goes through noiseEncrypted packets:
 * - Private messages with TLV encoding
 * - Delivery acknowledgments
 * - Read receipts
 * - Future encrypted payload types
 */

/**
 * Types of payloads embedded within noiseEncrypted messages
 * Matches iOS NoisePayloadType exactly
 */
enum class NoisePayloadType(val value: UByte) {
    PRIVATE_MESSAGE(0x01u),     // Private chat message with TLV encoding
    READ_RECEIPT(0x02u),        // Message was read
    DELIVERED(0x03u),           // Message was delivered
    FILE_TRANSFER(0x20u);


    companion object {
        fun fromValue(value: UByte): NoisePayloadType? {
            return values().find { it.value == value }
        }
    }
}

/**
 * Helper class for creating and parsing Noise payloads
 * Matches iOS NoisePayload helper exactly
 */
@Parcelize
data class NoisePayload(
    val type: NoisePayloadType,
    val data: ByteArray
) : Parcelable {

    /**
     * Encode payload with type prefix - exactly like iOS
     * Format: [type_byte][payload_data]
     */
    fun encode(): ByteArray {
        val result = ByteArray(1 + data.size)
        result[0] = type.value.toByte()
        data.copyInto(result, destinationOffset = 1)
        return result
    }

    companion object {
        /**
         * Decode payload from data - exactly like iOS
         * Expects: [type_byte][payload_data]
         */
        fun decode(data: ByteArray): NoisePayload? {
            if (data.isEmpty()) return null
            
            val typeValue = data[0].toUByte()
            val type = NoisePayloadType.fromValue(typeValue) ?: return null
            
            // Extract payload data (remaining bytes after type byte)
            val payloadData = if (data.size > 1) {
                data.copyOfRange(1, data.size)
            } else {
                ByteArray(0)
            }
            
            return NoisePayload(type, payloadData)
        }
    }

    // Override equals and hashCode since we use ByteArray
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as NoisePayload
        
        if (type != other.type) return false
        if (!data.contentEquals(other.data)) return false
        
        return true
    }

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}

/**
 * Private message packet with TLV encoding - matches iOS PrivateMessagePacket exactly
 */
@Parcelize
data class PrivateMessagePacket(
    val messageID: String,
    val content: String
) : Parcelable {

    /**
     * TLV types matching iOS implementation exactly
     */
    private enum class TLVType(val value: UByte) {
        MESSAGE_ID(0x00u),
        CONTENT(0x01u);
        
        companion object {
            fun fromValue(value: UByte): TLVType? {
                return values().find { it.value == value }
            }
        }
    }

    /**
     * Encode to TLV binary data - exactly like iOS
     * Format: [type][length][value] for each field
     */
    fun encode(): ByteArray? {
        val messageIDData = messageID.toByteArray(Charsets.UTF_8)
        val contentData = content.toByteArray(Charsets.UTF_8)
        
        // Check size limits (TLV length field is 1 byte = max 255)
        if (messageIDData.size > 255 || contentData.size > 255) {
            return null
        }
        
        val result = mutableListOf<Byte>()
        
        // TLV for messageID
        result.add(TLVType.MESSAGE_ID.value.toByte())
        result.add(messageIDData.size.toByte())
        result.addAll(messageIDData.toList())
        
        // TLV for content
        result.add(TLVType.CONTENT.value.toByte())
        result.add(contentData.size.toByte())
        result.addAll(contentData.toList())
        
        return result.toByteArray()
    }
    
    companion object {
        /**
         * Decode from TLV binary data - exactly like iOS
         */
        fun decode(data: ByteArray): PrivateMessagePacket? {
            var offset = 0
            var messageID: String? = null
            var content: String? = null
            
            while (offset + 2 <= data.size) {
                // Read TLV type
                val typeValue = data[offset].toUByte()
                val type = TLVType.fromValue(typeValue) ?: return null
                offset += 1
                
                // Read TLV length
                val length = data[offset].toUByte().toInt()
                offset += 1
                
                // Check bounds
                if (offset + length > data.size) return null
                
                // Read TLV value
                val value = data.copyOfRange(offset, offset + length)
                offset += length
                
                when (type) {
                    TLVType.MESSAGE_ID -> {
                        messageID = String(value, Charsets.UTF_8)
                    }
                    TLVType.CONTENT -> {
                        content = String(value, Charsets.UTF_8)
                    }
                }
            }
            
            return if (messageID != null && content != null) {
                PrivateMessagePacket(messageID, content)
            } else {
                null
            }
        }
    }
    
    override fun toString(): String {
        return "PrivateMessagePacket(messageID='$messageID', content='${content.take(50)}${if (content.length > 50) "..." else ""}')"
    }
}

/**
 * Read receipt data class for transport compatibility
 */
@Parcelize
data class ReadReceipt(
    val originalMessageID: String,
    val readerPeerID: String? = null
) : Parcelable
