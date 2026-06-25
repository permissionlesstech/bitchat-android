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
 * Simple implementation of the Poly1305 message authenticator.
 */
class Poly1305 : Destroyable {
    // The 130-bit intermediate values are broken up into five 26-bit words.
    private val nonce = ByteArray(16)
    private val block = ByteArray(16)
    private val h = IntArray(5)
    private val r = IntArray(5)
    private val c = IntArray(5)
    private val t = LongArray(10)
    private var posn = 0


    /**
     * Resets the message authenticator with a new key.
     * 
     * @param key The buffer containing the 32 byte key.
     * @param offset The offset into the buffer of the first key byte.
     */
    fun reset(key: ByteArray, offset: Int) {

        // Kotlin KMP Array Copy
        key.copyInto(destination = nonce, destinationOffset = 0, startIndex = offset + 16, endIndex = offset + 32)

        h.fill(0)
        posn = 0


        // Convert the first 16 bytes of the key into a 130-bit
        // "r" value while masking off the bits that we don't need.
        r[0] = ((key[offset].toInt() and 0xFF)) or
                ((key[offset + 1].toInt() and 0xFF) shl 8) or
                ((key[offset + 2].toInt() and 0xFF) shl 16) or
                ((key[offset + 3].toInt() and 0x03) shl 24)
        r[1] = ((key[offset + 3].toInt() and 0x0C) shr 2) or
                ((key[offset + 4].toInt() and 0xFC) shl 6) or
                ((key[offset + 5].toInt() and 0xFF) shl 14) or
                ((key[offset + 6].toInt() and 0x0F) shl 22)
        r[2] = ((key[offset + 6].toInt() and 0xF0) shr 4) or
                ((key[offset + 7].toInt() and 0x0F) shl 4) or
                ((key[offset + 8].toInt() and 0xFC) shl 12) or
                ((key[offset + 9].toInt() and 0x3F) shl 20)
        r[3] = ((key[offset + 9].toInt() and 0xC0) shr 6) or
                ((key[offset + 10].toInt() and 0xFF) shl 2) or
                ((key[offset + 11].toInt() and 0x0F) shl 10) or
                ((key[offset + 12].toInt() and 0xFC) shl 18)
        r[4] = ((key[offset + 13].toInt() and 0xFF)) or
                ((key[offset + 14].toInt() and 0xFF) shl 8) or
                ((key[offset + 15].toInt() and 0x0F) shl 16)
    }

    /**
     * Updates the message authenticator with more input data.
     * 
     * @param data The buffer containing the input data.
     * @param offset The offset of the first byte of input.
     * @param length The number of bytes of input.
     */
    fun update(data: ByteArray, offset: Int, length: Int) {
        var offset = offset
        var length = length
        while (length > 0) {
            if (posn == 0 && length >= 16) {
                // We can process the chunk directly out of the input buffer.
                processChunk(data, offset, false)
                offset += 16
                length -= 16
            } else {
                // Collect up partial bytes in the block buffer.
                var temp = 16 - posn
                if (temp > length) temp = length
                System.arraycopy(data, offset, block, posn, temp)
                offset += temp
                length -= temp
                posn += temp
                if (posn >= 16) {
                    processChunk(block, 0, false)
                    posn = 0
                }
            }
        }
    }

    /**
     * Pads the input with zeroes to a multiple of 16 bytes.
     */
    fun pad() {
        if (posn != 0) {
            // Kotlin Array Fill
            block.fill(0.toByte(), fromIndex = posn, toIndex = 16)
            processChunk(block, 0, false)
            posn = 0
        }
    }

