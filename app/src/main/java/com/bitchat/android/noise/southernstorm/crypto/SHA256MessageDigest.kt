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
 * Fallback implementation of SHA256.
 */
class SHA256MessageDigest : MessageDigest("SHA-256"), Destroyable {
    private val h = IntArray(8)
    private val block = ByteArray(64)
    private val w = IntArray(64)
    private var length: Long = 0
    private var posn = 0

    init {
        engineReset()
    }

    override fun destroy() {
        h.fill(0)
        block.fill(0.toByte())
        w.fill(0)
    }

    override fun engineDigest(): ByteArray {
        val digest = ByteArray(32)
        try {
            engineDigest(digest, 0, 32)
        } catch (e: DigestException) {
            // Shouldn't happen, but just in case.
            digest.fill(0.toByte())
        }
        return digest
    }

    @Throws(DigestException::class)
    override fun engineDigest(buf: ByteArray, offset: Int, len: Int): Int {
        if (len < 32) throw DigestException("Invalid digest length for SHA256")
        if (posn <= (64 - 9)) {
            block[posn] = 0x80.toByte()
            block.fill(0.toByte(), posn + 1, 64 - 8)
        } else {
            block[posn] = 0x80.toByte()
            block.fill(0.toByte(), posn + 1, 64)
            transform(block, 0)
            block.fill(0.toByte(), 0, 64 - 8)
        }
        writeBE32(block, 64 - 8, (length shr 32).toInt())
        writeBE32(block, 64 - 4, length.toInt())
        transform(block, 0)
        posn = 0
        for (index in 0..7) writeBE32(buf, offset + index * 4, h[index])
        return 32
    }

    override fun engineGetDigestLength(): Int {
        return 32
    }

    override fun engineReset() {
        h[0] = 0x6A09E667
        h[1] = -0x4498517b
        h[2] = 0x3C6EF372
        h[3] = -0x5ab00ac6
        h[4] = 0x510E527F
        h[5] = -0x64fa9774
        h[6] = 0x1F83D9AB
        h[7] = 0x5BE0CD19
        length = 0
        posn = 0
    }

    override fun engineUpdate(input: Byte) {
        block[posn++] = input
        length += 8
        if (posn >= 64) {
            transform(block, 0)
            posn = 0
        }
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        var offset = offset
        var len = len
        while (len > 0) {
            if (posn == 0 && len >= 64) {
                transform(input, offset)
                offset += 64
                len -= 64
                length += (64 * 8).toLong()
            } else {
                var temp = 64 - posn
                if (temp > len) temp = len
                System.arraycopy(input, offset, block, posn, temp)
                posn += temp
                length += (temp * 8).toLong()
                if (posn >= 64) {
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

        // Convert the 16 input message words from big endian to host byte order.
        for (index in 0 until 16) {
            w[index] = ((m[currentOffset].toInt() and 0xFF) shl 24) or
                    ((m[currentOffset + 1].toInt() and 0xFF) shl 16) or
                    ((m[currentOffset + 2].toInt() and 0xFF) shl 8) or
                    (m[currentOffset + 3].toInt() and 0xFF)
            currentOffset += 4
        }


        // Extend the first 16 words to 64.
        for (index in 16 until 64) {
            w[index] = w[index - 16] + w[index - 7] +
                    (rightRotate(w[index - 15], 7) xor
                            rightRotate(w[index - 15], 18) xor
                            (w[index - 15] ushr 3)) +
                    (rightRotate(w[index - 2], 17) xor
                            rightRotate(w[index - 2], 19) xor
                            (w[index - 2] ushr 10))
        }


        // Compression function main loop.
        for (index in 0 until 64) {
            val temp1 = h + k[index] + w[index] +
                    (rightRotate(e, 6) xor rightRotate(e, 11) xor rightRotate(e, 25)) +
                    ((e and f) xor (e.inv() and g))
            val temp2 = (rightRotate(a, 2) xor rightRotate(a, 13) xor rightRotate(a, 22)) +
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
        private fun writeBE32(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value shr 24).toByte()
            buf[offset + 1] = (value shr 16).toByte()
            buf[offset + 2] = (value shr 8).toByte()
            buf[offset + 3] = value.toByte()
        }

        private val k = intArrayOf(
            0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b,
            0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
            -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3,
            0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
            -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc,
            0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039,
            -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
            0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
            -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d,
            -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
            0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8,
            -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e
        )

        private fun rightRotate(value: Int, n: Int): Int {
            return (value ushr n) or (value shl (32 - n))
        }
    }
}
