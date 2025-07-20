package com.bitchat.android.noise

import com.bitchat.android.noise.southernstorm.protocol.*
import org.junit.Test
import org.junit.Assert.*
import java.security.SecureRandom

/**
 * Test to verify that we can set pre-generated keys on noise-java DHState objects
 * This isolated test will prove the correct approach for our handshake key injection
 */
class NoiseKeySettingTest {

    companion object {
        private const val PROTOCOL_NAME = "Noise_XX_25519_ChaChaPoly_SHA256"
    }

    @Test
    fun testDHStateKeySettingBasics() {
        println("=== Testing DHState Key Setting Basics ===")
        
        try {
            // Create a DHState object for Curve25519
            val dhState = Noise.createDH("25519")
            
            println("Created DHState: ${dhState.javaClass.name}")
            println("Algorithm: ${dhState.dhName}")
            println("Private key length: ${dhState.privateKeyLength}")
            println("Public key length: ${dhState.publicKeyLength}")
            
            // Check initial state
            println("Initial state - hasPrivateKey: ${dhState.hasPrivateKey()}, hasPublicKey: ${dhState.hasPublicKey()}")
            assertFalse("DHState should not have private key initially", dhState.hasPrivateKey())
            assertFalse("DHState should not have public key initially", dhState.hasPublicKey())
            
            // Generate a key pair first to see what working keys look like
            dhState.generateKeyPair()
            assertTrue("Generated key pair should have private key", dhState.hasPrivateKey())
            assertTrue("Generated key pair should have public key", dhState.hasPublicKey())
            
            // Extract the generated keys
            val generatedPrivate = ByteArray(32)
            val generatedPublic = ByteArray(32)
            dhState.getPrivateKey(generatedPrivate, 0)
            dhState.getPublicKey(generatedPublic, 0)
            
            println("Generated private key: ${generatedPrivate.joinToString("") { "%02x".format(it) }}")
            println("Generated public key: ${generatedPublic.joinToString("") { "%02x".format(it) }}")
            
            // Clean up
            dhState.destroy()
            println("✅ Basic DHState operations work")
            
        } catch (e: Exception) {
            println("❌ Basic DHState test failed: ${e.message}")
            e.printStackTrace()
            fail("Basic DHState operations should work")
        }
    }

    @Test
    fun testSettingPreGeneratedKeys() {
        println("=== Testing Setting Pre-Generated Keys ===")
        
        try {
            // First, generate a valid key pair using the noise library
            val originalDH = Noise.createDH("25519")
            originalDH.generateKeyPair()
            
            val validPrivateKey = ByteArray(32)
            val validPublicKey = ByteArray(32)
            originalDH.getPrivateKey(validPrivateKey, 0)
            originalDH.getPublicKey(validPublicKey, 0)
            
            println("Valid keys generated:")
            println("Private: ${validPrivateKey.joinToString("") { "%02x".format(it) }}")
            println("Public: ${validPublicKey.joinToString("") { "%02x".format(it) }}")
            
            originalDH.destroy()
            
            // Now try to set these keys on a new DHState
            val newDH = Noise.createDH("25519")
            
            println("Setting keys on new DHState...")
            
            // Try setting private key
            try {
                newDH.setPrivateKey(validPrivateKey, 0)
                println("Private key set successfully")
            } catch (e: Exception) {
                println("Failed to set private key: ${e.message}")
                throw e
            }
            
            // Try setting public key
            try {
                newDH.setPublicKey(validPublicKey, 0)
                println("Public key set successfully")
            } catch (e: Exception) {
                println("Failed to set public key: ${e.message}")
                throw e
            }
            
            // Verify the keys were set
            assertTrue("DHState should have private key after setting", newDH.hasPrivateKey())
            assertTrue("DHState should have public key after setting", newDH.hasPublicKey())
            
            // Verify we can extract the same keys back
            val extractedPrivate = ByteArray(32)
            val extractedPublic = ByteArray(32)
            newDH.getPrivateKey(extractedPrivate, 0)
            newDH.getPublicKey(extractedPublic, 0)
            
            assertArrayEquals("Extracted private key should match set key", validPrivateKey, extractedPrivate)
            assertArrayEquals("Extracted public key should match set key", validPublicKey, extractedPublic)
            
            newDH.destroy()
            println("✅ Setting pre-generated keys works!")
            
        } catch (e: Exception) {
            println("❌ Setting pre-generated keys failed: ${e.message}")
            e.printStackTrace()
            fail("Should be able to set pre-generated keys")
        }
    }

