package com.bitchat.android.protocol

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compression utilities - 100% iOS-compatible zlib implementation
 * Uses the same zlib algorithm as iOS CompressionUtil.swift
 */
object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = com.bitchat.android.util.AppConstants.Protocol.COMPRESSION_THRESHOLD_BYTES  // bytes - same as iOS
    
    /**
     * Helper to check if compression is worth it - exact same logic as iOS
     */
    fun shouldCompress(data: ByteArray): Boolean {
        // Don't compress if:
        // 1. Data is too small
        // 2. Data appears to be already compressed (high entropy)
        if (data.size < COMPRESSION_THRESHOLD) return false
        
        // Simple entropy check - count unique bytes (exact same as iOS)
        val byteFrequency = mutableMapOf<Byte, Int>()
        for (byte in data) {
            byteFrequency[byte] = (byteFrequency[byte] ?: 0) + 1
        }
        
        // If we have very high byte diversity, data is likely already compressed
        val uniqueByteRatio = byteFrequency.size.toDouble() / minOf(data.size, 256).toDouble()
        return uniqueByteRatio < 0.9 // Compress if less than 90% unique bytes
    }
    
    /**
     * Compress data using deflate algorithm - exact same as iOS
     * iOS COMPRESSION_ZLIB actually produces raw deflate data (no zlib headers)
     */
    fun compress(data: ByteArray): ByteArray? {
        // Skip compression for small data
        if (data.size < COMPRESSION_THRESHOLD) return null
        
        try {
            // Use raw deflate format (no headers) to match iOS COMPRESSION_ZLIB behavior
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true) // true = raw deflate, no headers
            deflater.setInput(data)
            deflater.finish()
            
            val outputStream = ByteArrayOutputStream(data.size)
            val buffer = ByteArray(1024)
            
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                outputStream.write(buffer, 0, count)
            }
            deflater.end()
            
            val compressedData = outputStream.toByteArray()
            
            // Only return if compression was beneficial (same logic as iOS)
            return if (compressedData.size > 0 && compressedData.size < data.size) {
                compressedData
            } else {
                null
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Decompress deflate compressed data - exact same as iOS
     * iOS COMPRESSION_ZLIB produces raw deflate data (no headers)
     */
    fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {
        // iOS COMPRESSION_ZLIB produces raw deflate format (no headers)
        try {
            val inflater = Inflater(true) // true = raw deflate, no headers
            inflater.setInput(compressedData)
            
            val decompressedBuffer = ByteArray(originalSize)
            val actualSize = inflater.inflate(decompressedBuffer)
            inflater.end()
            
            // Verify decompressed size matches expected (same validation as iOS)
            return if (actualSize == originalSize) {
                decompressedBuffer
            } else if (actualSize > 0) {
                // Handle case where actual size is different
                decompressedBuffer.copyOfRange(0, actualSize)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.d("CompressionUtil", "Raw deflate decompression failed: ${e.message}, trying with zlib headers...")
            
            // Fallback: try with zlib headers in case of mixed usage
            try {
                val inflater = Inflater(false) // false = expect zlib headers
                inflater.setInput(compressedData)
                
                val decompressedBuffer = ByteArray(originalSize)
                val actualSize = inflater.inflate(decompressedBuffer)
                inflater.end()
                
                return if (actualSize == originalSize) {
                    decompressedBuffer
                } else if (actualSize > 0) {
                    decompressedBuffer.copyOfRange(0, actualSize)
                } else {
                    null
                }
            } catch (fallbackException: Exception) {
                Log.e("CompressionUtil", "Both raw deflate and zlib decompression failed: ${fallbackException.message}")
                return null
            }
        }
    }
    
    /**
     * Test function to verify deflate compression works correctly
     * This can be called during app initialization to ensure compatibility
     */
    fun testCompression(): Boolean {
        try {
            // Create test data that should compress well (repeating pattern like iOS would use)
            val testMessage = "This is a test message that should compress well. ".repeat(10)
            val originalData = testMessage.toByteArray()
            
            Log.d("CompressionUtil", "Testing deflate compression with ${originalData.size} bytes")
            
            // Test shouldCompress
            val shouldCompress = shouldCompress(originalData)
            Log.d("CompressionUtil", "shouldCompress() returned: $shouldCompress")
            
            if (!shouldCompress) {
                Log.e("CompressionUtil", "shouldCompress failed for test data")
                return false
            }
            
            // Test compression
            val compressed = compress(originalData)
            if (compressed == null) {
                Log.e("CompressionUtil", "Compression failed")
                return false
            }
            
            Log.d("CompressionUtil", "Compressed ${originalData.size} bytes to ${compressed.size} bytes (${(compressed.size.toDouble() / originalData.size * 100).toInt()}%)")
            
            // Test decompression
            val decompressed = decompress(compressed, originalData.size)
            if (decompressed == null) {
                Log.e("CompressionUtil", "Decompression failed")
                return false
            }
            
            // Verify data integrity
            val isIdentical = originalData.contentEquals(decompressed)
            Log.d("CompressionUtil", "Data integrity check: $isIdentical")
            
            if (!isIdentical) {
                Log.e("CompressionUtil", "Decompressed data doesn't match original")
                return false
            }
            
            Log.i("CompressionUtil", "✅ deflate compression test PASSED - ready for iOS compatibility")
            return true
            
        } catch (e: Exception) {
            Log.e("CompressionUtil", "deflate compression test failed: ${e.message}")
            return false
        }
    }
}
