package com.bitchat.android.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import com.bitchat.android.util.*
import java.util.*

/**
 * Handshake request for pending messages - exact same as iOS version
 * Uses binary encoding for efficient protocol communication
 */
@Parcelize
data class HandshakeRequest(
    val requestID: String,
    val requesterID: String,           // Who needs the handshake
    val requesterNickname: String,     // Nickname of requester
    val targetID: String,              // Who should initiate handshake
    val pendingMessageCount: UByte,    // Number of messages queued
    val timestamp: Date
) : Parcelable {

    // Primary constructor for creating new requests
    constructor(requesterID: String, requesterNickname: String, targetID: String, pendingMessageCount: UByte) : this(
        requestID = UUID.randomUUID().toString(),
        requesterID = requesterID,
        requesterNickname = requesterNickname,
        targetID = targetID,
        pendingMessageCount = pendingMessageCount,
        timestamp = Date()
    )

    /**
     * Encode to binary data matching iOS toBinaryData implementation
     */
    fun toBinaryData(): ByteArray {
        val builder = BinaryDataBuilder()
        
        // Append request ID UUID
        builder.appendUUID(requestID)
        
        // RequesterID as 8-byte hex string
        val requesterData = ByteArray(8) { 0 }
        var tempID = requesterID
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                requesterData[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        builder.buffer.addAll(requesterData.toList())
        
        // TargetID as 8-byte hex string
        val targetData = ByteArray(8) { 0 }
        tempID = targetID
        index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                targetData[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        builder.buffer.addAll(targetData.toList())
        
        // Append pending message count (UInt8)
        builder.appendUInt8(pendingMessageCount)
        
        // Append timestamp
        builder.appendDate(timestamp)
        
        // Append requester nickname as string
        builder.appendString(requesterNickname)
        
        return builder.toByteArray()
    }
    
    companion object {
        /**
         * Decode from binary data matching iOS fromBinaryData implementation
         */
        fun fromBinaryData(data: ByteArray): HandshakeRequest? {
            // Create defensive copy
            val dataCopy = data.copyOf()
            
            // Minimum size: UUID (16) + requesterID (8) + targetID (8) + count (1) + timestamp (8) + min nickname
            if (dataCopy.size < 42) return null
            
            val offset = intArrayOf(0)
            
            val requestID = dataCopy.readUUID(offset) ?: return null
            
            val requesterIDData = dataCopy.readFixedBytes(offset, 8) ?: return null
            val requesterID = requesterIDData.hexEncodedString()
            
            val targetIDData = dataCopy.readFixedBytes(offset, 8) ?: return null
            val targetID = targetIDData.hexEncodedString()
            
            val pendingMessageCount = dataCopy.readUInt8(offset) ?: return null
            val timestamp = dataCopy.readDate(offset) ?: return null
            val requesterNickname = dataCopy.readString(offset) ?: return null
            
            return HandshakeRequest(
                requestID = requestID,
                requesterID = requesterID,
                requesterNickname = requesterNickname,
                targetID = targetID,
                pendingMessageCount = pendingMessageCount,
                timestamp = timestamp
            )
        }
    }
}
