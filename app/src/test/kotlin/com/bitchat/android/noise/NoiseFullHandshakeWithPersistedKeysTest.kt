package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test
import org.junit.Assert.*
import java.security.SecureRandom

/**
 * COMPREHENSIVE test to verify that the ENTIRE Noise XX handshake works with persisted keys
 * This test will definitively prove whether noise-java can complete a full handshake using pre-set keys
 */
class NoiseFullHandshakeWithPersistedKeysTest {

    companion object {
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    }

    @Test
    fun testCompleteHandshakeWithPersistedKeys() {
        println("=== TESTING COMPLETE XX HANDSHAKE WITH PERSISTED KEYS ===")
        println("This test will prove definitively if noise-java supports our use case")
        
        try {
            // Step 1: Generate persistent identity keys for Alice and Bob
            val (alicePrivate, alicePublic) = generatePersistentKeys("Alice")
            val (bobPrivate, bobPublic) = generatePersistentKeys("Bob")
            
            println("\n--- Generated Persistent Identity Keys ---")
            println("Alice private: ${alicePrivate.joinToString("") { "%02x".format(it) }}")
            println("Alice public:  ${alicePublic.joinToString("") { "%02x".format(it) }}")
            println("Bob private:   ${bobPrivate.joinToString("") { "%02x".format(it) }}")
            println("Bob public:    ${bobPublic.joinToString("") { "%02x".format(it) }}")
            
            // Step 2: Set up Alice as initiator with her persistent keys
            println("\n--- Setting up Alice (Initiator) ---")
            val aliceHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            configureHandshakeWithPersistedKeys(aliceHandshake, alicePrivate, alicePublic, "Alice")
            aliceHandshake.start()
            println("✅ Alice handshake initialized with persistent keys")
            
            // Step 3: Set up Bob as responder with his persistent keys
            println("\n--- Setting up Bob (Responder) ---")
            val bobHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
            configureHandshakeWithPersistedKeys(bobHandshake, bobPrivate, bobPublic, "Bob")
            bobHandshake.start()
            println("✅ Bob handshake initialized with persistent keys")
            
            // Step 4: Execute the complete XX handshake
            println("\n--- Executing Complete XX Handshake ---")
            
            // XX Message 1: Alice -> Bob (e)
            println("Step 1: Alice sends message 1 (ephemeral key)")
            val message1Buffer = ByteArray(256)
            val message1Length = aliceHandshake.writeMessage(message1Buffer, 0, ByteArray(0), 0, 0)
            val message1 = message1Buffer.copyOf(message1Length)
            println("Alice sent message 1: ${message1.size} bytes")
            assertEquals("XX message 1 should be 32 bytes", 32, message1.size)
            
            // Bob receives message 1
            val payload1Buffer = ByteArray(256)
            val payload1Length = bobHandshake.readMessage(message1, 0, message1.size, payload1Buffer, 0)
            println("Bob received message 1, payload length: $payload1Length")
            assertEquals("Handshake action should be WRITE_MESSAGE", HandshakeState.WRITE_MESSAGE, bobHandshake.getAction())
            
            // XX Message 2: Bob -> Alice (e, ee, s, es)
            println("Step 2: Bob sends message 2 (ephemeral + encrypted static)")
            val message2Buffer = ByteArray(256)
            val message2Length = bobHandshake.writeMessage(message2Buffer, 0, ByteArray(0), 0, 0)
            val message2 = message2Buffer.copyOf(message2Length)
            println("Bob sent message 2: ${message2.size} bytes")
            assertTrue("XX message 2 should be around 80 bytes", message2.size >= 70 && message2.size <= 90)
            
            // Alice receives message 2
            val payload2Buffer = ByteArray(256)
            val payload2Length = aliceHandshake.readMessage(message2, 0, message2.size, payload2Buffer, 0)
            println("Alice received message 2, payload length: $payload2Length")
            assertEquals("Handshake action should be WRITE_MESSAGE", HandshakeState.WRITE_MESSAGE, aliceHandshake.getAction())
            
            // XX Message 3: Alice -> Bob (s, se)
            println("Step 3: Alice sends message 3 (encrypted static)")
            val message3Buffer = ByteArray(256)
            val message3Length = aliceHandshake.writeMessage(message3Buffer, 0, ByteArray(0), 0, 0)
            val message3 = message3Buffer.copyOf(message3Length)
            println("Alice sent message 3: ${message3.size} bytes")
            assertTrue("XX message 3 should be around 48 bytes", message3.size >= 40 && message3.size <= 55)
            
            // Bob receives message 3 - this should complete the handshake
            val payload3Buffer = ByteArray(256)
            val payload3Length = bobHandshake.readMessage(message3, 0, message3.size, payload3Buffer, 0)
            println("Bob received message 3, payload length: $payload3Length")
            assertEquals("Handshake should be complete", HandshakeState.SPLIT, bobHandshake.getAction())
            assertEquals("Alice should also be ready to split", HandshakeState.SPLIT, aliceHandshake.getAction())
            
            // Step 5: Split into transport keys and verify they work
            println("\n--- Splitting into Transport Ciphers ---")
            
            val aliceCiphers = aliceHandshake.split()
            val aliceSend = aliceCiphers.getSender()
            val aliceReceive = aliceCiphers.getReceiver()
            
            val bobCiphers = bobHandshake.split()
            val bobSend = bobCiphers.getSender()
            val bobReceive = bobCiphers.getReceiver()
            
            println("✅ Transport ciphers created successfully")
            
            // Step 6: Verify we can extract the remote static keys
            println("\n--- Verifying Remote Key Exchange ---")
            
            assertTrue("Alice should have Bob's remote key", aliceHandshake.hasRemotePublicKey())
            assertTrue("Bob should have Alice's remote key", bobHandshake.hasRemotePublicKey())
            
            // Extract and verify remote keys
            val aliceRemoteKey = ByteArray(32)
            val bobRemoteKey = ByteArray(32) 
            aliceHandshake.getRemotePublicKey().getPublicKey(aliceRemoteKey, 0)
            bobHandshake.getRemotePublicKey().getPublicKey(bobRemoteKey, 0)
            
            println("Alice sees Bob's key: ${aliceRemoteKey.joinToString("") { "%02x".format(it) }}")
            println("Bob sees Alice's key: ${bobRemoteKey.joinToString("") { "%02x".format(it) }}")
            
            // Verify key exchange worked correctly
            assertArrayEquals("Alice should receive Bob's public key", bobPublic, aliceRemoteKey)
            assertArrayEquals("Bob should receive Alice's public key", alicePublic, bobRemoteKey)
            println("✅ Key exchange verified - persistent keys exchanged correctly!")
            
            // Step 7: Test transport encryption with the derived keys
            println("\n--- Testing Transport Encryption ---")
            
            val testMessage = "Hello from Alice using persistent keys!".toByteArray()
            
            // Alice encrypts
            val ciphertext = ByteArray(testMessage.size + 16)
            val ciphertextLength = aliceSend.encryptWithAd(null, testMessage, 0, ciphertext, 0, testMessage.size)
            val encryptedData = ciphertext.copyOf(ciphertextLength)
            println("Alice encrypted: ${testMessage.size} bytes -> ${encryptedData.size} bytes")
            
            // Bob decrypts
            val decrypted = ByteArray(testMessage.size)
            val decryptedLength = bobReceive.decryptWithAd(null, encryptedData, 0, decrypted, 0, encryptedData.size)
            val decryptedMessage = decrypted.copyOf(decryptedLength)
            
            assertArrayEquals("Decrypted message should match original", testMessage, decryptedMessage)
            println("✅ Transport encryption verified: '${String(decryptedMessage)}'")
            
            // Test reverse direction
            val bobMessage = "Hello back from Bob!".toByteArray()
            val bobCiphertext = ByteArray(bobMessage.size + 16)
            val bobCiphertextLength = bobSend.encryptWithAd(null, bobMessage, 0, bobCiphertext, 0, bobMessage.size)
            val bobEncrypted = bobCiphertext.copyOf(bobCiphertextLength)
            
            val aliceDecrypted = ByteArray(bobMessage.size)
            val aliceDecryptedLength = aliceReceive.decryptWithAd(null, bobEncrypted, 0, aliceDecrypted, 0, bobEncrypted.size)
            val aliceDecryptedMessage = aliceDecrypted.copyOf(aliceDecryptedLength)
            
            assertArrayEquals("Bob's message should decrypt correctly", bobMessage, aliceDecryptedMessage)
            println("✅ Reverse encryption verified: '${String(aliceDecryptedMessage)}'")
            
            // Step 8: Cleanup
            println("\n--- Cleanup ---")
            aliceSend.destroy()
            aliceReceive.destroy()
            bobSend.destroy()
            bobReceive.destroy()
            aliceHandshake.destroy()
            bobHandshake.destroy()
            
            println("\n🎉 COMPLETE SUCCESS! 🎉")
            println("The full XX handshake works perfectly with persistent identity keys!")
            println("✅ Persistent keys can be set on HandshakeState")
            println("✅ Complete XX handshake executes successfully")
            println("✅ Remote keys are exchanged correctly")
            println("✅ Transport encryption works with derived keys")
            println("✅ This proves noise-java DOES support our use case!")
            
        } catch (e: Exception) {
            println("\n❌ HANDSHAKE FAILED: ${e.message}")
            e.printStackTrace()
            fail("Complete handshake with persistent keys should work. Error: ${e.message}")
        }
    }

