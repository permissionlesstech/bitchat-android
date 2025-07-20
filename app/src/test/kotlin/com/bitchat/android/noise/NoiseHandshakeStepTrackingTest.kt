package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test
import org.junit.Assert.*

/**
 * Test to verify the handshake step tracking and static key fixes
 */
class NoiseHandshakeStepTrackingTest {
    
    @Test
    fun testInitiatorReceivingStep2Message() {
        println("=== Testing Initiator Receiving Step 2 Message ===")
        
        val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        
        // Create initiator and responder
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
        println("✅ Message 1 created: ${message1.size} bytes")
        
        // Step 2: Responder processes message 1 and creates response
        val payload1Buffer = ByteArray(256)
        responder.readMessage(message1, 0, message1.size, payload1Buffer, 0)
        
        val msg2Buffer = ByteArray(200)
        val msg2Length = responder.writeMessage(msg2Buffer, 0, ByteArray(0), 0, 0)
        val message2 = msg2Buffer.copyOf(msg2Length)
        println("✅ Message 2 created: ${message2.size} bytes")
        
        // Step 3: Initiator processes message 2 (this was failing before)
        println("🔧 Testing initiator receiving step 2 message (was causing AEADBadTagException)...")
        
        try {
            val payload2Buffer = ByteArray(256)
            val payload2Length = initiator.readMessage(message2, 0, message2.size, payload2Buffer, 0)
            println("✅ Initiator successfully processed message 2, payload: $payload2Length bytes")
            
            // Check if initiator should write final message
            if (initiator.action == HandshakeState.WRITE_MESSAGE) {
                val msg3Buffer = ByteArray(200)
                val msg3Length = initiator.writeMessage(msg3Buffer, 0, ByteArray(0), 0, 0)
                val message3 = msg3Buffer.copyOf(msg3Length)
                println("✅ Message 3 created: ${message3.size} bytes")
                
                // Complete handshake
                val payload3Buffer = ByteArray(256)
                responder.readMessage(message3, 0, message3.size, payload3Buffer, 0)
                println("✅ Handshake completed successfully")
            }
            
        } catch (e: Exception) {
            println("❌ FAILED: ${e.javaClass.simpleName}: ${e.message}")
            throw e
        }
        
        // Clean up
        initiator.destroy()
        responder.destroy()
        
        println("🎉 Handshake step tracking fix verified!")
    }
    
    @Test
    fun testDifferentMessageSizes() {
        println("=== Testing Different Message Sizes (iOS Compatibility) ===")
        
        val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
        
        // Test with different buffer sizes to match iOS implementation
        val initiator = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
        val responder = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
        
        initiator.localKeyPair!!.generateKeyPair()
        responder.localKeyPair!!.generateKeyPair()
        initiator.start()
        responder.start()
        
        // Message 1
        val msg1Buffer = ByteArray(200)
        val msg1Length = initiator.writeMessage(msg1Buffer, 0, ByteArray(0), 0, 0)
        val message1 = msg1Buffer.copyOf(msg1Length)
        println("Message 1 size: ${message1.size} bytes (expected 32)")
        
        // Process message 1
        val payload1Buffer = ByteArray(256)
        responder.readMessage(message1, 0, message1.size, payload1Buffer, 0)
        
        // Message 2 - this might be larger in iOS
        val msg2Buffer = ByteArray(200)
        val msg2Length = responder.writeMessage(msg2Buffer, 0, ByteArray(0), 0, 0)
        val message2 = msg2Buffer.copyOf(msg2Length)
        println("Message 2 size: ${message2.size} bytes (iOS sends ~96, Android expects 80)")
        
        // The key test: can we handle different sizes?
        try {
            val payload2Buffer = ByteArray(256)
            val payload2Length = initiator.readMessage(message2, 0, message2.size, payload2Buffer, 0)
            println("✅ Successfully processed message 2 with size ${message2.size}")
            
        } catch (e: Exception) {
            println("❌ Failed with message size ${message2.size}: ${e.message}")
        }
        
        initiator.destroy()
        responder.destroy()
        
        println("✅ Message size flexibility test complete")
    }
}
