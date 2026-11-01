package com.bitchat.android.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthenticatedPeerStateTest {
    private val signingKey = ByteArray(32) { it.toByte() }

    @Test
    fun `encoder matches canonical iOS bytes`() {
        val encoded = AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, signingKey).encode()

        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0x02, 0x00, 0x01, 0x02, 0x20) + signingKey,
            encoded
        )
        assertEquals(
            AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, signingKey),
            AuthenticatedPeerState.decode(encoded)
        )
    }

    @Test
    fun `decoder skips unknown TLVs and accepts either known-field order`() {
        val payload = byteArrayOf(
            0x01,
            0x7F, 0x02, 0x55, 0x66,
            0x02, 0x20
        ) + signingKey + byteArrayOf(0x01, 0x01, 0x00)

        assertEquals(
            AuthenticatedPeerState(PeerCapabilities.NONE, signingKey),
            AuthenticatedPeerState.decode(payload)
        )
    }

    @Test
    fun `decoder rejects malformed duplicate missing and noncanonical fields`() {
        val validCapabilities = byteArrayOf(0x01, 0x01, 0x00)
        val validSigning = byteArrayOf(0x02, 0x20) + signingKey
        val invalid = listOf(
            byteArrayOf(),
            byteArrayOf(0x02) + validCapabilities + validSigning,
            byteArrayOf(0x01) + validCapabilities,
            byteArrayOf(0x01) + validSigning,
            byteArrayOf(0x01) + validCapabilities + validCapabilities + validSigning,
            byteArrayOf(0x01, 0x01, 0x02, 0x00, 0x00) + validSigning,
            byteArrayOf(0x01, 0x01, 0x00) + validSigning,
            byteArrayOf(0x01, 0x01, 0x09) + ByteArray(9) + validSigning,
            byteArrayOf(0x01, 0x02, 0x1F) + ByteArray(31) + validCapabilities,
            byteArrayOf(0x01, 0x7F),
            byteArrayOf(0x01, 0x7F, 0x02, 0x01)
        )

        invalid.forEach { assertNull("Expected rejection for ${it.joinToString()}", AuthenticatedPeerState.decode(it)) }
    }

    @Test
    fun `Noise wrapper emits and decodes canonical 0x21`() {
        val state = AuthenticatedPeerState(PeerCapabilities.NONE, signingKey)
        val encoded = NoisePayload(NoisePayloadType.PEER_STATE, state.encode()).encode()

        assertEquals(0x21, encoded[0].toInt() and 0xFF)
        assertEquals(NoisePayloadType.PEER_STATE, NoisePayload.decode(encoded)?.type)
    }
}
