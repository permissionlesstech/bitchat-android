/*
 * Copyright (C) 2016 Southern Storm Software, Pty Ltd.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a
 * copy of this software and associated documentation files (the "Software"),
 * to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included
 * in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS
 * OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 * FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 * DEALINGS IN THE SOFTWARE.
 */
package com.bitchat.android.noise.southernstorm.crypto

import com.bitchat.android.noise.southernstorm.protocol.Destroyable
import java.security.DigestException
import java.security.MessageDigest

/**
 * Fallback implementation of SHA512.
 * 
 * Note: This implementation is limited to a maximum 2^56 - 1 bytes of input.
 * That is, we don't bother trying to implement 128-bit length values.
 */
class SHA512MessageDigest : MessageDigest("SHA-512"), Destroyable {
    private val h = LongArray(8)
    private val block = ByteArray(128)
    private val w = LongArray(80)
    private var length: Long = 0
    private var posn = 0

    init {
        engineReset()
    }

    override fun destroy() {
        h.fill(0L)
        block.fill(0.toByte())
        w.fill(0L)
    }


    override fun engineDigest(): ByteArray {
        val digest = ByteArray(64)
        try {
            engineDigest(digest, 0, 64)
        } catch (e: DigestException) {
            // Shouldn't happen, but just in case.
            digest.fill(0.toByte())
        }
        return digest
    }

    @Throws(DigestException::class)
    override fun engineDigest(buf: ByteArray, offset: Int, len: Int): Int {
        if (len < 64) throw DigestException("Invalid digest length for SHA512")
        if (posn <= (128 - 17)) {
            block[posn] = 0x80.toByte()
            block.fill(0.toByte(), posn + 1, 128 - 8)
        } else {
            block[posn] = 0x80.toByte()
            block.fill(0.toByte(), posn + 1, 128)
            transform(block, 0)
            block.fill(0.toByte(), 0, 128 - 8)
        }
        writeBE64(block, 128 - 8, length)
        transform(block, 0)
        posn = 0
        for (index in 0..7) writeBE64(buf, offset + index * 8, h[index])
        return 64
    }

    override fun engineGetDigestLength(): Int = 64


    override fun engineReset() {
        h[0] = 0x6a09e667f3bcc908L
        h[1] = -0x4498517a7b3558c5L
        h[2] = 0x3c6ef372fe94f82bL
        h[3] = -0x5ab00ac5a0e2c90fL
        h[4] = 0x510e527fade682d1L
        h[5] = -0x64fa9773d4c193e1L
        h[6] = 0x1f83d9abfb41bd6bL
        h[7] = 0x5be0cd19137e2179L
        length = 0
        posn = 0
    }

    override fun engineUpdate(input: Byte) {
        block[posn++] = input
        length += 8
        if (posn >= 128) {
            transform(block, 0)
            posn = 0
        }
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        var offset = offset
        var len = len
        while (len > 0) {
            if (posn == 0 && len >= 128) {
                transform(input, offset)
                offset += 128
                len -= 128
                length += (128 * 8).toLong()
            } else {
                var temp = 128 - posn
                if (temp > len) temp = len
                System.arraycopy(input, offset, block, posn, temp)
                posn += temp
                length += (temp * 8).toLong()
                if (posn >= 128) {
                    transform(block, 0)
                    posn = 0
                }
                offset += temp
                len -= temp
            }
        }
    }

