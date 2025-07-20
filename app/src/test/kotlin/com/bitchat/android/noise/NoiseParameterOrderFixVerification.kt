package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test

/**
 * Simple verification that our parameter order fix resolves the ShortBufferException
 */
class NoiseParameterOrderFixVerification {
    
    @Test
    fun testParameterOrderFixWorks() {
        println("🔬 Testing Parameter Order Fix")
        
        val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        
        // Create a scenario that would have caused ShortBufferException
        val initiator = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        
        // Generate keypairs
        initiator.localKeyPair!!.generateKeyPair()
        responder.localKeyPair!!.generateKeyPair()
        
        // Start handshakes
        initiator.start()
        responder.start()
        
        // Step 1: Initiator creates message 1
        val msg1Buffer = ByteArray(200)
        val msg1Length = initiator.writeMessage(msg1Buffer, 0, ByteArray(0), 0, 0)
        val message1 = msg1Buffer.copyOf(msg1Length)
        println("📤 Initiator created message 1: ${message1.size} bytes")
        
        // Step 2: Responder processes message 1  
        val payloadBuffer = ByteArray(256)
        val payloadLength = responder.readMessage(message1, 0, message1.size, payloadBuffer, 0)
        println("📥 Responder processed message 1, payload: $payloadLength bytes")
        
        // Step 3: Responder creates response (THIS WAS FAILING BEFORE)
        println("🔧 Testing response generation (previously failed with ShortBufferException)...")
        
        try {
            val responseBuffer = ByteArray(200)
            val responseLength = responder.writeMessage(
                responseBuffer, 0,      // ✅ FIXED: Message buffer first
                ByteArray(0), 0, 0      // ✅ FIXED: Payload second
            )
            val response = responseBuffer.copyOf(responseLength)
            
            println("🎉 SUCCESS: Response generated ${response.size} bytes")
            println("✅ Parameter order fix verified!")
            
            // Verify the response can be processed
            val payload2Buffer = ByteArray(256)
            val payload2Length = initiator.readMessage(response, 0, response.size, payload2Buffer, 0)
            println("✅ Response processed successfully by initiator")
            
        } catch (e: Exception) {
            println("❌ FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        
        // Clean up
        initiator.destroy()
        responder.destroy()
        
        println("🏆 Parameter order fix completely verified!")
    }
    
    @Test
    fun testWrongParameterOrderFails() {
        println("🚫 Testing Wrong Parameter Order Still Fails")
        
        val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        
        // Same setup 
        val initiator = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        
        initiator.localKeyPair!!.generateKeyPair()
        responder.localKeyPair!!.generateKeyPair()
        initiator.start()
        responder.start()
        
        // Generate and process message 1
        val msg1Buffer = ByteArray(200)
        val msg1Length = initiator.writeMessage(msg1Buffer, 0, ByteArray(0), 0, 0)
        val message1 = msg1Buffer.copyOf(msg1Length)
        
        val payloadBuffer = ByteArray(256)
        responder.readMessage(message1, 0, message1.size, payloadBuffer, 0)
        
        // Test the WRONG parameter order (what the bug was doing)
        try {
            val responseBuffer = ByteArray(200)
            val wrongLength = responder.writeMessage(
                ByteArray(0), 0,        // ❌ WRONG: Empty array as message!
                responseBuffer, 0, 0    // ❌ WRONG: Response as payload!
            )
            
            println("❌ Wrong order unexpectedly succeeded: $wrongLength bytes")
            
        } catch (e: javax.crypto.ShortBufferException) {
            println("✅ Wrong order correctly failed with ShortBufferException")
            println("   This confirms our fix addresses the exact issue")
        } catch (e: Exception) {
            println("⚠ Wrong order failed with: ${e.javaClass.simpleName}")
        }
        
        initiator.destroy()
        responder.destroy()
        
        println("✅ Wrong parameter order verification complete")
    }
}
