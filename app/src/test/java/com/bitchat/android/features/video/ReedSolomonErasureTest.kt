package com.bitchat.android.features.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ReedSolomonErasureTest {
    @Test
    fun fieldInverseIsConsistent() {
        assertEquals(0x1D, ReedSolomonErasure.multiply(0x80, 0x02))
        for (value in 1..255) {
            assertEquals(1, ReedSolomonErasure.multiply(value, ReedSolomonErasure.inverse(value)))
        }
    }

    @Test
    fun pinnedParityVectorMatchesSpecification() {
        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
        val sources = listOf(
            bytes(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07),
            bytes(0x80, 0x40, 0x20, 0x10, 0x08, 0x04, 0x02, 0x01),
            bytes(0xff, 0xde, 0xad, 0xbe, 0xef, 0x00, 0x1d, 0xc3),
        )
        val parity = ReedSolomonErasure.encode(sources, 2)
        assertArrayEquals(bytes(0xbf, 0x0a, 0x48, 0xb7, 0x1c, 0x01, 0x1e, 0xbb), parity[0])
        assertArrayEquals(bytes(0xdd, 0xd0, 0x9f, 0x5e, 0x07, 0xe0, 0x57, 0xc1), parity[1])
        val rebuilt = ReedSolomonErasure.reconstruct(
            listOf(null, null, sources[2]), mapOf(0 to parity[0], 1 to parity[1])
        )!!
        sources.indices.forEach { assertArrayEquals(sources[it], rebuilt[it]) }
    }

    @Test
    fun anyKOfKPlusRSymbolsRebuildTheBlock() {
        val random = Random(1234)
        for (k in listOf(1, 2, 5, 12, 32)) {
            for (r in listOf(1, 2, 4, 16)) {
                val sources = List(k) { ByteArray(64).also(random::nextBytes) }
                val parity = ReedSolomonErasure.encode(sources, r)
                repeat(20) {
                    val lostCount = random.nextInt(0, minOf(k, r) + 1)
                    val lost = (0 until k).shuffled(random).take(lostCount).toSet()
                    val keptParity = (0 until r).shuffled(random).take(lostCount)
                        .associateWith { parity[it] }
                    val slots = sources.mapIndexed { index, symbol -> symbol.takeUnless { index in lost } }
                    val rebuilt = ReedSolomonErasure.reconstruct(slots, keptParity)!!
                    sources.indices.forEach { assertArrayEquals(sources[it], rebuilt[it]) }
                }
            }
        }
    }

    @Test
    fun tooFewSymbolsCannotRebuild() {
        val sources = List(4) { index -> ByteArray(8) { (index * 8 + it).toByte() } }
        val parity = ReedSolomonErasure.encode(sources, 1)
        val slots = listOf(null, sources[1], null, sources[3])
        assertNull(ReedSolomonErasure.reconstruct(slots, mapOf(0 to parity[0])))
    }

    @Test
    fun singleSourceParityIsARepetition() {
        val source = byteArrayOf(1, 2, 3, 4)
        val parity = ReedSolomonErasure.encode(listOf(source), 1)
        assertArrayEquals(source, ReedSolomonErasure.reconstruct(listOf(null), mapOf(0 to parity[0]))!![0])
    }
}
