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
package com.bitchat.android.noise.southernstorm.protocol

import com.bitchat.android.noise.southernstorm.crypto.ChaChaCore.hash
import com.bitchat.android.noise.southernstorm.crypto.ChaChaCore.initIV
import com.bitchat.android.noise.southernstorm.crypto.ChaChaCore.initKey256
import com.bitchat.android.noise.southernstorm.crypto.Poly1305
import com.bitchat.android.noise.southernstorm.protocol.Noise.destroy
import com.bitchat.android.noise.southernstorm.protocol.Noise.throwBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.ShortBufferException

/**
 * Implements the ChaChaPoly cipher for Noise.
 */
internal class ChaChaPolyCipherState : CipherState {
    private val poly: Poly1305 = Poly1305()
    private val input: IntArray = IntArray(16)
    private val output: IntArray = IntArray(16)
    private val polyKey: ByteArray = ByteArray(32)
    private var n: Long = 0
    private var haskey = false

    override fun destroy() {
        poly.destroy()
        input.fill(0)
        output.fill(0)
        destroy(polyKey)
    }

    override val cipherName: String get() = "ChaChaPoly"
    override val keyLength: Int get() = 32
    override val macLength: Int get() = if (haskey) 16 else 0

    override fun initializeKey(key: ByteArray, offset: Int) {
        initKey256(input, key, offset)
        n = 0
        haskey = true
    }

    override fun hasKey(): Boolean {
        return haskey
    }

    /**
     * Set up to encrypt or decrypt the next packet.
     * 
     * @param ad The associated data for the packet.
     */
    private fun setup(ad: ByteArray?) {
        check(n != -1L) { "Nonce has wrapped around" }
        initIV(input, n++)
        hash(output, input)
        polyKey.fill(0)
        xorBlock(polyKey, 0, polyKey, 0, 32, output)
        poly.reset(polyKey, 0)
        if (ad != null) {
            poly.update(ad, 0, ad.size)
            poly.pad()
        }
        if (++input[12] == 0) ++input[13]
    }

    /**
     * Finishes up the authentication tag for a packet.
     * 
     * @param ad The associated data.
     * @param length The length of the plaintext data.
     */
    private fun finish(ad: ByteArray?, length: Int) {
        poly.pad()
        putLittleEndian64(polyKey, 0, (ad?.size ?: 0).toLong())
        putLittleEndian64(polyKey, 8, length.toLong())
        poly.update(polyKey, 0, 16)
        poly.finish(polyKey, 0)
    }

    /**
     * Encrypts or decrypts a buffer of bytes for the active packet.
     * 
     * @param plaintext The plaintext data to be encrypted.
     * @param plaintextOffset The offset to the first plaintext byte.
     * @param ciphertext The ciphertext data that results from encryption.
     * @param ciphertextOffset The offset to the first ciphertext byte.
     * @param length The number of bytes to encrypt.
     */
    private fun encrypt(
        plaintext: ByteArray, plaintextOffset: Int,
        ciphertext: ByteArray, ciphertextOffset: Int, length: Int
    ) {
        var plaintextOffset = plaintextOffset
        var ciphertextOffset = ciphertextOffset
        var length = length
        while (length > 0) {
            var tempLen = 64
            if (tempLen > length) tempLen = length
            hash(output, input)
            xorBlock(plaintext, plaintextOffset, ciphertext, ciphertextOffset, tempLen, output)
            if (++input[12] == 0) ++input[13]
            plaintextOffset += tempLen
            ciphertextOffset += tempLen
            length -= tempLen
        }
    }

    @Throws(ShortBufferException::class)
    override fun encryptWithAd(
        ad: ByteArray?, plaintext: ByteArray, plaintextOffset: Int,
        ciphertext: ByteArray, ciphertextOffset: Int, length: Int
    ): Int {
        require(!(ciphertextOffset < 0 || ciphertextOffset > ciphertext.size))
        require(!(length < 0 || plaintextOffset < 0 || plaintextOffset > plaintext.size || length > plaintext.size || (plaintext.size - plaintextOffset) < length))
        val space: Int = ciphertext.size - ciphertextOffset
        if (!haskey) {
            // The key is not set yet - return the plaintext as-is.
            if (length > space) throw ShortBufferException()
            if (plaintext !== ciphertext || plaintextOffset != ciphertextOffset) System.arraycopy(
                plaintext,
                plaintextOffset,
                ciphertext,
                ciphertextOffset,
                length
            )
            return length
        }
        if (space < 16 || length > (space - 16)) throw ShortBufferException()
        setup(ad)
        encrypt(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length)
        poly.update(ciphertext, ciphertextOffset, length)
        finish(ad, length)
        System.arraycopy(polyKey, 0, ciphertext, ciphertextOffset + length, 16)
        return length + 16
    }