    @Test
    fun testMultipleHandshakesWithSameKeys() {
        println("=== TESTING MULTIPLE HANDSHAKES WITH SAME PERSISTENT KEYS ===")
        
        try {
            // Generate one set of persistent keys
            val (alicePrivate, alicePublic) = generatePersistentKeys("Alice")
            val (bobPrivate, bobPublic) = generatePersistentKeys("Bob")
            
            // Run 3 separate handshakes with the same keys
            for (i in 1..3) {
                println("\n--- Handshake Round $i ---")
                
                val aliceHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
                val bobHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
                
                configureHandshakeWithPersistedKeys(aliceHandshake, alicePrivate, alicePublic, "Alice")
                configureHandshakeWithPersistedKeys(bobHandshake, bobPrivate, bobPublic, "Bob")
                
                aliceHandshake.start()
                bobHandshake.start()
                
                // Execute abbreviated handshake
                val msg1 = executeHandshakeStep(aliceHandshake, null, "Alice message 1")
                val msg2 = executeHandshakeStep(bobHandshake, msg1, "Bob message 2")
                val msg3 = executeHandshakeStep(aliceHandshake, msg2, "Alice message 3")
                executeHandshakeStep(bobHandshake, msg3, "Bob complete")
                
                assertEquals("Both should be ready to split", HandshakeState.SPLIT, aliceHandshake.getAction())
                assertEquals("Both should be ready to split", HandshakeState.SPLIT, bobHandshake.getAction())
                
                // Verify remote keys are consistent across sessions
                val aliceRemote = ByteArray(32)
                val bobRemote = ByteArray(32)
                aliceHandshake.getRemotePublicKey().getPublicKey(aliceRemote, 0)
                bobHandshake.getRemotePublicKey().getPublicKey(bobRemote, 0)
                
                assertArrayEquals("Alice should always see Bob's key", bobPublic, aliceRemote)
                assertArrayEquals("Bob should always see Alice's key", alicePublic, bobRemote)
                
                aliceHandshake.destroy()
                bobHandshake.destroy()
                println("✅ Round $i successful - persistent keys work consistently")
            }
            
            println("✅ Multiple handshakes with same persistent keys work perfectly!")
            
        } catch (e: Exception) {
            println("❌ Multiple handshake test failed: ${e.message}")
            e.printStackTrace()
            fail("Multiple handshakes should work with same keys")
        }
    }