    /**
     * Finishes the message authenticator and returns the 16-byte token.
     * 
     * @param token The buffer to receive the token.
     * @param offset The offset of the token in the buffer.
     */
    fun finish(token: ByteArray, offset: Int) {
        // Pad and flush the final chunk.
        if (posn != 0) {
            block[posn] = 1.toByte()
            block.fill(0.toByte(), fromIndex = posn + 1, toIndex = 16)
            processChunk(block, 0, true)
        }


        // At this point, processChunk() has left h as a partially reduced
        // result that is less than (2^130 - 5) * 6.  Perform one more
        // reduction and a trial subtraction to produce the final result.

        // Multiply the high bits of h by 5 and add them to the 130 low bits.
        var carry = (h[4] shr 26) * 5 + h[0]
        h[0] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[1]
        h[1] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[2]
        h[2] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[3]
        h[3] = carry and 0x03FFFFFF
        h[4] = (carry shr 26) + (h[4] and 0x03FFFFFF)

        // Subtract (2^130 - 5) from h by computing c = h + 5 - 2^130.
        // The "minus 2^130" step is implicit.
        carry = 5 + h[0]
        c[0] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[1]
        c[1] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[2]
        c[2] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[3]
        c[3] = carry and 0x03FFFFFF
        c[4] = (carry shr 26) + h[4]

        // Borrow occurs if bit 2^130 of the previous c result is zero.
        // Carefully turn this into a selection mask so we can select either
        // h or c as the final result.
        val mask = -((c[4] shr 26) and 0x01)
        val nmask = mask.inv()
        h[0] = (h[0] and nmask) or (c[0] and mask)
        h[1] = (h[1] and nmask) or (c[1] and mask)
        h[2] = (h[2] and nmask) or (c[2] and mask)
        h[3] = (h[3] and nmask) or (c[3] and mask)
        h[4] = (h[4] and nmask) or (c[4] and mask)


        // Convert h into little-endian in the block buffer.
        block[0] = (h[0]).toByte()
        block[1] = (h[0] shr 8).toByte()
        block[2] = (h[0] shr 16).toByte()
        block[3] = ((h[0] shr 24) or (h[1] shl 2)).toByte()
        block[4] = (h[1] shr 6).toByte()
        block[5] = (h[1] shr 14).toByte()
        block[6] = ((h[1] shr 22) or (h[2] shl 4)).toByte()
        block[7] = (h[2] shr 4).toByte()
        block[8] = (h[2] shr 12).toByte()
        block[9] = ((h[2] shr 20) or (h[3] shl 6)).toByte()
        block[10] = (h[3] shr 2).toByte()
        block[11] = (h[3] shr 10).toByte()
        block[12] = (h[3] shr 18).toByte()
        block[13] = (h[4]).toByte()
        block[14] = (h[4] shr 8).toByte()
        block[15] = (h[4] shr 16).toByte()


        // Add the nonce and write the final result to the token.
        carry = (nonce[0].toInt() and 0xFF) + (block[0].toInt() and 0xFF)
        token[offset] = carry.toByte()
        for (x in 1..15) {
            carry = (carry shr 8) + (nonce[x].toInt() and 0xFF) + (block[x].toInt() and 0xFF)
            token[offset + x] = carry.toByte()
        }
    }

