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

/**
 * Implementation of the GHASH primitive for GCM.
 */
class GHASH : Destroyable {
    private val h = LongArray(2)
    private val y = ByteArray(16)
    private var posn: Int = 0

    /**
     * Resets this GHASH object with a new key.
     * 
     * @param key The key, which must contain at least 16 bytes.
     * @param offset The offset of the first key byte.
     */
    fun reset(key: ByteArray, offset: Int) {
        h[0] = readBigEndian(key, offset)
        h[1] = readBigEndian(key, offset + 8)
        y.fill(0)
        posn = 0
    }

    /**
     * Resets the GHASH object but retains the previous key.
     */
    fun reset() {
        y.fill(0)
        posn = 0
    }

    /**
     * Updates this GHASH object with more data.
     * 
     * @param data Buffer containing the data.
     * @param offset Offset of the first data byte in the buffer.
     * @param length The number of bytes from the buffer to hash.
     */
    fun update(data: ByteArray, offset: Int, length: Int) {
        var currentOffset = offset
        var remainingLength = length
        while (remainingLength > 0) {
            var size = 16 - posn
            if (size > remainingLength) size = remainingLength
            for (index in 0 until size) {
                y[posn + index] = (y[posn + index].toInt() xor data[currentOffset + index].toInt()).toByte()
            }
            posn += size
            remainingLength -= size
            currentOffset += size
            if (posn == 16) {
                gf128Mul(y, h)
                posn = 0
            }
        }
    }

    /**
     * Finishes the GHASH process and returns the tag.
     * 
     * @param tag Buffer to receive the tag.
     * @param offset Offset of the first byte of the tag.
     * @param length The length of the tag, which must be less
     * than or equal to 16.
     */
    fun finish(tag: ByteArray, offset: Int, length: Int) {
        pad()
        y.copyInto(tag, destinationOffset = offset, startIndex = 0, endIndex = length)
    }

    /**
     * Pads the input to a 16-byte boundary.
     */
    fun pad() {
        if (posn != 0) {
            // Padding involves XOR'ing the rest of state->Y with zeroes,
            // which does nothing.  Immediately process the next chunk.
            gf128Mul(y, h)
            posn = 0
        }
    }

    /**
     * Pads the input to a 16-byte boundary and then adds a block
     * containing the AD and data lengths.
     * 
     * @param adLen Length of the associated data in bytes.
     * @param dataLen Length of the data in bytes.
     */
    fun pad(adLen: Long, dataLen: Long) {
        val temp = ByteArray(16)
        try {
            pad()
            writeBigEndian(temp, 0, adLen * 8)
            writeBigEndian(temp, 8, dataLen * 8)
            update(temp, 0, 16)
        } finally {
            temp.fill(0)
        }
    }

    override fun destroy() {
        h.fill(0L)
        y.fill(0)
    }

    companion object {
        private fun readBigEndian(buf: ByteArray, offset: Int): Long {
            return ((buf[offset].toLong() and 0xFFL) shl 56) or
                    ((buf[offset + 1].toLong() and 0xFFL) shl 48) or
                    ((buf[offset + 2].toLong() and 0xFFL) shl 40) or
                    ((buf[offset + 3].toLong() and 0xFFL) shl 32) or
                    ((buf[offset + 4].toLong() and 0xFFL) shl 24) or
                    ((buf[offset + 5].toLong() and 0xFFL) shl 16) or
                    ((buf[offset + 6].toLong() and 0xFFL) shl 8) or
                    (buf[offset + 7].toLong() and 0xFFL)
        }

        private fun writeBigEndian(buf: ByteArray, offset: Int, value: Long) {
            buf[offset] = (value shr 56).toByte()
            buf[offset + 1] = (value shr 48).toByte()
            buf[offset + 2] = (value shr 40).toByte()
            buf[offset + 3] = (value shr 32).toByte()
            buf[offset + 4] = (value shr 24).toByte()
            buf[offset + 5] = (value shr 16).toByte()
            buf[offset + 6] = (value shr 8).toByte()
            buf[offset + 7] = value.toByte()
        }

        private fun gf128Mul(y: ByteArray, h: LongArray) {
            var z0: Long = 0 // Z = 0
            var z1: Long = 0
            var v0 = h[0] // V = H
            var v1 = h[1]

            // Multiply Z by V for the set bits in Y, starting at the top.
            // This is a very simple bit by bit version that may not be very
            // fast but it should be resistant to cache timing attacks.
            for (pos in 0..15) {
                val value = y[pos].toInt() and 0xFF
                for (bit in 7 downTo 0) {
                    // Extract the high bit of "value" and turn it into a mask.
                    var mask = -(((value shr bit) and 0x01).toLong())

                    // XOR V with Z if the bit is 1.
                    z0 = z0 xor (v0 and mask)
                    z1 = z1 xor (v1 and mask)

                    // Rotate V right by 1 bit.
                    mask = (((v1 and 0x01L).inv()) + 1) and -0x1f00000000000000L
                    v1 = (v1 ushr 1) or (v0 shl 63)
                    v0 = (v0 ushr 1) xor mask
                }
            }

            // We have finished the block so copy Z into Y and byte-swap.
            writeBigEndian(y, 0, z0)
            writeBigEndian(y, 8, z1)
        }
    }
}
