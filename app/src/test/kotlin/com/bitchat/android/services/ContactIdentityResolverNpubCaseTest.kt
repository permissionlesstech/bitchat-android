package com.bitchat.android.services

import com.bitchat.android.nostr.Bech32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test

class ContactIdentityResolverNpubCaseTest {
    private val pubkey = ByteArray(32) { (it + 1).toByte() }
    private val npub = Bech32.encode("npub", pubkey)
    private val expected = ContactIdentityResolver.nostrPubkeyHex(npub)

    @Test
    fun `uppercase npub resolves to the same key`() {
        assertEquals(expected, ContactIdentityResolver.nostrPubkeyHex(npub.uppercase()))
    }

    @Test
    fun `mixed case npub is rejected`() {
        val mixed = npub.substring(0, 6).uppercase() + npub.substring(6)
        assertNull(ContactIdentityResolver.nostrPubkeyHex(mixed))
    }

    @Test
    fun `kelvin sign is rejected rather than folded into the charset`() {
        val kelvin = npub.uppercase().replace("K", "\u212A")
        assumeTrue(kelvin != npub.uppercase())
        assertNull(ContactIdentityResolver.nostrPubkeyHex(kelvin))
    }
}
