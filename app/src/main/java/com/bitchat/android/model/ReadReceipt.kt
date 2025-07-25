package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.bitchat.android.util.*
import java.util.*

/**
 * Read receipt structure - exact same as iOS version
 * Uses binary encoding for efficient protocol communication
 */
@Parcelize
data class ReadReceipt(
    val originalMessageID: String,
    val receiptID: String = UUID.randomUUID().toString(),
    val readerID: String,
    val readerNickname: String,
    val timestamp: Date = Date()
) : Parcelable {

    // Primary constructor for creating new read receipts
    constructor(originalMessageID: String, readerID: String, readerNickname: String) : this(
        originalMessageID = originalMessageID,
        receiptID = UUID.randomUUID().toString(),
        readerID = readerID,
        readerNickname = readerNickname,
        timestamp = Date()
    )
    
    /**
     * Encode to binary data matching iOS toBinaryData implementation
     */
    fun encode(): ByteArray {
        val builder = BinaryDataBuilder()
        
        // Append original message UUID
        builder.appendUUID(originalMessageID)
        
        // Append receipt ID UUID
        builder.appendUUID(receiptID)
        
        // Append reader ID as 8-byte hex string
        val readerData = ByteArray(8) { 0 }
        var tempID = readerID
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                readerData[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        builder.buffer.addAll(readerData.toList())
        
        // Append timestamp
        builder.appendDate(timestamp)
        
        // Append reader nickname as string
        builder.appendString(readerNickname)
        
        return builder.toByteArray()
    }
    
    companion object {
        /**
         * Decode from binary data matching iOS fromBinaryData implementation
         */
        fun decode(data: ByteArray): ReadReceipt? {
            // Create defensive copy
            val dataCopy = data.copyOf()
            
            // Minimum size: 2 UUIDs (32) + readerID (8) + timestamp (8) + min nickname
            if (dataCopy.size < 49) return null
            
            val offset = intArrayOf(0)
            
            val originalMessageID = dataCopy.readUUID(offset) ?: return null
            val receiptID = dataCopy.readUUID(offset) ?: return null
            
            val readerIDData = dataCopy.readFixedBytes(offset, 8) ?: return null
            val readerID = readerIDData.hexEncodedString()
            
            val timestamp = dataCopy.readDate(offset) ?: return null
            val readerNickname = dataCopy.readString(offset) ?: return null
            
            return ReadReceipt(
                originalMessageID = originalMessageID,
                receiptID = receiptID,
                readerID = readerID,
                readerNickname = readerNickname,
                timestamp = timestamp
            )
        }
    }
}
