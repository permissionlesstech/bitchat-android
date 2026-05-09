/*
 * Based on the public domain C reference code for New Hope.
 * This Java version is also placed into the public domain.
 * 
 * Original authors: Erdem Alkim, Léo Ducas, Thomas Pöppelmann, Peter Schwabe
 * Java port: Rhys Weatherley
 */
package com.bitchat.android.noise.southernstorm.crypto

import java.security.SecureRandom


/**
 * NewHope key exchange algorithm.
 * 
 * This class implements the standard "ref" version of the New Hope
 * algorithm.
 * 
 * @see NewHopeTor
 */
open class NewHope {
    // -------------- newhope.c --------------
    private var sk: Poly? = null

    /**
     * Destroys sensitive material in this object.
     */
    fun destroy() {
        sk?.destroy()
        sk = null
    }

    /**
     * Generates the keypair for Alice.
     */
    fun keygen(send: ByteArray, sendOffset: Int) {
        val a = Poly()
        val e = Poly()
        val r = Poly()
        val pk = Poly()
        val seed = ByteArray(SEEDBYTES + 32)
        val noiseseed = ByteArray(32)

        try {
            randombytes(seed)
            sha3256(seed, 0, seed, 0, SEEDBYTES) /* Don't send output of system RNG */
            seed.copyInto(noiseseed, 0, SEEDBYTES, SEEDBYTES + 32)

            uniform(a.coeffs, seed)

            val currentSk = sk ?: Poly().also { sk = it }
            currentSk.getnoise(noiseseed, 0.toByte())
            currentSk.ntt()

            e.getnoise(noiseseed, 1.toByte())
            e.ntt()

            r.pointwise(currentSk, a)
            pk.add(e, r)

            encode_a(send, sendOffset, pk, seed)
        } finally {
            a.destroy()
            e.destroy()
            r.destroy()
            pk.destroy()
            seed.fill(0)
            noiseseed.fill(0)
        }
    }

    /**
     * Generates the public key and shared secret for Bob.
     *
     * @param sharedkey Buffer to place the shared secret for Bob in.
     * @param sharedkeyOffset Offset of the first byte in the sharedkey buffer to populate.
     * @param send Buffer to place the public key for Bob in to be sent to Alice.
     * @param sendOffset Offset of the first byte in the send buffer to populate.
     * @param received Buffer containing the public key value received from Alice.
     * @param receivedOffset Offset of the first byte of the value received from Alice.
     */
    fun sharedb(
        sharedkey: ByteArray, sharedkeyOffset: Int,
        send: ByteArray, sendOffset: Int,
        received: ByteArray, receivedOffset: Int
    ) {
        val sp = Poly()
        val ep = Poly()
        val v = Poly()
        val a = Poly()
        val pka = Poly()
        val c = Poly()
        val epp = Poly()
        val bp = Poly()
        val seed = ByteArray(SEEDBYTES)
        val noiseseed = ByteArray(32)
        val skey = ByteArray(32)

        try {
            randombytes(noiseseed)

            decode_a(pka, seed, received, receivedOffset)
            uniform(a.coeffs, seed)

            sp.getnoise(noiseseed, 0.toByte())
            sp.ntt()
            ep.getnoise(noiseseed, 1.toByte())
            ep.ntt()

            bp.pointwise(a, sp)
            bp.add(bp, ep)

            v.pointwise(pka, sp)
            v.invntt()

            epp.getnoise(noiseseed, 2.toByte())
            v.add(v, epp)

            helprec(c, v, noiseseed, 3.toByte())

            encode_b(send, sendOffset, bp, c)

            rec(skey, v, c)

            sha3256(sharedkey, sharedkeyOffset, skey, 0, 32)
        } finally {
            sp.destroy()
            ep.destroy()
            v.destroy()
            a.destroy()
            pka.destroy()
            c.destroy()
            epp.destroy()
            bp.destroy()
            seed.fill(0)
            noiseseed.fill(0)
            skey.fill(0)
        }
    }

    /**
     * Generates the shared secret for Alice.
     */
    fun shareda(
        sharedkey: ByteArray, sharedkeyOffset: Int,
        received: ByteArray, receivedOffset: Int
    ) {
        val v = Poly()
        val bp = Poly()
        val c = Poly()
        val skey = ByteArray(32)

        try {
            decode_b(bp, c, received, receivedOffset)

            val currentSk = sk ?: throw IllegalStateException("Private key not initialized")
            v.pointwise(currentSk, bp)
            v.invntt()

            rec(skey, v, c)

            sha3256(sharedkey, sharedkeyOffset, skey, 0, 32)
        } finally {
            v.destroy()
            bp.destroy()
            c.destroy()
            skey.fill(0)
        }
    }

    protected open fun randombytes(buffer: ByteArray) {
        SecureRandom().nextBytes(buffer)
    }

    // -------------- poly.c --------------
    inner class Poly {
        val coeffs = CharArray(PARAM_N)

        fun destroy() {
            coeffs.fill(0.toChar())
        }

        fun frombytes(a: ByteArray, offset: Int) {
            for (i in 0 until PARAM_N / 4) {
                coeffs[4 * i + 0] = ((a[offset + 7 * i + 0].toInt() and 0xff) or ((a[offset + 7 * i + 1].toInt() and 0x3f) shl 8)).toChar()
                coeffs[4 * i + 1] = (((a[offset + 7 * i + 1].toInt() and 0xc0) shr 6) or ((a[offset + 7 * i + 2].toInt() and 0xff) shl 2) or ((a[offset + 7 * i + 3].toInt() and 0x0f) shl 10)).toChar()
                coeffs[4 * i + 2] = (((a[offset + 7 * i + 3].toInt() and 0xf0) shr 4) or ((a[offset + 7 * i + 4].toInt() and 0xff) shl 4) or ((a[offset + 7 * i + 5].toInt() and 0x03) shl 12)).toChar()
                coeffs[4 * i + 3] = (((a[offset + 7 * i + 5].toInt() and 0xfc) shr 2) or ((a[offset + 7 * i + 6].toInt() and 0xff) shl 6)).toChar()
            }
        }

        fun tobytes(r: ByteArray, offset: Int) {
            for (i in 0 until PARAM_N / 4) {
                var t0 = barrett_reduce(coeffs[4 * i + 0].code)
                var t1 = barrett_reduce(coeffs[4 * i + 1].code)
                var t2 = barrett_reduce(coeffs[4 * i + 2].code)
                var t3 = barrett_reduce(coeffs[4 * i + 3].code)

                var m = t0 - PARAM_Q
                var mask = m shr 15
                t0 = m xor ((t0 xor m) and mask)

                m = t1 - PARAM_Q
                mask = m shr 15
                t1 = m xor ((t1 xor m) and mask)

                m = t2 - PARAM_Q
                mask = m shr 15
                t2 = m xor ((t2 xor m) and mask)

                m = t3 - PARAM_Q
                mask = m shr 15
                t3 = m xor ((t3 xor m) and mask)

                r[offset + 7 * i + 0] = (t0 and 0xff).toByte()
                r[offset + 7 * i + 1] = ((t0 shr 8) or (t1 shl 6)).toByte()
                r[offset + 7 * i + 2] = (t1 shr 2).toByte()
                r[offset + 7 * i + 3] = ((t1 shr 10) or (t2 shl 4)).toByte()
                r[offset + 7 * i + 4] = (t2 shr 4).toByte()
                r[offset + 7 * i + 5] = ((t2 shr 12) or (t3 shl 2)).toByte()
                r[offset + 7 * i + 6] = (t3 shr 6).toByte()
            }
        }

        fun getnoise(seed: ByteArray, nonce: Byte) {
            val buf = ByteArray(4 * PARAM_N)
            try {
                crypto_stream_chacha20(buf, 0, 4 * PARAM_N, nonce.toLong(), seed)
                for (i in 0 until PARAM_N) {
                    var a = (buf[4 * i].toInt() and 0xff) or ((buf[4 * i + 1].toInt() and 0xff) shl 8)
                    a = a - ((a shr 1) and 0x5555)
                    a = (a and 0x3333) + ((a shr 2) and 0x3333)
                    a = ((a shr 4) + a) and 0x0F0F
                    a = ((a shr 8) + a) and 0x00FF

                    var b = (buf[4 * i + 2].toInt() and 0xff) or ((buf[4 * i + 3].toInt() and 0xff) shl 8)
                    b = b - ((b shr 1) and 0x5555)
                    b = (b and 0x3333) + ((b shr 2) and 0x3333)
                    b = ((b shr 4) + b) and 0x0F0F
                    b = ((b shr 8) + b) and 0x00FF

                    coeffs[i] = (a + PARAM_Q - b).toChar()
                }
            } finally {
                buf.fill(0)
            }
        }

        fun pointwise(a: Poly, b: Poly) {
            for (i in 0 until PARAM_N) {
                val t = montgomery_reduce(3186 * b.coeffs[i].code)
                coeffs[i] = montgomery_reduce(a.coeffs[i].code * t).toChar()
            }
        }

        fun add(a: Poly, b: Poly) {
            for (i in 0 until PARAM_N) {
                coeffs[i] = barrett_reduce(a.coeffs[i].code + b.coeffs[i].code).toChar()
            }
        }

        fun ntt() {
            mul_coefficients(coeffs, psis_bitrev_montgomery)
            ntt_global(coeffs, omegas_montgomery)
        }

        fun invntt() {
            bitrev_vector(coeffs)
            ntt_global(coeffs, omegas_inv_montgomery)
            mul_coefficients(coeffs, psis_inv_montgomery)
        }
    }

    /**
     * Derives the public "a" value from a 32-byte seed.
     */
    protected open fun uniform(coeffs: CharArray, seed: ByteArray) {
        var pos = 0
        var ctr = 0
        val state = LongArray(25)
        var nblocks = 14
        val buf = ByteArray(SHAKE128_RATE * nblocks)

        try {
            shake128_absorb(state, seed, 0, SEEDBYTES)
            shake128_squeezeblocks(buf, 0, nblocks, state)

            while (ctr < PARAM_N) {
                val value = (buf[pos].toInt() and 0xff) or ((buf[pos + 1].toInt() and 0xff) shl 8)
                if (value < 5 * PARAM_Q) coeffs[ctr++] = value.toChar()
                pos += 2
                if (pos > SHAKE128_RATE * nblocks - 2) {
                    nblocks = 1
                    shake128_squeezeblocks(buf, 0, nblocks, state)
                    pos = 0
                }
            }
        } finally {
            state.fill(0)
            buf.fill(0)
        }
    }

