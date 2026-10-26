package com.bitchat.android.nostr

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class GeohashIdentityProofTest {
    private val savedBits = GeohashIdentityProof.requiredBits

    @After
    fun restore() {
        GeohashIdentityProof.requiredBits = savedBits
    }

    private fun pubkeyOf(privateKeyHex: String) = NostrCrypto.derivePublicKey(privateKeyHex)

    @Test
    fun `ground key is proven in its own geohash only`() {
        GeohashIdentityProof.requiredBits = 12
        val base = BigInteger("1f".repeat(32), 16)
        val j = GeohashIdentityProof.grind(base, "abcde", bits = 12)!!
        val pubkey = pubkeyOf(GeohashIdentityProof.offsetPrivateKeyHex(base, j))
        assertTrue(GeohashIdentityProof.isProven(pubkey, "abcde"))
        assertTrue(GeohashIdentityProof.isProven(pubkey, "ABCDE"))
        val elsewhere = listOf("abcdf", "zzzzz", "abcd", "abcdee").count { GeohashIdentityProof.isProven(pubkey, it) }
        // A 12-bit proof passes by chance in 1 of 4096 other channels.
        assertEquals(0, elsewhere)
    }

    @Test
    fun `grinding finds the smallest passing offset`() {
        GeohashIdentityProof.requiredBits = 8
        val base = BigInteger("2a".repeat(32), 16)
        val j = GeohashIdentityProof.grind(base, "abcde", bits = 8)!!
        for (k in 0 until j) {
            val pub = pubkeyOf(GeohashIdentityProof.offsetPrivateKeyHex(base, k))
            assertFalse(GeohashIdentityProof.isProven(pub, "abcde"))
        }
    }

    @Test
    fun `random keys are not proven`() {
        GeohashIdentityProof.requiredBits = 20
        val proven = (1..2_000).count { i ->
            GeohashIdentityProof.isProven(pubkeyOf(BigInteger.valueOf(i.toLong() * 7919).toString(16).padStart(64, '0')), "abcde")
        }
        assertEquals(0, proven)
    }

    @Test
    fun `malformed pubkeys are not proven`() {
        assertFalse(GeohashIdentityProof.isProven("zz", "abcde"))
        assertFalse(GeohashIdentityProof.isProven("ab".repeat(31), "abcde"))
        GeohashIdentityProof.requiredBits = 0
        assertTrue(GeohashIdentityProof.isProven("0f".repeat(32), "abcde"))
        assertFalse(GeohashIdentityProof.isProven("+f" + "0f".repeat(31), "abcde"))
    }

    @Test
    fun `grinding stops when cancelled`() {
        assertEquals(null, GeohashIdentityProof.grind(BigInteger.TEN, "abcde", bits = 64) { false })
    }
}
