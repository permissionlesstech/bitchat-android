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

import com.bitchat.android.noise.southernstorm.crypto.Blake2bMessageDigest
import com.bitchat.android.noise.southernstorm.crypto.Blake2sMessageDigest
import com.bitchat.android.noise.southernstorm.crypto.SHA256MessageDigest
import com.bitchat.android.noise.southernstorm.crypto.SHA512MessageDigest
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.security.SecureRandom
import javax.crypto.BadPaddingException

/**
 * Utility functions for the Noise protocol library.
 */
object Noise {
    /**
     * Maximum length for Noise packets.
     */
    const val MAX_PACKET_LEN: Int = 65535

    private val random = SecureRandom()

    private var forceFallbacks = false

    /**
     * Generates random data using the system random number generator.
     *
     * @param data The data buffer to fill with random data.
     */
    @JvmStatic
    fun random(data: ByteArray) {
        random.nextBytes(data)
    }

    /**
     * Force the use of plain Java fallback crypto implementations.
     * 
     * @param force Set to true for force fallbacks, false to
     * try to use the system implementation before falling back.
     * 
     * This function is intended for testing purposes to toggle between
     * the system JCA/JCE implementations and the plain Java fallback
     * reference implementations.
     */
    fun setForceFallbacks(force: Boolean) {
        forceFallbacks = force
    }

    /**
     * Creates a Diffie-Hellman object from its Noise protocol name.
     * 
     * @param name The name of the DH algorithm; e.g. "25519", "448", etc.
     * 
     * @return The Diffie-Hellman object if the name is recognized.
     * 
     * @throws NoSuchAlgorithmException The name is not recognized as a
     * valid Noise protocol name, or there is no cryptography provider
     * in the system that implements the algorithm.
     */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class)
    fun createDH(name: String): DHState = when (name) {
        "25519" -> Curve25519DHState()
        "448" -> Curve448DHState()
        "NewHope" -> NewHopeDHState()
        else -> throw NoSuchAlgorithmException("Unknown Noise DH algorithm name: $name")
    }

    /**
     * Creates a cipher object from its Noise protocol name.
     * 
     * @param name The name of the cipher algorithm; e.g. "AESGCM", "ChaChaPoly", etc.
     * 
     * @return The cipher object if the name is recognized.
     * 
     * @throws NoSuchAlgorithmException The name is not recognized as a
     * valid Noise protocol name, or there is no cryptography provider
     * in the system that implements the algorithm.
     */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class)
    fun createCipher(name: String): CipherState = when (name) {
        "AESGCM" -> {
            if (forceFallbacks) {
                AESGCMFallbackCipherState()
            } else {
                // "AES/GCM/NoPadding" exists in some recent JDK's but it is flaky
                // to use and not easily back-portable to older Android versions.
                // We instead emulate AESGCM on top of "AES/CTR/NoPadding".
                try {
                    AESGCMOnCtrCipherState()
                } catch (_: NoSuchAlgorithmException) {
                    AESGCMFallbackCipherState()
                }
            }
        }
        "ChaChaPoly" -> ChaChaPolyCipherState()
        else -> throw NoSuchAlgorithmException("Unknown Noise cipher algorithm name: $name")
    }

    /**
     * Creates a hash object from its Noise protocol name.
     * 
     * @param name The name of the hash algorithm; e.g. "SHA256", "BLAKE2s", etc.
     * 
     * @return The hash object if the name is recognized.
     * 
     * @throws NoSuchAlgorithmException The name is not recognized as a
     * valid Noise protocol name, or there is no cryptography provider
     * in the system that implements the algorithm.
     */
    @JvmStatic
    @Throws(NoSuchAlgorithmException::class)
    fun createHash(name: String): MessageDigest {
        // Look for a JCA/JCE provider first and if that doesn't work,
        // use the fallback implementations in this library instead.
        // The only algorithm that is required to be implemented by a
        // JDK is "SHA-256", although "SHA-512" is fairly common as well.
        val (jcaName, fallback) = when (name) {
            "SHA256" -> "SHA-256" to ::SHA256MessageDigest
            "SHA512" -> "SHA-512" to ::SHA512MessageDigest
            "BLAKE2b" -> "BLAKE2B-512" to ::Blake2bMessageDigest
            "BLAKE2s" -> "BLAKE2S-256" to ::Blake2sMessageDigest
            else -> throw NoSuchAlgorithmException("Unknown Noise hash algorithm name: $name")
        }
        if (forceFallbacks) return fallback()
        return try {
            MessageDigest.getInstance(jcaName)
        } catch (_: NoSuchAlgorithmException) {
            fallback()
        }
    }

    // The rest of this class consists of internal utility functions
    // that are not part of the public API.
    /**
     * Destroys the contents of a byte array.
     * 
     * @param array The array whose contents should be destroyed.
     */
    @JvmStatic
    fun destroy(array: ByteArray) {
        array.fill(0)
    }

    /**
     * Makes a copy of part of an array.
     * 
     * @param data The buffer containing the data to copy.
     * @param offset Offset of the first byte to copy.
     * @param length The number of bytes to copy.
     * 
     * @return A new array with a copy of the sub-array.
     */
    @JvmStatic
    fun copySubArray(data: ByteArray, offset: Int, length: Int): ByteArray =
        data.copyOfRange(offset, offset + length)

    /**
     * Throws an instance of AEADBadTagException.
     * 
     * @throws BadPaddingException The AEAD exception.
     * 
     * If the underlying JDK does not have the AEADBadTagException
     * class, then this function will instead throw an instance of
     * the superclass BadPaddingException.
     */
    @JvmStatic
    @Throws(BadPaddingException::class)
    fun throwBadTagException() {
        val aead = try {
            val c = Class.forName("javax.crypto.AEADBadTagException")
            c.getDeclaredConstructor().newInstance() as BadPaddingException
        } catch (_: ReflectiveOperationException) {
            null
        }
        throw aead ?: BadPaddingException()
    }
}
