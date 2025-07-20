package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test
import org.junit.Assert.*
import java.security.SecureRandom

/**
 * Test our local noise-java fork to verify key setting works correctly
 */
class LocalNoiseForKeySettingTest {

    companion object {
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    }

    @Test
    fun testLocalForkKeySetting() {
        println("=== TESTING LOCAL NOISE FORK KEY SETTING ===")
        
        try {
            // Create DH state using our local fork
            val dhState = Noise.createDH("25519")
            
            println("Created DH state: ${dhState.javaClass.name}")
            println("Algorithm: ${dhState.dhName}")
            println("Private key length: ${dhState.privateKeyLength}")
            println("Public key length: ${dhState.publicKeyLength}")
            
            // Check initial state
            println("Initial - hasPrivateKey: ${dhState.hasPrivateKey()}, hasPublicKey: ${dhState.hasPublicKey()}")
            assertFalse("Should not have private key initially", dhState.hasPrivateKey())
            assertFalse("Should not have public key initially", dhState.hasPublicKey())
            
            // Generate a test key pair
            val privateKey = ByteArray(32)
            val publicKey = ByteArray(32) 
            val random = SecureRandom()
            random.nextBytes(privateKey)
            
            println("Generated test private key: ${privateKey.joinToString("") { "%02x".format(it) }}")
            
            // Set the private key
            dhState.setPrivateKey(privateKey, 0)
            println("Set private key")
            
            // Check if the key was set correctly
            assertTrue("Should have private key after setting", dhState.hasPrivateKey())
            assertTrue("Should have public key after setting private key", dhState.hasPublicKey())
            
            // Get the keys back
            val retrievedPrivate = ByteArray(32)
            val retrievedPublic = ByteArray(32)
            dhState.getPrivateKey(retrievedPrivate, 0)
            dhState.getPublicKey(retrievedPublic, 0)
            
            println("Retrieved private key: ${retrievedPrivate.joinToString("") { "%02x".format(it) }}")
            println("Retrieved public key:  ${retrievedPublic.joinToString("") { "%02x".format(it) }}")
            
            // Verify the keys match
            assertArrayEquals("Private key should match", privateKey, retrievedPrivate)
            
            dhState.destroy()
            
            println("✅ LOCAL FORK KEY SETTING WORKS!")
            
        } catch (e: Exception) {
            println("❌ Local fork key setting failed: ${e.message}")
            e.printStackTrace()
            fail("Local fork should support key setting")
        }
    }

    @Test
    fun testLocalForkHandshakeWithPreSetKeys() {
        println("=== TESTING HANDSHAKE WITH PRE-SET KEYS ===")
        
        try {
            // Generate two key pairs
            val (alicePriv, alicePub) = generateKeyPair()
            val (bobPriv, bobPub) = generateKeyPair()
            
            println("Alice private: ${alicePriv.joinToString("") { "%02x".format(it) }}")
            println("Alice public:  ${alicePub.joinToString("") { "%02x".format(it) }}")
            println("Bob private:   ${bobPriv.joinToString("") { "%02x".format(it) }}")  
            println("Bob public:    ${bobPub.joinToString("") { "%02x".format(it) }}")
            
            // Create handshake states
            val aliceHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            val bobHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
            
            // Set pre-existing keys
            if (aliceHandshake.needsLocalKeyPair()) {
                val aliceKeyPair = aliceHandshake.localKeyPair
                aliceKeyPair.setPrivateKey(alicePriv, 0)
                assertTrue("Alice should have private key", aliceKeyPair.hasPrivateKey())
                assertTrue("Alice should have public key", aliceKeyPair.hasPublicKey())
                println("✅ Alice keys set successfully")
            }
            
            if (bobHandshake.needsLocalKeyPair()) {
                val bobKeyPair = bobHandshake.localKeyPair
                bobKeyPair.setPrivateKey(bobPriv, 0)
                assertTrue("Bob should have private key", bobKeyPair.hasPrivateKey())
                assertTrue("Bob should have public key", bobKeyPair.hasPublicKey())
                println("✅ Bob keys set successfully")
            }
            
            // Start handshakes
            aliceHandshake.start()
            bobHandshake.start()
            
            // Execute handshake
            val msg1 = executeHandshakeStep(aliceHandshake, null, "Alice msg1")
            val msg2 = executeHandshakeStep(bobHandshake, msg1!!, "Bob msg2")
            val msg3 = executeHandshakeStep(aliceHandshake, msg2!!, "Alice msg3")
            executeHandshakeStep(bobHandshake, msg3!!, "Bob complete")
            
            assertEquals("Both should be ready to split", HandshakeState.SPLIT, aliceHandshake.action)
            assertEquals("Both should be ready to split", HandshakeState.SPLIT, bobHandshake.action)
            
            // Verify remote keys
            assertTrue("Alice should have Bob's remote key", aliceHandshake.hasRemotePublicKey())
            assertTrue("Bob should have Alice's remote key", bobHandshake.hasRemotePublicKey())
            
            val aliceSeenRemote = ByteArray(32)
            val bobSeenRemote = ByteArray(32)
            aliceHandshake.remotePublicKey.getPublicKey(aliceSeenRemote, 0)
            bobHandshake.remotePublicKey.getPublicKey(bobSeenRemote, 0)
            
            assertArrayEquals("Alice should see Bob's public key", bobPub, aliceSeenRemote)
            assertArrayEquals("Bob should see Alice's public key", alicePub, bobSeenRemote)
            
            println("✅ Handshake with pre-set keys completed successfully!")
            
            aliceHandshake.destroy()
            bobHandshake.destroy()
            
        } catch (e: Exception) {
            println("❌ Handshake with pre-set keys failed: ${e.message}")
            e.printStackTrace()
            fail("Handshake should work with pre-set keys")
        }
    }

    private fun generateKeyPair(): Pair<ByteArray, ByteArray> {
        val dhState = Noise.createDH("25519")
        dhState.generateKeyPair()
        
        val privateKey = ByteArray(32)
        val publicKey = ByteArray(32)
        dhState.getPrivateKey(privateKey, 0)
        dhState.getPublicKey(publicKey, 0)
        
        dhState.destroy()
        return Pair(privateKey, publicKey)
    }

    private fun executeHandshakeStep(handshake: HandshakeState, incoming: ByteArray?, stepName: String): ByteArray? {
        return if (incoming != null) {
            val payload = ByteArray(256)
            handshake.readMessage(incoming, 0, incoming.size, payload, 0)
            println("$stepName: Read ${incoming.size} bytes")
            
            if (handshake.action == HandshakeState.WRITE_MESSAGE) {
                val response = ByteArray(256)
                val len = handshake.writeMessage(response, 0, ByteArray(0), 0, 0)
                val actualResponse = response.copyOf(len)
                println("$stepName: Sent ${actualResponse.size} bytes")
                actualResponse
            } else {
                println("$stepName: No response needed, action: ${handshake.action}")
                null
            }
        } else {
            val msg = ByteArray(256)
            val len = handshake.writeMessage(msg, 0, ByteArray(0), 0, 0)
            val actualMsg = msg.copyOf(len)
            println("$stepName: Sent initial ${actualMsg.size} bytes")
            actualMsg
        }
    }
}
