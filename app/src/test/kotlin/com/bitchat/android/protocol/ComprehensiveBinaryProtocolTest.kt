package com.bitchat.android.protocol

import com.bitchat.android.model.BitchatMessage
import org.junit.Test
import org.junit.Assert.*
import java.util.Date

/**
 * Comprehensive binary protocol tests matching iOS test patterns
 */
class ComprehensiveBinaryProtocolTest {
    
    companion object {
        // Fixed test values to match iOS behavior
        private const val TEST_TIMESTAMP = 1672531200000L
        private const val TEST_PEER_ID = "a1b2c3d4"
        private const val TEST_RECIPIENT_ID = "e5f6g7h8"
    }
    
    @Test
    fun testBasicPacketEncodingDecoding() {
        // Create packet exactly like iOS test
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToByteArray("testuser"), // Mimic iOS "testuser".utf8
            recipientID = hexToByteArray("recipient"), // Mimic iOS "recipient".utf8
            timestamp = TEST_TIMESTAMP.toULong(),
            payload = "Hello, World!".toByteArray(Charsets.UTF_8),
            signature = null,
            ttl = 5u
        )
        
        // Encode
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull("Failed to encode packet", encoded)
        
        println("=== Basic Packet Test ===")
        println("Original packet:")
        println("  Version: ${packet.version}")
        println("  Type: 0x${"%02x".format(packet.type.toByte())}")
        println("  TTL: ${packet.ttl}")
        println("  Timestamp: ${packet.timestamp}")
        println("  SenderID: ${packet.senderID.joinToString(" ") { "%02x".format(it) }}")
        println("  RecipientID: ${packet.recipientID?.joinToString(" ") { "%02x".format(it) }}")
        println("  Payload: ${String(packet.payload, Charsets.UTF_8)}")
        
        println("\nEncoded binary (${encoded!!.size} bytes):")
        println("  ${encoded.take(50).joinToString(" ") { "%02x".format(it) }}")
        if (encoded.size > 50) println("  ... (${encoded.size - 50} more bytes)")
        
        // Decode
        val decoded = BinaryProtocol.decode(encoded)
        assertNotNull("Failed to decode packet", decoded)
        
        println("\nDecoded packet:")
        println("  Version: ${decoded!!.version}")
        println("  Type: 0x${"%02x".format(decoded.type.toByte())}")
        println("  TTL: ${decoded.ttl}")
        println("  Timestamp: ${decoded.timestamp}")
        println("  SenderID: ${decoded.senderID.joinToString(" ") { "%02x".format(it) }}")
        println("  RecipientID: ${decoded.recipientID?.joinToString(" ") { "%02x".format(it) }}")
        println("  Payload: ${String(decoded.payload, Charsets.UTF_8)}")
        
