package com.bitchat.android.protocol

import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Compression utilities - 100% iOS-compatible zlib implementation
 * Uses the same zlib algorithm as iOS CompressionUtil.swift
 */
object CompressionUtil {
    private const val COMPRESSION_THRESHOLD = com.bitchat.android.util.AppConstants.Protocol.COMPRESSION_THRESHOLD_BYTES  // bytes - same as iOS

    private val decompressionPool = DecompressionResourcePool.forRuntime()
    
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
        if (!isValidRequest(compressedData, originalSize)) return null
        return withDecompressionResources(originalSize.toLong()) {
            decompressWithResourcesReserved(compressedData, originalSize)
        }
    }

    internal fun <T> withDecompressionResources(bytes: Long, block: () -> T): T? =
        decompressionPool.withReservation(bytes, block)

    /**
     * Inflate after the caller has reserved all packet-specific allocations.
     * This avoids nested acquisition when BinaryProtocol reserves both its input copy and output.
     */
    internal fun decompressWithResourcesReserved(
        compressedData: ByteArray,
        originalSize: Int
    ): ByteArray? {
        if (!isValidRequest(compressedData, originalSize)) return null
        return decompressExact(compressedData, originalSize)
    }

    private fun isValidRequest(compressedData: ByteArray, originalSize: Int): Boolean {
        val maxExpandedSize = com.bitchat.android.util.AppConstants.Protocol.MAX_PAYLOAD_LENGTH
        if (compressedData.isEmpty()) {
            Log.w("CompressionUtil", "Refusing an empty compressed payload")
            return false
        }
        if (originalSize <= 0 || originalSize > maxExpandedSize) {
            Log.w(
                "CompressionUtil",
                "Refusing expanded payload size $originalSize outside 1..$maxExpandedSize"
            )
            return false
        }
        return true
    }

    private fun decompressExact(compressedData: ByteArray, originalSize: Int): ByteArray? {
        return if (looksLikeZlib(compressedData)) {
            // A raw stream can coincidentally begin with a valid-looking zlib header. The
            // header therefore only determines which format to try first; any non-exact zlib
            // result must still fall back to raw under the same size/completion bounds.
            val zlibResult = try {
                inflateExact(compressedData, originalSize, nowrap = false)
            } catch (zlibException: DataFormatException) {
                null
            }
            if (zlibResult != null) {
                zlibResult
            } else {
                try {
                    inflateExact(compressedData, originalSize, nowrap = true)
                } catch (rawException: DataFormatException) {
                    Log.d("CompressionUtil", "Invalid zlib/raw deflate stream")
                    null
                }
            }
        } else {
            try {
                inflateExact(compressedData, originalSize, nowrap = true)
            } catch (rawException: DataFormatException) {
                Log.d("CompressionUtil", "Invalid raw deflate stream")
                null
            }
        }
    }

    /** RFC 1950 header check used to avoid speculative double inflation. */
    private fun looksLikeZlib(data: ByteArray): Boolean {
        if (data.size < 2) return false
        val cmf = data[0].toInt() and 0xFF
        val flg = data[1].toInt() and 0xFF
        return (cmf and 0x0F) == 8 &&
            (cmf ushr 4) <= 7 &&
            ((cmf shl 8) or flg) % 31 == 0
    }

    /**
     * Inflate one complete stream into exactly [originalSize] bytes.
     *
     * A full output buffer alone is not success: an attacker can under-declare a larger stream so
     * the first inflate call fills the buffer while [Inflater.finished] remains false. Conversely,
     * a truncated or over-declared stream can produce a non-empty prefix. Both forms are rejected,
     * as are trailing bytes after the compressed stream.
     *
     * [DataFormatException] is deliberately allowed to escape so the caller can try the legacy
     * zlib-wrapped format. Size/completion mismatches return null; the fallback must then prove the
     * same bytes are a complete, exact-sized zlib stream before they can be accepted.
     */
    @Throws(DataFormatException::class)
    private fun inflateExact(
        compressedData: ByteArray,
        originalSize: Int,
        nowrap: Boolean
    ): ByteArray? {
        val inflater = Inflater(nowrap)
        return try {
            inflater.setInput(compressedData)
            val output = ByteArray(originalSize)
            var written = 0

            while (written < originalSize) {
                val count = inflater.inflate(output, written, originalSize - written)
                if (count == 0) break
                written += count
            }

            if (written != originalSize) return null

            // Give Inflater one byte of room to consume the end marker. Any produced byte proves
            // the declared size was smaller than the actual expansion.
            val overflowProbe = ByteArray(1)
            if (inflater.inflate(overflowProbe) != 0) return null

            if (!inflater.finished() || inflater.remaining != 0) return null
            output
        } finally {
            inflater.end()
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
