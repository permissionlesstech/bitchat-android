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

import com.bitchat.android.noise.southernstorm.crypto.GHASH
import com.bitchat.android.noise.southernstorm.crypto.RijndaelAES
import com.bitchat.android.noise.southernstorm.protocol.Noise.destroy
import com.bitchat.android.noise.southernstorm.protocol.Noise.throwBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.ShortBufferException

/**
 * Fallback implementation of "AESGCM" on platforms where
 * the JCA/JCE does not have a suitable GCM or CTR provider.
 */
internal class AESGCMFallbackCipherState : CipherState {
    private val aes: RijndaelAES = RijndaelAES()
    private var n: Long = 0
    private val iv: ByteArray = ByteArray(16)
    private val enciv: ByteArray = ByteArray(16)
    private val hashKey: ByteArray = ByteArray(16)
    private val ghash: GHASH = GHASH()
    private var haskey: Boolean = false

    override fun destroy() {
        aes.destroy()
        ghash.destroy()
        destroy(hashKey)
        destroy(iv)
        destroy(enciv)
    }

    override val cipherName: String get() = "AESGCM"
    override val keyLength: Int get() = 32
    override val macLength: Int get() = if (haskey) 16 else 0

    override fun initializeKey(key: ByteArray, offset: Int) {
        // Set up the AES key.
        aes.setupEnc(key, offset, 256)
        haskey = true

        // Generate the hashing key by encrypting a block of zeroes.
        hashKey.fill(0)
        aes.encrypt(hashKey, 0, hashKey, 0)
        ghash.reset(hashKey, 0)


        // Reset the nonce.
        n = 0
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
        // Check for nonce wrap-around.
        check(n != -1L) { "Nonce has wrapped around" }


        // Format the counter/IV block.
        iv[0] = 0
        iv[1] = 0
        iv[2] = 0
        iv[3] = 0
        iv[4] = (n shr 56).toByte()
        iv[5] = (n shr 48).toByte()
        iv[6] = (n shr 40).toByte()
        iv[7] = (n shr 32).toByte()
        iv[8] = (n shr 24).toByte()
        iv[9] = (n shr 16).toByte()
        iv[10] = (n shr 8).toByte()
        iv[11] = n.toByte()
        iv[12] = 0
        iv[13] = 0
        iv[14] = 0
        iv[15] = 1
        ++n


        // Encrypt a block of zeroes to generate the hash key to XOR
        // the GHASH tag with at the end of the encrypt/decrypt operation.
        hashKey.fill(0)
        aes.encrypt(iv, 0, hashKey, 0)


        // Initialize the GHASH with the associated data value.
        ghash.reset()
        if (ad != null) {
            ghash.update(ad, 0, ad.size)
            ghash.pad()
        }
    }

    /**
     * Encrypts a block in CTR mode.
     * 
     * @param plaintext The plaintext to encrypt.
     * @param plaintextOffset Offset of the first plaintext byte.
     * @param ciphertext The resulting ciphertext.
     * @param ciphertextOffset Offset of the first ciphertext byte.
     * @param length The number of bytes to encrypt.
     * 
     * This function can also be used to decrypt.
     */
    private fun encryptCTR(
        plaintext: ByteArray,
        plaintextOffset: Int,
        ciphertext: ByteArray,
        ciphertextOffset: Int,
        length: Int
    ) {
        var plaintextOffset = plaintextOffset
        var ciphertextOffset = ciphertextOffset
        var length = length
        while (length > 0) {
            // Increment the IV (big-endian 4-byte counter at iv[12..15])
            // and encrypt it to get the next keystream block.
            iv[15] = (iv[15] + 1).toByte()
            if (iv[15].toInt() == 0) {
                iv[14] = (iv[14] + 1).toByte()
                if (iv[14].toInt() == 0) {
                    iv[13] = (iv[13] + 1).toByte()
                    if (iv[13].toInt() == 0) {
                        iv[12] = (iv[12] + 1).toByte()
                    }
                }
            }
            aes.encrypt(iv, 0, enciv, 0)


            // XOR the keystream block with the plaintext to create the ciphertext.
            var temp = length
            if (temp > 16) temp = 16
            for (index in 0..<temp) ciphertext[ciphertextOffset + index] =
                (plaintext[plaintextOffset + index].toInt() xor enciv[index].toInt()).toByte()


            // Advance to the next block.
            plaintextOffset += temp
            ciphertextOffset += temp
            length -= temp
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
        encryptCTR(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length)
        ghash.update(ciphertext, ciphertextOffset, length)
        ghash.pad((if (ad != null) ad.size else 0).toLong(), length.toLong())
        ghash.finish(ciphertext, ciphertextOffset + length, 16)
        for (index in 0..15) {
            ciphertext[ciphertextOffset + length + index] =
                (ciphertext[ciphertextOffset + length + index].toInt() xor hashKey[index].toInt()).toByte()
        }
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
        var space: Int
        require(!(ciphertextOffset < 0 || ciphertextOffset > ciphertext.size))
        space = ciphertext.size - ciphertextOffset
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
        ghash.update(ciphertext, ciphertextOffset, dataLen)
        ghash.pad((if (ad != null) ad.size else 0).toLong(), dataLen.toLong())
        ghash.finish(enciv, 0, 16)
        var temp = 0
        for (index in 0..15) temp =
            temp or (hashKey[index].toInt() xor enciv[index].toInt() xor ciphertext[ciphertextOffset + dataLen + index].toInt())
        if ((temp and 0xFF) != 0) throwBadTagException()
        encryptCTR(ciphertext, ciphertextOffset, plaintext, plaintextOffset, dataLen)
        return dataLen
    }

    override fun fork(key: ByteArray, offset: Int): CipherState {
        val cipher: CipherState = AESGCMFallbackCipherState()
        cipher.initializeKey(key, offset)
        return cipher
    }

    override fun setNonce(nonce: Long) {
        n = nonce
    }
}