        // Verify
        assertEquals("Version mismatch", packet.version, decoded.version)
        assertEquals("Type mismatch", packet.type, decoded.type)
        assertEquals("TTL mismatch", packet.ttl, decoded.ttl)
        assertEquals("Timestamp mismatch", packet.timestamp, decoded.timestamp)
        assertArrayEquals("Payload mismatch", packet.payload, decoded.payload)
    }
    
    @Test
    fun testBroadcastPacket() {
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToByteArray("sender"),
            recipientID = SpecialRecipients.BROADCAST,
            timestamp = TEST_TIMESTAMP.toULong(),
            payload = "Broadcast message".toByteArray(Charsets.UTF_8),
            signature = null,
            ttl = 3u
        )
        
        println("\n=== Broadcast Packet Test ===")
        println("Broadcast recipient: ${SpecialRecipients.BROADCAST.joinToString(" ") { "%02x".format(it) }}")
        
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull("Failed to encode broadcast packet", encoded)
        
        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull("Failed to decode broadcast packet", decoded)
        
        // Verify broadcast recipient
        assertArrayEquals("Broadcast recipient mismatch", SpecialRecipients.BROADCAST, decoded!!.recipientID)
    }
    
    @Test
    fun testPacketWithSignature() {
        val signature = ByteArray(64) { 0xAB.toByte() }
        val packet = BitchatPacket(
            version = 1u,
            type = MessageType.MESSAGE.value,
            senderID = hexToByteArray("sender"),
            recipientID = hexToByteArray("recipient"),
            timestamp = TEST_TIMESTAMP.toULong(),
            payload = "Signed message".toByteArray(Charsets.UTF_8),
            signature = signature,
            ttl = 5u
        )
        
        println("\n=== Signed Packet Test ===")
        println("Signature: ${signature.take(8).joinToString(" ") { "%02x".format(it) }}... (64 bytes)")
        
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull("Failed to encode signed packet", encoded)
        
        val decoded = BinaryProtocol.decode(encoded!!)
        assertNotNull("Failed to decode signed packet", decoded)
        
        assertNotNull("Signature missing", decoded!!.signature)
        assertArrayEquals("Signature mismatch", signature, decoded.signature)
    }
    
    @Test
    fun testBitchatMessageSerialization() {
        // Test simple message
        val message = BitchatMessage(
            id = "test123",
            sender = "testuser",
            content = "Hello world",
            timestamp = Date(TEST_TIMESTAMP),
            isPrivate = false
        )
        
        println("\n=== BitchatMessage Serialization Test ===")
        println("Message: ${message.content}")
        println("Sender: ${message.sender}")
        println("ID: ${message.id}")
        println("Timestamp: ${message.timestamp}")
        
        val payload = message.toBinaryPayload()
        assertNotNull("Failed to serialize message", payload)
        
        println("Payload (${payload!!.size} bytes):")
        println("  ${payload.joinToString(" ") { "%02x".format(it) }}")
        
        // Analyze payload structure
        if (payload.size >= 1) {
            val flags = payload[0]
            println("Flags: 0x${"%02x".format(flags)}")
            println("  isRelay: ${(flags.toInt() and 0x01) != 0}")
            println("  isPrivate: ${(flags.toInt() and 0x02) != 0}")
            println("  hasOriginalSender: ${(flags.toInt() and 0x04) != 0}")
            println("  hasRecipientNickname: ${(flags.toInt() and 0x08) != 0}")
            println("  hasSenderPeerID: ${(flags.toInt() and 0x10) != 0}")
            println("  hasMentions: ${(flags.toInt() and 0x20) != 0}")
            println("  hasChannel: ${(flags.toInt() and 0x40) != 0}")
            println("  isEncrypted: ${(flags.toInt() and 0x80) != 0}")
        }
        
        if (payload.size >= 9) {
            // Extract timestamp (bytes 1-8)
            var extractedTimestamp = 0L
            for (i in 1..8) {
                extractedTimestamp = (extractedTimestamp shl 8) or (payload[i].toLong() and 0xFF)
            }
            println("Extracted timestamp: $extractedTimestamp")
        }
        
        val decoded = BitchatMessage.fromBinaryPayload(payload)
        assertNotNull("Failed to deserialize message", decoded)
        
        println("Decoded message:")
        println("  ID: ${decoded!!.id}")
        println("  Sender: ${decoded.sender}")
        println("  Content: ${decoded.content}")
        
        assertEquals("Message round-trip failed", message.content, decoded.content)
        assertEquals("Sender round-trip failed", message.sender, decoded.sender)
        assertEquals("ID round-trip failed", message.id, decoded.id)
    }
    
    @Test
    fun testComplexMessage() {
        val message = BitchatMessage(
            id = "complex123",
            sender = "alice",
            content = "Hello @bob, #general channel test!",
            timestamp = Date(TEST_TIMESTAMP),
            isPrivate = true,
            recipientNickname = "bob",
            senderPeerID = TEST_PEER_ID,
            mentions = listOf("bob"),
            channel = "#general"
        )
        
        println("\n=== Complex Message Test ===")
        println("Message has:")
        println("  Private: ${message.isPrivate}")
        println("  Recipient: ${message.recipientNickname}")
        println("  SenderPeerID: ${message.senderPeerID}")
        println("  Mentions: ${message.mentions}")
        println("  Channel: ${message.channel}")
        
        val payload = message.toBinaryPayload()
        assertNotNull("Failed to serialize complex message", payload)
        
        println("Payload size: ${payload!!.size} bytes")
        
        val decoded = BitchatMessage.fromBinaryPayload(payload)
        assertNotNull("Failed to deserialize complex message", decoded)
        
        assertEquals("Content", message.content, decoded!!.content)
        assertEquals("Private flag", message.isPrivate, decoded.isPrivate)
        assertEquals("Recipient", message.recipientNickname, decoded.recipientNickname)
        assertEquals("Sender peer ID", message.senderPeerID, decoded.senderPeerID)
        assertEquals("Mentions", message.mentions, decoded.mentions)
        assertEquals("Channel", message.channel, decoded.channel)
    }
    
    @Test
    fun testHexStringToByteArrayConversion() {
        println("\n=== Hex String Conversion Test ===")
        
        val testCases = listOf(
            "a1b2c3d4",
            "12345678", 
            "deadbeef",
            "00000000",
            "ffffffff",
            "A1B2C3D4" // Test uppercase
        )
        
        for (hexString in testCases) {
            val packet = BitchatPacket(
                type = MessageType.ANNOUNCE.value,
                ttl = 5u,
                senderID = hexString,
                payload = byteArrayOf()
            )
            
            println("Input: '$hexString' -> ${packet.senderID.joinToString(" ") { "%02x".format(it) }}")
            
            // Verify it's exactly 8 bytes
            assertEquals("SenderID must be 8 bytes", 8, packet.senderID.size)
            
            // Verify conversion is hex, not UTF-8
            val utf8Conversion = hexString.toByteArray(Charsets.UTF_8)
            assertFalse("Should not be UTF-8 conversion", packet.senderID.contentEquals(utf8Conversion))
        }
    }
    
    @Test
    fun testPaddingCompatibility() {
        println("\n=== Message Padding Test ===")
        
        val testData = "Hello World".toByteArray()
        println("Original data (${testData.size} bytes): ${testData.joinToString(" ") { "%02x".format(it) }}")
        
        // Test padding to 256 bytes
        val padded = MessagePadding.pad(testData, 256)
        println("Padded to 256 bytes: ${padded.size} bytes")
        println("First 20 bytes: ${padded.take(20).joinToString(" ") { "%02x".format(it) }}")
        println("Last 10 bytes: ${padded.takeLast(10).joinToString(" ") { "%02x".format(it) }}")
        
        if (padded.size == 256) {
            val paddingLength = padded[padded.size - 1].toInt() and 0xFF
            println("Padding length byte: $paddingLength")
            
            // Verify PKCS#7 padding
            assertEquals("Padding length should be difference", 256 - testData.size, paddingLength)
        }
        
        val unpadded = MessagePadding.unpad(padded)
        println("Unpadded (${unpadded.size} bytes): ${unpadded.joinToString(" ") { "%02x".format(it) }}")
        
        assertArrayEquals("Padding round-trip failed", testData, unpadded)
    }
    
    @Test
    fun testFullPacketWithMessage() {
        // Create a complete real-world scenario
        val message = BitchatMessage(
            id = "real123",
            sender = "android",
            content = "test broadcast",
            timestamp = Date(TEST_TIMESTAMP),
            isPrivate = false
        )
        
        val payload = message.toBinaryPayload()
        assertNotNull("Message serialization failed", payload)
        
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 5u,
            senderID = TEST_PEER_ID,
            payload = payload!!
        )
        
        println("\n=== Full Packet with Message Test ===")
        println("Creating packet with:")
        println("  Type: MESSAGE (0x04)")
        println("  SenderID: $TEST_PEER_ID")
        println("  Message content: '${message.content}'")
        
        val encoded = BinaryProtocol.encode(packet)
        assertNotNull("Packet encoding failed", encoded)
        
        println("\nEncoded packet structure:")
        if (encoded!!.size >= 13) {
            println("  Header (13 bytes):")
            println("    Version: 0x${"%02x".format(encoded[0])}")
            println("    Type: 0x${"%02x".format(encoded[1])} (expect 0x04)")
            println("    TTL: 0x${"%02x".format(encoded[2])}")
            println("    Timestamp: ${encoded.slice(3..10).joinToString(" ") { "%02x".format(it) }}")
            println("    Flags: 0x${"%02x".format(encoded[11])}")
            println("    Payload Length: 0x${"%02x%02x".format(encoded[12], encoded[13])}")
            
            if (encoded.size >= 22) {
                println("  SenderID (8 bytes): ${encoded.slice(14..21).joinToString(" ") { "%02x".format(it) }}")
            }
            
            if (encoded.size >= 54) { // 13 header + 8 senderID + some payload
                println("  Payload first 32 bytes: ${encoded.slice(22..53).joinToString(" ") { "%02x".format(it) }}")
            }
        }
        
        // Test decoding
        val decoded = BinaryProtocol.decode(encoded)
        assertNotNull("Packet decoding failed", decoded)
        
        assertEquals("Type should be MESSAGE", MessageType.MESSAGE.value, decoded!!.type)
        
        val decodedMessage = BitchatMessage.fromBinaryPayload(decoded.payload)
        assertNotNull("Message decoding failed", decodedMessage)
        assertEquals("Message content mismatch", message.content, decodedMessage!!.content)
    }
    
    @Test 
    fun testInvalidPacketHandling() {
        println("\n=== Invalid Packet Handling Test ===")
        
        // Test empty data
        val emptyResult = BinaryProtocol.decode(ByteArray(0))
        assertNull("Empty data should return null", emptyResult)
        
        // Test truncated data
        val truncated = ByteArray(10) { 0 }
        val truncatedResult = BinaryProtocol.decode(truncated)
        assertNull("Truncated data should return null", truncatedResult)
        
        // Test invalid version
        val invalidVersion = ByteArray(100) { 0 }
        invalidVersion[0] = 99 // Invalid version
        val invalidResult = BinaryProtocol.decode(invalidVersion)
        assertNull("Invalid version should return null", invalidResult)
        
        println("Invalid packet handling: PASSED")
    }
    
    /**
     * Helper to convert hex string to byte array (for testing)
     */
    private fun hexToByteArray(hexString: String): ByteArray {
        val cleanHex = hexString.replace(" ", "").lowercase()
        val len = minOf(cleanHex.length, 16) // Max 8 bytes = 16 hex chars
        val result = ByteArray(8) { 0 } // Always 8 bytes
        
        var i = 0
        var byteIndex = 0
        while (i < len - 1 && byteIndex < 8) {
            val hexByte = cleanHex.substring(i, i + 2)
            result[byteIndex] = hexByte.toInt(16).toByte()
            i += 2
            byteIndex++
        }
        
        return result
    }
}
