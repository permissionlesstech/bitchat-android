package com.bitchat.android.nostr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class Bech32PaddingTest {
    @Test
    fun `a literal canonical npub decodes to its key`() {
        val npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqzqujme"
        val (hrp, key) = Bech32.decode(npub)
        assertEquals("npub", hrp)
        assertArrayEquals(ByteArray(32), key)
        assertEquals(npub, Bech32.encode(hrp, key))
    }

    @Test
    fun `valid checksum does not permit nonzero discarded padding`() {
        // The last payload symbol differs by one bit from the canonical zero key.
        val npub = "npub1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqplkg8xt"
        assertThrows(IllegalArgumentException::class.java) { Bech32.decode(npub) }
    }

    @Test
    fun `a whole extra padding symbol cannot alias an empty payload`() {
        assertThrows(IllegalArgumentException::class.java) { Bech32.decode("test1q6j8x73") }
    }

    @Test
    fun `empty and oversized human readable prefixes are rejected`() {
        for (value in listOf("10a06t8", "x".repeat(84) + "1e5y3yu")) {
            assertThrows(IllegalArgumentException::class.java) { Bech32.decode(value) }
        }
        for (hrp in listOf("", "x".repeat(84))) {
            assertThrows(IllegalArgumentException::class.java) { Bech32.encode(hrp, byteArrayOf()) }
        }
    }

    @Test
    fun `all byte lengths preserve their canonical padding`() {
        for (length in 0..64) {
            val bytes = ByteArray(length) { (it * 37).toByte() }
            assertArrayEquals(bytes, Bech32.decode(Bech32.encode("test", bytes)).second)
        }
    }
}
