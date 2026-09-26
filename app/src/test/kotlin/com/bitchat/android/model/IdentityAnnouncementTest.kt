package com.bitchat.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentityAnnouncementTest {
    private val nickname = "peer"
    private val noiseKey = ByteArray(32) { 0x11 }
    private val signingKey = ByteArray(32) { 0x22 }

    @Test
    fun `private media capability uses iOS little-endian bytes`() {
        assertArrayEquals(byteArrayOf(0x00, 0x01), PeerCapabilities.PRIVATE_MEDIA.encoded())
        assertTrue(PeerCapabilities.decode(byteArrayOf(0x00, 0x01)).contains(PeerCapabilities.PRIVATE_MEDIA))
    }

    @Test
    fun `legacy announcement without capability TLV still decodes`() {
        val legacy = IdentityAnnouncement(nickname, noiseKey, signingKey).encode()!!

        val decoded = IdentityAnnouncement.decode(legacy)!!

        assertEquals(nickname, decoded.nickname)
        assertArrayEquals(noiseKey, decoded.noisePublicKey)
        assertArrayEquals(signingKey, decoded.signingPublicKey)
        assertNull(decoded.capabilities)
    }

    @Test
    fun `explicit empty capability TLV decodes as present but empty`() {
        val legacy = IdentityAnnouncement(nickname, noiseKey, signingKey).encode()!!

        val decoded = IdentityAnnouncement.decode(legacy + byteArrayOf(0x05, 0x00))!!

        assertEquals(PeerCapabilities.NONE, decoded.capabilities)
    }

    @Test
    fun `unknown capability bits and TLVs survive decode and re-encode`() {
        val legacy = IdentityAnnouncement(nickname, noiseKey, signingKey).encode()!!
        val wire = legacy + byteArrayOf(
            0x05, 0x02, 0x00, 0x81.toByte(), // privateMedia plus unknown bit 15
            0x7F, 0x03, 0x01, 0x02, 0x03
        )

        val decoded = IdentityAnnouncement.decode(wire)!!

        assertEquals(0x8100L, decoded.capabilities?.rawValue)
        assertEquals(1, decoded.unknownTLVs.size)
        assertEquals(0x7F, decoded.unknownTLVs.single().type)
        assertArrayEquals(byteArrayOf(0x01, 0x02, 0x03), decoded.unknownTLVs.single().value)

        val roundTripped = IdentityAnnouncement.decode(decoded.encode()!!)!!
        assertEquals(decoded.capabilities, roundTripped.capabilities)
        assertEquals(decoded.unknownTLVs, roundTripped.unknownTLVs)
    }

    @Test
    fun `local announcement send advertises private media`() {
        val encoded = IdentityAnnouncement.forLocalPeer(nickname, noiseKey, signingKey).encode()!!

        assertArrayEquals(
            byteArrayOf(0x05, 0x02, 0x00, 0x01),
            encoded.takeLast(4).toByteArray()
        )
        assertTrue(
            IdentityAnnouncement.decode(encoded)!!
                .capabilities!!
                .contains(PeerCapabilities.PRIVATE_MEDIA)
        )
    }
}
