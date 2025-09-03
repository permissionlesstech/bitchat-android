package com.bitchat

import com.bitchat.android.protocol.CompressionUtil
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertNull
import junit.framework.TestCase.assertTrue
import junit.framework.TestCase.assertFalse
import org.junit.Test

/**
 * LZ4 Cross-Platform Compatibility Test
 * 
 * This test specifically addresses the iOS-Android LZ4 compression compatibility issue
 * where Android throws "Malformed input at 9" when trying to decompress iOS compressed data.
 * 
 * The issue occurs when:
 * - iOS compresses data using Apple's Compression framework (COMPRESSION_LZ4)
 * - Android tries to decompress it using net.jpountz.lz4 library
 */
class LZ4CrossPlatformTest {

    companion object {
        // This is the exact iOS compressed data that causes Android to fail
        // From the original error case: iOS compressed "COMPRESSING DATA BRO" repeated 20 times
        // iOS output: 62763431900100009b000000ff05434f4d5052455353494e4720444154412042524f1400e9f071444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f62763424
        private const val IOS_COMPRESSED_HEX = "62763431900100009b000000ff05434f4d5052455353494e4720444154412042524f1400e9f071444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f434f4d5052455353494e4720444154412042524f62763424"
        
        // Original test data (should be 400 bytes when UTF-8 encoded)
        private const val TEST_MESSAGE = "COMPRESSING DATA BRO"
        private val TEST_DATA_REPEATED = TEST_MESSAGE.repeat(20)
        private val EXPECTED_ORIGINAL_SIZE = 400
    }

    @Test
    fun test_original_data_integrity() {
        // Verify our test data matches exactly what iOS used
        val originalData = TEST_DATA_REPEATED.toByteArray(Charsets.UTF_8)
        
        assertEquals("Test data should be exactly 400 bytes", EXPECTED_ORIGINAL_SIZE, originalData.size)
        assertEquals("Test string should be 400 characters", 400, TEST_DATA_REPEATED.length)
        assertTrue("Test string should start with 'COMPRESSING DATA BRO'", TEST_DATA_REPEATED.startsWith(TEST_MESSAGE))
        
        // Log the first few bytes for verification
        val firstBytes = originalData.take(20).joinToString(" ") { "%02x".format(it) }
        println("Original data first 20 bytes: $firstBytes")
        println("Original data size: ${originalData.size} bytes")
    }

    @Test
    fun test_android_compression_works() {
        // Verify Android can compress and decompress its own data
        val originalData = TEST_DATA_REPEATED.toByteArray(Charsets.UTF_8)
        
        // Test compression
        val shouldCompress = CompressionUtil.shouldCompress(originalData)
        assertTrue("Data should be suitable for compression", shouldCompress)
        
        val compressed = CompressionUtil.compress(originalData)
        assertNotNull("Android compression should succeed", compressed)
        assertTrue("Compressed data should be smaller", compressed!!.size < originalData.size)
        
        // Log Android compression result
        val androidHex = compressed.joinToString("") { "%02x".format(it) }
        println("Android compressed size: ${compressed.size} bytes")
        println("Android compressed hex: $androidHex")
        
        // Test decompression
        val decompressed = CompressionUtil.decompress(compressed, originalData.size)
        assertNotNull("Android decompression should succeed", decompressed)
        assertTrue("Decompressed data should match original", decompressed!!.contentEquals(originalData))
        
        println("✅ Android self-compression test passed")
    }

    @Test
    fun test_ios_compressed_data_structure() {
        // Parse and analyze the iOS compressed data structure
        val iosCompressedData = hexStringToByteArray(IOS_COMPRESSED_HEX)
        assertNotNull("iOS compressed hex should be valid", iosCompressedData)
        
        println("iOS compressed data analysis:")
        println("- Size: ${iosCompressedData!!.size} bytes")
        println("- First 16 bytes: ${iosCompressedData.take(16).joinToString(" ") { "%02x".format(it) }}")
        println("- Last 16 bytes: ${iosCompressedData.takeLast(16).joinToString(" ") { "%02x".format(it) }}")
        
        // Check if this starts with known LZ4 markers
        val firstFourBytes = iosCompressedData.take(4).joinToString("") { "%02x".format(it) }
        println("- First 4 bytes (potential header): $firstFourBytes")
        
        // The iOS data starts with "62763431" which may be a different LZ4 frame format
        assertEquals("iOS data should start with expected header", "62763431", firstFourBytes)
    }

    @Test
    fun test_cross_platform_compatibility_failure() {
        // This test demonstrates the incompatibility issue
        val iosCompressedData = hexStringToByteArray(IOS_COMPRESSED_HEX)
        assertNotNull("iOS compressed data should be valid", iosCompressedData)
        
        println("Testing iOS -> Android decompression compatibility...")
        
        // Try to decompress iOS data with Android LZ4 library
        val originalSize = EXPECTED_ORIGINAL_SIZE
        val decompressed = CompressionUtil.decompress(iosCompressedData!!, originalSize)
        
        if (decompressed != null) {
            // If this succeeds, check if the data is correct
            val decompressedString = decompressed.toString(Charsets.UTF_8)
            val isCorrect = decompressedString == TEST_DATA_REPEATED
            
            if (isCorrect) {
                println("✅ UNEXPECTED SUCCESS: iOS-Android compatibility works!")
            } else {
                println("❌ Decompression succeeded but data is corrupted")
                println("Expected: ${TEST_DATA_REPEATED.take(50)}...")
                println("Got: ${decompressedString.take(50)}...")
            }
            assertTrue("If decompression succeeds, data should be correct", isCorrect)
        } else {
            println("❌ EXPECTED FAILURE: Android cannot decompress iOS compressed data")
            println("This confirms the cross-platform compatibility issue")
            
            // This is the expected behavior - the test passes because we expect it to fail
            // This documents the known incompatibility issue
        }
    }

