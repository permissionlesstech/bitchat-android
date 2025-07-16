package com.bitchat.android.protocol

import java.security.SecureRandom

/**
 * Privacy-preserving padding utilities - exact same as iOS version
 * Provides traffic analysis resistance by normalizing message sizes
 */
object MessagePadding {
    // Standard block sizes for padding - exact same as iOS
    private val blockSizes = listOf(256, 512, 1024, 2048)
    
    /**
     * Find optimal block size for data - exact same logic as iOS
     */
    fun optimalBlockSize(dataSize: Int): Int {
        // Account for encryption overhead (~16 bytes for AES-GCM tag)
        val totalSize = dataSize + 16
        
        // Find smallest block that fits
        for (blockSize in blockSizes) {
            if (totalSize <= blockSize) {
                return blockSize
            }
        }
        
        // For very large messages, just use the original size
        // (will be fragmented anyway)
        return dataSize
    }
    
    /**
     * Add PKCS#7-style padding to reach target size - exact same as iOS
     */
    fun pad(data: ByteArray, targetSize: Int): ByteArray {
        if (data.size >= targetSize) return data
        
        val paddingNeeded = targetSize - data.size
        
        // PKCS#7 only supports padding up to 255 bytes
        // If we need more padding than that, don't pad - return original data
        if (paddingNeeded > 255) return data
        
        val result = ByteArray(targetSize)
        
        // Copy original data
        System.arraycopy(data, 0, result, 0, data.size)
        
        // Standard PKCS#7 padding - fill with random bytes then add padding length
        val randomBytes = ByteArray(paddingNeeded - 1)
        SecureRandom().nextBytes(randomBytes)
        
        // Copy random bytes
        System.arraycopy(randomBytes, 0, result, data.size, paddingNeeded - 1)
        
        // Last byte tells how much padding was added
        result[result.size - 1] = paddingNeeded.toByte()
        
        return result
    }
    
    /**
     * Remove padding from data - exact same as iOS
     */
    fun unpad(data: ByteArray): ByteArray {
        if (data.isEmpty()) return data
        
        // Last byte tells us how much padding to remove
        val paddingLength = data[data.size - 1].toInt() and 0xFF
        if (paddingLength <= 0 || paddingLength > data.size) {
            // Invalid padding, return original data
            return data
        }
        
        return data.copyOfRange(0, data.size - paddingLength)
    }
}
