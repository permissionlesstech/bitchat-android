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

import com.bitchat.android.noise.southernstorm.crypto.NewHope
import com.bitchat.android.noise.southernstorm.crypto.NewHopeTor

/**
 * Implementation of the New Hope post-quantum algorithm for the Noise protocol.
 */
internal class NewHopeDHState : DHStateHybrid {
    internal enum class KeyType {
        None,
        AlicePrivate,
        AlicePublic,
        BobPrivate,
        BobPublic,
        BobCalculated
    }

    private var nh: NewHopeTor? = null
    private var publicKey: ByteArray? = null
    private var privateKey: ByteArray? = null
    private var keyType: KeyType = KeyType.None

    /**
     * Special version of NewHopeTor that allows explicit random data
     * to be specified for test vectors.
     */
    private inner class NewHopeWithPrivateKey(private val randomData: ByteArray) : NewHopeTor() {
        override fun randombytes(buffer: ByteArray) {
            randomData.copyInto(buffer, 0, 0, buffer.size)
        }
    }

    private val isAlice: Boolean
        get() = keyType == KeyType.AlicePrivate || keyType == KeyType.AlicePublic

    override fun destroy() {
        clearKey()
    }

    override val dhName: String get() = "NewHope"

    override val publicKeyLength: Int
        get() = if (isAlice) NewHope.SENDABYTES else NewHope.SENDBBYTES

    override val privateKeyLength: Int
        // New Hope doesn't have private keys in the same sense as
        // Curve25519 and Curve448.  Instead return the number of
        // random bytes that we need to generate each key type.
        get() = if (isAlice) 64 else 32

    override val sharedKeyLength: Int get() = NewHope.SHAREDBYTES

    override fun generateKeyPair() {
        clearKey()
        keyType = KeyType.AlicePrivate
        nh = NewHopeTor()
        publicKey = ByteArray(NewHope.SENDABYTES)
        nh!!.keygen(publicKey!!, 0)
    }

    override fun generateKeyPair(remote: DHState?) {
        if (remote == null) {
            // No remote public key, so always generate in Alice mode.
            generateKeyPair()
            return
        }
        check(remote is NewHopeDHState) { "Mismatched DH objects" }
        if (remote.isAlice && remote.publicKey != null) {
            // We have a remote public key for Alice, so generate in Bob mode.
            clearKey()
            keyType = KeyType.BobCalculated
            nh = NewHopeTor()
            publicKey = ByteArray(NewHope.SENDBBYTES)
            privateKey = ByteArray(NewHope.SHAREDBYTES)
            nh!!.sharedb(privateKey!!, 0, publicKey!!, 0, remote.publicKey!!, 0)
        } else {
            generateKeyPair()
        }
    }

    override fun getPublicKey(key: ByteArray, offset: Int) {
        val pk = publicKey
        if (pk != null) pk.copyInto(key, offset, 0, publicKeyLength)
        else key.fill(0, offset, offset + publicKeyLength)
    }

    override fun setPublicKey(key: ByteArray, offset: Int) {
        publicKey?.let { Noise.destroy(it) }
        val len = publicKeyLength
        publicKey = key.copyOfRange(offset, offset + len)
    }

    override fun getPrivateKey(key: ByteArray, offset: Int) {
        val sk = privateKey
        if (sk != null) sk.copyInto(key, offset, 0, privateKeyLength)
        else key.fill(0, offset, offset + privateKeyLength)
    }

    override fun setPrivateKey(key: ByteArray, offset: Int) {
        clearKey()
        // Guess the key type from the length of the test data.
        keyType = if (offset == 0 && key.size == 64) KeyType.AlicePrivate else KeyType.BobPrivate
        val len = privateKeyLength
        privateKey = key.copyOfRange(offset, offset + len)
    }

    override fun setToNullPublicKey() {
        // Null public keys are not supported by New Hope.
        // Destroy the current values but otherwise ignore.
        clearKey()
    }

