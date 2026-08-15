package com.bitchat.android.model

import com.bitchat.android.util.AppConstants

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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

    // reassembly ceiling (TLV 0x03)

    @Test
    fun `ceiling round-trips as a two-byte big-endian TLV`() {
        val state = AuthenticatedPeerState(
            PeerCapabilities.PRIVATE_MEDIA,
            signingKey,
            AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID
        )

        val encoded = state.encode()

        assertArrayEquals(
            byteArrayOf(0x01, 0x01, 0x02, 0x00, 0x01, 0x02, 0x20) + signingKey +
                byteArrayOf(0x03, 0x02, 0x01, 0x00),
            encoded
        )
        assertEquals(state, AuthenticatedPeerState.decode(encoded))
        assertEquals(256, AuthenticatedPeerState.decode(encoded)?.maxReassemblyFragments)
    }

    @Test
    fun `a payload without the ceiling decodes as not advertised`() {
        // Exactly what every client released before this TLV emits. It must
        // read as "did not say", never as a ceiling of zero.
        val legacy = AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, signingKey).encode()

        assertEquals(1 + 4 + 34, legacy.size)
        assertNull(AuthenticatedPeerState.decode(legacy)?.maxReassemblyFragments)
    }

    @Test
    fun `an unknown trailing TLV still leaves the ceiling readable`() {
        // The compatibility direction that lets this ship independently: the
        // decoder ignores types it does not know, so a peer that predates
        // 0x03 still reads capabilities and key out of a payload carrying one.
        val withFutureField = AuthenticatedPeerState(
            PeerCapabilities.PRIVATE_MEDIA,
            signingKey,
            512
        ).encode() + byteArrayOf(0x7E, 0x01, 0x09)

        val decoded = AuthenticatedPeerState.decode(withFutureField)
        assertEquals(PeerCapabilities.PRIVATE_MEDIA, decoded?.capabilities)
        assertEquals(512, decoded?.maxReassemblyFragments)
    }

    @Test
    fun `decoder rejects an unreadable ceiling rather than ignoring it`() {
        val prefix = byteArrayOf(0x01, 0x01, 0x02, 0x00, 0x01, 0x02, 0x20) + signingKey
        val ceiling = byteArrayOf(0x03, 0x02, 0x01, 0x00)
        val invalid = listOf(
            // Zero reassembles nothing — never a value to honour.
            prefix + byteArrayOf(0x03, 0x02, 0x00, 0x00),
            // Wrong width is a different encoding, not this one.
            prefix + byteArrayOf(0x03, 0x01, 0x01),
            prefix + byteArrayOf(0x03, 0x04, 0x00, 0x00, 0x01, 0x00),
            // Two ceilings are ambiguous; picking either is a guess.
            prefix + ceiling + ceiling
        )

        invalid.forEach {
            assertNull("Expected rejection for ${it.joinToString()}", AuthenticatedPeerState.decode(it))
        }
    }

    @Test
    fun `the advertised ceiling is the bound the reassembler actually enforces`() {
        // The whole point of the field: a sender must be told the number
        // FragmentManager rejects above, not one chosen separately from it.
        // Asserted against `local()`, which is what the mesh services send —
        // so dropping the ceiling from the advertisement fails here.
        val local = AuthenticatedPeerState.local(signingKey)

        assertEquals(PeerCapabilities.LOCAL_SUPPORTED, local.capabilities)
        assertEquals(
            AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID,
            local.maxReassemblyFragments
        )
        assertEquals(
            local,
            AuthenticatedPeerState.decode(local.encode())
        )
        assertTrue(
            "The ceiling has to fit the two-byte wire field",
            AppConstants.Fragmentation.MAX_FRAGMENTS_PER_ID in 1..0xFFFF
        )
    }

    @Test
    fun `a ceiling outside the wire field is refused at construction`() {
        listOf(0, -1, 0x10000).forEach { invalid ->
            try {
                AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, signingKey, invalid)
                fail("Expected rejection for ceiling $invalid")
            } catch (expected: IllegalArgumentException) {
                // Encoding it would truncate, or advertise a peer that
                // reassembles nothing; both are worse than not advertising.
            }
        }
    }
}
