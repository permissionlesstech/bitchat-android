package com.bitchat.android.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * `p` and `m` arrive off the wire — a REQUEST_SYNC carries P as a uint8 — and
 * the decoded set decides which packets a peer is told it already has. Decoding
 * garbage from an out-of-range parameter therefore withholds real packets, so
 * out-of-range parameters have to decode to "peer has nothing" instead.
 */
class GCSFilterParameterTest {

    private fun ids(n: Int): List<ByteArray> {
        val random = Random(42)
        return List(n) {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            bytes
        }
    }

    @Test
    fun `a filter round-trips with the parameters it was built with`() {
        val params = GCSFilter.buildFilter(ids(20), maxBytes = 400, targetFpr = 0.01)
        val decoded = GCSFilter.decodeToSortedSet(params.p, params.m, params.data)

        assertTrue("expected a non-empty decode", decoded.isNotEmpty())
        assertTrue("values must stay in range", decoded.all { it in 1 until params.m })
        assertEquals(decoded.toList(), decoded.sorted())
    }

    @Test
    fun `an out-of-range p decodes to nothing rather than to garbage`() {
        val params = GCSFilter.buildFilter(ids(20), maxBytes = 400, targetFpr = 0.01)

        // 64 and above wrap Kotlin's shift operators; 255 is what the byte allows.
        for (p in listOf(0, 33, 64, 200, 255)) {
            val decoded = GCSFilter.decodeToSortedSet(p, params.m, params.data)
            assertTrue("p=$p should decode to nothing, got ${decoded.size} values", decoded.isEmpty())
        }
    }

    @Test
    fun `a degenerate m decodes to nothing`() {
        val params = GCSFilter.buildFilter(ids(20), maxBytes = 400, targetFpr = 0.01)

        assertTrue(GCSFilter.decodeToSortedSet(params.p, 0L, params.data).isEmpty())
        assertTrue(GCSFilter.decodeToSortedSet(params.p, 1L, params.data).isEmpty())
    }
}
