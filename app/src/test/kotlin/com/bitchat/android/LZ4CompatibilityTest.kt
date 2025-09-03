package com.bitchat.android

import com.bitchat.android.protocol.CompressionUtil
import org.junit.Test
import org.junit.Assert.*

/**
 * Cross-platform LZ4 compatibility verification tests
 * These tests must use IDENTICAL test data as iOS LZ4CompatibilityTests.swift
 */
class LZ4CompatibilityTest {
    
    companion object {
        // Test Data Constants (MUST MATCH iOS exactly)
        const val TEST_STRING_1 = "Hello LZ4 World!"
        const val TEST_STRING_4 = "Simple short message"
        
        // Non-const values that use repeat() function
        val TEST_STRING_2 = "COMPRESSING DATA BRO".repeat(20) // 400 bytes - same as error packet
        val TEST_STRING_3 = "This is a test message that should compress well. ".repeat(10)
    }
    
    @Test
    fun testAndroidCompressionOutput() {
        println("\n=== Android LZ4 Compression Compatibility Test ===")
        
        val testCases = listOf(
            "testString1" to TEST_STRING_1,
            "testString2" to TEST_STRING_2,
            "testString3" to TEST_STRING_3,
            "testString4" to TEST_STRING_4
        )
        
        for ((name, testString) in testCases) {
            println("\n--- Testing $name ---")
            
            val originalData = testString.toByteArray(Charsets.UTF_8)
            println("Original size: ${originalData.size} bytes")
            println("Original data (first 50 bytes): ${originalData.take(50).joinToString(" ") { "%02x".format(it) }}")
            
            // Test shouldCompress
            val shouldCompress = CompressionUtil.shouldCompress(originalData)
            println("shouldCompress: $shouldCompress")
            
            if (shouldCompress) {
                // Test compression
                val compressedData = CompressionUtil.compress(originalData)
                if (compressedData != null) {
                    println("Compressed size: ${compressedData.size} bytes")
                    println("Compression ratio: ${"%.1f".format(compressedData.size.toDouble() / originalData.size * 100)}%")
                    println("Android compressed data (hex): ${compressedData.joinToString(" ") { "%02x".format(it) }}")
                    
                    // Test decompression
                    val decompressedData = CompressionUtil.decompress(compressedData, originalData.size)
                    if (decompressedData != null) {
                        val decompressedString = decompressedData.toString(Charsets.UTF_8)
                        val isIdentical = decompressedData.contentEquals(originalData)
                        println("Decompression successful: $isIdentical")
                        
                        if (!isIdentical) {
                            println("❌ Decompressed data doesn't match original!")
                            println("Expected: ${testString.take(100)}")
                            println("Got: ${decompressedString.take(100)}")
                        } else {
                            println("✅ Android compression round-trip successful")
                        }
                    } else {
                        println("❌ Android decompression failed")
                    }
                } else {
                    println("❌ Android compression failed")
                }
            } else {
                println("⚠️  Data too small for compression")
            }
        }
    }
    
    @Test
    fun testCrossPlatformCompatibility() {
        println("\n=== Cross-Platform LZ4 Compatibility Test ===")
        
        // Test data that matches the error case exactly
        val errorCaseData = TEST_STRING_2.toByteArray(Charsets.UTF_8)
        println("Testing error case data: ${errorCaseData.size} bytes")
        println("Content preview: ${TEST_STRING_2.take(50)}...")
        
        val androidCompressed = CompressionUtil.compress(errorCaseData)
        assertNotNull("Android compression failed for error case data", androidCompressed)
        
        println("\nANDROID COMPRESSED OUTPUT for cross-platform test:")
        println("Size: ${androidCompressed!!.size} bytes")
        println("Hex data: ${androidCompressed.joinToString(" ") { "%02x".format(it) }}")
        
        // Output in format that can be easily copied to iOS test
        val hexString = androidCompressed.joinToString("") { "%02x".format(it) }
        println("iOS test input (copy this): \"$hexString\"")
        
        // Test if Android can decompress its own data (should work)
        val androidDecompressed = CompressionUtil.decompress(androidCompressed, errorCaseData.size)
        if (androidDecompressed != null) {
            val isValid = androidDecompressed.contentEquals(errorCaseData)
            println("Android self-decompression: ${if (isValid) "✅ SUCCESS" else "❌ FAILED"}")
        } else {
            println("Android self-decompression: ❌ FAILED")
        }
    }
    
