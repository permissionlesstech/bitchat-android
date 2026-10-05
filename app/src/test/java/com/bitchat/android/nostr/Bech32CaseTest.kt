package com.bitchat.android.nostr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * BIP-173 allows a bech32 string to be all lowercase or all uppercase, and
 * forbids mixing the two. The uppercase form is what QR encoders emit — it fits
 * the alphanumeric mode, which is denser — so an npub scanned from a QR code
 * can arrive uppercased.
 */
class Bech32CaseTest {

    private val pubkey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `uppercase decodes to the same payload as lowercase`() {
        val npub = Bech32.encode("npub", pubkey)
        val (lowerHrp, lowerData) = Bech32.decode(npub)
        val (upperHrp, upperData) = Bech32.decode(npub.uppercase())

        assertEquals("npub", lowerHrp)
        assertEquals("npub", upperHrp)
        assertArrayEquals(pubkey, lowerData)
        assertArrayEquals(pubkey, upperData)
    }

    @Test
    fun `mixed case is rejected`() {
        val npub = Bech32.encode("npub", pubkey)
        val mixed = npub.substring(0, 6).uppercase() + npub.substring(6)

        assertThrows(IllegalArgumentException::class.java) { Bech32.decode(mixed) }
    }

    @Test
    fun `a corrupted uppercase string still fails the checksum`() {
        val npub = Bech32.encode("npub", pubkey).uppercase()
        val flipped = npub.dropLast(1) + if (npub.last() == 'Q') 'P' else 'Q'

        assertThrows(IllegalArgumentException::class.java) { Bech32.decode(flipped) }
    }

    @Test
    fun `round trip through encode and decode is stable`() {
        val npub = Bech32.encode("npub", pubkey)
        val (hrp, data) = Bech32.decode(npub)
        assertEquals("npub", hrp)
        assertEquals(npub, Bech32.encode(hrp, data))
    }
}
