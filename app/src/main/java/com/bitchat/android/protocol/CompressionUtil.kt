package com.bitchat.android.protocol

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compression utilities - LZ4-like functionality using Deflater/Inflater
 * Android doesn't have native LZ4, so we use Java's built-in compression
 */
object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = 100  // bytes - same as iOS
    
    /**
     * Helper to check if compression is worth it - exact same logic as iOS
     */
    fun shouldCompress(data: ByteArray): Boolean {
        // TODO: COMPRESSION DOESN'T WORK WITH IOS YET
        return false
        // Don't compress if:
        // 1. Data is too small
        // 2. Data appears to be already compressed (high entropy)
        if (data.size < COMPRESSION_THRESHOLD) return false
        
        // Simple entropy check - count unique bytes
        val byteFrequency = mutableMapOf<Byte, Int>()
        for (byte in data) {
            byteFrequency[byte] = (byteFrequency[byte] ?: 0) + 1
        }
        
        // If we have very high byte diversity, data is likely already compressed
        val uniqueByteRatio = byteFrequency.size.toDouble() / minOf(data.size, 256).toDouble()
        return uniqueByteRatio < 0.9 // Compress if less than 90% unique bytes
    }
    
    /**
     * Compress data using Deflater (closest to LZ4 available on Android)
     */
    fun compress(data: ByteArray): ByteArray? {
        // Skip compression for small data
        if (data.size < COMPRESSION_THRESHOLD) return null
        
        try {
            val deflater = Deflater(Deflater.BEST_SPEED) // Fast compression like LZ4
            deflater.setInput(data)
            deflater.finish()
            
            val buffer = ByteArray(data.size + 16) // Some overhead space
            val compressedSize = deflater.deflate(buffer)
            deflater.end()
            
            // Only return if compression was beneficial
            if (compressedSize > 0 && compressedSize < data.size) {
                return buffer.copyOfRange(0, compressedSize)
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Decompress data using Inflater
     */
    fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {
        try {
            val inflater = Inflater()
            inflater.setInput(compressedData)
            
            val result = ByteArray(originalSize)
            val decompressedSize = inflater.inflate(result)
            inflater.end()
            
            if (decompressedSize > 0) {
                return if (decompressedSize == originalSize) {
                    result
                } else {
                    result.copyOfRange(0, decompressedSize)
                }
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }
}
