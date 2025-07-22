package com.bitchat.android.util

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.*

/**
 * Binary encoding utilities for efficient protocol messages
 * Compatible with iOS version BinaryEncodingUtils.swift
 */

// MARK: - Hex Encoding/Decoding Extensions

fun ByteArray.hexEncodedString(): String {
    if (this.isEmpty()) {
        return ""
    }
    return this.joinToString("") { "%02x".format(it) }
}

fun String.dataFromHexString(): ByteArray? {
    val len = this.length / 2
    val data = ByteArray(len)
    var index = 0
    
    for (i in 0 until len) {
        val hexByte = this.substring(i * 2, i * 2 + 2)
        val byte = hexByte.toIntOrNull(16)?.toByte() ?: return null
        data[index++] = byte
    }
    
    return data
}

// MARK: - Binary Encoding Utilities

class BinaryDataBuilder {
    private val _buffer = mutableListOf<Byte>()
    
    // Make buffer accessible for direct manipulation when needed
    val buffer: MutableList<Byte> get() = _buffer
    
    // MARK: Writing
    
    fun appendUInt8(value: UByte) {
        buffer.add(value.toByte())
    }
    
    fun appendUInt16(value: UShort) {
        buffer.add(((value.toInt() shr 8) and 0xFF).toByte())
        buffer.add((value.toInt() and 0xFF).toByte())
    }
    
    fun appendUInt32(value: UInt) {
        buffer.add(((value.toLong() shr 24) and 0xFF).toByte())
        buffer.add(((value.toLong() shr 16) and 0xFF).toByte())
        buffer.add(((value.toLong() shr 8) and 0xFF).toByte())
        buffer.add((value.toLong() and 0xFF).toByte())
    }
    
    fun appendUInt64(value: ULong) {
        for (i in 7 downTo 0) {
            buffer.add(((value.toLong() shr (i * 8)) and 0xFF).toByte())
        }
    }
    
    fun appendString(string: String, maxLength: Int = 255) {
        val data = string.toByteArray(Charsets.UTF_8)
        val length = minOf(data.size, maxLength)
        
        if (maxLength <= 255) {
            buffer.add(length.toByte())
        } else {
            appendUInt16(length.toUShort())
        }
        
        buffer.addAll(data.take(length).toList())
    }
    
    fun appendData(data: ByteArray, maxLength: Int = 65535) {
        val length = minOf(data.size, maxLength)
        
        if (maxLength <= 255) {
            buffer.add(length.toByte())
        } else {
            appendUInt16(length.toUShort())
        }
        
        buffer.addAll(data.take(length).toList())
    }
    
    fun appendDate(date: Date) {
        val timestamp = (date.time).toULong() // milliseconds
        appendUInt64(timestamp)
    }
    
    fun appendUUID(uuid: String) {
        // Convert UUID string to 16 bytes
        val uuidData = ByteArray(16)
        
        val cleanUUID = uuid.replace("-", "")
        var index = 0
        
        for (i in 0 until 16) {
            if (index + 1 < cleanUUID.length) {
                val hexByte = cleanUUID.substring(index, index + 2)
                uuidData[i] = hexByte.toIntOrNull(16)?.toByte() ?: 0
                index += 2
            }
        }
        
        buffer.addAll(uuidData.toList())
    }
    
    fun toByteArray(): ByteArray {
        return buffer.toByteArray()
    }
}

// MARK: - Binary Data Reading Extensions

class BinaryDataReader(private val data: ByteArray) {
    private var offset = 0
    
    // MARK: Reading
    
    fun readUInt8(): UByte? {
        if (offset >= data.size) return null
        val value = data[offset].toUByte()
        offset += 1
        return value
    }
    
    fun readUInt16(): UShort? {
        if (offset + 2 > data.size) return null
        val value = ((data[offset].toUByte().toInt() shl 8) or 
                    (data[offset + 1].toUByte().toInt())).toUShort()
        offset += 2
        return value
    }
    
    fun readUInt32(): UInt? {
        if (offset + 4 > data.size) return null
        val value = ((data[offset].toUByte().toUInt() shl 24) or
                    (data[offset + 1].toUByte().toUInt() shl 16) or
                    (data[offset + 2].toUByte().toUInt() shl 8) or
                    (data[offset + 3].toUByte().toUInt()))
        offset += 4
        return value
    }
    
    fun readUInt64(): ULong? {
        if (offset + 8 > data.size) return null
        var value = 0UL
        for (i in 0 until 8) {
            value = (value shl 8) or data[offset + i].toUByte().toULong()
        }
        offset += 8
        return value
    }
    
    fun readString(maxLength: Int = 255): String? {
        val length: Int = if (maxLength <= 255) {
            readUInt8()?.toInt() ?: return null
        } else {
            readUInt16()?.toInt() ?: return null
        }
        
        if (offset + length > data.size) return null
        
        val stringData = data.sliceArray(offset until offset + length)
        offset += length
        
        return String(stringData, Charsets.UTF_8)
    }
    
    fun readData(maxLength: Int = 65535): ByteArray? {
        val length: Int = if (maxLength <= 255) {
            readUInt8()?.toInt() ?: return null
        } else {
            readUInt16()?.toInt() ?: return null
        }
        
        if (offset + length > data.size) return null
        
        val data = this.data.sliceArray(offset until offset + length)
        offset += length
        
        return data
    }
    
