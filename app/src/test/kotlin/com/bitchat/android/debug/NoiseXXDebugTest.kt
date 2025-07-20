package com.bitchat.android.debug

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test
import java.security.SecureRandom

/**
 * Test to debug the XX handshake pattern step by step
 * This test replicates the exact scenario from your logs
 */
class NoiseXXDebugTest {
    
    @Test
    fun testXXHandshakeStepByStep() {
        println("=== Analyzing XX Handshake Pattern ===")
        
        // Generate test keys like your app would
        val random = SecureRandom()
        val initiatorStaticPriv = ByteArray(32)
        val responderStaticPriv = ByteArray(32) 
        random.nextBytes(initiatorStaticPriv)
        random.nextBytes(responderStaticPriv)
        
        // Create DH states to derive public keys
        val initiatorDH = Noise.createDH("25519")
        val responderDH = Noise.createDH("25519")
        
        initiatorDH.setPrivateKey(initiatorStaticPriv, 0)
        responderDH.setPrivateKey(responderStaticPriv, 0)
        
        val initiatorStaticPub = ByteArray(32)
        val responderStaticPub = ByteArray(32)
        initiatorDH.getPublicKey(initiatorStaticPub, 0)
        responderDH.getPublicKey(responderStaticPub, 0)
        
        println("Initiator static public: ${initiatorStaticPub.joinToString("") { "%02x".format(it) }}")
        println("Responder static public: ${responderStaticPub.joinToString("") { "%02x".format(it) }}")
        
        // Create handshake states
        val initiator = HandshakeState("Noise_XX_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)
        val responder = HandshakeState("Noise_XX_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER)
        
        // Set static keys
        initiator.localKeyPair?.setPrivateKey(initiatorStaticPriv, 0)
        responder.localKeyPair?.setPrivateKey(responderStaticPriv, 0)
        
        // Start handshakes
        initiator.start()
        responder.start()
        
        println("\n=== XX Pattern Flow ===")
        println("Expected: -> e")
        println("          <- e, ee, s, es")  
        println("          -> s, se")
        
        // Message 1: -> e
        println("\n--- Message 1: Initiator -> Responder ---")
        val msg1Buffer = ByteArray(256)
        val msg1Len = initiator.writeMessage(msg1Buffer, 0, ByteArray(0), 0, 0)
        val msg1 = msg1Buffer.copyOf(msg1Len)
        println("Message 1 length: $msg1Len bytes (expected: 32)")
        println("Message 1 content: ${msg1.joinToString("") { "%02x".format(it) }}")
        println("Initiator action after msg1: ${initiator.action}")
        
        // Responder processes message 1
        val responderPayload1 = ByteArray(256)
        val responderPayload1Len = responder.readMessage(msg1, 0, msg1.size, responderPayload1, 0)
        println("Responder processed msg1, payload len: $responderPayload1Len")
        println("Responder action after processing msg1: ${responder.action}")
        
        // Message 2: <- e, ee, s, es
        println("\n--- Message 2: Responder -> Initiator ---")
        val msg2Buffer = ByteArray(256)
        val msg2Len = responder.writeMessage(msg2Buffer, 0, ByteArray(0), 0, 0)
        val msg2 = msg2Buffer.copyOf(msg2Len)
        println("Message 2 length: $msg2Len bytes (expected: 80)")
        println("Message 2 content: ${msg2.joinToString("") { "%02x".format(it) }}")
        println("Responder action after msg2: ${responder.action}")
        
        // This is where the initiator should be able to process message 2
        // Let's see what happens
        try {
            val initiatorPayload2 = ByteArray(256)
            val initiatorPayload2Len = initiator.readMessage(msg2, 0, msg2.size, initiatorPayload2, 0)
            println("Initiator processed msg2 successfully, payload len: $initiatorPayload2Len")
            println("Initiator action after processing msg2: ${initiator.action}")
            
            // Message 3: -> s, se 
            println("\n--- Message 3: Initiator -> Responder ---")
            val msg3Buffer = ByteArray(256)
            val msg3Len = initiator.writeMessage(msg3Buffer, 0, ByteArray(0), 0, 0)
            val msg3 = msg3Buffer.copyOf(msg3Len)
            println("Message 3 length: $msg3Len bytes (expected: 48)")
            println("Message 3 content: ${msg3.joinToString("") { "%02x".format(it) }}")
            println("Initiator action after msg3: ${initiator.action}")
            
            // Responder processes message 3
            val responderPayload3 = ByteArray(256)
            val responderPayload3Len = responder.readMessage(msg3, 0, msg3.size, responderPayload3, 0)
            println("Responder processed msg3, payload len: $responderPayload3Len")
            println("Responder action after processing msg3: ${responder.action}")
            
            println("\n✓ Success: Handshake completed without errors")
            
        } catch (e: Exception) {
            println("\n❌ Error during initiator processing message 2:")
            println("Exception: ${e.javaClass.simpleName}")
            println("Message: ${e.message}")
            e.printStackTrace()
            
            // Let's analyze what went wrong
            analyzeMessage2Structure(msg2)
        }
        
        // Cleanup
        initiatorDH.destroy()
        responderDH.destroy()
        initiator.destroy()
        responder.destroy()
    }
    
    private fun analyzeMessage2Structure(msg2: ByteArray) {
        println("\n=== Analyzing Message 2 Structure ===")
        println("Total length: ${msg2.size}")
        
        if (msg2.size >= 32) {
            println("Ephemeral key (bytes 0-31): ${msg2.sliceArray(0..31).joinToString("") { "%02x".format(it) }}")
        }
        
        if (msg2.size >= 80) {
            println("Encrypted static + MAC (bytes 32-79): ${msg2.sliceArray(32..79).joinToString("") { "%02x".format(it) }}")
            println("  - Encrypted static (bytes 32-63): ${msg2.sliceArray(32..63).joinToString("") { "%02x".format(it) }}")
            println("  - MAC tag (bytes 64-79): ${msg2.sliceArray(64..79).joinToString("") { "%02x".format(it) }}")
        }
    }
}
