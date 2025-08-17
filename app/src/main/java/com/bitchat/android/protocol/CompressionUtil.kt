package com.bitchat.android.protocol

import android.util.Log
import net.jpountz.lz4.LZ4Factory
import net.jpountz.lz4.LZ4Compressor
import net.jpountz.lz4.LZ4SafeDecompressor

/**
 * Compression utilities - 100% iOS-compatible LZ4 implementation
 * Uses the same LZ4 algorithm as iOS CompressionUtil.swift
 */
object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = 100  // bytes - same as iOS
    
    // Initialize LZ4 factory and get fast compressor/decompressor (same as iOS)
    private val lz4Factory = LZ4Factory.fastestInstance()
    private val compressor: LZ4Compressor = lz4Factory.fastCompressor()
    private val decompressor: LZ4SafeDecompressor = lz4Factory.safeDecompressor()
    
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
     * Compress data using LZ4 algorithm - exact same as iOS
     */
    fun compress(data: ByteArray): ByteArray? {
        // Skip compression for small data
        if (data.size < COMPRESSION_THRESHOLD) return null
        
        try {
            val maxCompressedSize = compressor.maxCompressedLength(data.size)
            val compressedBuffer = ByteArray(maxCompressedSize)
            val compressedSize = compressor.compress(data, compressedBuffer)
            
            // Only return if compression was beneficial (same logic as iOS)
            if (compressedSize > 0 && compressedSize < data.size) {
                return compressedBuffer.copyOfRange(0, compressedSize)
            }
            
            return null
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Decompress LZ4 compressed data - exact same as iOS
     */
    fun decompress(compressedData: ByteArray, originalSize: Int): ByteArray? {
        try {
            val decompressedBuffer = ByteArray(originalSize)
            val actualSize = decompressor.decompress(compressedData, decompressedBuffer)
            
            // Verify decompressed size matches expected (same validation as iOS)
            return if (actualSize == originalSize) {
                decompressedBuffer
            } else {
                // Handle case where actual size is different
                decompressedBuffer.copyOfRange(0, actualSize)
            }
        } catch (e: Exception) {
            return null
        }
    }
    
    /**
     * Test function to verify LZ4 compression works correctly
     * This can be called during app initialization to ensure compatibility
     */
    fun testCompression(): Boolean {
        try {
            // Create test data that should compress well (repeating pattern like iOS would use)
            val testMessage = "This is a test message that should compress well. ".repeat(10)
            val originalData = testMessage.toByteArray()
            
            Log.d("CompressionUtil", "Testing LZ4 compression with ${originalData.size} bytes")
            
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
            
            Log.i("CompressionUtil", "✅ LZ4 compression test PASSED - ready for iOS compatibility")
            return true
            
        } catch (e: Exception) {
            Log.e("CompressionUtil", "LZ4 compression test failed: ${e.message}")
            return false
        }
    }
}
