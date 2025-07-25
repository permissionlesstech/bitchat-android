package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.bitchat.android.util.*
import java.util.*

/**
 * Delivery acknowledgment structure - exact same as iOS version
 * Uses binary encoding for efficient protocol communication
 */
@Parcelize
data class DeliveryAck(
    val originalMessageID: String,
    val ackID: String = UUID.randomUUID().toString(),
    val recipientID: String,
    val recipientNickname: String,
    val timestamp: Date = Date(),
    val hopCount: UByte
) : Parcelable {

    // Primary constructor for creating new acks
    constructor(originalMessageID: String, recipientID: String, recipientNickname: String, hopCount: UByte) : this(
        originalMessageID = originalMessageID,
        ackID = UUID.randomUUID().toString(),
        recipientID = recipientID,
        recipientNickname = recipientNickname,
        timestamp = Date(),
        hopCount = hopCount
    )

    /**
     * Encode to binary data matching iOS toBinaryData implementation
     */
    fun encode(): ByteArray {
        val builder = BinaryDataBuilder()
        
        // Append original message UUID
        builder.appendUUID(originalMessageID)
        
        // Append ack ID UUID
        builder.appendUUID(ackID)
        
        // Append recipient ID as 8-byte hex string
        val recipientData = ByteArray(8) { 0 }
        var tempID = recipientID
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                recipientData[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        builder.buffer.addAll(recipientData.toList())
        
        // Append hop count (UInt8)
        builder.appendUInt8(hopCount)
        
        // Append timestamp
        builder.appendDate(timestamp)
        
        // Append recipient nickname as string
        builder.appendString(recipientNickname)
        
        return builder.toByteArray()
    }
    
    companion object {
        /**
         * Decode from binary data matching iOS fromBinaryData implementation
         */
        fun decode(data: ByteArray): DeliveryAck? {
            // Create defensive copy
            val dataCopy = data.copyOf()
            
            // Minimum size: 2 UUIDs (32) + recipientID (8) + hopCount (1) + timestamp (8) + min nickname
            if (dataCopy.size < 50) return null
            
            val offset = intArrayOf(0)
            
            val originalMessageID = dataCopy.readUUID(offset) ?: return null
            val ackID = dataCopy.readUUID(offset) ?: return null
            
            val recipientIDData = dataCopy.readFixedBytes(offset, 8) ?: return null
            val recipientID = recipientIDData.hexEncodedString()
            
            val hopCount = dataCopy.readUInt8(offset) ?: return null
            val timestamp = dataCopy.readDate(offset) ?: return null
            val recipientNickname = dataCopy.readString(offset) ?: return null
            
            return DeliveryAck(
                originalMessageID = originalMessageID,
                ackID = ackID,
                recipientID = recipientID,
                recipientNickname = recipientNickname,
                timestamp = timestamp,
                hopCount = hopCount
            )
        }
    }
}