    private fun transform(m: ByteArray, offset: Int) {
        var currentOffset = offset

        // Initialize working variables to the current hash value.
        var a = this.h[0]
        var b = this.h[1]
        var c = this.h[2]
        var d = this.h[3]
        var e = this.h[4]
        var f = this.h[5]
        var g = this.h[6]
        var h = this.h[7]

        for (index in 0 until 16) {
            w[index] = ((m[currentOffset].toLong() and 0xFFL) shl 56) or
                    ((m[currentOffset + 1].toLong() and 0xFFL) shl 48) or
                    ((m[currentOffset + 2].toLong() and 0xFFL) shl 40) or
                    ((m[currentOffset + 3].toLong() and 0xFFL) shl 32) or
                    ((m[currentOffset + 4].toLong() and 0xFFL) shl 24) or
                    ((m[currentOffset + 5].toLong() and 0xFFL) shl 16) or
                    ((m[currentOffset + 6].toLong() and 0xFFL) shl 8) or
                    (m[currentOffset + 7].toLong() and 0xFFL)
            currentOffset += 8
        }


        // Extend the first 16 words to 80.
        for (index in 16 until 80) {
            w[index] = w[index - 16] + w[index - 7] +
                    (rightRotate(w[index - 15], 1) xor
                            rightRotate(w[index - 15], 8) xor
                            (w[index - 15] ushr 7)) +
                    (rightRotate(w[index - 2], 19) xor
                            rightRotate(w[index - 2], 61) xor
                            (w[index - 2] ushr 6))
        }

        // Compression function main loop.
        for (index in 0 until 80) {
            val temp1 = h + k[index] + w[index] +
                    (rightRotate(e, 14) xor rightRotate(e, 18) xor rightRotate(e, 41)) +
                    ((e and f) xor (e.inv() and g))
            val temp2 = (rightRotate(a, 28) xor rightRotate(a, 34) xor rightRotate(a, 39)) +
                    ((a and b) xor (a and c) xor (b and c))
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        // Add the compressed chunk to the current hash value.
        this.h[0] += a
        this.h[1] += b
        this.h[2] += c
        this.h[3] += d
        this.h[4] += e
        this.h[5] += f
        this.h[6] += g
        this.h[7] += h
    }

    companion object {
        private fun writeBE64(buf: ByteArray, offset: Int, value: Long) {
            buf[offset] = (value shr 56).toByte()
            buf[offset + 1] = (value shr 48).toByte()
            buf[offset + 2] = (value shr 40).toByte()
            buf[offset + 3] = (value shr 32).toByte()
            buf[offset + 4] = (value shr 24).toByte()
            buf[offset + 5] = (value shr 16).toByte()
            buf[offset + 6] = (value shr 8).toByte()
            buf[offset + 7] = value.toByte()
        }

        private val k = longArrayOf(
            0x428A2F98D728AE22L, 0x7137449123EF65CDL, -0x4a3f043013b2c4d1L,
            -0x164a245a7e762444L, 0x3956C25BF348B538L, 0x59F111F1B605D019L,
            -0x6dc07d5b50e6b065L, -0x54e3a12a25927ee8L, -0x27f855675cfcfdbeL,
            0x12835B0145706FBEL, 0x243185BE4EE4B28CL, 0x550C7DC3D5FFB4E2L,
            0x72BE5D74F27B896FL, -0x7f214e01c4e9694fL, -0x6423f958da38edcbL,
            -0x3e640e8b3096d96cL, -0x1b64963e610eb52eL, -0x1041b879c7b0da1dL,
            0x0FC19DC68B8CD5B5L, 0x240CA1CC77AC9C65L, 0x2DE92C6F592B0275L,
            0x4A7484AA6EA6E483L, 0x5CB0A9DCBD41FBD4L, 0x76F988DA831153B5L,
            -0x67c1aead11992055L, -0x57ce3992d24bcdf0L, -0x4ffcd8376704dec1L,
            -0x40a680384110f11cL, -0x391ff40cc257703eL, -0x2a586eb86cf558dbL,
            0x06CA6351E003826FL, 0x142929670A0E6E70L, 0x27B70A8546D22FFCL,
            0x2E1B21385C26C926L, 0x4D2C6DFC5AC42AEDL, 0x53380D139D95B3DFL,
            0x650A73548BAF63DEL, 0x766A0ABB3C77B2A8L, -0x7e3d36d1b812511aL,
            -0x6d8dd37aeb7dcac5L, -0x5d40175eb30efc9cL, -0x57e599b443bdcfffL,
            -0x3db4748f2f07686fL, -0x3893ae5cf9ab41d0L, -0x2e6d17e62910ade8L,
            -0x2966f9dbaa9a56f0L, -0xbf1ca7aa88edfd6L, 0x106AA07032BBD1B8L,
            0x19A4C116B8D2D0C8L, 0x1E376C085141AB53L, 0x2748774CDF8EEB99L,
            0x34B0BCB5E19B48A8L, 0x391C0CB3C5C95A63L, 0x4ED8AA4AE3418ACBL,
            0x5B9CCA4F7763E373L, 0x682E6FF3D6B2B8A3L, 0x748F82EE5DEFB2FCL,
            0x78A5636F43172F60L, -0x7b3787eb5e0f548eL, -0x7338fdf7e59bc614L,
            -0x6f410005dc9ce1d8L, -0x5baf9314217d4217L, -0x41065c084d3986ebL,
            -0x398e870d1c8dacd5L, -0x35d8c13115d99e64L, -0x2e794738de3f3df9L,
            -0x15258229321f14e2L, -0xa82b08011912e88L, 0x06F067AA72176FBAL,
            0x0A637DC5A2C898A6L, 0x113F9804BEF90DAEL, 0x1B710B35131C471BL,
            0x28DB77F523047D84L, 0x32CAAB7B40C72493L, 0x3C9EBE0A15C9BEBCL,
            0x431D67C49C100D4CL, 0x4CC5D4BECB3E42B6L, 0x597F299CFC657E2AL,
            0x5FCB6FAB3AD6FAECL, 0x6C44198C4A475817L
        )

        private fun rightRotate(value: Long, n: Int): Long {
            return (value ushr n) or (value shl (64 - n))
        }
    }
}
