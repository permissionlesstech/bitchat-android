package com.bitchat.android.sync

import kotlin.math.ceil
import kotlin.math.ln

/**
 * Rotating Bloom filter for seen packet IDs.
 * Maintains two filters for seamless rotation when reaching capacity thresholds.
 */
class SeenPacketsBloomFilter(
    private val maxBytes: Int = 256,        // up to 256 bytes
    private val targetFpr: Double = 0.01,   // 1%
    private val seed: Long = 0x9E3779B97F4A7C15L
) {
    data class Snapshot(val mBytes: Int, val k: Int, val bits: ByteArray)

    private data class Filter(
        val mBits: Int,
        val k: Int,
        val bits: ByteArray,
        var count: Int = 0
    )

    // Compute optimal k and capacity for desired FPR given m bits
    private fun deriveParams(mBits: Int, fpr: Double): Pair<Int, Int> {
        // Optimal k ≈ (m/n) ln 2 -> n ≈ -(m ln 2^2) / ln p
        val n = (-mBits * (ln(2.0) * ln(2.0)) / ln(fpr)).toInt().coerceAtLeast(1)
        val k = ceil((mBits.toDouble() / n) * ln(2.0)).toInt().coerceAtLeast(1)
        return k to n
    }

    private val mBits = (maxBytes * 8).coerceAtLeast(8)
    private val (kOptimal, capacityOptimal) = deriveParams(mBits, targetFpr)

    // Active/standby filters
    @Volatile private var active = newFilter()
    @Volatile private var standby = newFilter()
    @Volatile private var usingStandby = false

    private fun newFilter(): Filter {
        val k = kOptimal
        return Filter(mBits = mBits, k = k, bits = ByteArray(maxBytes), count = 0)
    }

    // Double hashing: h_i(x) = h1(x) + i*h2(x)
    private fun indicesFor(id: ByteArray, mBits: Int, k: Int): IntArray {
        var h1 = 1469598103934665603L // FNV offset basis
        var h2 = seed
        for (b in id) {
            h1 = (h1 xor (b.toLong() and 0xFF)) * 1099511628211L
            h2 = (h2 xor (b.toLong() and 0xFF)) * 0x100000001B3L
        }
        val result = IntArray(k)
        for (i in 0 until k) {
            val combined = (h1 + i * h2)
            val idx = ((combined and Long.MAX_VALUE) % mBits).toInt()
            result[i] = idx
        }
        return result
    }

    @Synchronized fun add(id: ByteArray) {
        // Rotation: if active at >=50% capacity, start using standby for new inserts
        val startStandbyAt = capacityOptimal / 2
        if (!usingStandby && active.count >= startStandbyAt) {
            standby = newFilter()
            usingStandby = true
        }

        // Insert into active
        insertInto(active, id)

        // If using standby, also insert into standby
        if (usingStandby) {
            insertInto(standby, id)
        }

        // If active reached full capacity, rotate: clear active and swap
        if (active.count >= capacityOptimal) {
            active = standby
            standby = newFilter()
            usingStandby = false
        }
    }

    private fun insertInto(filter: Filter, id: ByteArray) {
        val idx = indicesFor(id, filter.mBits, filter.k)
        for (i in idx) {
            val byteIndex = i / 8
            val bitIndex = i % 8
            filter.bits[byteIndex] = (filter.bits[byteIndex].toInt() or (1 shl (7 - bitIndex))).toByte()
        }
        filter.count += 1
    }

    fun mightContain(id: ByteArray): Boolean {
        val a = active
        val idx = indicesFor(id, a.mBits, a.k)
        var inActive = true
        for (i in idx) {
            val byteIndex = i / 8
            val bitIndex = i % 8
            val set = ((a.bits[byteIndex].toInt() shr (7 - bitIndex)) and 1) == 1
            if (!set) { inActive = false; break }
        }
        if (inActive) return true

        // Also check standby if we’re in rotation phase
        if (usingStandby) {
            val s = standby
            val idx2 = indicesFor(id, s.mBits, s.k)
            for (i in idx2) {
                val byteIndex = i / 8
                val bitIndex = i % 8
                val set = ((s.bits[byteIndex].toInt() shr (7 - bitIndex)) and 1) == 1
                if (!set) return false
            }
            return true
        }
        return false
    }

    fun snapshotActive(): Snapshot {
        val a = active
        return Snapshot(mBytes = a.bits.size, k = a.k, bits = a.bits.copyOf())
    }
}