    @Test
    fun testIOSCompressedDataDecompression() {
        println("\n=== Testing iOS Compressed Data on Android ===")
        
        // This will be filled in after running iOS tests
        // Placeholder for iOS compressed data - will be updated after iOS test runs
        val iosCompressedHex = "" // Will be updated with iOS output
        
        if (iosCompressedHex.isEmpty()) {
            println("⚠️  iOS compressed data not available yet - run iOS test first")
            return
        }
        
        // Convert hex string to data
        val iosCompressedData = hexStringToByteArray(iosCompressedHex)
        if (iosCompressedData == null) {
            println("❌ Failed to parse iOS compressed hex data")
            return
        }
        
        println("iOS compressed data size: ${iosCompressedData.size} bytes")
        println("Attempting Android decompression...")
        
        // Try to decompress iOS compressed data with Android
        val originalSize = TEST_STRING_2.toByteArray(Charsets.UTF_8).size
        val androidDecompressed = CompressionUtil.decompress(iosCompressedData, originalSize)
        
        if (androidDecompressed != null) {
            val decompressedString = androidDecompressed.toString(Charsets.UTF_8)
            val expectedString = TEST_STRING_2
            val isCompatible = decompressedString == expectedString
            
            println("Cross-platform compatibility: ${if (isCompatible) "✅ COMPATIBLE" else "❌ INCOMPATIBLE"}")
            
            if (!isCompatible) {
                println("Expected: ${expectedString.take(100)}...")
                println("Got: ${decompressedString.take(100)}...")
            }
        } else {
            println("❌ Android cannot decompress iOS compressed data - INCOMPATIBLE")
        }
    }
    
    @Test
    fun testDataIntegrity() {
        // Verify our test data matches exactly what will be used in iOS
        val testData = TEST_STRING_2.toByteArray(Charsets.UTF_8)
        println("\n=== Test Data Verification ===")
        println("testString2 length: ${TEST_STRING_2.length} characters")
        println("testString2 bytes: ${testData.size} bytes")
        println("testString2 content: '$TEST_STRING_2'")
        println("First 32 bytes (hex): ${testData.take(32).joinToString(" ") { "%02x".format(it) }}")
        
        // This should exactly match iOS test output
        assertEquals("Test string should be exactly 400 bytes", 400, testData.size)
        assertEquals("Test string should be 400 characters", 400, TEST_STRING_2.length)
    }
    
    @Test
    fun testSpecificErrorCaseData() {
        println("\n=== Testing Specific Error Case Data ===")
        
        // Recreate the exact data that caused the error
        val errorMessage = "COMPRESSING DATA BRO"
        val repeatedData = errorMessage.repeat(20) // This creates exactly 400 bytes
        val originalData = repeatedData.toByteArray(Charsets.UTF_8)
        
        println("Error case data:")
        println("- Message: '$errorMessage'")
        println("- Repeated: 20 times")
        println("- Total length: ${repeatedData.length} characters")
        println("- Total bytes: ${originalData.size} bytes")
        println("- First 64 bytes: ${originalData.take(64).joinToString(" ") { "%02x".format(it) }}")
        
        // This should match exactly what iOS sends that causes Android to fail
        val shouldCompress = CompressionUtil.shouldCompress(originalData)
        println("Should compress: $shouldCompress")
        
        if (shouldCompress) {
            val compressed = CompressionUtil.compress(originalData)
            if (compressed != null) {
                println("Android compression successful: ${compressed.size} bytes")
                println("Full compressed data: ${compressed.joinToString(" ") { "%02x".format(it) }}")
                
                // Try to decompress to verify integrity
                val decompressed = CompressionUtil.decompress(compressed, originalData.size)
                if (decompressed != null && decompressed.contentEquals(originalData)) {
                    println("✅ Android compression/decompression works for error case data")
                } else {
                    println("❌ Android compression/decompression failed for error case data")
                }
            } else {
                println("❌ Android compression failed for error case data")
            }
        }
    }
    
    // Helper function to convert hex string to byte array
    private fun hexStringToByteArray(hexString: String): ByteArray? {
        val cleanHex = hexString.replace(" ", "")
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