    @Test
    fun test_format_comparison() {
        // Compare Android and iOS compression formats
        val originalData = TEST_DATA_REPEATED.toByteArray(Charsets.UTF_8)
        val androidCompressed = CompressionUtil.compress(originalData)
        val iosCompressedData = hexStringToByteArray(IOS_COMPRESSED_HEX)
        
        assertNotNull("Android compression should work", androidCompressed)
        assertNotNull("iOS compressed data should be valid", iosCompressedData)
        
        println("Format comparison:")
        println("Android compressed: ${androidCompressed!!.size} bytes")
        println("iOS compressed: ${iosCompressedData!!.size} bytes")
        
        // Compare headers
        val androidHeader = androidCompressed.take(8).joinToString(" ") { "%02x".format(it) }
        val iosHeader = iosCompressedData.take(8).joinToString(" ") { "%02x".format(it) }
        
        println("Android header: $androidHeader")
        println("iOS header: $iosHeader")
        
        val headersMatch = androidHeader == iosHeader
        println("Headers match: $headersMatch")
        
        if (!headersMatch) {
            println("❌ Different headers confirm format incompatibility")
            println("Android uses: net.jpountz.lz4 format")
            println("iOS uses: Apple Compression framework format")
        }
    }

    @Test
    fun test_manual_format_detection() {
        // Try to detect what format iOS is using
        val iosCompressedData = hexStringToByteArray(IOS_COMPRESSED_HEX)
        assertNotNull("iOS data should be valid", iosCompressedData)
        
        println("iOS format analysis:")
        
        // Check for common LZ4 signatures
        val first4 = iosCompressedData!!.take(4)
        val header = first4.joinToString("") { "%02x".format(it) }
        
        when (header) {
            "04224d18" -> println("- Format: LZ4 legacy format")
            "184d2204" -> println("- Format: LZ4 frame format (little endian)")
            "62763431" -> println("- Format: Unknown/Custom format (starts with $header)")
            else -> println("- Format: Unrecognized (starts with $header)")
        }
        
        // Try to find patterns
        val dataBytes = iosCompressedData.toList()
        val hasRepeatingPattern = dataBytes.windowed(4).any { window ->
            window.all { it == window[0] }
        }
        
        println("- Has repeating 4-byte patterns: $hasRepeatingPattern")
        println("- Total size: ${iosCompressedData.size} bytes")
        
        // Look for the original text in compressed data (sometimes visible in LZ4)
        val dataString = iosCompressedData.toString(Charsets.ISO_8859_1)
        val hasOriginalText = dataString.contains("COMPRESSING") || dataString.contains("DATA") || dataString.contains("BRO")
        println("- Contains original text fragments: $hasOriginalText")
    }

    @Test
    fun test_potential_solutions() {
        println("Potential solutions for iOS-Android LZ4 compatibility:")
        println("1. Use same LZ4 library on both platforms")
        println("2. Implement format conversion layer")
        println("3. Switch to different compression algorithm (e.g., gzip)")
        println("4. Use raw LZ4 without frame headers")
        println("5. Implement custom frame format wrapper")
        
        // Test if Android can handle raw LZ4 data (without frame headers)
        val iosCompressedData = hexStringToByteArray(IOS_COMPRESSED_HEX)
        assertNotNull("iOS data should be valid", iosCompressedData)
        
        // Try skipping potential header bytes and see if we can find LZ4 payload
        for (skipBytes in listOf(0, 4, 8, 12, 16)) {
            if (skipBytes >= iosCompressedData!!.size) continue
            
            val payload = iosCompressedData.drop(skipBytes).toByteArray()
            if (payload.isEmpty()) continue
            
            println("Trying to decompress starting at byte $skipBytes (${payload.size} bytes remaining)...")
            
            val result = CompressionUtil.decompress(payload, EXPECTED_ORIGINAL_SIZE)
            if (result != null) {
                val resultString = result.toString(Charsets.UTF_8)
                val isCorrect = resultString == TEST_DATA_REPEATED
                
                if (isCorrect) {
                    println("✅ SUCCESS: Found valid LZ4 payload at offset $skipBytes")
                    return
                } else {
                    println("Decompressed but data incorrect at offset $skipBytes")
                }
            }
        }
        
        println("❌ No valid LZ4 payload found at any offset")
    }

    // Helper function to convert hex string to byte array
    private fun hexStringToByteArray(hexString: String): ByteArray? {
        val cleanHex = hexString.replace(" ", "").replace("-", "")
        if (cleanHex.length % 2 != 0) return null
        
        return try {
            cleanHex.chunked(2)
                .map { it.toInt(16).toByte() }
                .toByteArray()
        } catch (e: NumberFormatException) {
            null
        }
    }
}
