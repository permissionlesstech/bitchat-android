package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test
import org.junit.Assert.*

/**
 * VERIFICATION: Test our working solution with fresh keys works
 * This confirms our approach is viable for the app
 */
class NoiseWorkingSolutionVerification {

    companion object {
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    }

    @Test
    fun testFreshKeysWork() {
        println("=== TESTING FRESH KEYS WORK WITH NOISE-JAVA ===")
        
        try {
            // Set up handshakes with fresh keys
            val aliceHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            val bobHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
            
            // Generate fresh keys - this WORKS
            configureWithFreshKeys(aliceHandshake, "Alice")
            configureWithFreshKeys(bobHandshake, "Bob")
            
            aliceHandshake.start()
            bobHandshake.start()
            
            // Execute handshake
            val msg1 = executeStep(aliceHandshake, null)
            val msg2 = executeStep(bobHandshake, msg1!!)
            val msg3 = executeStep(aliceHandshake, msg2!!)
            executeStep(bobHandshake, msg3!!)
            
            assertEquals("Both should be ready to split", HandshakeState.SPLIT, aliceHandshake.getAction())
            assertEquals("Both should be ready to split", HandshakeState.SPLIT, bobHandshake.getAction())
            
            println("✅ Fresh keys handshake works perfectly!")
            
            // Split and test encryption
            val aliceCiphers = aliceHandshake.split()
            val bobCiphers = bobHandshake.split()
            
            val message = "Test message".toByteArray()
            val ciphertext = ByteArray(message.size + 16)
            val cipherLen = aliceCiphers.getSender().encryptWithAd(null, message, 0, ciphertext, 0, message.size)
            
            val plaintext = ByteArray(message.size)
            val plainLen = bobCiphers.getReceiver().decryptWithAd(null, ciphertext, 0, plaintext, 0, cipherLen)
            
            assertArrayEquals("Encryption should work", message, plaintext.copyOf(plainLen))
            println("✅ Transport encryption works!")
            
            // Cleanup
            aliceCiphers.getSender().destroy()
            aliceCiphers.getReceiver().destroy()
            bobCiphers.getSender().destroy() 
            bobCiphers.getReceiver().destroy()
            aliceHandshake.destroy()
            bobHandshake.destroy()
            
            println("🎉 FRESH KEYS SOLUTION VERIFIED!")
            
        } catch (e: Exception) {
            println("❌ Fresh keys test failed: ${e.message}")
            e.printStackTrace()
            fail("Fresh keys should work")
        }
    }

    private fun configureWithFreshKeys(handshake: HandshakeState, name: String) {
        if (handshake.needsLocalKeyPair()) {
            val localKeyPair = handshake.getLocalKeyPair()
            localKeyPair.generateKeyPair() // This WORKS
            assertTrue("$name should have keys", localKeyPair.hasPrivateKey() && localKeyPair.hasPublicKey())
            println("✅ $name configured with fresh keys")
        }
    }

    private fun executeStep(handshake: HandshakeState, incoming: ByteArray?): ByteArray? {
        return if (incoming != null) {
            val payload = ByteArray(256) 
            handshake.readMessage(incoming, 0, incoming.size, payload, 0)
            if (handshake.getAction() == HandshakeState.WRITE_MESSAGE) {
                val response = ByteArray(256)
                val len = handshake.writeMessage(response, 0, ByteArray(0), 0, 0)
                response.copyOf(len)
            } else null
        } else {
            val msg = ByteArray(256)
            val len = handshake.writeMessage(msg, 0, ByteArray(0), 0, 0) 
            msg.copyOf(len)
        }
    }
}
