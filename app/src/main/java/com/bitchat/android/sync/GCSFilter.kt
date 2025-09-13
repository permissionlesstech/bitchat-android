package com.bitchat.android.sync

import java.security.MessageDigest
import kotlin.math.ceil
import kotlin.math.ln

/**
 * Golomb-Coded Set (GCS) filter implementation for sync.
 *
 * Hashing:
 * - h64(id) = first 8 bytes of SHA-256 over the 16-byte PacketId (big-endian unsigned)
 * - Map to range [0, M) via (h64 % M)
 *
 * Encoding (v1):
 * - Sort mapped values ascending; encode deltas (first is v0, then vi - v{i-1}) as positive integers
 * - For each delta x >= 1, write Golomb-Rice code with parameter P:
 *   q = (x - 1) >> P (unary q ones followed by a zero), then P low bits r = (x - 1) & ((1<<P)-1)
 * - Bitstream is packed MSB-first in each byte.
 */
object GCSFilter {
    data class Params(
        val p: Int,         // Golomb-Rice parameter (>= 1)
        val m: Long,        // Range M = N * 2^P
        val data: ByteArray // Encoded GR bitstream
    )

    // Derive P from target FPR; FPR ~= 1 / 2^P
    fun deriveP(targetFpr: Double): Int {
        val f = targetFpr.coerceIn(0.000001, 0.25)
        return ceil(ln(1.0 / f) / ln(2.0)).toInt().coerceAtLeast(1)
    }

    // Rough capacity estimate: expected bits per element ~= P + 2 (quotient unary ~ around 2 bits)
    fun estimateMaxElementsForSize(bytes: Int, p: Int): Int {
        val bits = (bytes * 8).coerceAtLeast(8)
        val per = (p + 2).coerceAtLeast(3)
        return (bits / per).coerceAtLeast(1)
    }

    fun buildFilter(
        ids: List<ByteArray>, // 16-byte PacketId bytes
        maxBytes: Int,
        targetFpr: Double
    ): Params {
        val p = deriveP(targetFpr)
        var nCap = estimateMaxElementsForSize(maxBytes, p)
        val n = ids.size.coerceAtMost(nCap)
        val selected = ids.take(n)
        // Map to [0, M)
        val m = (n.toLong() shl p)
        val mapped = selected.map { id -> (h64(id) % m) }.sorted()
        var encoded = encode(mapped, p)
        // If estimate was too optimistic, trim until it fits
        var trimmedN = n
        while (encoded.size > maxBytes && trimmedN > 0) {
            trimmedN = (trimmedN * 9) / 10 // drop 10%
            val mapped2 = mapped.take(trimmedN)
            encoded = encode(mapped2, p)
        }
        val finalM = (trimmedN.toLong() shl p)
        return Params(p = p, m = finalM, data = encoded)
    }

    fun decodeToSortedSet(p: Int, m: Long, data: ByteArray): LongArray {
        val values = ArrayList<Long>()
        val reader = BitReader(data)
        var acc = 0L
        val mask = (1L shl p) - 1L
        while (!reader.eof()) {
            // Read unary quotient (q ones terminated by zero)
            var q = 0L
            while (true) {
                val b = reader.readBit() ?: break
                if (b == 1) q++ else break
            }
            if (reader.lastWasEOF) break
            // Read remainder
            val r = reader.readBits(p) ?: break
            val x = (q shl p) + r + 1
            acc += x
            if (acc >= m) break // out of range safeguard
            values.add(acc)
        }
        return values.toLongArray()
    }

    fun contains(sortedValues: LongArray, candidate: Long): Boolean {
        var lo = 0
        var hi = sortedValues.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val v = sortedValues[mid]
            if (v == candidate) return true
            if (v < candidate) lo = mid + 1 else hi = mid - 1
        }
        return false
    }

    private fun h64(id16: ByteArray): Long {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(id16)
        val d = md.digest()
        var x = 0L
        for (i in 0 until 8) {
            x = (x shl 8) or ((d[i].toLong() and 0xFF))
        }
        return x and 0x7fff_ffff_ffff_ffffL // positive
    }

    private fun encode(sorted: List<Long>, p: Int): ByteArray {
        val bw = BitWriter()
        var prev = 0L
        val mask = (1L shl p) - 1L
        for (v in sorted) {
            val delta = v - prev
            prev = v
            val x = delta
            val q = (x - 1) ushr p
            val r = (x - 1) and mask
            // unary q ones then a zero
            repeat(q.toInt()) { bw.writeBit(1) }
            bw.writeBit(0)
            // then P bits of r (MSB-first)
            bw.writeBits(r, p)
        }
        return bw.toByteArray()
    }

    // Simple MSB-first bit writer
    private class BitWriter {
        private val buf = ArrayList<Byte>()
        private var cur = 0
        private var nbits = 0
        fun writeBit(bit: Int) {
            cur = (cur shl 1) or (bit and 1)
            nbits++
            if (nbits == 8) {
                buf.add(cur.toByte())
                cur = 0; nbits = 0
            }
        }
        fun writeBits(value: Long, count: Int) {
            if (count <= 0) return
            for (i in count - 1 downTo 0) {
                val bit = ((value ushr i) and 1L).toInt()
                writeBit(bit)
            }
        }
        fun toByteArray(): ByteArray {
            if (nbits > 0) {
                val rem = cur shl (8 - nbits)
                buf.add(rem.toByte())
                cur = 0; nbits = 0
            }
            return buf.toByteArray()
        }
    }

    // Simple MSB-first bit reader
    private class BitReader(private val data: ByteArray) {
        private var i = 0
        private var nleft = 8
        private var cur = if (data.isNotEmpty()) (data[0].toInt() and 0xFF) else 0
        var lastWasEOF: Boolean = false
            private set
        fun eof() = i >= data.size
        fun readBit(): Int? {
            if (i >= data.size) { lastWasEOF = true; return null }
            val bit = (cur ushr 7) and 1
            cur = (cur shl 1) and 0xFF
            nleft--
            if (nleft == 0) {
                i++
                if (i < data.size) {
                    cur = data[i].toInt() and 0xFF
                    nleft = 8
                }
            }
            return bit
        }
        fun readBits(count: Int): Long? {
            var v = 0L
            for (k in 0 until count) {
                val b = readBit() ?: return null
                v = (v shl 1) or b.toLong()
            }
            return v
        }
    }
}