    @Test 
    fun testExactBitchatKeyFormat() {
        println("=== TESTING EXACT BITCHAT KEY FORMAT ===")
        
        try {
            // Simulate the exact key format from NoiseEncryptionService.generateStaticKeyPair()
            val dhForGeneration = Noise.createDH("25519")
            dhForGeneration.generateKeyPair()
            
            val staticPrivateKey = ByteArray(32)
            val staticPublicKey = ByteArray(32)
            dhForGeneration.getPrivateKey(staticPrivateKey, 0)
            dhForGeneration.getPublicKey(staticPublicKey, 0)
            dhForGeneration.destroy()
            
            println("Generated static identity keys (bitchat format):")
            println("Private: ${staticPrivateKey.joinToString("") { "%02x".format(it) }}")
            println("Public:  ${staticPublicKey.joinToString("") { "%02x".format(it) }}")
            
            // Test these exact keys in a handshake scenario
            val initiatorHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            val responderHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
            
            // Set up both with the same identity (for testing - normally they'd be different)
            configureHandshakeWithPersistedKeys(initiatorHandshake, staticPrivateKey, staticPublicKey, "Initiator")
            configureHandshakeWithPersistedKeys(responderHandshake, staticPrivateKey, staticPublicKey, "Responder")
            
            initiatorHandshake.start()
            responderHandshake.start()
            
            println("✅ Both handshakes initialized with bitchat key format")
            
            // Execute handshake steps
            val step1Buffer = ByteArray(256)
            val step1Length = initiatorHandshake.writeMessage(step1Buffer, 0, ByteArray(0), 0, 0)
            val step1Message = step1Buffer.copyOf(step1Length)
            
            val step1PayloadBuffer = ByteArray(256)
            responderHandshake.readMessage(step1Message, 0, step1Message.size, step1PayloadBuffer, 0)
            
            println("✅ Step 1 completed with bitchat key format")
            
            initiatorHandshake.destroy()
            responderHandshake.destroy()
            
            println("✅ Bitchat exact key format test passed!")
            
        } catch (e: Exception) {
            println("❌ Bitchat key format test failed: ${e.message}")
            e.printStackTrace()
            fail("Bitchat key format should work")
        }
    }