    companion object {
        // -------------- params.h --------------
        const val PARAM_N = 1024
        const val PARAM_Q = 12289
        const val POLY_BYTES = 1792
        const val SEEDBYTES = 32
        const val RECBYTES = 256
        const val SHAKE128_RATE = 168
        const val SHAREDBYTES = 32
        const val SENDABYTES = POLY_BYTES + SEEDBYTES
        const val SENDBBYTES = POLY_BYTES + RECBYTES

        private fun encode_a(r: ByteArray, roffset: Int, pk: Poly, seed: ByteArray) {
            pk.tobytes(r, roffset)
            seed.copyInto(r, POLY_BYTES + roffset, 0, SEEDBYTES)
        }

        private fun decode_a(pk: Poly, seed: ByteArray, r: ByteArray, roffset: Int) {
            pk.frombytes(r, roffset)
            r.copyInto(seed, 0, POLY_BYTES + roffset, POLY_BYTES + roffset + SEEDBYTES)
        }

        private fun encode_b(r: ByteArray, roffset: Int, b: Poly, c: Poly) {
            b.tobytes(r, roffset)
            for (i in 0 until PARAM_N / 4) {
                r[POLY_BYTES + roffset + i] = (c.coeffs[4 * i].code or (c.coeffs[4 * i + 1].code shl 2) or (c.coeffs[4 * i + 2].code shl 4) or (c.coeffs[4 * i + 3].code shl 6)).toByte()
            }
        }

        private fun decode_b(b: Poly, c: Poly, r: ByteArray, roffset: Int) {
            b.frombytes(r, roffset)
            for (i in 0 until PARAM_N / 4) {
                val v = r[POLY_BYTES + roffset + i].toInt()
                c.coeffs[4 * i + 0] = (v and 0x03).toChar()
                c.coeffs[4 * i + 1] = ((v shr 2) and 0x03).toChar()
                c.coeffs[4 * i + 2] = ((v shr 4) and 0x03).toChar()
                c.coeffs[4 * i + 3] = ((v shr 6) and 0x03).toChar()
            }
        }

        private fun montgomery_reduce(a: Int): Int {
            val u = (a * 12287) and ((1 shl 18) - 1)
            return (a + u * PARAM_Q) ushr 18
        }

        private fun barrett_reduce(a: Int): Int {
            val a16 = a and 0xffff
            val u = (a16 * 5) shr 16
            return (a16 - u * PARAM_Q) and 0xffff
        }

        // -------------- error_correction.c --------------
        private fun abs(v: Int): Int {
            val mask = v shr 31
            return (v xor mask) - mask
        }

        private fun f(v0: IntArray, v0offset: Int, v1: IntArray, v1offset: Int, x: Int): Int {
            var b = x * 2730
            var t = b shr 25
            b = x - t * 12289
            b = 12288 - b
            t -= (b shr 31)
            v0[v0offset] = (t shr 1) + (t and 1)
            t -= 1
            v1[v1offset] = (t shr 1) + (t and 1)
            return abs(x - (v0[v0offset] * 2 * PARAM_Q))
        }

        private fun g(x: Int): Int {
            var b = x * 2730
            var t = b shr 27
            b = x - t * 49156
            b = 49155 - b
            t -= (b shr 31)
            t = (t shr 1) + (t and 1)
            return abs(t * 8 * PARAM_Q - x)
        }

        private fun helprec(c: Poly, v: Poly, seed: ByteArray, nonce: Byte) {
            val v0 = IntArray(8)
            val rand = ByteArray(32)
            try {
                crypto_stream_chacha20(rand, 0, 32, nonce.toLong() shl 56, seed)
                for (i in 0 until 256) {
                    val rbit = (rand[i shr 3].toInt() shr (i and 7)) and 1
                    var k = f(v0, 0, v0, 4, 8 * v.coeffs[i].code + 4 * rbit)
                    k += f(v0, 1, v0, 5, 8 * v.coeffs[256 + i].code + 4 * rbit)
                    k += f(v0, 2, v0, 6, 8 * v.coeffs[512 + i].code + 4 * rbit)
                    k += f(v0, 3, v0, 7, 8 * v.coeffs[768 + i].code + 4 * rbit)
                    k = (2 * PARAM_Q - 1 - k) shr 31
                    val vt0 = (k.inv() and v0[0]) xor (k and v0[4])
                    val vt1 = (k.inv() and v0[1]) xor (k and v0[5])
                    val vt2 = (k.inv() and v0[2]) xor (k and v0[6])
                    val vt3 = (k.inv() and v0[3]) xor (k and v0[7])
                    c.coeffs[i] = ((vt0 - vt3) and 3).toChar()
                    c.coeffs[256 + i] = ((vt1 - vt3) and 3).toChar()
                    c.coeffs[512 + i] = ((vt2 - vt3) and 3).toChar()
                    c.coeffs[768 + i] = ((-k + 2 * vt3) and 3).toChar()
                }
            } finally {
                v0.fill(0); rand.fill(0)
            }
        }

        private fun rec(key: ByteArray, v: Poly, c: Poly) {
            key.fill(0)
            for (i in 0 until 256) {
                val c768 = c.coeffs[768 + i].code
                val t0 = 16 * PARAM_Q + 8 * v.coeffs[i].code - PARAM_Q * (2 * c.coeffs[i].code + c768)
                val t1 = 16 * PARAM_Q + 8 * v.coeffs[256 + i].code - PARAM_Q * (2 * c.coeffs[256 + i].code + c768)
                val t2 = 16 * PARAM_Q + 8 * v.coeffs[512 + i].code - PARAM_Q * (2 * c.coeffs[512 + i].code + c768)
                val t3 = 16 * PARAM_Q + 8 * v.coeffs[768 + i].code - PARAM_Q * c768
                key[i shr 3] = (key[i shr 3].toInt() or (LDDecode(t0, t1, t2, t3) shl (i and 7))).toByte()
            }
        }

        private fun LDDecode(xi0: Int, xi1: Int, xi2: Int, xi3: Int): Int {
            var t = g(xi0) + g(xi1) + g(xi2) + g(xi3)
            return ((t - 8 * PARAM_Q) shr 31) and 1
        }

        private fun bitrev_vector(poly: CharArray) {
            for (p in 0 until 496) {
                val indices = bitrev_table_combined[p]
                val i = indices and 0x03FF
                val r = indices shr 10
                val tmp = poly[i]
                poly[i] = poly[r]
                poly[r] = tmp
            }
        }

        private fun mul_coefficients(poly: CharArray, factors: CharArray) {
            for (i in 0 until PARAM_N) {
                poly[i] = montgomery_reduce(poly[i].code * factors[i].code).toChar()
            }
        }

        private fun ntt_global(a: CharArray, omega: CharArray) {
            for (i in 0 until 10 step 2) {
                var dist = 1 shl i
                repeat(dist) { start ->
                    var tw = 0
                    var j = start
                    while (j < PARAM_N - 1) {
                        val w = omega[tw++]; val tmp = a[j]
                        a[j] = (tmp.code + a[j + dist].code).toChar()
                        a[j + dist] = montgomery_reduce(w.code * (tmp.code + 3 * PARAM_Q - a[dist + j].code)).toChar()
                        j += 2 * dist
                    }
                }
                dist = dist shl 1
                repeat(dist) { start ->
                    var tw = 0
                    var j = start
                    while (j < PARAM_N - 1) {
                        val w = omega[tw++]; val tmp = a[j]
                        a[j] = barrett_reduce(tmp.code + a[j + dist].code).toChar()
                        a[j + dist] = montgomery_reduce(w.code * (tmp.code + 3 * PARAM_Q - a[dist + j].code)).toChar()
                        j += 2 * dist
                    }
                }
            }
        }

        private fun crypto_stream_chacha20(c: ByteArray, coffset: Int, clen: Int, n: Long, k: ByteArray) {
            if (clen <= 0) return
            var off = coffset; var len = clen; var blk = 0
            while (len >= 64) {
                crypto_core_chacha20(c, off, n, blk++, k)
                len -= 64; off += 64
            }
            if (len > 0) {
                val block = ByteArray(64)
                try {
                    crypto_core_chacha20(block, 0, n, blk, k)
                    block.copyInto(c, off, 0, len)
                } finally { block.fill(0) }
            }
        }

        private fun crypto_core_chacha20(out: ByteArray, outOffset: Int, nonce: Long, blknum: Int, k: ByteArray) {
            val state = IntArray(16)
            state[0] = 0x61707865; state[1] = 0x3320646e; state[2] = 0x79622d32; state[3] = 0x6b206574
            for (i in 0 until 8) state[4 + i] = (k[4 * i].toInt() and 0xff) or ((k[4 * i + 1].toInt() and 0xff) shl 8) or ((k[4 * i + 2].toInt() and 0xff) shl 16) or ((k[4 * i + 3].toInt() and 0xff) shl 24)
            state[12] = blknum; state[13] = 0; state[14] = nonce.toInt(); state[15] = (nonce ushr 32).toInt()
            val working = state.copyOf()
            for (i in 0 until 20 step 2) {
                qr(working, 0, 4, 8, 12); qr(working, 1, 5, 9, 13); qr(working, 2, 6, 10, 14); qr(working, 3, 7, 11, 15)
                qr(working, 0, 5, 10, 15); qr(working, 1, 6, 11, 12); qr(working, 2, 7, 8, 13); qr(working, 3, 4, 9, 14)
            }
            for (i in 0 until 16) {
                val v = working[i] + state[i]
                out[outOffset + 4 * i] = v.toByte()
                out[outOffset + 4 * i + 1] = (v shr 8).toByte()
                out[outOffset + 4 * i + 2] = (v shr 16).toByte()
                out[outOffset + 4 * i + 3] = (v shr 24).toByte()
            }
        }
        private fun qr(v: IntArray, a: Int, b: Int, c: Int, d: Int) {
            v[a] += v[b]; v[d] = rotl(v[d] xor v[a], 16)
            v[c] += v[d]; v[b] = rotl(v[b] xor v[c], 12)
            v[a] += v[b]; v[d] = rotl(v[d] xor v[a], 8)
            v[c] += v[d]; v[b] = rotl(v[b] xor v[c], 7)
        }
        private fun rotl(v: Int, n: Int) = (v shl n) or (v ushr (32 - n))

        fun shake128_absorb(s: LongArray, input: ByteArray, offset: Int, len: Int) {
            keccak_absorb(s, SHAKE128_RATE, input, offset, len, 0x1F.toByte())
        }

        fun shake128_squeezeblocks(output: ByteArray, offset: Int, nblocks: Int, s: LongArray) {
            keccak_squeezeblocks(output, offset, nblocks, s, SHAKE128_RATE)
        }

        private fun keccak_absorb(s: LongArray, r: Int, m: ByteArray, offset: Int, mlen: Int, p: Byte) {
            var off = offset; var len = mlen
            s.fill(0L)
            while (len >= r) {
                for (i in 0 until r / 8) s[i] = s[i] xor load64(m, off + 8 * i)
                KeccakF1600_StatePermute(s)
                len -= r; off += r
            }
            val t = ByteArray(200)
            try {
                for (i in 0 until len) t[i] = m[off + i]
                t[len] = p; t[r - 1] = (t[r - 1].toInt() or 128).toByte()
                for (i in 0 until r / 8) s[i] = s[i] xor load64(t, 8 * i)
            } finally { t.fill(0) }
        }

        private fun keccak_squeezeblocks(h: ByteArray, offset: Int, nblocks: Int, s: LongArray, r: Int) {
            var off = offset
            repeat(nblocks) {
                KeccakF1600_StatePermute(s)
                for (i in 0 until (r shr 3)) store64(h, off + 8 * i, s[i])
                off += r
            }
        }

        private fun load64(x: ByteArray, off: Int): Long {
            var r = 0L
            for (i in 0..7) r = r or ((x[off + i].toLong() and 0xffL) shl (8 * i))
            return r
        }

        private fun store64(x: ByteArray, off: Int, u: Long) {
            var v = u
            for (i in 0..7) { x[off + i] = v.toByte(); v = v shr 8 }
        }

        private fun KeccakF1600_StatePermute(s: LongArray) {
            // Minimal Keccak-f[1600] implementation
            repeat(24) { r ->
                val bc = LongArray(5)
                for (i in 0..4) bc[i] = s[i] xor s[i + 5] xor s[i + 10] xor s[i + 15] xor s[i + 20]
                for (i in 0..4) {
                    val t = bc[(i + 4) % 5] xor rol64(bc[(i + 1) % 5], 1)
                    for (j in 0..20 step 5) s[i + j] = s[i + j] xor t
                }
                var cur = s[1]; var last = 0L
                for (i in 0..23) {
                    val next = s[keccak_pi[i]]; s[keccak_pi[i]] = rol64(cur, keccak_rho[i])
                    cur = next
                }
                for (j in 0..20 step 5) {
                    for (i in 0..4) bc[i] = s[i + j]
                    for (i in 0..4) s[i + j] = bc[i] xor (bc[(i + 1) % 5].inv() and bc[(i + 2) % 5])
                }
                s[0] = s[0] xor KeccakF_RoundConstants[r]
            }
        }
        private fun rol64(a: Long, n: Int) = if (n == 0) a else (a shl n) or (a ushr (64 - n))
        private val keccak_rho = intArrayOf(1, 62, 28, 27, 36, 44, 6, 55, 20, 3, 10, 43, 25, 39, 41, 45, 15, 21, 8, 18, 2, 61, 56, 14)
        private val keccak_pi = intArrayOf(10, 7, 11, 17, 18, 3, 5, 16, 8, 21, 24, 4, 15, 23, 19, 13, 12, 2, 20, 14, 22, 9, 6, 1)
        private val KeccakF_RoundConstants = longArrayOf(
            0x0000000000000001L, 0x0000000000008082L, -0x7FFFFFFFFFFF7F76L, -0x7FFFFFFF7FFF8000L,
            0x000000000000808BL, 0x0000000080000001L, -0x7FFFFFFF7FFF7F7FL, -0x7FFFFFFFFFFF7FF7L,
            0x000000000000008AL, 0x0000000000000088L, 0x0000000080008009L, 0x000000008000000AL,
            0x000000008000808BL, -0x7FFFFFFFFFFFFF75L, -0x7FFFFFFFFFFF7F77L, -0x7FFFFFFFFFFF7FFDL,
            -0x7FFFFFFFFFFF7FFEL, -0x7FFFFFFFFFFFFF80L, 0x000000000000800AL, -0x7FFFFFFF7FFFFFF6L,
            -0x7FFFFFFF7FFF7F7FL, -0x7FFFFFFFFFFF7F80L, 0x0000000080000001L, -0x7FFFFFFF7FFF7FF8L
        )

        private fun sha3256(output: ByteArray, outOffset: Int, input: ByteArray, inOffset: Int, len: Int) {
            val s = LongArray(25)
            keccak_absorb(s, 136, input, inOffset, len, 0x06.toByte())
            val t = ByteArray(136)
            keccak_squeezeblocks(t, 0, 1, s, 136)
            t.copyInto(output, outOffset, 0, 32)
        }

        private val omegas_montgomery = charArrayOf(
            4075.toChar(), 6974.toChar(), 7373.toChar(), 7965.toChar(), 3262.toChar(), 5079.toChar(), 522.toChar(), 2169.toChar(), 6364.toChar(), 1018.toChar(), 1041.toChar(), 8775.toChar(), 2344.toChar(), 11011.toChar(), 5574.toChar(), 1973.toChar(),
            4536.toChar(), 1050.toChar(), 6844.toChar(), 3860.toChar(), 3818.toChar(), 6118.toChar(), 2683.toChar(), 1190.toChar(), 4789.toChar(), 7822.toChar(), 7540.toChar(), 6752.toChar(), 5456.toChar(), 4449.toChar(), 3789.toChar(), 12142.toChar(),
            11973.toChar(), 382.toChar(), 3988.toChar(), 468.toChar(), 6843.toChar(), 5339.toChar(), 6196.toChar(), 3710.toChar(), 11316.toChar(), 1254.toChar(), 5435.toChar(), 10930.toChar(), 3998.toChar(), 10256.toChar(), 10367.toChar(), 3879.toChar(),
            11889.toChar(), 1728.toChar(), 6137.toChar(), 4948.toChar(), 5862.toChar(), 6136.toChar(), 3643.toChar(), 6874.toChar(), 8724.toChar(), 654.toChar(), 10302.toChar(), 1702.toChar(), 7083.toChar(), 6760.toChar(), 56.toChar(), 3199.toChar(),
            9987.toChar(), 605.toChar(), 11785.toChar(), 8076.toChar(), 5594.toChar(), 9260.toChar(), 6403.toChar(), 4782.toChar(), 6212.toChar(), 4624.toChar(), 9026.toChar(), 8689.toChar(), 4080.toChar(), 11868.toChar(), 6221.toChar(), 3602.toChar(),
            975.toChar(), 8077.toChar(), 8851.toChar(), 9445.toChar(), 5681.toChar(), 3477.toChar(), 1105.toChar(), 142.toChar(), 241.toChar(), 12231.toChar(), 1003.toChar(), 3532.toChar(), 5009.toChar(), 1956.toChar(), 6008.toChar(), 11404.toChar(),
            7377.toChar(), 2049.toChar(), 10968.toChar(), 12097.toChar(), 7591.toChar(), 5057.toChar(), 3445.toChar(), 4780.toChar(), 2920.toChar(), 7048.toChar(), 3127.toChar(), 8120.toChar(), 11279.toChar(), 6821.toChar(), 11502.toChar(), 8807.toChar(),
            12138.toChar(), 2127.toChar(), 2839.toChar(), 3957.toChar(), 431.toChar(), 1579.toChar(), 6383.toChar(), 9784.toChar(), 5874.toChar(), 677.toChar(), 3336.toChar(), 6234.toChar(), 2766.toChar(), 1323.toChar(), 9115.toChar(), 12237.toChar(),
            2031.toChar(), 6956.toChar(), 6413.toChar(), 2281.toChar(), 3969.toChar(), 3991.toChar(), 12133.toChar(), 9522.toChar(), 4737.toChar(), 10996.toChar(), 4774.toChar(), 5429.toChar(), 11871.toChar(), 3772.toChar(), 453.toChar(), 5908.toChar(),
            2882.toChar(), 1805.toChar(), 2051.toChar(), 1954.toChar(), 11713.toChar(), 3963.toChar(), 2447.toChar(), 6142.toChar(), 8174.toChar(), 3030.toChar(), 1843.toChar(), 2361.toChar(), 12071.toChar(), 2908.toChar(), 3529.toChar(), 3434.toChar(),
            3202.toChar(), 7796.toChar(), 2057.toChar(), 5369.toChar(), 11939.toChar(), 1512.toChar(), 6906.toChar(), 10474.toChar(), 11026.toChar(), 49.toChar(), 10806.toChar(), 5915.toChar(), 1489.toChar(), 9789.toChar(), 5942.toChar(), 10706.toChar(),
            10431.toChar(), 7535.toChar(), 426.toChar(), 8974.toChar(), 3757.toChar(), 10314.toChar(), 9364.toChar(), 347.toChar(), 5868.toChar(), 9551.toChar(), 9634.toChar(), 6554.toChar(), 10596.toChar(), 9280.toChar(), 11566.toChar(), 174.toChar(),
            2948.toChar(), 2503.toChar(), 6507.toChar(), 10723.toChar(), 11606.toChar(), 2459.toChar(), 64.toChar(), 3656.toChar(), 8455.toChar(), 5257.toChar(), 5919.toChar(), 7856.toChar(), 1747.toChar(), 9166.toChar(), 5486.toChar(), 9235.toChar(),
            6065.toChar(), 835.toChar(), 3570.toChar(), 4240.toChar(), 11580.toChar(), 4046.toChar(), 10970.toChar(), 9139.toChar(), 1058.toChar(), 8210.toChar(), 11848.toChar(), 922.toChar(), 7967.toChar(), 1958.toChar(), 10211.toChar(), 1112.toChar(),
            3728.toChar(), 4049.toChar(), 11130.toChar(), 5990.toChar(), 1404.toChar(), 325.toChar(), 948.toChar(), 11143.toChar(), 6190.toChar(), 295.toChar(), 11637.toChar(), 5766.toChar(), 8212.toChar(), 8273.toChar(), 2919.toChar(), 8527.toChar(),
            6119.toChar(), 6992.toChar(), 8333.toChar(), 1360.toChar(), 2555.toChar(), 6167.toChar(), 1200.toChar(), 7105.toChar(), 7991.toChar(), 3329.toChar(), 9597.toChar(), 12121.toChar(), 5106.toChar(), 5961.toChar(), 10695.toChar(), 10327.toChar(),
            3051.toChar(), 9923.toChar(), 4896.toChar(), 9326.toChar(), 81.toChar(), 3091.toChar(), 1000.toChar(), 7969.toChar(), 4611.toChar(), 726.toChar(), 1853.toChar(), 12149.toChar(), 4255.toChar(), 11112.toChar(), 2768.toChar(), 10654.toChar(),
            1062.toChar(), 2294.toChar(), 3553.toChar(), 4805.toChar(), 2747.toChar(), 4846.toChar(), 8577.toChar(), 9154.toChar(), 1170.toChar(), 2319.toChar(), 790.toChar(), 11334.toChar(), 9275.toChar(), 9088.toChar(), 1326.toChar(), 5086.toChar(),
            9094.toChar(), 6429.toChar(), 11077.toChar(), 10643.toChar(), 3504.toChar(), 3542.toChar(), 8668.toChar(), 9744.toChar(), 1479.toChar(), 1.toChar(), 8246.toChar(), 7143.toChar(), 11567.toChar(), 10984.toChar(), 4134.toChar(), 5736.toChar(),
            4978.toChar(), 10938.toChar(), 5777.toChar(), 8961.toChar(), 4591.toChar(), 5728.toChar(), 6461.toChar(), 5023.toChar(), 9650.toChar(), 7468.toChar(), 949.toChar(), 9664.toChar(), 2975.toChar(), 11726.toChar(), 2744.toChar(), 9283.toChar(),
            10092.toChar(), 5067.toChar(), 12171.toChar(), 2476.toChar(), 3748.toChar(), 11336.toChar(), 6522.toChar(), 827.toChar(), 9452.toChar(), 5374.toChar(), 12159.toChar(), 7935.toChar(), 3296.toChar(), 3949.toChar(), 9893.toChar(), 4452.toChar(),
            10908.toChar(), 2525.toChar(), 3584.toChar(), 8112.toChar(), 8011.toChar(), 10616.toChar(), 4989.toChar(), 6958.toChar(), 11809.toChar(), 9447.toChar(), 12280.toChar(), 1022.toChar(), 11950.toChar(), 9821.toChar(), 11745.toChar(), 5791.toChar(),
            5092.toChar(), 2089.toChar(), 9005.toChar(), 2881.toChar(), 3289.toChar(), 2013.toChar(), 9048.toChar(), 729.toChar(), 7901.toChar(), 1260.toChar(), 5755.toChar(), 4632.toChar(), 11955.toChar(), 2426.toChar(), 10593.toChar(), 1428.toChar(),
            4890.toChar(), 5911.toChar(), 3932.toChar(), 9558.toChar(), 8830.toChar(), 3637.toChar(), 5542.toChar(), 145.toChar(), 5179.toChar(), 8595.toChar(), 3707.toChar(), 10530.toChar(), 355.toChar(), 3382.toChar(), 4231.toChar(), 9741.toChar(),
            1207.toChar(), 9041.toChar(), 7012.toChar(), 1168.toChar(), 10146.toChar(), 11224.toChar(), 4645.toChar(), 11885.toChar(), 10911.toChar(), 10377.toChar(), 435.toChar(), 7952.toChar(), 4096.toChar(), 493.toChar(), 9908.toChar(), 6845.toChar(),
            6039.toChar(), 2422.toChar(), 2187.toChar(), 9723.toChar(), 8643.toChar(), 9852.toChar(), 9302.toChar(), 6022.toChar(), 7278.toChar(), 1002.toChar(), 4284.toChar(), 5088.toChar(), 1607.toChar(), 7313.toChar(), 875.toChar(), 8509.toChar(),
            9430.toChar(), 1045.toChar(), 2481.toChar(), 5012.toChar(), 7428.toChar(), 354.toChar(), 6591.toChar(), 9377.toChar(), 11847.toChar(), 2401.toChar(), 1067.toChar(), 7188.toChar(), 11516.toChar(), 390.toChar(), 8511.toChar(), 8456.toChar(),
            7270.toChar(), 545.toChar(), 8585.toChar(), 9611.toChar(), 12047.toChar(), 1537.toChar(), 4143.toChar(), 4714.toChar(), 4885.toChar(), 1017.toChar(), 5084.toChar(), 1632.toChar(), 3066.toChar(), 27.toChar(), 1440.toChar(), 8526.toChar(),
            9273.toChar(), 12046.toChar(), 11618.toChar(), 9289.toChar(), 3400.toChar(), 9890.toChar(), 3136.toChar(), 7098.toChar(), 8758.toChar(), 11813.toChar(), 7384.toChar(), 3985.toChar(), 11869.toChar(), 6730.toChar(), 10745.toChar(), 10111.toChar(),
            2249.toChar(), 4048.toChar(), 2884.toChar(), 11136.toChar(), 2126.toChar(), 1630.toChar(), 9103.toChar(), 5407.toChar(), 2686.toChar(), 9042.toChar(), 2969.toChar(), 8311.toChar(), 9424.toChar(), 9919.toChar(), 8779.toChar(), 5332.toChar(),
            10626.toChar(), 1777.toChar(), 4654.toChar(), 10863.toChar(), 7351.toChar(), 3636.toChar(), 9585.toChar(), 5291.toChar(), 8374.toChar(), 2166.toChar(), 4919.toChar(), 12176.toChar(), 9140.toChar(), 12129.toChar(), 7852.toChar(), 12286.toChar(),
            4895.toChar(), 10805.toChar(), 2780.toChar(), 5195.toChar(), 2305.toChar(), 7247.toChar(), 9644.toChar(), 4053.toChar(), 10600.toChar(), 3364.toChar(), 3271.toChar(), 4057.toChar(), 4414.toChar(), 9442.toChar(), 7917.toChar(), 2174.toChar()
        )
        private val omegas_inv_montgomery = charArrayOf(
            4075.toChar(), 5315.toChar(), 4324.toChar(), 4916.toChar(), 10120.toChar(), 11767.toChar(), 7210.toChar(), 9027.toChar(), 10316.toChar(), 6715.toChar(), 1278.toChar(), 9945.toChar(), 3514.toChar(), 11248.toChar(), 11271.toChar(), 5925.toChar(),
            147.toChar(), 8500.toChar(), 7840.toChar(), 6833.toChar(), 5537.toChar(), 4749.toChar(), 4467.toChar(), 7500.toChar(), 11099.toChar(), 9606.toChar(), 6171.toChar(), 8471.toChar(), 8429.toChar(), 5445.toChar(), 11239.toChar(), 7753.toChar(),
            9090.toChar(), 12233.toChar(), 5529.toChar(), 5206.toChar(), 10587.toChar(), 1987.toChar(), 11635.toChar(), 3565.toChar(), 5415.toChar(), 8646.toChar(), 6153.toChar(), 6427.toChar(), 7341.toChar(), 6152.toChar(), 10561.toChar(), 400.toChar(),
            8410.toChar(), 1922.toChar(), 2033.toChar(), 8291.toChar(), 1359.toChar(), 6854.toChar(), 11035.toChar(), 973.toChar(), 8579.toChar(), 6093.toChar(), 6950.toChar(), 5446.toChar(), 11821.toChar(), 8301.toChar(), 11907.toChar(), 316.toChar(),
            52.toChar(), 3174.toChar(), 10966.toChar(), 9523.toChar(), 6055.toChar(), 8953.toChar(), 11612.toChar(), 6415.toChar(), 2505.toChar(), 5906.toChar(), 10710.toChar(), 11858.toChar(), 8332.toChar(), 9450.toChar(), 10162.toChar(), 151.toChar(),
            3482.toChar(), 787.toChar(), 5468.toChar(), 1010.toChar(), 4169.toChar(), 9162.toChar(), 5241.toChar(), 9369.toChar(), 7509.toChar(), 8844.toChar(), 7232.toChar(), 4698.toChar(), 192.toChar(), 1321.toChar(), 10240.toChar(), 4912.toChar(),
            885.toChar(), 6281.toChar(), 10333.toChar(), 7280.toChar(), 8757.toChar(), 11286.toChar(), 58.toChar(), 12048.toChar(), 12147.toChar(), 11184.toChar(), 8812.toChar(), 6608.toChar(), 2844.toChar(), 3438.toChar(), 4212.toChar(), 11314.toChar(),
            8687.toChar(), 6068.toChar(), 421.toChar(), 8209.toChar(), 3600.toChar(), 3263.toChar(), 7665.toChar(), 6077.toChar(), 7507.toChar(), 5886.toChar(), 3029.toChar(), 6695.toChar(), 4213.toChar(), 504.toChar(), 11684.toChar(), 2302.toChar(),
            1962.toChar(), 1594.toChar(), 6328.toChar(), 7183.toChar(), 168.toChar(), 2692.toChar(), 8960.toChar(), 4298.toChar(), 5184.toChar(), 11089.toChar(), 6122.toChar(), 9734.toChar(), 10929.toChar(), 3956.toChar(), 5297.toChar(), 6170.toChar(),
            3762.toChar(), 9370.toChar(), 4016.toChar(), 4077.toChar(), 6523.toChar(), 652.toChar(), 11994.toChar(), 6099.toChar(), 1146.toChar(), 11341.toChar(), 11964.toChar(), 10885.toChar(), 6299.toChar(), 1159.toChar(), 8240.toChar(), 8561.toChar(),
            11177.toChar(), 2078.toChar(), 10331.toChar(), 4322.toChar(), 11367.toChar(), 441.toChar(), 4079.toChar(), 11231.toChar(), 3150.toChar(), 1319.toChar(), 8243.toChar(), 709.toChar(), 8049.toChar(), 8719.toChar(), 11454.toChar(), 6224.toChar(),
            3054.toChar(), 6803.toChar(), 3123.toChar(), 10542.toChar(), 4433.toChar(), 6370.toChar(), 7032.toChar(), 3834.toChar(), 8633.toChar(), 12225.toChar(), 9830.toChar(), 683.toChar(), 1566.toChar(), 5782.toChar(), 9786.toChar(), 9341.toChar(),
            12115.toChar(), 723.toChar(), 3009.toChar(), 1693.toChar(), 5735.toChar(), 2655.toChar(), 2738.toChar(), 6421.toChar(), 11942.toChar(), 2925.toChar(), 1975.toChar(), 8532.toChar(), 3315.toChar(), 11863.toChar(), 4754.toChar(), 1858.toChar(),
            1583.toChar(), 6347.toChar(), 2500.toChar(), 10800.toChar(), 6374.toChar(), 1483.toChar(), 12240.toChar(), 1263.toChar(), 1815.toChar(), 5383.toChar(), 10777.toChar(), 350.toChar(), 6920.toChar(), 10232.toChar(), 4493.toChar(), 9087.toChar(),
            8855.toChar(), 8760.toChar(), 9381.toChar(), 218.toChar(), 9928.toChar(), 10446.toChar(), 9259.toChar(), 4115.toChar(), 6147.toChar(), 9842.toChar(), 8326.toChar(), 576.toChar(), 10335.toChar(), 10238.toChar(), 10484.toChar(), 9407.toChar(),
            6381.toChar(), 11836.toChar(), 8517.toChar(), 418.toChar(), 6860.toChar(), 7515.toChar(), 1293.toChar(), 7552.toChar(), 2767.toChar(), 156.toChar(), 8298.toChar(), 8320.toChar(), 10008.toChar(), 5876.toChar(), 5333.toChar(), 10258.toChar(),
            10115.toChar(), 4372.toChar(), 2847.toChar(), 7875.toChar(), 8232.toChar(), 9018.toChar(), 8925.toChar(), 1689.toChar(), 8236.toChar(), 2645.toChar(), 5042.toChar(), 9984.toChar(), 7094.toChar(), 9509.toChar(), 1484.toChar(), 7394.toChar(),
            3.toChar(), 4437.toChar(), 160.toChar(), 3149.toChar(), 113.toChar(), 7370.toChar(), 10123.toChar(), 3915.toChar(), 6998.toChar(), 2704.toChar(), 8653.toChar(), 4938.toChar(), 1426.toChar(), 7635.toChar(), 10512.toChar(), 1663.toChar(),
            6957.toChar(), 3510.toChar(), 2370.toChar(), 2865.toChar(), 3978.toChar(), 9320.toChar(), 3247.toChar(), 9603.toChar(), 6882.toChar(), 3186.toChar(), 10659.toChar(), 10163.toChar(), 1153.toChar(), 9405.toChar(), 8241.toChar(), 10040.toChar(),
            2178.toChar(), 1544.toChar(), 5559.toChar(), 420.toChar(), 8304.toChar(), 4905.toChar(), 476.toChar(), 3531.toChar(), 5191.toChar(), 9153.toChar(), 2399.toChar(), 8889.toChar(), 3000.toChar(), 671.toChar(), 243.toChar(), 3016.toChar(),
            3763.toChar(), 10849.toChar(), 12262.toChar(), 9223.toChar(), 10657.toChar(), 7205.toChar(), 11272.toChar(), 7404.toChar(), 7575.toChar(), 8146.toChar(), 10752.toChar(), 242.toChar(), 2678.toChar(), 3704.toChar(), 11744.toChar(), 5019.toChar(),
            3833.toChar(), 3778.toChar(), 11899.toChar(), 773.toChar(), 5101.toChar(), 11222.toChar(), 9888.toChar(), 442.toChar(), 2912.toChar(), 5698.toChar(), 11935.toChar(), 4861.toChar(), 7277.toChar(), 9808.toChar(), 11244.toChar(), 2859.toChar(),
            3780.toChar(), 11414.toChar(), 4976.toChar(), 10682.toChar(), 7201.toChar(), 8005.toChar(), 11287.toChar(), 5011.toChar(), 6267.toChar(), 2987.toChar(), 2437.toChar(), 3646.toChar(), 2566.toChar(), 10102.toChar(), 9867.toChar(), 6250.toChar(),
            5444.toChar(), 2381.toChar(), 11796.toChar(), 8193.toChar(), 4337.toChar(), 11854.toChar(), 1912.toChar(), 1378.toChar(), 404.toChar(), 7644.toChar(), 1065.toChar(), 2143.toChar(), 11121.toChar(), 5277.toChar(), 3248.toChar(), 11082.toChar(),
            2548.toChar(), 8058.toChar(), 8907.toChar(), 11934.toChar(), 1759.toChar(), 8582.toChar(), 3694.toChar(), 7110.toChar(), 12144.toChar(), 6747.toChar(), 8652.toChar(), 3459.toChar(), 2731.toChar(), 8357.toChar(), 6378.toChar(), 7399.toChar(),
            10861.toChar(), 1696.toChar(), 9863.toChar(), 334.toChar(), 7657.toChar(), 6534.toChar(), 11029.toChar(), 4388.toChar(), 11560.toChar(), 3241.toChar(), 10276.toChar(), 9000.toChar(), 9408.toChar(), 3284.toChar(), 10200.toChar(), 7197.toChar(),
            6498.toChar(), 544.toChar(), 2468.toChar(), 339.toChar(), 11267.toChar(), 9.toChar(), 2842.toChar(), 480.toChar(), 5331.toChar(), 7300.toChar(), 1673.toChar(), 4278.toChar(), 4177.toChar(), 8705.toChar(), 9764.toChar(), 1381.toChar(),
            7837.toChar(), 2396.toChar(), 8340.toChar(), 8993.toChar(), 4354.toChar(), 130.toChar(), 6915.toChar(), 2837.toChar(), 11462.toChar(), 5767.toChar(), 953.toChar(), 8541.toChar(), 9813.toChar(), 118.toChar(), 7222.toChar(), 2197.toChar(),
            3006.toChar(), 9545.toChar(), 563.toChar(), 9314.toChar(), 2625.toChar(), 11340.toChar(), 4821.toChar(), 2639.toChar(), 7266.toChar(), 5828.toChar(), 6561.toChar(), 7698.toChar(), 3328.toChar(), 6512.toChar(), 1351.toChar(), 7311.toChar(),
            6553.toChar(), 8155.toChar(), 1305.toChar(), 722.toChar(), 5146.toChar(), 4043.toChar(), 12288.toChar(), 10810.toChar(), 2545.toChar(), 3621.toChar(), 8747.toChar(), 8785.toChar(), 1646.toChar(), 1212.toChar(), 5860.toChar(), 3195.toChar(),
            7203.toChar(), 10963.toChar(), 3201.toChar(), 3014.toChar(), 955.toChar(), 11499.toChar(), 9970.toChar(), 11119.toChar(), 3135.toChar(), 3712.toChar(), 7443.toChar(), 9542.toChar(), 7484.toChar(), 8736.toChar(), 9995.toChar(), 11227.toChar(),
            1635.toChar(), 9521.toChar(), 1177.toChar(), 8034.toChar(), 140.toChar(), 10436.toChar(), 11563.toChar(), 7678.toChar(), 4320.toChar(), 11289.toChar(), 9198.toChar(), 12208.toChar(), 2963.toChar(), 7393.toChar(), 2366.toChar(), 9238.toChar()
        )
        private val psis_bitrev_montgomery = charArrayOf(
            4075.toChar(), 6974.toChar(), 7373.toChar(), 7965.toChar(), 3262.toChar(), 5079.toChar(), 522.toChar(), 2169.toChar(), 6364.toChar(), 1018.toChar(), 1041.toChar(), 8775.toChar(), 2344.toChar(), 11011.toChar(), 5574.toChar(), 1973.toChar(),
            4536.toChar(), 1050.toChar(), 6844.toChar(), 3860.toChar(), 3818.toChar(), 6118.toChar(), 2683.toChar(), 1190.toChar(), 4789.toChar(), 7822.toChar(), 7540.toChar(), 6752.toChar(), 5456.toChar(), 4449.toChar(), 3789.toChar(), 12142.toChar(),
            11973.toChar(), 382.toChar(), 3988.toChar(), 468.toChar(), 6843.toChar(), 5339.toChar(), 6196.toChar(), 3710.toChar(), 11316.toChar(), 1254.toChar(), 5435.toChar(), 10930.toChar(), 3998.toChar(), 10256.toChar(), 10367.toChar(), 3879.toChar(),
            11889.toChar(), 1728.toChar(), 6137.toChar(), 4948.toChar(), 5862.toChar(), 6136.toChar(), 3643.toChar(), 6874.toChar(), 8724.toChar(), 654.toChar(), 10302.toChar(), 1702.toChar(), 7083.toChar(), 6760.toChar(), 56.toChar(), 3199.toChar(),
            9987.toChar(), 605.toChar(), 11785.toChar(), 8076.toChar(), 5594.toChar(), 9260.toChar(), 6403.toChar(), 4782.toChar(), 6212.toChar(), 4624.toChar(), 9026.toChar(), 8689.toChar(), 4080.toChar(), 11868.toChar(), 6221.toChar(), 3602.toChar(),
            975.toChar(), 8077.toChar(), 8851.toChar(), 9445.toChar(), 5681.toChar(), 3477.toChar(), 1105.toChar(), 142.toChar(), 241.toChar(), 12231.toChar(), 1003.toChar(), 3532.toChar(), 5009.toChar(), 1956.toChar(), 6008.toChar(), 11404.toChar(),
            7377.toChar(), 2049.toChar(), 10968.toChar(), 12097.toChar(), 7591.toChar(), 5057.toChar(), 3445.toChar(), 4780.toChar(), 2920.toChar(), 7048.toChar(), 3127.toChar(), 8120.toChar(), 11279.toChar(), 6821.toChar(), 11502.toChar(), 8807.toChar(),
            12138.toChar(), 2127.toChar(), 2839.toChar(), 3957.toChar(), 431.toChar(), 1579.toChar(), 6383.toChar(), 9784.toChar(), 5874.toChar(), 677.toChar(), 3336.toChar(), 6234.toChar(), 2766.toChar(), 1323.toChar(), 9115.toChar(), 12237.toChar(),
            2031.toChar(), 6956.toChar(), 6413.toChar(), 2281.toChar(), 3969.toChar(), 3991.toChar(), 12133.toChar(), 9522.toChar(), 4737.toChar(), 10996.toChar(), 4774.toChar(), 5429.toChar(), 11871.toChar(), 3772.toChar(), 453.toChar(), 5908.toChar(),
            2882.toChar(), 1805.toChar(), 2051.toChar(), 1954.toChar(), 11713.toChar(), 3963.toChar(), 2447.toChar(), 6142.toChar(), 8174.toChar(), 3030.toChar(), 1843.toChar(), 2361.toChar(), 12071.toChar(), 2908.toChar(), 3529.toChar(), 3434.toChar(),
            3202.toChar(), 7796.toChar(), 2057.toChar(), 5369.toChar(), 11939.toChar(), 1512.toChar(), 6906.toChar(), 10474.toChar(), 11026.toChar(), 49.toChar(), 10806.toChar(), 5915.toChar(), 1489.toChar(), 9789.toChar(), 5942.toChar(), 10706.toChar(),
            10431.toChar(), 7535.toChar(), 426.toChar(), 8974.toChar(), 3757.toChar(), 10314.toChar(), 9364.toChar(), 347.toChar(), 5868.toChar(), 9551.toChar(), 9634.toChar(), 6554.toChar(), 10596.toChar(), 9280.toChar(), 11566.toChar(), 174.toChar(),
            2948.toChar(), 2503.toChar(), 6507.toChar(), 10723.toChar(), 11606.toChar(), 2459.toChar(), 64.toChar(), 3656.toChar(), 8455.toChar(), 5257.toChar(), 5919.toChar(), 7856.toChar(), 1747.toChar(), 9166.toChar(), 5486.toChar(), 9235.toChar(),
            6065.toChar(), 835.toChar(), 3570.toChar(), 4240.toChar(), 11580.toChar(), 4046.toChar(), 10970.toChar(), 9139.toChar(), 1058.toChar(), 8210.toChar(), 11848.toChar(), 922.toChar(), 7967.toChar(), 1958.toChar(), 10211.toChar(), 1112.toChar(),
            3728.toChar(), 4049.toChar(), 11130.toChar(), 5990.toChar(), 1404.toChar(), 325.toChar(), 948.toChar(), 11143.toChar(), 6190.toChar(), 295.toChar(), 11637.toChar(), 5766.toChar(), 8212.toChar(), 8273.toChar(), 2919.toChar(), 8527.toChar(),
            6119.toChar(), 6992.toChar(), 8333.toChar(), 1360.toChar(), 2555.toChar(), 6167.toChar(), 1200.toChar(), 7105.toChar(), 7991.toChar(), 3329.toChar(), 9597.toChar(), 12121.toChar(), 5106.toChar(), 5961.toChar(), 10695.toChar(), 10327.toChar(),
            3051.toChar(), 9923.toChar(), 4896.toChar(), 9326.toChar(), 81.toChar(), 3091.toChar(), 1000.toChar(), 7969.toChar(), 4611.toChar(), 726.toChar(), 1853.toChar(), 12149.toChar(), 4255.toChar(), 11112.toChar(), 2768.toChar(), 10654.toChar(),
            1062.toChar(), 2294.toChar(), 3553.toChar(), 4805.toChar(), 2747.toChar(), 4846.toChar(), 8577.toChar(), 9154.toChar(), 1170.toChar(), 2319.toChar(), 790.toChar(), 11334.toChar(), 9275.toChar(), 9088.toChar(), 1326.toChar(), 5086.toChar(),
            9094.toChar(), 6429.toChar(), 11077.toChar(), 10643.toChar(), 3504.toChar(), 3542.toChar(), 8668.toChar(), 9744.toChar(), 1479.toChar(), 1.toChar(), 8246.toChar(), 7143.toChar(), 11567.toChar(), 10984.toChar(), 4134.toChar(), 5736.toChar(),
            4978.toChar(), 10938.toChar(), 5777.toChar(), 8961.toChar(), 4591.toChar(), 5728.toChar(), 6461.toChar(), 5023.toChar(), 9650.toChar(), 7468.toChar(), 949.toChar(), 9664.toChar(), 2975.toChar(), 11726.toChar(), 2744.toChar(), 9283.toChar(),
            10092.toChar(), 5067.toChar(), 12171.toChar(), 2476.toChar(), 3748.toChar(), 11336.toChar(), 6522.toChar(), 827.toChar(), 9452.toChar(), 5374.toChar(), 12159.toChar(), 7935.toChar(), 3296.toChar(), 3949.toChar(), 9893.toChar(), 4452.toChar(),
            10908.toChar(), 2525.toChar(), 3584.toChar(), 8112.toChar(), 8011.toChar(), 10616.toChar(), 4989.toChar(), 6958.toChar(), 11809.toChar(), 9447.toChar(), 12280.toChar(), 1022.toChar(), 11950.toChar(), 9821.toChar(), 11745.toChar(), 5791.toChar(),
            5092.toChar(), 2089.toChar(), 9005.toChar(), 2881.toChar(), 3289.toChar(), 2013.toChar(), 9048.toChar(), 729.toChar(), 7901.toChar(), 1260.toChar(), 5755.toChar(), 4632.toChar(), 11955.toChar(), 2426.toChar(), 10593.toChar(), 1428.toChar(),
            4890.toChar(), 5911.toChar(), 3932.toChar(), 9558.toChar(), 8830.toChar(), 3637.toChar(), 5542.toChar(), 145.toChar(), 5179.toChar(), 8595.toChar(), 3707.toChar(), 10530.toChar(), 355.toChar(), 3382.toChar(), 4231.toChar(), 9741.toChar(),
            1207.toChar(), 9041.toChar(), 7012.toChar(), 1168.toChar(), 10146.toChar(), 11224.toChar(), 4645.toChar(), 11885.toChar(), 10911.toChar(), 10377.toChar(), 435.toChar(), 7952.toChar(), 4096.toChar(), 493.toChar(), 9908.toChar(), 6845.toChar(),
            6039.toChar(), 2422.toChar(), 2187.toChar(), 9723.toChar(), 8643.toChar(), 9852.toChar(), 9302.toChar(), 6022.toChar(), 7278.toChar(), 1002.toChar(), 4284.toChar(), 5088.toChar(), 1607.toChar(), 7313.toChar(), 875.toChar(), 8509.toChar(),
            9430.toChar(), 1045.toChar(), 2481.toChar(), 5012.toChar(), 7428.toChar(), 354.toChar(), 6591.toChar(), 9377.toChar(), 11847.toChar(), 2401.toChar(), 1067.toChar(), 7188.toChar(), 11516.toChar(), 390.toChar(), 8511.toChar(), 8456.toChar(),
            7270.toChar(), 545.toChar(), 8585.toChar(), 9611.toChar(), 12047.toChar(), 1537.toChar(), 4143.toChar(), 4714.toChar(), 4885.toChar(), 1017.toChar(), 5084.toChar(), 1632.toChar(), 3066.toChar(), 27.toChar(), 1440.toChar(), 8526.toChar(),
            9273.toChar(), 12046.toChar(), 11618.toChar(), 9289.toChar(), 3400.toChar(), 9890.toChar(), 3136.toChar(), 7098.toChar(), 8758.toChar(), 11813.toChar(), 7384.toChar(), 3985.toChar(), 11869.toChar(), 6730.toChar(), 10745.toChar(), 10111.toChar(),
            2249.toChar(), 4048.toChar(), 2884.toChar(), 11136.toChar(), 2126.toChar(), 1630.toChar(), 9103.toChar(), 5407.toChar(), 2686.toChar(), 9042.toChar(), 2969.toChar(), 8311.toChar(), 9424.toChar(), 9919.toChar(), 8779.toChar(), 5332.toChar(),
            10626.toChar(), 1777.toChar(), 4654.toChar(), 10863.toChar(), 7351.toChar(), 3636.toChar(), 9585.toChar(), 5291.toChar(), 8374.toChar(), 2166.toChar(), 4919.toChar(), 12176.toChar(), 9140.toChar(), 12129.toChar(), 7852.toChar(), 12286.toChar(),
            4895.toChar(), 10805.toChar(), 2780.toChar(), 5195.toChar(), 2305.toChar(), 7247.toChar(), 9644.toChar(), 4053.toChar(), 10600.toChar(), 3364.toChar(), 3271.toChar(), 4057.toChar(), 4414.toChar(), 9442.toChar(), 7917.toChar(), 2174.toChar(),
            3947.toChar(), 11951.toChar(), 2455.toChar(), 6599.toChar(), 10545.toChar(), 10975.toChar(), 3654.toChar(), 2894.toChar(), 7681.toChar(), 7126.toChar(), 7287.toChar(), 12269.toChar(), 4119.toChar(), 3343.toChar(), 2151.toChar(), 1522.toChar(),
            7174.toChar(), 7350.toChar(), 11041.toChar(), 2442.toChar(), 2148.toChar(), 5959.toChar(), 6492.toChar(), 8330.toChar(), 8945.toChar(), 5598.toChar(), 3624.toChar(), 10397.toChar(), 1325.toChar(), 6565.toChar(), 1945.toChar(), 11260.toChar(),
            10077.toChar(), 2674.toChar(), 3338.toChar(), 3276.toChar(), 11034.toChar(), 506.toChar(), 6505.toChar(), 1392.toChar(), 5478.toChar(), 8778.toChar(), 1178.toChar(), 2776.toChar(), 3408.toChar(), 10347.toChar(), 11124.toChar(), 2575.toChar(),
            9489.toChar(), 12096.toChar(), 6092.toChar(), 10058.toChar(), 4167.toChar(), 6085.toChar(), 923.toChar(), 11251.toChar(), 11912.toChar(), 4578.toChar(), 10669.toChar(), 11914.toChar(), 425.toChar(), 10453.toChar(), 392.toChar(), 10104.toChar(),
            8464.toChar(), 4235.toChar(), 8761.toChar(), 7376.toChar(), 2291.toChar(), 3375.toChar(), 7954.toChar(), 8896.toChar(), 6617.toChar(), 7790.toChar(), 1737.toChar(), 11667.toChar(), 3982.toChar(), 9342.toChar(), 6680.toChar(), 636.toChar(),
            6825.toChar(), 7383.toChar(), 512.toChar(), 4670.toChar(), 2900.toChar(), 12050.toChar(), 7735.toChar(), 994.toChar(), 1687.toChar(), 11883.toChar(), 7021.toChar(), 146.toChar(), 10485.toChar(), 1403.toChar(), 5189.toChar(), 6094.toChar(),
            2483.toChar(), 2054.toChar(), 3042.toChar(), 10945.toChar(), 3981.toChar(), 10821.toChar(), 11826.toChar(), 8882.toChar(), 8151.toChar(), 180.toChar(), 9600.toChar(), 7684.toChar(), 5219.toChar(), 10880.toChar(), 6780.toChar(), 204.toChar(),
            11232.toChar(), 2600.toChar(), 7584.toChar(), 3121.toChar(), 3017.toChar(), 11053.toChar(), 7814.toChar(), 7043.toChar(), 4251.toChar(), 4739.toChar(), 11063.toChar(), 6771.toChar(), 7073.toChar(), 9261.toChar(), 2360.toChar(), 11925.toChar(),
            1928.toChar(), 11825.toChar(), 8024.toChar(), 3678.toChar(), 3205.toChar(), 3359.toChar(), 11197.toChar(), 5209.toChar(), 8581.toChar(), 3238.toChar(), 8840.toChar(), 1136.toChar(), 9363.toChar(), 1826.toChar(), 3171.toChar(), 4489.toChar(),
            7885.toChar(), 346.toChar(), 2068.toChar(), 1389.toChar(), 8257.toChar(), 3163.toChar(), 4840.toChar(), 6127.toChar(), 8062.toChar(), 8921.toChar(), 612.toChar(), 4238.toChar(), 10763.toChar(), 8067.toChar(), 125.toChar(), 11749.toChar(),
            10125.toChar(), 5416.toChar(), 2110.toChar(), 716.toChar(), 9839.toChar(), 10584.toChar(), 11475.toChar(), 11873.toChar(), 3448.toChar(), 343.toChar(), 1908.toChar(), 4538.toChar(), 10423.toChar(), 7078.toChar(), 4727.toChar(), 1208.toChar(),
            11572.toChar(), 3589.toChar(), 2982.toChar(), 1373.toChar(), 1721.toChar(), 10753.toChar(), 4103.toChar(), 2429.toChar(), 4209.toChar(), 5412.toChar(), 5993.toChar(), 9011.toChar(), 438.toChar(), 3515.toChar(), 7228.toChar(), 1218.toChar(),
            8347.toChar(), 5232.toChar(), 8682.toChar(), 1327.toChar(), 7508.toChar(), 4924.toChar(), 448.toChar(), 1014.toChar(), 10029.toChar(), 12221.toChar(), 4566.toChar(), 5836.toChar(), 12229.toChar(), 2717.toChar(), 1535.toChar(), 3200.toChar(),
            5588.toChar(), 5845.toChar(), 412.toChar(), 5102.toChar(), 7326.toChar(), 3744.toChar(), 3056.toChar(), 2528.toChar(), 7406.toChar(), 8314.toChar(), 9202.toChar(), 6454.toChar(), 6613.toChar(), 1417.toChar(), 10032.toChar(), 7784.toChar(),
            1518.toChar(), 3765.toChar(), 4176.toChar(), 5063.toChar(), 9828.toChar(), 2275.toChar(), 6636.toChar(), 4267.toChar(), 6463.toChar(), 2065.toChar(), 7725.toChar(), 3495.toChar(), 8328.toChar(), 8755.toChar(), 8144.toChar(), 10533.toChar(),
            5966.toChar(), 12077.toChar(), 9175.toChar(), 9520.toChar(), 5596.toChar(), 6302.toChar(), 8400.toChar(), 579.toChar(), 6781.toChar(), 11014.toChar(), 5734.toChar(), 11113.toChar(), 11164.toChar(), 4860.toChar(), 1131.toChar(), 10844.toChar(),
            9068.toChar(), 8016.toChar(), 9694.toChar(), 3837.toChar(), 567.toChar(), 9348.toChar(), 7000.toChar(), 6627.toChar(), 7699.toChar(), 5082.toChar(), 682.toChar(), 11309.toChar(), 5207.toChar(), 4050.toChar(), 7087.toChar(), 844.toChar(),
            7434.toChar(), 3769.toChar(), 293.toChar(), 9057.toChar(), 6940.toChar(), 9344.toChar(), 10883.toChar(), 2633.toChar(), 8190.toChar(), 3944.toChar(), 5530.toChar(), 5604.toChar(), 3480.toChar(), 2171.toChar(), 9282.toChar(), 11024.toChar(),
            2213.toChar(), 8136.toChar(), 3805.toChar(), 767.toChar(), 12239.toChar(), 216.toChar(), 11520.toChar(), 6763.toChar(), 10353.toChar(), 7.toChar(), 8566.toChar(), 845.toChar(), 7235.toChar(), 3154.toChar(), 4360.toChar(), 3285.toChar(),
            10268.toChar(), 2832.toChar(), 3572.toChar(), 1282.toChar(), 7559.toChar(), 3229.toChar(), 8360.toChar(), 10583.toChar(), 6105.toChar(), 3120.toChar(), 6643.toChar(), 6203.toChar(), 8536.toChar(), 8348.toChar(), 6919.toChar(), 3536.toChar(),
            9199.toChar(), 10891.toChar(), 11463.toChar(), 5043.toChar(), 1658.toChar(), 5618.toChar(), 8787.toChar(), 5789.toChar(), 4719.toChar(), 751.toChar(), 11379.toChar(), 6389.toChar(), 10783.toChar(), 3065.toChar(), 7806.toChar(), 6586.toChar(),
            2622.toChar(), 5386.toChar(), 510.toChar(), 7628.toChar(), 6921.toChar(), 578.toChar(), 10345.toChar(), 11839.toChar(), 8929.toChar(), 4684.toChar(), 12226.toChar(), 7154.toChar(), 9916.toChar(), 7302.toChar(), 8481.toChar(), 3670.toChar(),
            11066.toChar(), 2334.toChar(), 1590.toChar(), 7878.toChar(), 10734.toChar(), 1802.toChar(), 1891.toChar(), 5103.toChar(), 6151.toChar(), 8820.toChar(), 3418.toChar(), 7846.toChar(), 9951.toChar(), 4693.toChar(), 417.toChar(), 9996.toChar(),
            9652.toChar(), 4510.toChar(), 2946.toChar(), 5461.toChar(), 365.toChar(), 881.toChar(), 1927.toChar(), 1015.toChar(), 11675.toChar(), 11009.toChar(), 1371.toChar(), 12265.toChar(), 2485.toChar(), 11385.toChar(), 5039.toChar(), 6742.toChar(),
            8449.toChar(), 1842.toChar(), 12217.toChar(), 8176.toChar(), 9577.toChar(), 4834.toChar(), 7937.toChar(), 9461.toChar(), 2643.toChar(), 11194.toChar(), 3045.toChar(), 6508.toChar(), 4094.toChar(), 3451.toChar(), 7911.toChar(), 11048.toChar(),
            5406.toChar(), 4665.toChar(), 3020.toChar(), 6616.toChar(), 11345.toChar(), 7519.toChar(), 3669.toChar(), 5287.toChar(), 1790.toChar(), 7014.toChar(), 5410.toChar(), 11038.toChar(), 11249.toChar(), 2035.toChar(), 6125.toChar(), 10407.toChar(),
            4565.toChar(), 7315.toChar(), 5078.toChar(), 10506.toChar(), 2840.toChar(), 2478.toChar(), 9270.toChar(), 4194.toChar(), 9195.toChar(), 4518.toChar(), 7469.toChar(), 1160.toChar(), 6878.toChar(), 2730.toChar(), 10421.toChar(), 10036.toChar(),
            1734.toChar(), 3815.toChar(), 10939.toChar(), 5832.toChar(), 10595.toChar(), 10759.toChar(), 4423.toChar(), 8420.toChar(), 9617.toChar(), 7119.toChar(), 11010.toChar(), 11424.toChar(), 9173.toChar(), 189.toChar(), 10080.toChar(), 10526.toChar(),
            3466.toChar(), 10588.toChar(), 7592.toChar(), 3578.toChar(), 11511.toChar(), 7785.toChar(), 9663.toChar(), 530.toChar(), 12150.toChar(), 8957.toChar(), 2532.toChar(), 3317.toChar(), 9349.toChar(), 10243.toChar(), 1481.toChar(), 9332.toChar(),
            3454.toChar(), 3758.toChar(), 7899.toChar(), 4218.toChar(), 2593.toChar(), 11410.toChar(), 2276.toChar(), 982.toChar(), 6513.toChar(), 1849.toChar(), 8494.toChar(), 9021.toChar(), 4523.toChar(), 7988.toChar(), 8.toChar(), 457.toChar(),
            648.toChar(), 150.toChar(), 8000.toChar(), 2307.toChar(), 2301.toChar(), 874.toChar(), 5650.toChar(), 170.toChar(), 9462.toChar(), 2873.toChar(), 9855.toChar(), 11498.toChar(), 2535.toChar(), 11169.toChar(), 5808.toChar(), 12268.toChar(),
            9687.toChar(), 1901.toChar(), 7171.toChar(), 11787.toChar(), 3846.toChar(), 1573.toChar(), 6063.toChar(), 3793.toChar(), 466.toChar(), 11259.toChar(), 10608.toChar(), 3821.toChar(), 6320.toChar(), 4649.toChar(), 6263.toChar(), 2929.toChar()
        )
        private val psis_inv_montgomery = charArrayOf(
            256.toChar(), 10570.toChar(), 1510.toChar(), 7238.toChar(), 1034.toChar(), 7170.toChar(), 6291.toChar(), 7921.toChar(), 11665.toChar(), 3422.toChar(), 4000.toChar(), 2327.toChar(), 2088.toChar(), 5565.toChar(), 795.toChar(), 10647.toChar(),
            1521.toChar(), 5484.toChar(), 2539.toChar(), 7385.toChar(), 1055.toChar(), 7173.toChar(), 8047.toChar(), 11683.toChar(), 1669.toChar(), 1994.toChar(), 3796.toChar(), 5809.toChar(), 4341.toChar(), 9398.toChar(), 11876.toChar(), 12230.toChar(),
            10525.toChar(), 12037.toChar(), 12253.toChar(), 3506.toChar(), 4012.toChar(), 9351.toChar(), 4847.toChar(), 2448.toChar(), 7372.toChar(), 9831.toChar(), 3160.toChar(), 2207.toChar(), 5582.toChar(), 2553.toChar(), 7387.toChar(), 6322.toChar(),
            9681.toChar(), 1383.toChar(), 10731.toChar(), 1533.toChar(), 219.toChar(), 5298.toChar(), 4268.toChar(), 7632.toChar(), 6357.toChar(), 9686.toChar(), 8406.toChar(), 4712.toChar(), 9451.toChar(), 10128.toChar(), 4958.toChar(), 5975.toChar(),
            11387.toChar(), 8649.toChar(), 11769.toChar(), 6948.toChar(), 11526.toChar(), 12180.toChar(), 1740.toChar(), 10782.toChar(), 6807.toChar(), 2728.toChar(), 7412.toChar(), 4570.toChar(), 4164.toChar(), 4106.toChar(), 11120.toChar(), 12122.toChar(),
            8754.toChar(), 11784.toChar(), 3439.toChar(), 5758.toChar(), 11356.toChar(), 6889.toChar(), 9762.toChar(), 11928.toChar(), 1704.toChar(), 1999.toChar(), 10819.toChar(), 12079.toChar(), 12259.toChar(), 7018.toChar(), 11536.toChar(), 1648.toChar(),
            1991.toChar(), 2040.toChar(), 2047.toChar(), 2048.toChar(), 10826.toChar(), 12080.toChar(), 8748.toChar(), 8272.toChar(), 8204.toChar(), 1172.toChar(), 1923.toChar(), 7297.toChar(), 2798.toChar(), 7422.toChar(), 6327.toChar(), 4415.toChar(),
            7653.toChar(), 6360.toChar(), 11442.toChar(), 12168.toChar(), 7005.toChar(), 8023.toChar(), 9924.toChar(), 8440.toChar(), 8228.toChar(), 2931.toChar(), 7441.toChar(), 1063.toChar(), 3663.toChar(), 5790.toChar(), 9605.toChar(), 10150.toChar(),
            1450.toChar(), 8985.toChar(), 11817.toChar(), 10466.toChar(), 10273.toChar(), 12001.toChar(), 3470.toChar(), 7518.toChar(), 1074.toChar(), 1909.toChar(), 7295.toChar(), 9820.toChar(), 4914.toChar(), 702.toChar(), 5367.toChar(), 7789.toChar(),
            8135.toChar(), 9940.toChar(), 1420.toChar(), 3714.toChar(), 11064.toChar(), 12114.toChar(), 12264.toChar(), 1752.toChar(), 5517.toChar(), 9566.toChar(), 11900.toChar(), 1700.toChar(), 3754.toChar(), 5803.toChar(), 829.toChar(), 1874.toChar(),
            7290.toChar(), 2797.toChar(), 10933.toChar(), 5073.toChar(), 7747.toChar(), 8129.toChar(), 6428.toChar(), 6185.toChar(), 11417.toChar(), 1631.toChar(), 233.toChar(), 5300.toChar(), 9535.toChar(), 10140.toChar(), 11982.toChar(), 8734.toChar(),
            8270.toChar(), 2937.toChar(), 10953.toChar(), 8587.toChar(), 8249.toChar(), 2934.toChar(), 9197.toChar(), 4825.toChar(), 5956.toChar(), 4362.toChar(), 9401.toChar(), 1343.toChar(), 3703.toChar(), 529.toChar(), 10609.toChar(), 12049.toChar(),
            6988.toChar(), 6265.toChar(), 895.toChar(), 3639.toChar(), 4031.toChar(), 4087.toChar(), 4095.toChar(), 585.toChar(), 10617.toChar(), 8539.toChar(), 4731.toChar(), 4187.toChar(), 9376.toChar(), 3095.toChar(), 9220.toChar(), 10095.toChar(),
            10220.toChar(), 1460.toChar(), 10742.toChar(), 12068.toChar(), 1724.toChar(), 5513.toChar(), 11321.toChar(), 6884.toChar(), 2739.toChar(), 5658.toChar(), 6075.toChar(), 4379.toChar(), 11159.toChar(), 10372.toChar(), 8504.toChar(), 4726.toChar(),
            9453.toChar(), 3106.toChar(), 7466.toChar(), 11600.toChar(), 10435.toChar(), 8513.toChar(), 9994.toChar(), 8450.toChar(), 9985.toChar(), 3182.toChar(), 10988.toChar(), 8592.toChar(), 2983.toChar(), 9204.toChar(), 4826.toChar(), 2445.toChar(),
            5616.toChar(), 6069.toChar(), 867.toChar(), 3635.toChar(), 5786.toChar(), 11360.toChar(), 5134.toChar(), 2489.toChar(), 10889.toChar(), 12089.toChar(), 1727.toChar(), 7269.toChar(), 2794.toChar(), 9177.toChar(), 1311.toChar(), 5454.toChar(),
            9557.toChar(), 6632.toChar(), 2703.toChar(), 9164.toChar(), 10087.toChar(), 1441.toChar(), 3717.toChar(), 531.toChar(), 3587.toChar(), 2268.toChar(), 324.toChar(), 5313.toChar(), 759.toChar(), 1864.toChar(), 5533.toChar(), 2546.toChar(),
            7386.toChar(), 9833.toChar(), 8427.toChar(), 4715.toChar(), 11207.toChar(), 1601.toChar(), 7251.toChar(), 4547.toChar(), 11183.toChar(), 12131.toChar(), 1733.toChar(), 10781.toChar(), 10318.toChar(), 1474.toChar(), 10744.toChar(), 5046.toChar(),
            4232.toChar(), 11138.toChar(), 10369.toChar(), 6748.toChar(), 964.toChar(), 7160.toChar(), 4534.toChar(), 7670.toChar(), 8118.toChar(), 8182.toChar(), 4680.toChar(), 11202.toChar(), 6867.toChar(), 981.toChar(), 8918.toChar(), 1274.toChar(),
            182.toChar(), 26.toChar(), 7026.toChar(), 8026.toChar(), 11680.toChar(), 12202.toChar(), 10521.toChar(), 1503.toChar(), 7237.toChar(), 4545.toChar(), 5916.toChar(), 9623.toChar(), 8397.toChar(), 11733.toChar(), 10454.toChar(), 3249.toChar(),
            9242.toChar(), 6587.toChar(), 941.toChar(), 1890.toChar(), 270.toChar(), 10572.toChar(), 6777.toChar(), 9746.toChar(), 6659.toChar(), 6218.toChar(), 6155.toChar(), 6146.toChar(), 878.toChar(), 1881.toChar(), 7291.toChar(), 11575.toChar(),
            12187.toChar(), 1741.toChar(), 7271.toChar(), 8061.toChar(), 11685.toChar(), 6936.toChar(), 4502.toChar(), 9421.toChar(), 4857.toChar(), 4205.toChar(), 7623.toChar(), 1089.toChar(), 10689.toChar(), 1527.toChar(), 8996.toChar(), 10063.toChar(),
            11971.toChar(), 10488.toChar(), 6765.toChar(), 2722.toChar(), 3900.toChar(), 9335.toChar(), 11867.toChar(), 6962.toChar(), 11528.toChar(), 5158.toChar(), 4248.toChar(), 4118.toChar(), 5855.toChar(), 2592.toChar(), 5637.toChar(), 6072.toChar(),
            2623.toChar(), 7397.toChar(), 8079.toChar(), 9932.toChar(), 4930.toChar(), 5971.toChar(), 853.toChar(), 3633.toChar(), 519.toChar(), 8852.toChar(), 11798.toChar(), 3441.toChar(), 11025.toChar(), 1575.toChar(), 225.toChar(), 8810.toChar(),
            11792.toChar(), 12218.toChar(), 3501.toChar(), 9278.toChar(), 3081.toChar(), 9218.toChar(), 4828.toChar(), 7712.toChar(), 8124.toChar(), 11694.toChar(), 12204.toChar(), 3499.toChar(), 4011.toChar(), 573.toChar(), 3593.toChar(), 5780.toChar(),
            7848.toChar(), 9899.toChar(), 10192.toChar(), 1456.toChar(), 208.toChar(), 7052.toChar(), 2763.toChar(), 7417.toChar(), 11593.toChar(), 10434.toChar(), 12024.toChar(), 8740.toChar(), 11782.toChar(), 10461.toChar(), 3250.toChar(), 5731.toChar(),
            7841.toChar(), 9898.toChar(), 1414.toChar(), 202.toChar(), 3540.toChar(), 7528.toChar(), 2831.toChar(), 2160.toChar(), 10842.toChar(), 5060.toChar(), 4234.toChar(), 4116.toChar(), 588.toChar(), 84.toChar(), 12.toChar(), 7024.toChar(),
            2759.toChar(), 9172.toChar(), 6577.toChar(), 11473.toChar(), 1639.toChar(), 9012.toChar(), 3043.toChar(), 7457.toChar(), 6332.toChar(), 11438.toChar(), 1634.toChar(), 1989.toChar(), 9062.toChar(), 11828.toChar(), 8712.toChar(), 11778.toChar(),
            12216.toChar(), 10523.toChar(), 6770.toChar(), 9745.toChar(), 10170.toChar(), 4964.toChar(), 9487.toChar(), 6622.toChar(), 946.toChar(), 8913.toChar(), 6540.toChar(), 6201.toChar(), 4397.toChar(), 9406.toChar(), 8366.toChar(), 9973.toChar(),
            8447.toChar(), 8229.toChar(), 11709.toChar(), 8695.toChar(), 10020.toChar(), 3187.toChar(), 5722.toChar(), 2573.toChar(), 10901.toChar(), 6824.toChar(), 4486.toChar(), 4152.toChar(), 9371.toChar(), 8361.toChar(), 2950.toChar(), 2177.toChar(),
            311.toChar(), 1800.toChar(), 9035.toChar(), 8313.toChar(), 11721.toChar(), 3430.toChar(), 490.toChar(), 70.toChar(), 10.toChar(), 1757.toChar(), 251.toChar(), 3547.toChar(), 7529.toChar(), 11609.toChar(), 3414.toChar(), 7510.toChar(),
            4584.toChar(), 4166.toChar(), 9373.toChar(), 1339.toChar(), 5458.toChar(), 7802.toChar(), 11648.toChar(), 1664.toChar(), 7260.toChar(), 9815.toChar(), 10180.toChar(), 6721.toChar(), 9738.toChar(), 10169.toChar(), 8475.toChar(), 8233.toChar(),
            9954.toChar(), 1422.toChar(), 8981.toChar(), 1283.toChar(), 5450.toChar(), 11312.toChar(), 1616.toChar(), 3742.toChar(), 11068.toChar(), 10359.toChar(), 4991.toChar(), 713.toChar(), 3613.toChar(), 9294.toChar(), 8350.toChar(), 4704.toChar(),
            672.toChar(), 96.toChar(), 7036.toChar(), 9783.toChar(), 11931.toChar(), 3460.toChar(), 5761.toChar(), 823.toChar(), 10651.toChar(), 12055.toChar(), 10500.toChar(), 1500.toChar(), 5481.toChar(), 783.toChar(), 3623.toChar(), 11051.toChar(),
            8601.toChar(), 8251.toChar(), 8201.toChar(), 11705.toChar(), 10450.toChar(), 5004.toChar(), 4226.toChar(), 7626.toChar(), 2845.toChar(), 2162.toChar(), 3820.toChar(), 7568.toChar(), 9859.toChar(), 3164.toChar(), 452.toChar(), 10598.toChar(),
            1514.toChar(), 5483.toChar(), 6050.toChar(), 6131.toChar(), 4387.toChar(), 7649.toChar(), 8115.toChar(), 6426.toChar(), 918.toChar(), 8909.toChar(), 8295.toChar(), 1185.toChar(), 5436.toChar(), 11310.toChar(), 8638.toChar(), 1234.toChar(),
            5443.toChar(), 11311.toChar(), 5127.toChar(), 2488.toChar(), 2111.toChar(), 10835.toChar(), 5059.toChar(), 7745.toChar(), 2862.toChar(), 3920.toChar(), 560.toChar(), 80.toChar(), 1767.toChar(), 2008.toChar(), 3798.toChar(), 11076.toChar(),
            6849.toChar(), 2734.toChar(), 10924.toChar(), 12094.toChar(), 8750.toChar(), 1250.toChar(), 10712.toChar(), 6797.toChar(), 971.toChar(), 7161.toChar(), 1023.toChar(), 8924.toChar(), 4786.toChar(), 7706.toChar(), 4612.toChar(), 4170.toChar(),
            7618.toChar(), 6355.toChar(), 4419.toChar(), 5898.toChar(), 11376.toChar(), 10403.toChar(), 10264.toChar(), 6733.toChar(), 4473.toChar(), 639.toChar(), 5358.toChar(), 2521.toChar(), 9138.toChar(), 3061.toChar(), 5704.toChar(), 4326.toChar(),
            618.toChar(), 5355.toChar(), 765.toChar(), 5376.toChar(), 768.toChar(), 7132.toChar(), 4530.toChar(), 9425.toChar(), 3102.toChar(), 9221.toChar(), 6584.toChar(), 11474.toChar(), 10417.toChar(), 10266.toChar(), 12000.toChar(), 6981.toChar(),
            6264.toChar(), 4406.toChar(), 2385.toChar(), 7363.toChar(), 4563.toChar(), 4163.toChar(), 7617.toChar(), 9866.toChar(), 3165.toChar(), 9230.toChar(), 11852.toChar(), 10471.toChar(), 5007.toChar(), 5982.toChar(), 11388.toChar(), 5138.toChar(),
            734.toChar(), 3616.toChar(), 11050.toChar(), 12112.toChar(), 6997.toChar(), 11533.toChar(), 12181.toChar(), 10518.toChar(), 12036.toChar(), 3475.toChar(), 2252.toChar(), 7344.toChar(), 9827.toChar(), 4915.toChar(), 9480.toChar(), 6621.toChar(),
            4457.toChar(), 7659.toChar(), 9872.toChar(), 6677.toChar(), 4465.toChar(), 4149.toChar(), 7615.toChar(), 4599.toChar(), 657.toChar(), 3605.toChar(), 515.toChar(), 10607.toChar(), 6782.toChar(), 4480.toChar(), 640.toChar(), 1847.toChar(),
            3775.toChar(), 5806.toChar(), 2585.toChar(), 5636.toChar(), 9583.toChar(), 1369.toChar(), 10729.toChar(), 8555.toChar(), 10000.toChar(), 11962.toChar(), 5220.toChar(), 7768.toChar(), 8132.toChar(), 8184.toChar(), 9947.toChar(), 1421.toChar(),
            203.toChar(), 29.toChar(), 8782.toChar(), 11788.toChar(), 1684.toChar(), 10774.toChar(), 10317.toChar(), 4985.toChar(), 9490.toChar(), 8378.toChar(), 4708.toChar(), 11206.toChar(), 5112.toChar(), 5997.toChar(), 7879.toChar(), 11659.toChar(),
            12199.toChar(), 8765.toChar(), 10030.toChar(), 4944.toChar(), 5973.toChar(), 6120.toChar(), 6141.toChar(), 6144.toChar(), 7900.toChar(), 11662.toChar(), 1666.toChar(), 238.toChar(), 34.toChar(), 3516.toChar(), 5769.toChar(), 9602.toChar(),
            8394.toChar(), 9977.toChar(), 6692.toChar(), 956.toChar(), 10670.toChar(), 6791.toChar(), 9748.toChar(), 11926.toChar(), 8726.toChar(), 11780.toChar(), 5194.toChar(), 742.toChar(), 106.toChar(), 8793.toChar(), 10034.toChar(), 3189.toChar(),
            10989.toChar(), 5081.toChar(), 4237.toChar(), 5872.toChar(), 4350.toChar(), 2377.toChar(), 10873.toChar(), 6820.toChar(), 6241.toChar(), 11425.toChar(), 10410.toChar(), 10265.toChar(), 3222.toChar(), 5727.toChar(), 9596.toChar(), 4882.toChar(),
            2453.toChar(), 2106.toChar(), 3812.toChar(), 11078.toChar(), 12116.toChar(), 5242.toChar(), 4260.toChar(), 11142.toChar(), 8614.toChar(), 11764.toChar(), 12214.toChar(), 5256.toChar(), 4262.toChar(), 4120.toChar(), 11122.toChar(), 5100.toChar(),
            11262.toChar(), 5120.toChar(), 2487.toChar(), 5622.toChar(), 9581.toChar(), 8391.toChar(), 8221.toChar(), 2930.toChar(), 10952.toChar(), 12098.toChar(), 6995.toChar(), 6266.toChar(), 9673.toChar(), 4893.toChar(), 699.toChar(), 3611.toChar(),
            4027.toChar(), 5842.toChar(), 11368.toChar(), 1624.toChar(), 232.toChar(), 8811.toChar(), 8281.toChar(), 1183.toChar(), 169.toChar(), 8802.toChar(), 3013.toChar(), 2186.toChar(), 5579.toChar(), 797.toChar(), 3625.toChar(), 4029.toChar(),
            11109.toChar(), 1587.toChar(), 7249.toChar(), 11569.toChar(), 8675.toChar(), 6506.toChar(), 2685.toChar(), 10917.toChar(), 12093.toChar(), 12261.toChar(), 12285.toChar(), 1755.toChar(), 7273.toChar(), 1039.toChar(), 1904.toChar(), 272.toChar(),
            3550.toChar(), 9285.toChar(), 3082.toChar(), 5707.toChar(), 6082.toChar(), 4380.toChar(), 7648.toChar(), 11626.toChar(), 5172.toChar(), 4250.toChar(), 9385.toChar(), 8363.toChar(), 8217.toChar(), 4685.toChar(), 5936.toChar(), 848.toChar(),
            8899.toChar(), 6538.toChar(), 934.toChar(), 1889.toChar(), 3781.toChar(), 9318.toChar(), 10109.toChar(), 10222.toChar(), 6727.toChar(), 961.toChar(), 5404.toChar(), 772.toChar(), 5377.toChar(), 9546.toChar(), 8386.toChar(), 1198.toChar(),
            8949.toChar(), 3034.toChar(), 2189.toChar(), 7335.toChar(), 4559.toChar(), 5918.toChar(), 2601.toChar(), 10905.toChar(), 5069.toChar(), 9502.toChar(), 3113.toChar(), 7467.toChar(), 8089.toChar(), 11689.toChar(), 5181.toChar(), 9518.toChar(),
            8382.toChar(), 2953.toChar(), 3933.toChar(), 4073.toChar(), 4093.toChar(), 7607.toChar(), 8109.toChar(), 2914.toChar(), 5683.toChar(), 4323.toChar(), 11151.toChar(), 1593.toChar(), 10761.toChar(), 6804.toChar(), 972.toChar(), 3650.toChar(),
            2277.toChar(), 5592.toChar(), 4310.toChar(), 7638.toChar(), 9869.toChar(), 4921.toChar(), 703.toChar(), 1856.toChar(), 9043.toChar(), 4803.toChar(), 9464.toChar(), 1352.toChar(), 8971.toChar(), 11815.toChar(), 5199.toChar(), 7765.toChar(),
            6376.toChar(), 4422.toChar(), 7654.toChar(), 2849.toChar(), 407.toChar(), 8836.toChar(), 6529.toChar(), 7955.toChar(), 2892.toChar(), 9191.toChar(), 1313.toChar(), 10721.toChar(), 12065.toChar(), 12257.toChar(), 1751.toChar(), 9028.toChar(),
            8312.toChar(), 2943.toChar(), 2176.toChar(), 3822.toChar(), 546.toChar(), 78.toChar(), 8789.toChar(), 11789.toChar(), 10462.toChar(), 12028.toChar(), 6985.toChar(), 4509.toChar(), 9422.toChar(), 1346.toChar(), 5459.toChar(), 4291.toChar(),
            613.toChar(), 10621.toChar(), 6784.toChar(), 9747.toChar(), 3148.toChar(), 7472.toChar(), 2823.toChar(), 5670.toChar(), 810.toChar(), 7138.toChar(), 8042.toChar(), 4660.toChar(), 7688.toChar(), 6365.toChar(), 6176.toChar(), 6149.toChar(),
            2634.toChar(), 5643.toChar(), 9584.toChar(), 10147.toChar(), 11983.toChar(), 5223.toChar(), 9524.toChar(), 11894.toChar(), 10477.toChar(), 8519.toChar(), 1217.toChar(), 3685.toChar(), 2282.toChar(), 326.toChar(), 10580.toChar(), 3267.toChar(),
            7489.toChar(), 4581.toChar(), 2410.toChar(), 5611.toChar(), 11335.toChar(), 6886.toChar(), 8006.toChar(), 8166.toChar(), 11700.toChar(), 3427.toChar(), 11023.toChar(), 8597.toChar(), 10006.toChar(), 3185.toChar(), 455.toChar(), 65.toChar(),
            5276.toChar(), 7776.toChar(), 4622.toChar(), 5927.toChar(), 7869.toChar(), 9902.toChar(), 11948.toChar(), 5218.toChar(), 2501.toChar(), 5624.toChar(), 2559.toChar(), 10899.toChar(), 1557.toChar(), 1978.toChar(), 10816.toChar(), 10323.toChar(),
            8497.toChar(), 4725.toChar(), 675.toChar(), 1852.toChar(), 10798.toChar(), 12076.toChar(), 10503.toChar(), 3256.toChar(), 9243.toChar(), 3076.toChar(), 2195.toChar(), 10847.toChar(), 12083.toChar(), 10504.toChar(), 12034.toChar(), 10497.toChar()
        )
        private val bitrev_table_combined = intArrayOf(
            524289, 262146, 786435, 131076, 655365, 393222, 917511, 65544, 589833, 327690, 851979, 196620, 720909, 458766, 983055, 32784,
            557073, 294930, 819219, 163860, 688149, 426006, 950295, 98328, 622617, 360474, 884763, 229404, 753693, 491550, 1015839, 540705,
            278562, 802851, 147492, 671781, 409638, 933927, 81960, 606249, 344106, 868395, 213036, 737325, 475182, 999471, 573489, 311346,
            835635, 180276, 704565, 442422, 966711, 114744, 639033, 376890, 901179, 245820, 770109, 507966, 1032255, 532545, 270402, 794691,
            139332, 663621, 401478, 925767, 598089, 335946, 860235, 204876, 729165, 467022, 991311, 565329, 303186, 827475, 172116, 696405,
            434262, 958551, 106584, 630873, 368730, 893019, 237660, 761949, 499806, 1024095, 548961, 286818, 811107, 155748, 680037, 417894,
            942183, 614505, 352362, 876651, 221292, 745581, 483438, 1007727, 581745, 319602, 843891, 188532, 712821, 450678, 974967, 647289,
            385146, 909435, 254076, 778365, 516222, 1040511, 528513, 266370, 790659, 659589, 397446, 921735, 594057, 331914, 856203, 200844,
            725133, 462990, 987279, 561297, 299154, 823443, 168084, 692373, 430230, 954519, 626841, 364698, 888987, 233628, 757917, 495774,
            1020063, 544929, 282786, 807075, 676005, 413862, 938151, 610473, 348330, 872619, 217260, 741549, 479406, 1003695, 577713, 315570,
            839859, 708789, 446646, 970935, 643257, 381114, 905403, 250044, 774333, 512190, 1036479, 536769, 274626, 798915, 667845, 405702,
            929991, 602313, 340170, 864459, 733389, 471246, 995535, 569553, 307410, 831699, 700629, 438486, 962775, 635097, 372954, 897243,
            241884, 766173, 504030, 1028319, 553185, 291042, 815331, 684261, 422118, 946407, 618729, 356586, 880875, 749805, 487662, 1011951,
            585969, 323826, 848115, 717045, 454902, 979191, 651513, 389370, 913659, 782589, 520446, 1044735, 526593, 788739, 657669, 395526,
            919815, 592137, 329994, 854283, 723213, 461070, 985359, 559377, 297234, 821523, 690453, 428310, 952599, 624921, 362778, 887067,
            755997, 493854, 1018143, 543009, 805155, 674085, 411942, 936231, 608553, 346410, 870699, 739629, 477486, 1001775, 575793, 837939,
            706869, 444726, 969015, 641337, 379194, 903483, 772413, 510270, 1034559, 534849, 796995, 665925, 403782, 928071, 600393, 862539,
            731469, 469326, 993615, 567633, 829779, 698709, 436566, 960855, 633177, 371034, 895323, 764253, 502110, 1026399, 551265, 813411,
            682341, 420198, 944487, 616809, 878955, 747885, 485742, 1010031, 584049, 846195, 715125, 452982, 977271, 649593, 911739, 780669,
            518526, 1042815, 530817, 792963, 661893, 924039, 596361, 858507, 727437, 465294, 989583, 563601, 825747, 694677, 432534, 956823,
            629145, 891291, 760221, 498078, 1022367, 547233, 809379, 678309, 940455, 612777, 874923, 743853, 481710, 1005999, 580017, 842163,
            711093, 973239, 645561, 907707, 776637, 514494, 1038783, 539073, 801219, 670149, 932295, 604617, 866763, 735693, 997839, 571857,
            834003, 702933, 965079, 637401, 899547, 768477, 506334, 1030623, 555489, 817635, 686565, 948711, 621033, 883179, 752109, 1014255,
            588273, 850419, 719349, 981495, 653817, 915963, 784893, 1047039, 787971, 656901, 919047, 591369, 853515, 722445, 984591, 558609,
            820755, 689685, 951831, 624153, 886299, 755229, 1017375, 804387, 673317, 935463, 607785, 869931, 738861, 1001007, 837171, 706101,
            968247, 640569, 902715, 771645, 1033791, 796227, 665157, 927303, 861771, 730701, 992847, 829011, 697941, 960087, 632409, 894555,
            763485, 1025631, 812643, 681573, 943719, 878187, 747117, 1009263, 845427, 714357, 976503, 910971, 779901, 1042047, 792195, 923271,
            857739, 726669, 988815, 824979, 693909, 956055, 890523, 759453, 1021599, 808611, 939687, 874155, 743085, 1005231, 841395, 972471,
            906939, 775869, 1038015, 800451, 931527, 865995, 997071, 833235, 964311, 898779, 767709, 1029855, 816867, 947943, 882411, 1013487,
            849651, 980727, 915195, 1046271, 921351, 855819, 986895, 823059, 954135, 888603, 1019679, 937767, 872235, 1003311, 970551, 905019,
            1036095, 929607, 995151, 962391, 896859, 1027935, 946023, 1011567, 978807, 1044351, 991119, 958359, 1023903, 1007535, 1040319, 1032159
        )
    }
}
