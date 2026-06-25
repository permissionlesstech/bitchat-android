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
 * Fallback implementation of BLAKE2s for the Noise library.
 * 
 * This implementation only supports message digesting with an output
 * length of 32 bytes.  Keyed hashing and variable-length digests are
 * not supported.
 */
class Blake2sMessageDigest : MessageDigest("BLAKE2S-256"), Destroyable {
    private val h = IntArray(8)
    private val block = ByteArray(64)
    private val m = IntArray(16)
    private val v = IntArray(16)
    private var length: Long = 0
    private var posn = 0

    init {
        engineReset()
    }


    override fun engineDigest(): ByteArray {
        val digest = ByteArray(32)
        try {
            engineDigest(digest, 0, 32)
        } catch (e: DigestException) {
            // Shouldn't happen, but just in case.
            digest.fill(0)
        }
        return digest
    }

    @Throws(DigestException::class)
    override fun engineDigest(buf: ByteArray, offset: Int, len: Int): Int {
        var offset = offset
        if (len < 32) throw DigestException("Invalid digest length for BLAKE2s")
        block.fill(0, posn, 64)
        transform(-1)
        for (index in 0..7) {
            val value = h[index]
            buf[offset++] = value.toByte()
            buf[offset++] = (value shr 8).toByte()
            buf[offset++] = (value shr 16).toByte()
            buf[offset++] = (value shr 24).toByte()
        }
        return 32
    }

    override fun engineGetDigestLength(): Int = 32


    override fun engineReset() {
        h[0] = 0x6A09E667 xor 0x01010020
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
        if (posn >= 64) {
            transform(0)
            posn = 0
        }
        block[posn++] = input
        ++length
    }

    override fun engineUpdate(input: ByteArray, offset: Int, len: Int) {
        var currentOffset = offset
        var remainingLen = len
        while (remainingLen > 0) {
            if (posn >= 64) {
                transform(0)
                posn = 0
            }
            var temp = 64 - posn
            if (temp > remainingLen) temp = remainingLen

            input.copyInto(block, posn, currentOffset, currentOffset + temp)
            posn += temp
            length += temp.toLong()
            currentOffset += temp
            remainingLen -= temp
        }
    }

    private fun transform(f0: Int) {
        var currentOffset = 0


        // Unpack the input block from little-endian into host-endian.
        for (index in 0 until 16) {
            m[index] = (block[currentOffset].toInt() and 0xFF) or
                    ((block[currentOffset + 1].toInt() and 0xFF) shl 8) or
                    ((block[currentOffset + 2].toInt() and 0xFF) shl 16) or
                    ((block[currentOffset + 3].toInt() and 0xFF) shl 24)
            currentOffset += 4
        }

        // Format the block to be hashed.
        for (index in 0 until 8) {
            v[index] = h[index]
        }
        v[8] = 0x6A09E667
        v[9] = -0x4498517b
        v[10] = 0x3C6EF372
        v[11] = -0x5ab00ac6
        v[12] = 0x510E527F xor length.toInt()
        v[13] = -0x64fa9774 xor (length shr 32).toInt()
        v[14] = 0x1F83D9AB xor f0
        v[15] = 0x5BE0CD19


        // Perform the 10 BLAKE2s rounds.
        for (index in 0 until 10) {
            // Column round.
            quarterRound(0, 4, 8, 12, 0, index)
            quarterRound(1, 5, 9, 13, 1, index)
            quarterRound(2, 6, 10, 14, 2, index)
            quarterRound(3, 7, 11, 15, 3, index)

            // Diagonal round.
            quarterRound(0, 5, 10, 15, 4, index)
            quarterRound(1, 6, 11, 12, 5, index)
            quarterRound(2, 7, 8, 13, 6, index)
            quarterRound(3, 4, 9, 14, 7, index)
        }


        // Combine the new and old hash values.
        for (index in 0 until 8) {
            h[index] = h[index] xor (v[index] xor v[index + 8])
        }
    }

    private fun quarterRound(a: Int, b: Int, c: Int, d: Int, i: Int, row: Int) {
        v[a] += v[b] + m[sigma[row][2 * i].toInt()]
        v[d] = rightRotate16(v[d] xor v[a])
        v[c] += v[d]
        v[b] = rightRotate12(v[b] xor v[c])
        v[a] += v[b] + m[sigma[row][2 * i + 1].toInt()]
        v[d] = rightRotate8(v[d] xor v[a])
        v[c] += v[d]
        v[b] = rightRotate7(v[b] xor v[c])
    }

    override fun destroy() {
        h.fill(0)
        block.fill(0)
        m.fill(0)
        v.fill(0)
    }

    companion object {
        // Permutation on the message input state for BLAKE2s.
        val sigma: Array<ByteArray> = arrayOf(
            byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
            byteArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
            byteArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
            byteArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
            byteArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
            byteArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
            byteArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
            byteArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
            byteArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
            byteArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
        )

        private fun rightRotate16(v: Int): Int = (v shl 16) or (v ushr 16)
        private fun rightRotate12(v: Int): Int = (v shl 20) or (v ushr 12)
        private fun rightRotate8(v: Int): Int = (v shl 24) or (v ushr 8)
        private fun rightRotate7(v: Int): Int = (v shl 25) or (v ushr 7)
    }
}
