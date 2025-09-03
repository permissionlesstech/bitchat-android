package com.bitchat.android.nostr

import org.junit.Test
import org.junit.Assert.*

/**
 * Test class for NostrEvent with real Schnorr signatures
 */
class NostrEventTest {

    @Test
    fun testKeyGeneration() {
        // Test key pair generation
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        
        assertNotNull(privateKey)
        assertNotNull(publicKey)
        assertEquals(64, privateKey.length) // 32 bytes as hex
        assertEquals(64, publicKey.length)  // 32 bytes as hex
        
        // Validate the keys
        assertTrue(NostrCrypto.isValidPrivateKey(privateKey))
        assertTrue(NostrCrypto.isValidPublicKey(publicKey))
        
        // Derive public key from private key and verify they match
        val derivedPublicKey = NostrCrypto.derivePublicKey(privateKey)
        assertEquals(publicKey, derivedPublicKey)
    }
    
    @Test
    fun testSchnorrSignatureBasic() {
        // Generate a key pair
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        
        // Create test message hash
        val messageHash = "hello world".toByteArray().let { data ->
            java.security.MessageDigest.getInstance("SHA-256").digest(data)
        }
        
        // Sign the hash
        val signature = NostrCrypto.schnorrSign(messageHash, privateKey)
        
        assertNotNull(signature)
        assertEquals(128, signature.length) // 64 bytes as hex
        
        // Verify the signature
        val isValid = NostrCrypto.schnorrVerify(messageHash, signature, publicKey)
        assertTrue("Schnorr signature should be valid", isValid)
    }
    
    @Test
    fun testNostrEventCreationAndSigning() {
        // Generate a key pair
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        
        // Create a text note event
        val event = NostrEvent.createTextNote(
            content = "Hello, Nostr! This is a test message.",
            publicKeyHex = publicKey,
            privateKeyHex = privateKey,
            tags = listOf(listOf("t", "test"))
        )
        
        assertNotNull(event.id)
        assertNotNull(event.sig)
        assertEquals(publicKey, event.pubkey)
        assertEquals(NostrKind.TEXT_NOTE, event.kind)
        assertEquals("Hello, Nostr! This is a test message.", event.content)
        
        // Verify the event is valid
        assertTrue("Event should be valid", event.isValid())
        assertTrue("Event signature should be valid", event.isValidSignature())
    }
    
    @Test
    fun testNostrEventJsonSerialization() {
        // Generate a key pair
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        
        // Create an event
        val originalEvent = NostrEvent.createTextNote(
            content = "Test serialization",
            publicKeyHex = publicKey,
            privateKeyHex = privateKey
        )
        
        // Serialize to JSON
        val jsonString = originalEvent.toJsonString()
        assertNotNull(jsonString)
        assertTrue(jsonString.contains("\"kind\":1"))
        assertTrue(jsonString.contains("\"content\":\"Test serialization\""))
        
        // Deserialize from JSON
        val deserializedEvent = NostrEvent.fromJsonString(jsonString)
        assertNotNull(deserializedEvent)
        
        // Verify they match
        assertEquals(originalEvent.id, deserializedEvent!!.id)
        assertEquals(originalEvent.pubkey, deserializedEvent.pubkey)
        assertEquals(originalEvent.content, deserializedEvent.content)
        assertEquals(originalEvent.sig, deserializedEvent.sig)
        
        // Verify the deserialized event is still valid
        assertTrue("Deserialized event should be valid", deserializedEvent.isValid())
    }
    
    @Test
    fun testMetadataEventCreation() {
        // Generate a key pair
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        
        // Create metadata JSON
        val metadata = """{"name":"Test User","about":"Testing Nostr implementation","picture":"https://example.com/avatar.jpg"}"""
        
        // Create a metadata event
        val event = NostrEvent.createMetadata(
            metadata = metadata,
            publicKeyHex = publicKey,
            privateKeyHex = privateKey
        )
        
        assertEquals(NostrKind.METADATA, event.kind)
        assertEquals(metadata, event.content)
        assertTrue("Metadata event should be valid", event.isValid())
    }
    
    @Test
    fun testEventIdConsistency() {
        // Create the same event multiple times and verify the ID is consistent
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        
        val timestamp = 1234567890
        val content = "Consistent ID test"
        
        val event1 = NostrEvent(
            pubkey = publicKey,
            createdAt = timestamp,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content
        ).sign(privateKey)
        
        val event2 = NostrEvent(
            pubkey = publicKey,
            createdAt = timestamp,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = content
        ).sign(privateKey)
        
        // IDs should be the same (deterministic based on content)
        assertEquals("Event IDs should be consistent", event1.id, event2.id)
        
        // But signatures should be different (due to nonce randomness)
        assertNotEquals("Signatures should be different due to random nonce", event1.sig, event2.sig)
        
        // Both should be valid
        assertTrue("First event should be valid", event1.isValid())
        assertTrue("Second event should be valid", event2.isValid())
    }
    
    @Test
    fun testInvalidSignatureDetection() {
        // Generate a key pair
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        
        // Create a valid event
        val validEvent = NostrEvent.createTextNote(
            content = "Valid event",
            publicKeyHex = publicKey,
            privateKeyHex = privateKey
        )
        
        assertTrue("Valid event should pass validation", validEvent.isValid())
        
        // Create an event with tampered content
        val tamperedEvent = validEvent.copy(content = "Tampered content")
        
        assertFalse("Tampered event should fail validation", tamperedEvent.isValid())
        assertFalse("Tampered event signature should be invalid", tamperedEvent.isValidSignature())
    }
}