    // Helper Methods

    private fun generatePersistentKeys(name: String): Pair<ByteArray, ByteArray> {
        println("Generating persistent identity keys for $name...")
        
        val dhState = Noise.createDH("25519")
        dhState.generateKeyPair()
        
        val privateKey = ByteArray(32)
        val publicKey = ByteArray(32)
        dhState.getPrivateKey(privateKey, 0)
        dhState.getPublicKey(publicKey, 0)
        
        dhState.destroy()
        
        println("$name keys generated - private: ${privateKey.size} bytes, public: ${publicKey.size} bytes")
        return Pair(privateKey, publicKey)
    }

    private fun configureHandshakeWithPersistedKeys(
        handshake: HandshakeState, 
        privateKey: ByteArray, 
        publicKey: ByteArray, 
        name: String
    ) {
        if (handshake.needsLocalKeyPair()) {
            val localKeyPair = handshake.getLocalKeyPair()
            assertNotNull("$name should get local key pair", localKeyPair)
            
            // This is the EXACT pattern from our NoiseSession.kt
            localKeyPair!!.setPrivateKey(privateKey, 0)
            localKeyPair.setPublicKey(publicKey, 0)
            
            // Verify the keys were set correctly
            assertTrue("$name should have private key after setting", localKeyPair.hasPrivateKey())
            assertTrue("$name should have public key after setting", localKeyPair.hasPublicKey())
            
            println("✅ $name configured with persistent keys")
        } else {
            println("$name does not need local key pair")
        }
    }

    private fun executeHandshakeStep(handshake: HandshakeState, incomingMessage: ByteArray?, stepName: String): ByteArray? {
        return if (incomingMessage != null) {
            // Read incoming message first
            val payloadBuffer = ByteArray(256)
            handshake.readMessage(incomingMessage, 0, incomingMessage.size, payloadBuffer, 0)
            println("$stepName: Read ${incomingMessage.size} bytes")
            
            // Check if we need to respond
            if (handshake.getAction() == HandshakeState.WRITE_MESSAGE) {
                val responseBuffer = ByteArray(256)
                val responseLength = handshake.writeMessage(responseBuffer, 0, ByteArray(0), 0, 0)
                val response = responseBuffer.copyOf(responseLength)
                println("$stepName: Sent ${response.size} bytes")
                response
            } else {
                println("$stepName: No response needed, action: ${handshake.getAction()}")
                null
            }
        } else {
            // Send initial message
            val messageBuffer = ByteArray(256)
            val messageLength = handshake.writeMessage(messageBuffer, 0, ByteArray(0), 0, 0)
            val message = messageBuffer.copyOf(messageLength)
            println("$stepName: Sent initial ${message.size} bytes")
            message
        }
    }
}