    /**
     * Processes the next chunk of input data.
     * 
     * @param chunk Buffer containing the input data chunk.
     * @param offset Offset of the first byte of the 16-byte chunk.
     * @param finalChunk Set to true if this is the final chunk.
     */
    private fun processChunk(chunk: ByteArray, offset: Int, finalChunk: Boolean) {

        // Unpack the 128-bit chunk into a 130-bit value in "c".
        c[0] = ((chunk[offset].toInt() and 0xFF)) or
                ((chunk[offset + 1].toInt() and 0xFF) shl 8) or
                ((chunk[offset + 2].toInt() and 0xFF) shl 16) or
                ((chunk[offset + 3].toInt() and 0x03) shl 24)
        c[1] = ((chunk[offset + 3].toInt() and 0xFC) shr 2) or
                ((chunk[offset + 4].toInt() and 0xFF) shl 6) or
                ((chunk[offset + 5].toInt() and 0xFF) shl 14) or
                ((chunk[offset + 6].toInt() and 0x0F) shl 22)
        c[2] = ((chunk[offset + 6].toInt() and 0xF0) shr 4) or
                ((chunk[offset + 7].toInt() and 0xFF) shl 4) or
                ((chunk[offset + 8].toInt() and 0xFF) shl 12) or
                ((chunk[offset + 9].toInt() and 0x3F) shl 20)
        c[3] = ((chunk[offset + 9].toInt() and 0xC0) shr 6) or
                ((chunk[offset + 10].toInt() and 0xFF) shl 2) or
                ((chunk[offset + 11].toInt() and 0xFF) shl 10) or
                ((chunk[offset + 12].toInt() and 0xFF) shl 18)
        c[4] = ((chunk[offset + 13].toInt() and 0xFF)) or
                ((chunk[offset + 14].toInt() and 0xFF) shl 8) or
                ((chunk[offset + 15].toInt() and 0xFF) shl 16)
        if (!finalChunk) c[4] = c[4] or (1 shl 24)


        // Compute h = ((h + c) * r) mod (2^130 - 5)

        // Start with h += c.  We assume that h is less than (2^130 - 5) * 6
        // and that c is less than 2^129, so the result will be less than 2^133.
        h[0] += c[0]
        h[1] += c[1]
        h[2] += c[2]
        h[3] += c[3]
        h[4] += c[4]

        // Multiply h by r.  We know that r is less than 2^124 because the
        // top 4 bits were AND-ed off by reset().  That makes h * r less
        // than 2^257.  Which is less than the (2^130 - 6)^2 we want for
        // the modulo reduction step that follows.  The intermediate limbs
        // are 52 bits in size, which allows us to collect up carries in the
        // extra bits of the 64 bit longs and propagate them later.
        var hv = h[0].toLong()
        t[0] = hv * r[0]
        t[1] = hv * r[1]
        t[2] = hv * r[2]
        t[3] = hv * r[3]
        t[4] = hv * r[4]

        for (x in 1 until 5) {
            hv = h[x].toLong()
            t[x] += hv * r[0]
            t[x + 1] += hv * r[1]
            t[x + 2] += hv * r[2]
            t[x + 3] += hv * r[3]
            t[x + 4] = hv * r[4]
        }


        // Propagate carries to convert the t limbs from 52-bit back to 26-bit.
        // The low bits are placed into h and the high bits are placed into c.
        h[0] = (t[0].toInt()) and 0x03FFFFFF
        hv = t[1] + (t[0] shr 26)
        h[1] = (hv.toInt()) and 0x03FFFFFF
        hv = t[2] + (hv shr 26)
        h[2] = (hv.toInt()) and 0x03FFFFFF
        hv = t[3] + (hv shr 26)
        h[3] = (hv.toInt()) and 0x03FFFFFF
        hv = t[4] + (hv shr 26)
        h[4] = (hv.toInt()) and 0x03FFFFFF
        hv = t[5] + (hv shr 26)
        c[0] = (hv.toInt()) and 0x03FFFFFF
        hv = t[6] + (hv shr 26)
        c[1] = (hv.toInt()) and 0x03FFFFFF
        hv = t[7] + (hv shr 26)
        c[2] = (hv.toInt()) and 0x03FFFFFF
        hv = t[8] + (hv shr 26)
        c[3] = (hv.toInt()) and 0x03FFFFFF
        hv = t[9] + (hv shr 26)
        c[4] = (hv.toInt())


        // Reduce h * r modulo (2^130 - 5) by multiplying the high 130 bits by 5
        // and adding them to the low 130 bits.  This will leave the result at
        // most 5 subtractions away from the answer we want.
        var carry = h[0] + c[0] * 5
        h[0] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[1] + c[1] * 5
        h[1] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[2] + c[2] * 5
        h[2] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[3] + c[3] * 5
        h[3] = carry and 0x03FFFFFF
        carry = (carry shr 26) + h[4] + c[4] * 5
        h[4] = carry
    }

    override fun destroy() {
        // Kotlin Array Fill
        nonce.fill(0.toByte())
        block.fill(0.toByte())
        h.fill(0)
        r.fill(0)
        c.fill(0)
        t.fill(0L)
    }
}
