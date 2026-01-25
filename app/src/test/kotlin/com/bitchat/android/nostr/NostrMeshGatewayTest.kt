package com.bitchat.android.nostr

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests for NostrMeshGateway deduplication logic
 */
class NostrMeshGatewayTest {

    @Before
    fun setUp() {
        // Reset any state between tests
    }

    @Test
    fun `NostrMeshSerializer TYPE_NOSTR_RELAY_REQUEST header is correct`() {
        assertEquals(0x7E.toByte(), NostrMeshSerializer.TYPE_NOSTR_RELAY_REQUEST)
    }

    @Test
    fun `NostrMeshSerializer TYPE_NOSTR_PLAINTEXT header is correct`() {
        assertEquals(0x00.toByte(), NostrMeshSerializer.TYPE_NOSTR_PLAINTEXT)
    }

    @Test
    fun `serializeEventForMesh produces valid packet with header`() {
        val event = NostrEvent(
            id = "abc123",
            pubkey = "pubkey123",
            createdAt = 1234567890,
            kind = 1,
            tags = emptyList(),
            content = "Hello World",
            sig = "sig123"
        )
        
        val serialized = NostrMeshSerializer.serializeEventForMesh(event)
        
        // First byte should be header (either 0x7E for compressed or 0x00 for plaintext)
        assertTrue(
            serialized[0] == NostrMeshSerializer.TYPE_NOSTR_RELAY_REQUEST ||
            serialized[0] == NostrMeshSerializer.TYPE_NOSTR_PLAINTEXT
        )
        
        // Should have at least 5 bytes (1 header + 4 size)
        assertTrue(serialized.size >= 5)
    }

    @Test
    fun `serializeEventForMesh and deserializeEventFromMesh are inverse operations`() {
        val event = NostrEvent(
            id = "testid123456789",
            pubkey = "testpubkey",
            createdAt = 1700000000,
            kind = 1,
            tags = listOf(listOf("nonce", "12345", "8")),
            content = "Test content for serialization",
            sig = "testsignature"
        )
        
        val serialized = NostrMeshSerializer.serializeEventForMesh(event)
        val deserialized = NostrMeshSerializer.deserializeEventFromMesh(serialized)
        
        assertNotNull(deserialized)
        
        // Parse back to event and verify
        val parsedEvent = NostrEvent.fromJsonString(deserialized!!)
        assertNotNull(parsedEvent)
        assertEquals(event.id, parsedEvent!!.id)
        assertEquals(event.pubkey, parsedEvent.pubkey)
        assertEquals(event.content, parsedEvent.content)
        assertEquals(event.kind, parsedEvent.kind)
    }

    @Test
    fun `deserializeEventFromMesh returns null for invalid header`() {
        val invalidPacket = byteArrayOf(0x99.toByte(), 0x00, 0x00, 0x00, 0x05, 0x48, 0x65, 0x6C, 0x6C, 0x6F)
        
        val result = NostrMeshSerializer.deserializeEventFromMesh(invalidPacket)
        
        assertNull(result)
    }

    @Test
    fun `deserializeEventFromMesh returns null for empty packet`() {
        val emptyPacket = byteArrayOf()
        
        val result = NostrMeshSerializer.deserializeEventFromMesh(emptyPacket)
        
        assertNull(result)
    }

    @Test
    fun `deserializeEventFromMesh returns null for packet too small`() {
        val tooSmall = byteArrayOf(0x7E, 0x00, 0x00) // Only 3 bytes, need at least 5
        
        val result = NostrMeshSerializer.deserializeEventFromMesh(tooSmall)
        
        assertNull(result)
    }
}
