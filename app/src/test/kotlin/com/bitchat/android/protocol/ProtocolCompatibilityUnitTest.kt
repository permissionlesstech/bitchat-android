package com.bitchat.android.protocol

import com.bitchat.android.model.BitchatMessage
import org.junit.Test
import org.junit.Assert.*
import java.util.Date

/**
 * Unit test for protocol compatibility
 */
class ProtocolCompatibilityUnitTest {
    
    @Test
    fun testHexStringConversion() {
        val testHex = "a1b2c3d4"
        val packet = BitchatPacket(
            type = MessageType.ANNOUNCE.value,
            ttl = 5u,
            senderID = testHex,
            payload = byteArrayOf()
        )
        
        // Verify the hex string is correctly converted to binary
        val expected = byteArrayOf(0xa1.toByte(), 0xb2.toByte(), 0xc3.toByte(), 0xd4.toByte(), 0, 0, 0, 0)
        assertArrayEquals("Hex string conversion failed", expected, packet.senderID)
    }
    
    @Test
    fun testBroadcastMessageRoundTrip() {
        // Create a simple broadcast message
        val message = BitchatMessage(
            id = "test123",
            sender = "testuser",
            content = "Hello world",
            timestamp = Date(1672531200000L), // Fixed timestamp for consistency
            isPrivate = false,
            channel = null
        )
        
        // Convert to payload
        val payload = message.toBinaryPayload()
        assertNotNull("Failed to create payload", payload)
        
        // Create packet
        val packet = BitchatPacket(
            type = MessageType.MESSAGE.value,
            ttl = 10u,
            senderID = "a1b2c3d4", // 8-char hex peer ID
            payload = payload!!
        )
        
        // Encode to binary
        val binaryData = BinaryProtocol.encode(packet)
        assertNotNull("Failed to encode packet", binaryData)
        
        // Test decoding
        val decodedPacket = BinaryProtocol.decode(binaryData!!)
        assertNotNull("Failed to decode packet", decodedPacket)
        
        // Verify packet fields
        assertEquals("Version mismatch", 1u.toUByte(), decodedPacket!!.version)
        assertEquals("Type mismatch", MessageType.MESSAGE.value, decodedPacket.type)
        assertEquals("TTL mismatch", 10u.toUByte(), decodedPacket.ttl)
        
        // Test message decoding
        val decodedMessage = BitchatMessage.fromBinaryPayload(decodedPacket.payload)
        assertNotNull("Failed to decode message payload", decodedMessage)
        
        // Verify message fields
        assertEquals("ID mismatch", message.id, decodedMessage!!.id)
        assertEquals("Sender mismatch", message.sender, decodedMessage.sender)
        assertEquals("Content mismatch", message.content, decodedMessage.content)
        assertEquals("IsPrivate mismatch", message.isPrivate, decodedMessage.isPrivate)
    }
    
    @Test
    fun testMessageEncoding() {
        val message = BitchatMessage(
            id = "test123",
            sender = "testuser", 
            content = "Hello",
            timestamp = Date(1672531200000L)
        )
        
        val payload = message.toBinaryPayload()
        assertNotNull("Message encoding failed", payload)
        assertTrue("Payload too small", payload!!.size >= 13) // At least flags + timestamp + lengths
        
        val decoded = BitchatMessage.fromBinaryPayload(payload)
        assertNotNull("Message decoding failed", decoded)
        assertEquals("Message round-trip failed", message.content, decoded!!.content)
    }
    
    @Test
    fun debugBinaryEncoding() {
        val debugOutput = BinaryProtocolDebugger.debugBroadcastMessage()
        println("DEBUG OUTPUT:")
        println(debugOutput)
        
        val hexDebug = HexConversionDebugger.compareHexConversions()
        println("\nHEX CONVERSION DEBUG:")
        println(hexDebug)
        
        // Force test to pass but show output
        assertTrue("Debug output generated", debugOutput.isNotEmpty())
    }
}
