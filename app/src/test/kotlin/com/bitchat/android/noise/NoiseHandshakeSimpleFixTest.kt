package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test
import org.junit.Assert.*

/**
 * Simple verification that the parameter order fix works
 */
class NoiseHandshakeSimpleFixTest {
    
    private val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    
    @Test
    fun testCorrectParameterOrderSolution() {
        println("=== Testing Parameter Order Fix ===")
        
        // Create initiator and generate message 1
        val initiator = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
        initiator.localKeyPair!!.generateKeyPair()
        initiator.start()
        
        val message1Buffer = ByteArray(200)
        val message1Length = initiator.writeMessage(
            message1Buffer, 0,      // ✅ CORRECT: Message buffer first
            ByteArray(0), 0, 0      // ✅ CORRECT: Payload buffer second  
        )
        val message1 = message1Buffer.copyOf(message1Length)
        println("✅ Message 1 created successfully: ${message1.size} bytes")
        
        // Create responder and process message 1
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        responder.localKeyPair!!.generateKeyPair()
        responder.start()
        
        val payloadBuffer = ByteArray(256)
        responder.readMessage(message1, 0, message1.size, payloadBuffer, 0)
        println("✅ Message 1 processed successfully at responder")
        
        // THIS IS THE CRITICAL TEST: Generate response message 2
        println("Testing response generation (this was failing with ShortBufferException)...")
        
        val responseBuffer = ByteArray(200)
        val responseLength = responder.writeMessage(
            responseBuffer, 0,      // ✅ CORRECT: Message buffer first  
            ByteArray(0), 0, 0      // ✅ CORRECT: Payload buffer second
        )
        val response = responseBuffer.copyOf(responseLength)
        
        println("✅ Response generated successfully: ${response.size} bytes")
        assertTrue("Response should be non-empty", response.isNotEmpty())
        
        // Verify the response can be processed by initiator
        val payload2Buffer = ByteArray(256)
        val payload2Length = initiator.readMessage(response, 0, response.size, payload2Buffer, 0)
        println("✅ Response processed successfully by initiator, payload length: $payload2Length")
        
        // Clean up
        initiator.destroy()
        responder.destroy()
        
        println("🎉 Parameter order fix verified - handshake works correctly!")
    }
    
    @Test 
    fun testWrongParameterOrderStillFails() {
        println("=== Verifying Wrong Parameter Order Still Fails ===")
        
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        responder.localKeyPair!!.generateKeyPair()
        responder.start()
        
        // Still need to process a message first
        val initiator = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
        initiator.localKeyPair!!.generateKeyPair()
        initiator.start()
        
        val message1Buffer = ByteArray(200)
        val message1Length = initiator.writeMessage(message1Buffer, 0, ByteArray(0), 0, 0)
        val message1 = message1Buffer.copyOf(message1Length)
        
        val payloadBuffer = ByteArray(256)
        responder.readMessage(message1, 0, message1.size, payloadBuffer, 0)
        
        // Now test WRONG parameter order
        try {
            val responseBuffer = ByteArray(200)
            val wrongLength = responder.writeMessage(
                ByteArray(0), 0,        // ❌ WRONG: Empty buffer as message buffer
                responseBuffer, 0, 0    // ❌ WRONG: Response buffer as payload 
            )
            
            println("❌ Wrong parameter order unexpectedly succeeded: $wrongLength bytes")
            fail("Wrong parameter order should have failed")
            
        } catch (e: Exception) {
            println("✅ Wrong parameter order correctly failed: ${e.javaClass.simpleName}: ${e.message}")
            assertTrue("Should be ShortBufferException", e is javax.crypto.ShortBufferException)
        }
        
        initiator.destroy()
        responder.destroy()
    }
}
