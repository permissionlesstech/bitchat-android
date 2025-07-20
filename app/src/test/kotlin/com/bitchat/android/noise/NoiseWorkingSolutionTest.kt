package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test
import org.junit.Assert.*
import java.security.SecureRandom

/**
 * FINAL TEST: The working solution for noise-java key injection
 * The solution: generateKeyPair() first, then setPrivateKey()/setPublicKey()
 */
class NoiseWorkingSolutionTest {

    companion object {
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    }

    @Test
    fun testWorkingSolutionPattern() {
        println("=== Testing Working Solution Pattern ===")
        
        try {
            // Simulate our app's static keys
            val ourPrivateKey = ByteArray(32)
            val ourPublicKey = ByteArray(32) 
            SecureRandom().nextBytes(ourPrivateKey)
            SecureRandom().nextBytes(ourPublicKey)
            
            println("Our static keys:")
            println("Private: ${ourPrivateKey.joinToString("") { "%02x".format(it) }}")
            println("Public: ${ourPublicKey.joinToString("") { "%02x".format(it) }}")
            
            // WORKING PATTERN:
            val dhState = Noise.createDH("25519")
            
            // Step 1: Initialize with generateKeyPair() first
            dhState.generateKeyPair()
            assertTrue("Should have private key after generate", dhState.hasPrivateKey())
            assertTrue("Should have public key after generate", dhState.hasPublicKey())
            
            // Step 2: Overwrite with our static keys
            dhState.setPrivateKey(ourPrivateKey, 0)
            dhState.setPublicKey(ourPublicKey, 0)
            
            // Step 3: Verify our keys are actually set
            assertTrue("Should still have private key after setting", dhState.hasPrivateKey())
            assertTrue("Should still have public key after setting", dhState.hasPublicKey())
            
            // Step 4: Extract keys and verify they are ours
            val extractedPrivate = ByteArray(32)
            val extractedPublic = ByteArray(32)
            dhState.getPrivateKey(extractedPrivate, 0)
            dhState.getPublicKey(extractedPublic, 0)
            
            assertArrayEquals("Extracted private key should match our key", ourPrivateKey, extractedPrivate)
            assertArrayEquals("Extracted public key should match our key", ourPublicKey, extractedPublic)
            
            println("✅ Our static keys are correctly set and extractable!")
            
            dhState.destroy()
            
        } catch (e: Exception) {
            println("❌ Working solution test failed: ${e.message}")
            e.printStackTrace()
            fail("Working solution should work")
        }
    }

    @Test
    fun testCompleteHandshakeWithWorkingSolution() {
        println("=== Testing Complete Handshake with Working Solution ===")
        
        try {
            // Simulate Alice and Bob's static keys
            val alicePrivate = ByteArray(32)
            val alicePublic = ByteArray(32)
            val bobPrivate = ByteArray(32)
            val bobPublic = ByteArray(32)
            
            SecureRandom().nextBytes(alicePrivate)
            SecureRandom().nextBytes(alicePublic)
            SecureRandom().nextBytes(bobPrivate)
            SecureRandom().nextBytes(bobPublic)
            
            // Create Alice (initiator)
            val aliceHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            configureHandshakeWithWorkingSolution(aliceHandshake, alicePrivate, alicePublic, "Alice")
            aliceHandshake.start()
            println("✅ Alice handshake started")
            
            // Create Bob (responder)
            val bobHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
            configureHandshakeWithWorkingSolution(bobHandshake, bobPrivate, bobPublic, "Bob")
            bobHandshake.start()
            println("✅ Bob handshake started")
            
            // Perform complete XX handshake
            println("\n--- Performing XX Handshake ---")
            
            // Message 1: Alice -> Bob
            val message1 = ByteArray(200)
            val message1Length = aliceHandshake.writeMessage(message1, 0, null, 0, 0)
            val finalMessage1 = message1.copyOf(message1Length)
            println("Message 1: ${finalMessage1.size} bytes")
            assertEquals(32, finalMessage1.size)
            
            val payload1 = ByteArray(100)
            bobHandshake.readMessage(finalMessage1, 0, finalMessage1.size, payload1, 0)
            
            // Message 2: Bob -> Alice
            val message2 = ByteArray(200)
            val message2Length = bobHandshake.writeMessage(message2, 0, null, 0, 0)
            val finalMessage2 = message2.copyOf(message2Length)
            println("Message 2: ${finalMessage2.size} bytes")
            assertEquals(80, finalMessage2.size)
            
            val payload2 = ByteArray(100)
            aliceHandshake.readMessage(finalMessage2, 0, finalMessage2.size, payload2, 0)
            
            // Message 3: Alice -> Bob
            val message3 = ByteArray(200)
            val message3Length = aliceHandshake.writeMessage(message3, 0, null, 0, 0)
            val finalMessage3 = message3.copyOf(message3Length)
            println("Message 3: ${finalMessage3.size} bytes")
            assertEquals(48, finalMessage3.size)
            
            val payload3 = ByteArray(100)
            bobHandshake.readMessage(finalMessage3, 0, finalMessage3.size, payload3, 0)
            
            // Verify handshake completion
            assertEquals("Alice ready to split", HandshakeState.SPLIT, aliceHandshake.action)
            assertEquals("Bob ready to split", HandshakeState.SPLIT, bobHandshake.action)
            
            // Split into transport keys
            val aliceCiphers = aliceHandshake.split()
            val bobCiphers = bobHandshake.split()
            
            println("✅ Complete XX handshake successful with our static keys!")
            
            // Test transport encryption
            val testMessage = "Hello from Alice to Bob with our static keys!".toByteArray()
            
            val encrypted = ByteArray(testMessage.size + 16)
            val encryptedLength = aliceCiphers.sender.encryptWithAd(null, testMessage, 0, encrypted, 0, testMessage.size)
            val finalEncrypted = encrypted.copyOf(encryptedLength)
            
            val decrypted = ByteArray(finalEncrypted.size)
            val decryptedLength = bobCiphers.receiver.decryptWithAd(null, finalEncrypted, 0, decrypted, 0, finalEncrypted.size)
            val finalDecrypted = decrypted.copyOf(decryptedLength)
            
            assertArrayEquals(testMessage, finalDecrypted)
            println("✅ Transport encryption working with static keys!")
            
            // Clean up
            aliceHandshake.destroy()
            bobHandshake.destroy()
            aliceCiphers.destroy()
            bobCiphers.destroy()
            
        } catch (e: Exception) {
            println("❌ Complete handshake failed: ${e.message}")
            e.printStackTrace()
            fail("Complete handshake with working solution should succeed")
        }
    }

    private fun configureHandshakeWithWorkingSolution(
        handshake: HandshakeState, 
        privateKey: ByteArray, 
        publicKey: ByteArray, 
        name: String
    ) {
        if (handshake.needsLocalKeyPair()) {
            val localKeyPair = handshake.getLocalKeyPair()
            assertNotNull("$name should get local key pair", localKeyPair)
            
            // CRITICAL: Use working solution pattern
            // Step 1: Generate keypair to initialize internal state
            localKeyPair!!.generateKeyPair()
            
            // Step 2: Overwrite with our static keys
            localKeyPair.setPrivateKey(privateKey, 0)
            localKeyPair.setPublicKey(publicKey, 0)
            
            // Step 3: Verify
            assertTrue("$name should have private key", localKeyPair.hasPrivateKey())
            assertTrue("$name should have public key", localKeyPair.hasPublicKey())
            
            println("✅ $name configured with working solution pattern")
        }
    }
}
