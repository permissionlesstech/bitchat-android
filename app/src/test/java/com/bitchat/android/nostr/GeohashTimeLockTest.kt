package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger

class GeohashTimeLockTest {
    private val alice = "a1".repeat(32)
    private val bob = "b2".repeat(32)
    private val t = 3_000L
    private val token = GeohashTimeLock.compute(alice, "abcde", iterations = t)!!

    private fun ok(pub: String, geo: String, tok: GeohashTimeLock.Token, min: Long = t) =
        GeohashTimeLock.verify(pub, geo, tok, minIterations = min)

    @Test
    fun `modulus is the 2048-bit RSA challenge number`() {
        assertEquals(2048, GeohashTimeLock.MODULUS.bitLength())
        assertEquals(617, GeohashTimeLock.MODULUS.toString().length)
    }

    @Test
    fun `a computed token verifies for its own key and channel`() {
        assertTrue(ok(alice, "abcde", token))
        assertTrue(ok(alice, "ABCDE", token))
    }

    @Test
    fun `a token is worthless in another channel or for another key`() {
        assertFalse(ok(alice, "abcdf", token))
        assertFalse(ok(bob, "abcde", token))
    }

    @Test
    fun `tampered values fail`() {
        assertFalse(ok(alice, "abcde", token.copy(y = token.y.add(BigInteger.ONE))))
        assertFalse(ok(alice, "abcde", token.copy(proof = token.proof.add(BigInteger.ONE))))
        assertFalse(ok(alice, "abcde", token.copy(y = token.y.add(GeohashTimeLock.MODULUS))))
        assertFalse(ok(alice, "abcde", token.copy(proof = BigInteger.ZERO)))
    }

    @Test
    fun `claiming more work than was done fails`() {
        assertFalse(ok(alice, "abcde", token.copy(iterations = t * 2), min = t))
        assertFalse(ok(alice, "abcde", token.copy(iterations = t + 1), min = t))
    }

    @Test
    fun `too little work is rejected even when honest`() {
        assertFalse(ok(alice, "abcde", token, min = t + 1))
    }

    @Test
    fun `tokens round-trip through the event tag`() {
        val parsed = GeohashTimeLock.parseTag(listOf(listOf("g", "abcde"), token.toTag()))
        assertNotNull(parsed)
        assertTrue(ok(alice, "abcde", parsed!!))
    }

    @Test
    fun `malformed tags and keys are rejected`() {
        assertNull(GeohashTimeLock.parseTag(listOf(listOf("vdf", "x", "1", "1"))))
        assertNull(GeohashTimeLock.parseTag(listOf(listOf("vdf", "-5", "1", "1"))))
        assertNull(GeohashTimeLock.parseTag(listOf(listOf("vdf", "10", "f".repeat(600), "1"))))
        assertFalse(ok("A1".repeat(32), "abcde", token))
    }

    @Test
    fun `computing stops when cancelled`() {
        assertNull(GeohashTimeLock.compute(alice, "abcde", iterations = 1_000_000) { false })
    }

    @Test
    fun `verifier remembers proven senders and caps checks of new tokens`() {
        var now = 0L
        val verifier = GeohashTimeLock.Verifier(burst = 5.0, perSecond = 1.0, clockMs = { now }, minIterations = { t })
        val tags = listOf(listOf("g", "abcde"), token.toTag())
        assertTrue(verifier.isProven(tags, alice, "abcde"))
        val fake = listOf(listOf("g", "abcde"), token.copy(y = token.y.add(BigInteger.ONE)).toTag())
        val checked = (0 until 100).count { i -> !verifier.isProven(fake, "%064x".format(i), "abcde") }
        assertEquals(100, checked)
        // The budget is spent, yet alice is remembered and needs no new check.
        assertTrue(verifier.isProven(emptyList(), alice, "abcde"))
        // An uppercase spelling of her key is not canonical and is never treated as proven.
        assertFalse(verifier.isProven(emptyList(), alice.uppercase(), "abcde"))
        // Bob's real token waits for the budget to refill.
        val bobToken = GeohashTimeLock.compute(bob, "abcde", iterations = t)!!
        val bobTags = listOf(bobToken.toTag())
        assertFalse(verifier.isProven(bobTags, bob, "abcde"))
        now += 1_000
        assertTrue(verifier.isProven(bobTags, bob, "abcde"))
    }

    @Test
    fun `verification is cheap`() {
        repeat(20) { ok(alice, "abcde", token) } // warm-up
        val start = System.nanoTime()
        repeat(200) { ok(alice, "abcde", token) }
        val ms = (System.nanoTime() - start) / 1e6 / 200
        println("TIMELOCK jvm verify %.2f ms".format(ms))
        assertTrue("verify took $ms ms", ms < 50)
    }
}