    override fun clearKey() {
        nh?.let {
            it.destroy()
            nh = null
        }
        publicKey?.let {
            Noise.destroy(it)
            publicKey = null
        }
        privateKey?.let {
            Noise.destroy(it)
            privateKey = null
        }
        keyType = KeyType.None
    }

    override fun hasPublicKey(): Boolean = publicKey != null

    override fun hasPrivateKey(): Boolean = privateKey != null

    override fun isNullPublicKey(): Boolean = false

    override fun calculate(sharedKey: ByteArray, offset: Int, publicDH: DHState) {
        require(publicDH is NewHopeDHState) { "Incompatible DH algorithms" }
        when (keyType) {
            KeyType.AlicePrivate -> {
                // Compute the shared key for Alice.
                // Note: matches original Java — offset is intentionally unused;
                // the shared key is always written starting at index 0.
                nh!!.shareda(sharedKey, 0, publicDH.publicKey!!, 0)
            }
            KeyType.BobCalculated -> {
                // The shared key for Bob was already computed when the key was generated.
                privateKey!!.copyInto(sharedKey, 0, 0, NewHope.SHAREDBYTES)
            }
            else -> throw IllegalStateException("Cannot calculate with this DH object")
        }
    }

    override fun copyFrom(other: DHState) {
        check(other is NewHopeDHState) { "Mismatched DH key objects" }
        if (other === this) return
        clearKey()
        when (other.keyType) {
            KeyType.None -> {}
            KeyType.AlicePrivate -> {
                val src = other.privateKey ?: throw IllegalStateException("Cannot copy generated key for Alice")
                keyType = KeyType.AlicePrivate
                privateKey = src.copyOf()
            }
            KeyType.BobPrivate, KeyType.BobCalculated ->
                throw IllegalStateException("Cannot copy private key for Bob without public key for Alice")
            KeyType.AlicePublic, KeyType.BobPublic -> {
                keyType = other.keyType
                publicKey = other.publicKey!!.copyOf()
            }
        }
    }

    override fun copyFrom(other: DHState, remote: DHState?) {
        if (remote == null) {
            copyFrom(other)
            return
        }
        check(other is NewHopeDHState && remote is NewHopeDHState) { "Mismatched DH key objects" }
        if (other === this) return
        clearKey()
        when (other.keyType) {
            KeyType.None -> {}
            KeyType.AlicePrivate -> {
                val src = other.privateKey ?: throw IllegalStateException("Cannot copy generated key for Alice")
                // Generate Alice's public and private key now.
                keyType = KeyType.AlicePrivate
                nh = NewHopeWithPrivateKey(src)
                publicKey = ByteArray(NewHope.SENDABYTES)
                nh!!.keygen(publicKey!!, 0)
            }
            KeyType.BobPrivate -> {
                val src = other.privateKey
                if (src != null && remote.keyType == KeyType.AlicePublic) {
                    // Now we know the public key for Alice, we can calculate Bob's public and shared keys.
                    keyType = KeyType.BobCalculated
                    nh = NewHopeWithPrivateKey(src)
                    publicKey = ByteArray(NewHope.SENDBBYTES)
                    privateKey = ByteArray(NewHope.SHAREDBYTES)
                    nh!!.sharedb(privateKey!!, 0, publicKey!!, 0, remote.publicKey!!, 0)
                } else {
                    throw IllegalStateException("Cannot copy private key for Bob without public key for Alice")
                }
            }
            KeyType.BobCalculated -> throw IllegalStateException("Cannot copy generated key for Bob")
            KeyType.AlicePublic, KeyType.BobPublic -> {
                keyType = other.keyType
                publicKey = other.publicKey!!.copyOf()
            }
        }
    }

    override fun specifyPeer(local: DHState?) {
        if (local !is NewHopeDHState) return
        clearKey()
        keyType = if (local.keyType == KeyType.AlicePrivate) KeyType.BobPublic else KeyType.AlicePublic
    }
}
