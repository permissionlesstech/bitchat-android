package com.bitchat.android.features.video

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

class ReedSolomonErasureTest {
    @Test
    fun fieldInverseIsConsistent() {
        for (value in 1..255) {
            assertEquals(1, ReedSolomonErasure.multiply(value, ReedSolomonErasure.inverse(value)))
        }
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
