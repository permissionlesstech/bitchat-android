package com.bitchat.android.model

import com.bitchat.android.protocol.MessageType

/**
 * FragmentPayload - 100% iOS-compatible fragment payload structure
 * 
 * This class handles the encoding and decoding of fragment payloads exactly
 * as implemented in iOS bitchat SimplifiedBluetoothService.
 * 
 * Fragment payload structure (matching iOS):
 * - 8 bytes: Fragment ID (random bytes)
 * - 2 bytes: Index (big-endian) 
 * - 2 bytes: Total count (big-endian)
 * - 1 byte: Original message type
 * - Variable: Fragment data
 * 
 * Total header size: 13 bytes
 */
data class FragmentPayload(
    val fragmentID: ByteArray,      // 8 bytes - random fragment identifier
    val index: Int,                 // Fragment index (0-based)
    val total: Int,                 // Total number of fragments
    val originalType: UByte,        // Original message type before fragmentation
    val data: ByteArray             // Fragment data
) {
    
    companion object {
        const val HEADER_SIZE = 13
        const val FRAGMENT_ID_SIZE = 8
        
        /**
         * Decode fragment payload from binary data
         * Matches iOS implementation exactly
         */
        fun decode(payloadData: ByteArray): FragmentPayload? {
            if (payloadData.size < HEADER_SIZE) {
                return null
            }
            
            try {
                // Extract fragment ID (8 bytes)
                val fragmentID = payloadData.sliceArray(0..<FRAGMENT_ID_SIZE)
                
                // Extract index (2 bytes, big-endian) - matching iOS
                val index = ((payloadData[8].toInt() and 0xFF) shl 8) or 
                           (payloadData[9].toInt() and 0xFF)
                
                // Extract total (2 bytes, big-endian) - matching iOS  
                val total = ((payloadData[10].toInt() and 0xFF) shl 8) or
                           (payloadData[11].toInt() and 0xFF)
                
                // Extract original type (1 byte)
                val originalType = payloadData[12].toUByte()
                
                // Extract fragment data (remaining bytes)
                val data = if (payloadData.size > HEADER_SIZE) {
                    payloadData.sliceArray(HEADER_SIZE..<payloadData.size)
                } else {
                    ByteArray(0)
                }
                
                return FragmentPayload(fragmentID, index, total, originalType, data)
                
            } catch (e: Exception) {
                return null
            }
        }
        
        /**
         * Generate random fragment ID (8 bytes)
         * Matches iOS implementation: Data((0..<8).map { _ in UInt8.random(in: 0...255) })
         */
        fun generateFragmentID(): ByteArray {
            val fragmentID = ByteArray(FRAGMENT_ID_SIZE)
            kotlin.random.Random.nextBytes(fragmentID)
            return fragmentID
        }
    }
    
    /**
     * Encode fragment payload to binary data
     * Matches iOS implementation exactly
     */
    fun encode(): ByteArray {
        val payload = ByteArray(HEADER_SIZE + data.size)
        
        // Fragment ID (8 bytes)
        System.arraycopy(fragmentID, 0, payload, 0, FRAGMENT_ID_SIZE)
        
        // Index (2 bytes, big-endian) - matching iOS withUnsafeBytes(of: UInt16(index).bigEndian)
        payload[8] = ((index shr 8) and 0xFF).toByte()
        payload[9] = (index and 0xFF).toByte()
        
        // Total (2 bytes, big-endian) - matching iOS withUnsafeBytes(of: UInt16(fragments.count).bigEndian)
        payload[10] = ((total shr 8) and 0xFF).toByte()
        payload[11] = (total and 0xFF).toByte()
        
        // Original type (1 byte)
        payload[12] = originalType.toByte()
        
        // Fragment data
        if (data.isNotEmpty()) {
            System.arraycopy(data, 0, payload, HEADER_SIZE, data.size)
        }
        
        return payload
    }
    
    /**
     * Get fragment ID as hex string for logging/debugging
     */
    fun getFragmentIDString(): String {
        return fragmentID.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Validate fragment payload constraints
     */
    fun isValid(): Boolean {
        return fragmentID.size == FRAGMENT_ID_SIZE &&
               index >= 0 &&
               total > 0 &&
               index < total &&
               data.isNotEmpty()
    }
    
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        
        other as FragmentPayload
        
        if (!fragmentID.contentEquals(other.fragmentID)) return false
        if (index != other.index) return false
        if (total != other.total) return false
        if (originalType != other.originalType) return false
        if (!data.contentEquals(other.data)) return false
        
        return true
    }
    
    override fun hashCode(): Int {
        var result = fragmentID.contentHashCode()
        result = 31 * result + index
        result = 31 * result + total
        result = 31 * result + originalType.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
    
    override fun toString(): String {
        return "FragmentPayload(fragmentID=${getFragmentIDString()}, index=$index, total=$total, originalType=$originalType, dataSize=${data.size})"
    }
}