    fun readDate(): Date? {
        val timestamp = readUInt64() ?: return null
        return Date(timestamp.toLong())
    }
    
    fun readUUID(): String? {
        if (offset + 16 > data.size) return null
        
        val uuidData = data.sliceArray(offset until offset + 16)
        offset += 16
        
        // Convert 16 bytes to UUID string format
        val uuid = uuidData.joinToString("") { "%02x".format(it) }
        
        // Insert hyphens at proper positions: 8-4-4-4-12
        val result = StringBuilder()
        for ((index, char) in uuid.withIndex()) {
            if (index == 8 || index == 12 || index == 16 || index == 20) {
                result.append("-")
            }
            result.append(char)
        }
        
        return result.toString().uppercase()
    }
    
    fun readFixedBytes(count: Int): ByteArray? {
        if (offset + count > data.size) return null
        
        val data = this.data.sliceArray(offset until offset + count)
        offset += count
        
        return data
    }
    
    // Get current offset position
    val currentOffset: Int get() = offset
}

// MARK: - Binary Message Protocol

interface BinaryEncodable {
    fun toBinaryData(): ByteArray
}

// MARK: - Message Type Registry

enum class BinaryMessageType(val value: UByte) {
    DELIVERY_ACK(0x01u),
    READ_RECEIPT(0x02u),
    CHANNEL_KEY_VERIFY_REQUEST(0x03u),
    CHANNEL_KEY_VERIFY_RESPONSE(0x04u),
    CHANNEL_PASSWORD_UPDATE(0x05u),
    CHANNEL_METADATA(0x06u),
    VERSION_HELLO(0x07u),
    VERSION_ACK(0x08u),
    NOISE_IDENTITY_ANNOUNCEMENT(0x09u),
    NOISE_MESSAGE(0x0Au);
    
    companion object {
        fun fromValue(value: UByte): BinaryMessageType? {
            return values().find { it.value == value }
        }
    }
}

// Extension functions for ByteArray to support iOS-style data manipulation
fun ByteArray.readUInt8(at: IntArray): UByte? {
    val offset = at[0]
    if (offset >= this.size) return null
    val value = this[offset].toUByte()
    at[0] += 1
    return value
}

fun ByteArray.readUInt16(at: IntArray): UShort? {
    val offset = at[0]
    if (offset + 2 > this.size) return null
    val value = ((this[offset].toUByte().toInt() shl 8) or 
                (this[offset + 1].toUByte().toInt())).toUShort()
    at[0] += 2
    return value
}

fun ByteArray.readUInt32(at: IntArray): UInt? {
    val offset = at[0]
    if (offset + 4 > this.size) return null
    val value = ((this[offset].toUByte().toUInt() shl 24) or
                (this[offset + 1].toUByte().toUInt() shl 16) or
                (this[offset + 2].toUByte().toUInt() shl 8) or
                (this[offset + 3].toUByte().toUInt()))
    at[0] += 4
    return value
}

fun ByteArray.readUInt64(at: IntArray): ULong? {
    val offset = at[0]
    if (offset + 8 > this.size) return null
    var value = 0UL
    for (i in 0 until 8) {
        value = (value shl 8) or this[offset + i].toUByte().toULong()
    }
    at[0] += 8
    return value
}

fun ByteArray.readString(at: IntArray, maxLength: Int = 255): String? {
    val length: Int = if (maxLength <= 255) {
        readUInt8(at)?.toInt() ?: return null
    } else {
        readUInt16(at)?.toInt() ?: return null
    }
    
    val offset = at[0]
    if (offset + length > this.size) return null
    
    val stringData = this.sliceArray(offset until offset + length)
    at[0] += length
    
    return String(stringData, Charsets.UTF_8)
}

fun ByteArray.readData(at: IntArray, maxLength: Int = 65535): ByteArray? {
    val length: Int = if (maxLength <= 255) {
        readUInt8(at)?.toInt() ?: return null
    } else {
        readUInt16(at)?.toInt() ?: return null
    }
    
    val offset = at[0]
    if (offset + length > this.size) return null
    
    val data = this.sliceArray(offset until offset + length)
    at[0] += length
    
    return data
}

fun ByteArray.readDate(at: IntArray): Date? {
    val timestamp = readUInt64(at) ?: return null
    return Date(timestamp.toLong())
}

fun ByteArray.readUUID(at: IntArray): String? {
    val offset = at[0]
    if (offset + 16 > this.size) return null
    
    val uuidData = this.sliceArray(offset until offset + 16)
    at[0] += 16
    
    // Convert 16 bytes to UUID string format
    val uuid = uuidData.joinToString("") { "%02x".format(it) }
    
    // Insert hyphens at proper positions: 8-4-4-4-12
    val result = StringBuilder()
    for ((index, char) in uuid.withIndex()) {
        if (index == 8 || index == 12 || index == 16 || index == 20) {
            result.append("-")
        }
        result.append(char)
    }
    
    return result.toString().uppercase()
}

fun ByteArray.readFixedBytes(at: IntArray, count: Int): ByteArray? {
    val offset = at[0]
    if (offset + count > this.size) return null
    
    val data = this.sliceArray(offset until offset + count)
    at[0] += count
    
    return data
}
