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
import java.util.Arrays

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
    private var keyType: KeyType

    /**
     * Special version of NewHopeTor that allows explicit random data
     * to be specified for test vectors.
     */
    private inner class NewHopeWithPrivateKey(var randomData: ByteArray) : NewHopeTor() {
        override fun randombytes(buffer: ByteArray) {
            System.arraycopy(randomData, 0, buffer, 0, buffer.size)
        }
    }

    /**
     * Constructs a new key exchange object for New Hope.
     */
    init {
        keyType = KeyType.None
    }

    private val isAlice: Boolean
        get() = keyType == KeyType.AlicePrivate || keyType == KeyType.AlicePublic

    override fun destroy() {
        clearKey()
    }

    override fun getDHName(): String {
        return "NewHope"
    }

    override fun getPublicKeyLength(): Int {
        if (this.isAlice) return NewHope.SENDABYTES
        else return NewHope.SENDBBYTES
    }

    override fun getPrivateKeyLength(): Int {
        // New Hope doesn't have private keys in the same sense as
        // Curve25519 and Curve448.  Instead return the number of
        // random bytes that we need to generate each key type.
        if (this.isAlice) return 64
        else return 32
    }

    override fun getSharedKeyLength(): Int {
        return NewHope.SHAREDBYTES
    }

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
        } else check(remote is NewHopeDHState) { "Mismatched DH objects" }
        val r = remote
        if (r.isAlice && r.publicKey != null) {
            // We have a remote public key for Alice, so generate in Bob mode.
            clearKey()
            keyType = KeyType.BobCalculated
            nh = NewHopeTor()
            publicKey = ByteArray(NewHope.SENDBBYTES)
            privateKey = ByteArray(NewHope.SHAREDBYTES)
            nh!!.sharedb(privateKey!!, 0, publicKey!!, 0, r.publicKey!!, 0)
        } else {
            generateKeyPair()
        }
    }

    override fun getPublicKey(key: ByteArray, offset: Int) {
        if (publicKey != null) System.arraycopy(publicKey, 0, key, offset, getPublicKeyLength())
        else Arrays.fill(key, 0, getPublicKeyLength(), 0.toByte())
    }

    override fun setPublicKey(key: ByteArray, offset: Int) {
        if (publicKey != null) Noise.destroy(publicKey!!)
        publicKey = ByteArray(getPublicKeyLength())
        System.arraycopy(key, 0, publicKey, 0, publicKey!!.size)
    }

    override fun getPrivateKey(key: ByteArray, offset: Int) {
        if (privateKey != null) System.arraycopy(privateKey, 0, key, offset, getPrivateKeyLength())
        else Arrays.fill(key, 0, getPrivateKeyLength(), 0.toByte())
    }

    override fun setPrivateKey(key: ByteArray, offset: Int) {
        clearKey()
        // Guess the key type from the length of the test data.
        if (offset == 0 && key.size == 64) keyType = KeyType.AlicePrivate
        else keyType = KeyType.BobPrivate
        privateKey = ByteArray(getPrivateKeyLength())
        System.arraycopy(key, 0, privateKey, 0, privateKey!!.size)
    }

    override fun setToNullPublicKey() {
        // Null public keys are not supported by New Hope.
        // Destroy the current values but otherwise ignore.
        clearKey()
    }

    override fun clearKey() {
        if (nh != null) {
            nh!!.destroy()
            nh = null
        }
        if (publicKey != null) {
            Noise.destroy(publicKey!!)
            publicKey = null
        }
        if (privateKey != null) {
            Noise.destroy(privateKey!!)
            privateKey = null
        }
        keyType = KeyType.None
    }

    override fun hasPublicKey(): Boolean {
        return publicKey != null
    }

    override fun hasPrivateKey(): Boolean {
        return privateKey != null
    }

    override fun isNullPublicKey(): Boolean {
        return false
    }

    override fun calculate(sharedKey: ByteArray, offset: Int, publicDH: DHState?) {
        require(publicDH is NewHopeDHState) { "Incompatible DH algorithms" }
        val other = publicDH
        if (keyType == KeyType.AlicePrivate) {
            // Compute the shared key for Alice.
            nh!!.shareda(sharedKey, 0, other.publicKey!!, 0)
        } else if (keyType == KeyType.BobCalculated) {
            // The shared key for Bob was already computed when the key was generated.
            System.arraycopy(privateKey, 0, sharedKey, 0, NewHope.SHAREDBYTES)
        } else {
            throw IllegalStateException("Cannot calculate with this DH object")
        }
    }

    override fun copyFrom(other: DHState?) {
        check(other is NewHopeDHState) { "Mismatched DH key objects" }
        if (other === this) return
        val dh = other
        clearKey()
        when (dh.keyType) {
            KeyType.None -> {}
            KeyType.AlicePrivate -> if (dh.privateKey != null) {
                keyType = KeyType.AlicePrivate
                privateKey = ByteArray(dh.privateKey!!.size)
                System.arraycopy(dh.privateKey, 0, privateKey, 0, privateKey!!.size)
            } else {
                throw IllegalStateException("Cannot copy generated key for Alice")
            }

            KeyType.BobPrivate, KeyType.BobCalculated -> throw IllegalStateException("Cannot copy private key for Bob without public key for Alice")

            KeyType.AlicePublic, KeyType.BobPublic -> {
                keyType = dh.keyType
                publicKey = ByteArray(dh.publicKey!!.size)
                System.arraycopy(dh.publicKey, 0, publicKey, 0, publicKey!!.size)
            }
        }
    }

    override fun copyFrom(other: DHState?, remote: DHState?) {
        if (remote == null) {
            copyFrom(other)
            return
        }
        check(!(other !is NewHopeDHState || remote !is NewHopeDHState)) { "Mismatched DH key objects" }
        if (other === this) return
        val dh = other
        val remotedh = remote
        clearKey()
        when (dh.keyType) {
            KeyType.None -> {}
            KeyType.AlicePrivate -> if (dh.privateKey != null) {
                // Generate Alice's public and private key now.
                keyType = KeyType.AlicePrivate
                nh = NewHopeWithPrivateKey(dh.privateKey!!)
                publicKey = ByteArray(NewHope.SENDABYTES)
                nh!!.keygen(publicKey!!, 0)
            } else {
                throw IllegalStateException("Cannot copy generated key for Alice")
            }

            KeyType.BobPrivate -> if (dh.privateKey != null && remotedh.keyType == KeyType.AlicePublic) {
                // Now we know the public key for Alice, we can calculate Bob's public and shared keys.
                keyType = KeyType.BobCalculated
                nh = NewHopeWithPrivateKey(dh.privateKey!!)
                publicKey = ByteArray(NewHope.SENDBBYTES)
                privateKey = ByteArray(NewHope.SHAREDBYTES)
                nh!!.sharedb(privateKey!!, 0, publicKey!!, 0, remotedh.publicKey!!, 0)
            } else {
                throw IllegalStateException("Cannot copy private key for Bob without public key for Alice")
            }

            KeyType.BobCalculated -> throw IllegalStateException("Cannot copy generated key for Bob")

            KeyType.AlicePublic, KeyType.BobPublic -> {
                keyType = dh.keyType
                publicKey = ByteArray(dh.publicKey!!.size)
                System.arraycopy(dh.publicKey, 0, publicKey, 0, publicKey!!.size)
            }
        }
    }

    override fun specifyPeer(local: DHState?) {
        if (local !is NewHopeDHState) return
        clearKey()
        if (local.keyType == KeyType.AlicePrivate) keyType = KeyType.BobPublic
        else keyType = KeyType.AlicePublic
    }
}
