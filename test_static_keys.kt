/**
 * Quick test to verify static key handling works correctly with noise-java
 */

import com.bitchat.android.noise.southernstorm.protocol.*
import java.security.SecureRandom

fun main() {
    println("Testing static key handling with noise-java library...\n")
    
    try {
        // Create test static keys
        val random = SecureRandom()
        val staticPrivate1 = ByteArray(32).also { random.nextBytes(it) }
        val staticPrivate2 = ByteArray(32).also { random.nextBytes(it) }
        
        // Test 1: Verify DHState supports setPrivateKey correctly
        println("1. Testing DHState.setPrivateKey()...")
        val dhState = Noise.createDH("25519")
        dhState.setPrivateKey(staticPrivate1, 0)
        
        if (!dhState.hasPrivateKey() || !dhState.hasPublicKey()) {
            throw RuntimeException("DHState failed to generate key pair from private key")
        }
        
        val retrievedPrivate = ByteArray(32)
        val generatedPublic = ByteArray(32)
        dhState.getPrivateKey(retrievedPrivate, 0)
        dhState.getPublicKey(generatedPublic, 0)
        
        if (!retrievedPrivate.contentEquals(staticPrivate1)) {
            throw RuntimeException("Retrieved private key doesn't match set private key")
        }
        
        println("✓ DHState setPrivateKey() works correctly")
        
        // Test 2: Full XX handshake with static keys
        println("\n2. Testing full XX handshake with persistent static keys...")
        
        // Initiator
        val initiatorHandshake = HandshakeState("Noise_XX_25519_ChaChaPoly_SHA256", HandshakeState.INITIATOR)
        val initiatorKeyPair = initiatorHandshake.getLocalKeyPair()!!
        initiatorKeyPair.setPrivateKey(staticPrivate1, 0)
        initiatorHandshake.start()
        
        // Responder  
        val responderHandshake = HandshakeState("Noise_XX_25519_ChaChaPoly_SHA256", HandshakeState.RESPONDER)
        val responderKeyPair = responderHandshake.getLocalKeyPair()!!
        responderKeyPair.setPrivateKey(staticPrivate2, 0)
        responderHandshake.start()
        
        // Message 1: -> e
        val message1Buffer = ByteArray(64)
        val message1Length = initiatorHandshake.writeMessage(message1Buffer, 0, ByteArray(0), 0, 0)
        val message1 = message1Buffer.copyOf(message1Length)
        println("   Message 1: ${message1.size} bytes")
        
        val payload1Buffer = ByteArray(64)
        responderHandshake.readMessage(message1, 0, message1.size, payload1Buffer, 0)
        
        // Message 2: <- e, ee, s, es
        val message2Buffer = ByteArray(128)
        val message2Length = responderHandshake.writeMessage(message2Buffer, 0, ByteArray(0), 0, 0)
        val message2 = message2Buffer.copyOf(message2Length)
        println("   Message 2: ${message2.size} bytes")
        
        val payload2Buffer = ByteArray(64)
        initiatorHandshake.readMessage(message2, 0, message2.size, payload2Buffer, 0)
        
        // Message 3: -> s, se
        val message3Buffer = ByteArray(128)
        val message3Length = initiatorHandshake.writeMessage(message3Buffer, 0, ByteArray(0), 0, 0)
        val message3 = message3Buffer.copyOf(message3Length)
        println("   Message 3: ${message3.size} bytes")
        
        val payload3Buffer = ByteArray(64)
        responderHandshake.readMessage(message3, 0, message3.size, payload3Buffer, 0)
        
        // Verify handshake completed
        if (initiatorHandshake.getAction() != HandshakeState.SPLIT || 
            responderHandshake.getAction() != HandshakeState.SPLIT) {
            throw RuntimeException("Handshake did not complete properly")
        }
        
        println("✓ Full XX handshake completed successfully")
        
        // Test 3: Split into transport keys
        println("\n3. Testing transport key derivation...")
        val initiatorCiphers = initiatorHandshake.split()
        val responderCiphers = responderHandshake.split()
        
        // Test encryption/decryption
        val testMessage = "Hello from Android with persistent static keys!".toByteArray()
        val encrypted = ByteArray(testMessage.size + 16)
        val encryptedLength = initiatorCiphers.getSender().encryptWithAd(null, testMessage, 0, encrypted, 0, testMessage.size)
        
        val decrypted = ByteArray(testMessage.size)
        val decryptedLength = responderCiphers.getReceiver().decryptWithAd(null, encrypted, 0, decrypted, 0, encryptedLength)
        
        if (!testMessage.contentEquals(decrypted.copyOf(decryptedLength))) {
            throw RuntimeException("Encrypted/decrypted message doesn't match")
        }
        
        println("✓ Transport encryption/decryption works correctly")
        
        // Test 4: Verify static keys were used
        println("\n4. Verifying static keys were used...")
        val initiatorStaticKey = ByteArray(32)
        val responderStaticKey = ByteArray(32)
        
        if (initiatorHandshake.hasRemotePublicKey()) {
            initiatorHandshake.getRemotePublicKey()!!.getPublicKey(responderStaticKey, 0)
        }
        if (responderHandshake.hasRemotePublicKey()) {
            responderHandshake.getRemotePublicKey()!!.getPublicKey(initiatorStaticKey, 0) 
        }
        
        // Verify these are derived from our original private keys
        val testDH1 = Noise.createDH("25519")
        testDH1.setPrivateKey(staticPrivate1, 0)
        val expectedPublic1 = ByteArray(32)
        testDH1.getPublicKey(expectedPublic1, 0)
        
        val testDH2 = Noise.createDH("25519")
        testDH2.setPrivateKey(staticPrivate2, 0)
        val expectedPublic2 = ByteArray(32)
        testDH2.getPublicKey(expectedPublic2, 0)
        
        if (!initiatorStaticKey.contentEquals(expectedPublic1)) {
            throw RuntimeException("Initiator static key wasn't used correctly")
        }
        if (!responderStaticKey.contentEquals(expectedPublic2)) {
            throw RuntimeException("Responder static key wasn't used correctly") 
        }
        
        println("✓ Persistent static keys were preserved and used correctly")
        
        println("\n🎉 ALL TESTS PASSED!")
        println("The noise-java library fully supports persistent static keys for XX handshakes.")
        println("Our Android implementation should be 100% compatible with iOS now.")
        
    } catch (e: Exception) {
        println("❌ Test failed: ${e.message}")
        e.printStackTrace()
    }
}