    @Throws(ShortBufferException::class, BadPaddingException::class)
    override fun decryptWithAd(
        ad: ByteArray?,
        ciphertext: ByteArray,
        ciphertextOffset: Int,
        plaintext: ByteArray,
        plaintextOffset: Int,
        length: Int
    ): Int {
        require(!(ciphertextOffset < 0 || ciphertextOffset > ciphertext.size))
        var space: Int = ciphertext.size - ciphertextOffset
        if (length > space) throw ShortBufferException()
        require(!(length < 0 || plaintextOffset < 0 || plaintextOffset > plaintext.size || length > ciphertext.size || (ciphertext.size - ciphertextOffset) < length))
        space = plaintext.size - plaintextOffset
        if (!haskey) {
            // The key is not set yet - return the ciphertext as-is.
            if (length > space) throw ShortBufferException()
            if (plaintext !== ciphertext || plaintextOffset != ciphertextOffset) System.arraycopy(
                ciphertext,
                ciphertextOffset,
                plaintext,
                plaintextOffset,
                length
            )
            return length
        }
        if (length < 16) throwBadTagException()
        val dataLen = length - 16
        if (dataLen > space) throw ShortBufferException()
        setup(ad)
        poly.update(ciphertext, ciphertextOffset, dataLen)
        finish(ad, dataLen)
        var temp = 0
        for (index in 0..15) temp =
            temp or (polyKey[index].toInt() xor ciphertext[ciphertextOffset + dataLen + index].toInt())
        if ((temp and 0xFF) != 0) throwBadTagException()
        encrypt(ciphertext, ciphertextOffset, plaintext, plaintextOffset, dataLen)
        return dataLen
    }

    override fun fork(key: ByteArray, offset: Int): CipherState {
        val cipher: CipherState = ChaChaPolyCipherState()
        cipher.initializeKey(key, offset)
        return cipher
    }

    override fun setNonce(nonce: Long) {
        n = nonce
    }

    companion object {
        /**
         * XOR's the output of ChaCha20 with a byte buffer.
         * 
         * @param input The input byte buffer.
         * @param inputOffset The offset of the first input byte.
         * @param output The output byte buffer (can be the same as the input).
         * @param outputOffset The offset of the first output byte.
         * @param length The number of bytes to XOR between 1 and 64.
         * @param block The ChaCha20 output block.
         */
        private fun xorBlock(
            input: ByteArray,
            inputOffset: Int,
            output: ByteArray,
            outputOffset: Int,
            length: Int,
            block: IntArray
        ) {
            var inputOffset = inputOffset
            var outputOffset = outputOffset
            var length = length
            var posn = 0
            var value: Int
            while (length >= 4) {
                value = block[posn++]
                output[outputOffset] = (input[inputOffset].toInt() xor value).toByte()
                output[outputOffset + 1] =
                    (input[inputOffset + 1].toInt() xor (value shr 8)).toByte()
                output[outputOffset + 2] =
                    (input[inputOffset + 2].toInt() xor (value shr 16)).toByte()
                output[outputOffset + 3] =
                    (input[inputOffset + 3].toInt() xor (value shr 24)).toByte()
                inputOffset += 4
                outputOffset += 4
                length -= 4
            }
            when (length) {
                3 -> {
                    value = block[posn]
                    output[outputOffset] = (input[inputOffset].toInt() xor value).toByte()
                    output[outputOffset + 1] =
                        (input[inputOffset + 1].toInt() xor (value shr 8)).toByte()
                    output[outputOffset + 2] =
                        (input[inputOffset + 2].toInt() xor (value shr 16)).toByte()
                }
                2 -> {
                    value = block[posn]
                    output[outputOffset] = (input[inputOffset].toInt() xor value).toByte()
                    output[outputOffset + 1] =
                        (input[inputOffset + 1].toInt() xor (value shr 8)).toByte()
                }
                1 -> {
                    value = block[posn]
                    output[outputOffset] = (input[inputOffset].toInt() xor value).toByte()
                }
            }
        }

        /**
         * Puts a 64-bit integer into a buffer in little-endian order.
         * 
         * @param output The output buffer.
         * @param offset The offset into the output buffer.
         * @param value The 64-bit integer value.
         */
        private fun putLittleEndian64(output: ByteArray, offset: Int, value: Long) {
            output[offset] = value.toByte()
            output[offset + 1] = (value shr 8).toByte()
            output[offset + 2] = (value shr 16).toByte()
            output[offset + 3] = (value shr 24).toByte()
            output[offset + 4] = (value shr 32).toByte()
            output[offset + 5] = (value shr 40).toByte()
            output[offset + 6] = (value shr 48).toByte()
            output[offset + 7] = (value shr 56).toByte()
        }
    }
}
