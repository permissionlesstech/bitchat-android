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

import android.util.Log
import com.bitchat.android.noise.southernstorm.protocol.Noise.createCipher
import com.bitchat.android.noise.southernstorm.protocol.Noise.createHash
import com.bitchat.android.noise.southernstorm.protocol.Noise.destroy
import java.io.UnsupportedEncodingException
import java.security.DigestException
import java.security.MessageDigest
import javax.crypto.BadPaddingException
import javax.crypto.ShortBufferException

/**
 * Symmetric state for helping manage a Noise handshake.
 */
internal class SymmetricState(protocolName: String, cipherName: String, hashName: String) :
    Destroyable {
    /**
     * Gets the name of the Noise protocol.
     * 
     * @return The protocol name.
     */
    val protocolName: String
    private var cipher: CipherState?
    private var hash: MessageDigest?
    private var ck: ByteArray?

    /**
     * Gets the current value of the handshake hash.
     * 
     * @return The handshake hash.  This must not be modified by the caller.
     * 
     * The handshake hash value is only of use to the application after
     * split() has been called.
     */
    var handshakeHash: ByteArray?
        private set
    private var prevH: ByteArray?

    /**
     * Constructs a new symmetric state object.
     * 
     * @param protocolName The name of the Noise protocol, which is assumed to be valid.
     * @param cipherName The name of the cipher within protocolName.
     * @param hashName The name of the hash within protocolName.
     * 
     * @throws NoSuchAlgorithmException The cipher or hash algorithm in the
     * protocol name is not supported.
     */
    init {
        this.protocolName = protocolName
        cipher = createCipher(cipherName)
        hash = createHash(hashName)
        val hashLength = hash!!.digestLength
        ck = ByteArray(hashLength)
        val initialHash = ByteArray(hashLength)
        this.handshakeHash = initialHash
        prevH = ByteArray(hashLength)

        val protocolNameBytes: ByteArray = try {
            protocolName.toByteArray(charset("UTF-8"))
        } catch (_: UnsupportedEncodingException) {
            // If UTF-8 is not supported, then we are definitely in trouble!
            throw UnsupportedOperationException("UTF-8 encoding is not supported")
        }

        if (protocolNameBytes.size <= hashLength) {
            protocolNameBytes.copyInto(initialHash, 0, 0, protocolNameBytes.size)
            initialHash.fill(0, protocolNameBytes.size, initialHash.size)
        } else {
            hashOne(
                protocolNameBytes, 0, protocolNameBytes.size,
                initialHash, 0, initialHash.size
            )
        }

        initialHash.copyInto(ck!!, 0, 0, hashLength)


        // LOGGING: Initial symmetric state after protocol name initialization (matching iOS)
        Log.d(TAG, "=== ANDROID SYMMETRIC STATE INITIALIZED ===")
        Log.d(TAG, "Protocol: $protocolName")
        Log.d(TAG, "Initial hash (h): " + bytesToHex(initialHash))
        Log.d(TAG, "Initial chaining key (ck): " + bytesToHex(ck!!))
        Log.d(TAG, "Hash length: " + initialHash.size)
        Log.d(TAG, "=========================================")
    }

    /**
     * Length of MAC values in the current state, or zero if the cipher
     * has not yet been initialized with a key.
     */
    val macLength: Int
        get() = cipher!!.macLength

    /**
     * Mixes data into the chaining key.
     * 
     * @param data The buffer containing the data to mix in.
     * @param offset The offset of the first data byte to mix in.
     * @param length The number of bytes to mix in.
     */
    fun mixKey(data: ByteArray, offset: Int, length: Int) {
        // LOGGING: Before mixKey operation (matching iOS)
        val inputData = ByteArray(length)
        data.copyInto(inputData, 0, offset, offset + length)
        Log.d(TAG, "*** Android mixKey() BEFORE ***")
        Log.d(TAG, "Input data ($length bytes): " + bytesToHex(inputData))
        Log.d(TAG, "Current CK: " + bytesToHex(ck!!))
        Log.d(TAG, "Current Hash: " + bytesToHex(this.handshakeHash!!))

        val keyLength = cipher!!.keyLength
        val tempKey = ByteArray(keyLength)
        try {
            hkdf(
                ck!!,
                0,
                ck!!.size,
                data,
                offset,
                length,
                ck!!,
                0,
                ck!!.size,
                tempKey,
                0,
                keyLength
            )
            cipher!!.initializeKey(tempKey, 0)
        } finally {
            destroy(tempKey)
        }


        // LOGGING: After mixKey operation (matching iOS)
        Log.d(TAG, "*** Android mixKey() AFTER ***")
        Log.d(TAG, "New CK: " + bytesToHex(ck!!))
        Log.d(TAG, "Hash unchanged: " + bytesToHex(this.handshakeHash!!))
        Log.d(TAG, "Cipher now has key: " + (cipher!!.macLength > 0))
    }

    /**
     * Mixes data into the handshake hash.
     * 
     * @param data The buffer containing the data to mix in.
     * @param offset The offset of the first data byte to mix in.
     * @param length The number of bytes to mix in.
     */
    fun mixHash(data: ByteArray, offset: Int, length: Int) {
        // LOGGING: Before mixHash operation (matching iOS)
        val inputData = ByteArray(length)
        data.copyInto(inputData, 0, offset, offset + length)
        Log.d(TAG, "*** Android mixHash() BEFORE ***")
        Log.d(TAG, "Input data ($length bytes): " + bytesToHex(inputData))
        Log.d(TAG, "Current Hash: " + bytesToHex(this.handshakeHash!!))

        val h = handshakeHash!!
        hashTwo(
            h, 0, h.size, data, offset, length,
            h, 0, h.size
        )


        // LOGGING: After mixHash operation (matching iOS)
        Log.d(TAG, "*** Android mixHash() AFTER ***")
        Log.d(TAG, "New Hash: " + bytesToHex(this.handshakeHash!!))
    }

    /**
     * Mixes a pre-shared key into the chaining key and handshake hash.
     * 
     * @param key The pre-shared key value.
     */
    fun mixPreSharedKey(key: ByteArray) {
        val temp = ByteArray(hash!!.digestLength)
        try {
            hkdf(ck!!, 0, ck!!.size, key, 0, key.size, ck!!, 0, ck!!.size, temp, 0, temp.size)
            mixHash(temp, 0, temp.size)
        } finally {
            destroy(temp)
        }
    }

    /**
     * Mixes a pre-supplied public key into the handshake hash.
     * 
     * @param dh The object containing the public key.
     */
    fun mixPublicKey(dh: DHState) {
        val temp = ByteArray(dh.publicKeyLength)
        try {
            dh.getPublicKey(temp, 0)
            mixHash(temp, 0, temp.size)
        } finally {
            destroy(temp)
        }
    }

    /**
     * Mixes a pre-supplied public key into the chaining key.
     * 
     * @param dh The object containing the public key.
     */
    fun mixPublicKeyIntoCK(dh: DHState) {
        val temp = ByteArray(dh.publicKeyLength)
        try {
            dh.getPublicKey(temp, 0)
            mixKey(temp, 0, temp.size)
        } finally {
            destroy(temp)
        }
    }

    /**
     * Encrypts a block of plaintext and mixes the ciphertext into the handshake hash.
     * 
     * @param plaintext The buffer containing the plaintext to encrypt.
     * @param plaintextOffset The offset within the plaintext buffer of the
     * first byte or plaintext data.
     * @param ciphertext The buffer to place the ciphertext in.  This can
     * be the same as the plaintext buffer.
     * @param ciphertextOffset The first offset within the ciphertext buffer
     * to place the ciphertext and the MAC tag.
     * @param length The length of the plaintext.
     * @return The length of the ciphertext plus the MAC tag.
     * 
     * @throws ShortBufferException There is not enough space in the
     * ciphertext buffer for the encrypted data plus MAC value.
     * 
     * The plaintext and ciphertext buffers can be the same for in-place
     * encryption.  In that case, plaintextOffset must be identical to
     * ciphertextOffset.
     * 
     * There must be enough space in the ciphertext buffer to accomodate
     * length + getMACLength() bytes of data starting at ciphertextOffset.
     */
    @Throws(ShortBufferException::class)
    fun encryptAndHash(
        plaintext: ByteArray,
        plaintextOffset: Int,
        ciphertext: ByteArray,
        ciphertextOffset: Int,
        length: Int
    ): Int {
        val ciphertextLength = cipher!!.encryptWithAd(
            this.handshakeHash,
            plaintext,
            plaintextOffset,
            ciphertext,
            ciphertextOffset,
            length
        )
        mixHash(ciphertext, ciphertextOffset, ciphertextLength)
        return ciphertextLength
    }

    /**
     * Decrypts a block of ciphertext and mixes it into the handshake hash.
     * 
     * @param ciphertext The buffer containing the ciphertext to decrypt.
     * @param ciphertextOffset The offset within the ciphertext buffer of
     * the first byte of ciphertext data.
     * @param plaintext The buffer to place the plaintext in.  This can be
     * the same as the ciphertext buffer.
     * @param plaintextOffset The first offset within the plaintext buffer
     * to place the plaintext.
     * @param length The length of the incoming ciphertext plus the MAC tag.
     * @return The length of the plaintext with the MAC tag stripped off.
     * 
     * @throws ShortBufferException There is not enough space in the plaintext
     * buffer for the decrypted data.
     * 
     * @throws BadPaddingException The MAC value failed to verify.
     * 
     * The plaintext and ciphertext buffers can be the same for in-place
     * decryption.  In that case, ciphertextOffset must be identical to
     * plaintextOffset.
     */
    @Throws(ShortBufferException::class, BadPaddingException::class)
    fun decryptAndHash(
        ciphertext: ByteArray,
        ciphertextOffset: Int,
        plaintext: ByteArray,
        plaintextOffset: Int,
        length: Int
    ): Int {
        val h = handshakeHash!!
        h.copyInto(prevH!!, 0, 0, h.size)
        mixHash(ciphertext, ciphertextOffset, length)
        return cipher!!.decryptWithAd(
            prevH,
            ciphertext,
            ciphertextOffset,
            plaintext,
            plaintextOffset,
            length
        )
    }

    /**
     * Splits the symmetric state into two ciphers for session encryption,
     * and optionally mixes in a secondary symmetric key.
     *
     * @param secondaryKey The buffer containing the secondary key.
     * @param offset The offset of the first secondary key byte.
     * @param length The length of the secondary key in bytes, which
     * must be either 0 or 32.
     * @return The pair of ciphers for sending and receiving.
     *
     * @throws IllegalArgumentException The length is not 0 or 32.
     */
    @JvmOverloads
    fun split(
        secondaryKey: ByteArray? = ByteArray(0),
        offset: Int = 0,
        length: Int = 0
    ): CipherStatePair {
        require(!(length != 0 && length != 32)) { "Secondary keys must be 0 or 32 bytes in length" }
        val keyLength = cipher!!.keyLength
        val k1 = ByteArray(keyLength)
        val k2 = ByteArray(keyLength)
        try {
            // When length == 0 the buffer is unused by HKDF; substitute an
            // empty array so the helper signature can stay non-null.
            val sk = secondaryKey ?: ByteArray(0)
            hkdf(ck!!, 0, ck!!.size, sk, offset, length, k1, 0, k1.size, k2, 0, k2.size)
            var c1: CipherState? = null
            var c2: CipherState? = null
            var pair: CipherStatePair? = null
            try {
                c1 = cipher!!.fork(k1, 0)
                c2 = cipher!!.fork(k2, 0)
                pair = CipherStatePair(c1, c2)
            } finally {
                if (c1 == null || c2 == null || pair == null) {
                    // Could not create some of the objects.  Clean up the others
                    // to avoid accidental leakage of k1 or k2.
                    c1?.destroy()
                    c2?.destroy()
                    pair = null
                }
            }
            return pair!!
        } finally {
            destroy(k1)
            destroy(k2)
        }
    }

    override fun destroy() {
        if (cipher != null) {
            cipher!!.destroy()
            cipher = null
        }
        if (hash != null) {
            // The built-in fallback implementations are destroyable.
            // JCA/JCE implementations aren't, so try reset() instead.
            if (hash is Destroyable) (hash as Destroyable).destroy()
            else hash!!.reset()
            hash = null
        }
        if (ck != null) {
            Noise.destroy(ck!!)
            ck = null
        }
        if (this.handshakeHash != null) {
            Noise.destroy(this.handshakeHash!!)
            this.handshakeHash = null
        }
        if (prevH != null) {
            Noise.destroy(prevH!!)
            prevH = null
        }
    }

    /**
     * Hashes a single data buffer.
     * 
     * @param data The buffer containing the data to hash.
     * @param offset Offset into the data buffer of the first byte to hash.
     * @param length Length of the data to be hashed.
     * @param output The buffer to receive the output hash value.
     * @param outputOffset Offset into the output buffer to place the hash value.
     * @param outputLength The length of the hash output.
     * 
     * The output buffer can be the same as the input data buffer.
     */
    private fun hashOne(
        data: ByteArray,
        offset: Int,
        length: Int,
        output: ByteArray,
        outputOffset: Int,
        outputLength: Int
    ) {
        hash!!.reset()
        hash!!.update(data, offset, length)
        try {
            hash!!.digest(output, outputOffset, outputLength)
        } catch (_: DigestException) {
            output.fill(0, outputOffset, outputLength)
        }
    }

    /**
     * Hashes two data buffers.
     * 
     * @param data1 The buffer containing the first data to hash.
     * @param offset1 Offset into the first data buffer of the first byte to hash.
     * @param length1 Length of the first data to be hashed.
     * @param data2 The buffer containing the second data to hash.
     * @param offset2 Offset into the second data buffer of the first byte to hash.
     * @param length2 Length of the second data to be hashed.
     * @param output The buffer to receive the output hash value.
     * @param outputOffset Offset into the output buffer to place the hash value.
     * @param outputLength The length of the hash output.
     * 
     * The output buffer can be same as either of the input buffers.
     */
    private fun hashTwo(
        data1: ByteArray, offset1: Int, length1: Int,
        data2: ByteArray, offset2: Int, length2: Int,
        output: ByteArray, outputOffset: Int, outputLength: Int
    ) {
        hash!!.reset()
        hash!!.update(data1, offset1, length1)
        hash!!.update(data2, offset2, length2)
        try {
            hash!!.digest(output, outputOffset, outputLength)
        } catch (_: DigestException) {
            output.fill(0, outputOffset, outputLength)
        }
    }

    /**
     * Computes a HMAC value using key and data values.
     * 
     * @param key The buffer that contains the key.
     * @param keyOffset The offset of the key in the key buffer.
     * @param keyLength The length of the key in bytes.
     * @param data The buffer that contains the data.
     * @param dataOffset The offset of the data in the data buffer.
     * @param dataLength The length of the data in bytes.
     * @param output The output buffer to place the HMAC value in.
     * @param outputOffset Offset into the output buffer for the HMAC value.
     * @param outputLength The length of the HMAC output.
     */
    private fun hmac(
        key: ByteArray, keyOffset: Int, keyLength: Int,
        data: ByteArray, dataOffset: Int, dataLength: Int,
        output: ByteArray, outputOffset: Int, outputLength: Int
    ) {
        // In all of the algorithms of interest to us, the block length
        // is twice the size of the hash length.
        val hashLength = hash!!.digestLength
        val blockLength = hashLength * 2
        val block = ByteArray(blockLength)
        try {
            if (keyLength <= blockLength) {
                System.arraycopy(key, keyOffset, block, 0, keyLength)
                block.fill(0, keyLength, blockLength)
            } else {
                hash!!.reset()
                hash!!.update(key, keyOffset, keyLength)
                hash!!.digest(block, 0, hashLength)
                block.fill(0, hashLength, blockLength)
            }
            for (i in 0 until blockLength) {
                block[i] = (block[i].toInt() xor 0x36).toByte()
            }
            hash!!.reset()
            hash!!.update(block, 0, blockLength)
            hash!!.update(data, dataOffset, dataLength)
            hash!!.digest(output, outputOffset, hashLength)
            for (i in 0 until blockLength) {
                block[i] = (block[i].toInt() xor (0x36 xor 0x5C)).toByte()
            }
            hash!!.reset()
            hash!!.update(block, 0, blockLength)
            hash!!.update(output, outputOffset, hashLength)
            hash!!.digest(output, outputOffset, outputLength)
        } catch (_: DigestException) {
            output.fill(0, outputOffset, outputLength)
        } finally {
            destroy(block)
        }
    }

    /**
     * Computes a HKDF value.
     * 
     * @param key The buffer that contains the key.
     * @param keyOffset The offset of the key in the key buffer.
     * @param keyLength The length of the key in bytes.
     * @param data The buffer that contains the data.
     * @param dataOffset The offset of the data in the data buffer.
     * @param dataLength The length of the data in bytes.
     * @param output1 The first output buffer.
     * @param output1Offset Offset into the first output buffer.
     * @param output1Length Length of the first output which can be
     * less than the hash length.
     * @param output2 The second output buffer.
     * @param output2Offset Offset into the second output buffer.
     * @param output2Length Length of the second output which can be
     * less than the hash length.
     */
    private fun hkdf(
        key: ByteArray, keyOffset: Int, keyLength: Int,
        data: ByteArray, dataOffset: Int, dataLength: Int,
        output1: ByteArray, output1Offset: Int, output1Length: Int,
        output2: ByteArray, output2Offset: Int, output2Length: Int
    ) {
        val hashLength = hash!!.digestLength
        val tempKey = ByteArray(hashLength)
        val tempHash = ByteArray(hashLength + 1)
        try {
            hmac(key, keyOffset, keyLength, data, dataOffset, dataLength, tempKey, 0, hashLength)
            tempHash[0] = 0x01.toByte()
            hmac(tempKey, 0, hashLength, tempHash, 0, 1, tempHash, 0, hashLength)
            System.arraycopy(tempHash, 0, output1, output1Offset, output1Length)
            tempHash[hashLength] = 0x02.toByte()
            hmac(tempKey, 0, hashLength, tempHash, 0, hashLength + 1, tempHash, 0, hashLength)
            System.arraycopy(tempHash, 0, output2, output2Offset, output2Length)
        } finally {
            destroy(tempKey)
            destroy(tempHash)
        }
    }

    companion object {
        private const val TAG = "AndroidSymmetric"

        /**
         * Converts a byte array to hex string for logging (matching iOS hex format)
         */
        private fun bytesToHex(bytes: ByteArray): String {
            val sb = StringBuilder()
            for (b in bytes) {
                sb.append("%02x".format(b))
            }
            return sb.toString()
        }
    }
}