    @Test
    fun testHandshakeStateWithPreGeneratedKeys() {
        println("=== Testing HandshakeState with Pre-Generated Keys ===")
        
        try {
            // Generate two key pairs for Alice and Bob
            val aliceKeys = generateValidKeyPair()
            val bobKeys = generateValidKeyPair()
            
            println("Alice keys generated:")
            println("Private: ${aliceKeys.first.joinToString("") { "%02x".format(it) }}")
            println("Public: ${aliceKeys.second.joinToString("") { "%02x".format(it) }}")
            
            println("Bob keys generated:")
            println("Private: ${bobKeys.first.joinToString("") { "%02x".format(it) }}")
            println("Public: ${bobKeys.second.joinToString("") { "%02x".format(it) }}")
            
            // Test Alice as initiator
            println("\n--- Testing Alice as Initiator ---")
            val aliceHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.INITIATOR)
            
            if (aliceHandshake.needsLocalKeyPair()) {
                println("Alice needs local key pair")
                val aliceLocalKeyPair = aliceHandshake.getLocalKeyPair()
                assertNotNull("Alice should get local key pair", aliceLocalKeyPair)
                
                println("Setting Alice's keys...")
                aliceLocalKeyPair!!.setPrivateKey(aliceKeys.first, 0)
                aliceLocalKeyPair.setPublicKey(aliceKeys.second, 0)
                
                assertTrue("Alice should have private key", aliceLocalKeyPair.hasPrivateKey())
                assertTrue("Alice should have public key", aliceLocalKeyPair.hasPublicKey())
                println("✅ Alice's keys set successfully")
            }
            
            println("Starting Alice's handshake...")
            aliceHandshake.start()
            println("✅ Alice's handshake started successfully!")
            
            // Test Bob as responder  
            println("\n--- Testing Bob as Responder ---")
            val bobHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
            
            if (bobHandshake.needsLocalKeyPair()) {
                println("Bob needs local key pair")
                val bobLocalKeyPair = bobHandshake.getLocalKeyPair()
                assertNotNull("Bob should get local key pair", bobLocalKeyPair)
                
                println("Setting Bob's keys...")
                bobLocalKeyPair!!.setPrivateKey(bobKeys.first, 0)
                bobLocalKeyPair.setPublicKey(bobKeys.second, 0)
                
                assertTrue("Bob should have private key", bobLocalKeyPair.hasPrivateKey())
                assertTrue("Bob should have public key", bobLocalKeyPair.hasPublicKey())
                println("✅ Bob's keys set successfully")
            }
            
            println("Starting Bob's handshake...")
            bobHandshake.start()
            println("✅ Bob's handshake started successfully!")
            
            // Clean up
            aliceHandshake.destroy()
            bobHandshake.destroy()
            
            println("✅ HandshakeState with pre-generated keys works!")
            
        } catch (e: Exception) {
            println("❌ HandshakeState test failed: ${e.message}")
            e.printStackTrace()
            fail("HandshakeState should work with pre-generated keys")
        }
    }

    @Test
    fun testRawKeyDataAsInOurApp() {
        println("=== Testing Raw Key Data As Generated In Our App ===")
        
        try {
            // Simulate the exact key generation method from NoiseEncryptionService.kt
            val dhState = Noise.createDH("25519")
            dhState.generateKeyPair()
            
            val rawPrivateKey = ByteArray(32)
            val rawPublicKey = ByteArray(32)
            dhState.getPrivateKey(rawPrivateKey, 0)
            dhState.getPublicKey(rawPublicKey, 0)
            
            dhState.destroy()
            
            println("Raw keys from our generation method:")
            println("Private: ${rawPrivateKey.joinToString("") { "%02x".format(it) }}")
            println("Public: ${rawPublicKey.joinToString("") { "%02x".format(it) }}")
            println("Private key size: ${rawPrivateKey.size}")
            println("Public key size: ${rawPublicKey.size}")
            
            // Now try using these exact keys in a HandshakeState
            val testHandshake = HandshakeState(PROTOCOL_NAME, HandshakeState.RESPONDER)
            
            if (testHandshake.needsLocalKeyPair()) {
                val localKeyPair = testHandshake.getLocalKeyPair()
                assertNotNull("Should get local key pair", localKeyPair)
                
                println("DHState info:")
                println("Algorithm: ${localKeyPair!!.dhName}")
                println("Expected private key length: ${localKeyPair.privateKeyLength}")
                println("Expected public key length: ${localKeyPair.publicKeyLength}")
                
                // This is the exact pattern from our app
                localKeyPair.setPrivateKey(rawPrivateKey, 0)
                localKeyPair.setPublicKey(rawPublicKey, 0)
                
                assertTrue("Should have private key", localKeyPair.hasPrivateKey())
                assertTrue("Should have public key", localKeyPair.hasPublicKey())
                
                println("✅ Keys set successfully using our app's exact pattern!")
                
                // Verify we can start the handshake
                testHandshake.start()
                println("✅ Handshake started successfully!")
            }
            
            testHandshake.destroy()
            println("✅ Raw key data test passed!")
            
        } catch (e: Exception) {
            println("❌ Raw key data test failed: ${e.message}")
            e.printStackTrace()
            fail("Raw key data should work")
        }
    }

    // Helper functions
    
    private fun generateValidKeyPair(): Pair<ByteArray, ByteArray> {
        val dhState = Noise.createDH("25519")
        dhState.generateKeyPair()
        
        val privateKey = ByteArray(32)
        val publicKey = ByteArray(32)
        dhState.getPrivateKey(privateKey, 0)
        dhState.getPublicKey(publicKey, 0)
        
        dhState.destroy()
        return Pair(privateKey, publicKey)
    }
    
    private fun configureHandshakeWithKeys(handshake: HandshakeState, privateKey: ByteArray, publicKey: ByteArray, name: String) {
        if (handshake.needsLocalKeyPair()) {
            val localKeyPair = handshake.getLocalKeyPair()
            assertNotNull("$name should get local key pair", localKeyPair)
            
            localKeyPair!!.setPrivateKey(privateKey, 0)
            localKeyPair.setPublicKey(publicKey, 0)
            
            assertTrue("$name should have private key", localKeyPair.hasPrivateKey())
            assertTrue("$name should have public key", localKeyPair.hasPublicKey())
            println("✅ $name's keys configured successfully")
        }
    }
}
