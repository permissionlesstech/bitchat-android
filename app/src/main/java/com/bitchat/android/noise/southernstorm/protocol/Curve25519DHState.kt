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

import com.bitchat.android.noise.southernstorm.crypto.Curve25519
import com.bitchat.android.noise.southernstorm.protocol.Noise.destroy
import com.bitchat.android.noise.southernstorm.protocol.Noise.random

/**
 * Implementation of the Curve25519 algorithm for the Noise protocol.
 */
internal class Curve25519DHState : DHState {
    private val publicKey: ByteArray = ByteArray(32)
    private val privateKey: ByteArray = ByteArray(32)
    private var mode = 0

    override fun destroy() {
        clearKey()
    }

    override val dhName: String get() = "25519"
    override val publicKeyLength: Int get() = 32
    override val privateKeyLength: Int get() = 32
    override val sharedKeyLength: Int get() = 32

    override fun generateKeyPair() {
        random(privateKey)
        Curve25519.eval(publicKey, 0, privateKey, null)
        mode = 0x03
    }

    override fun getPublicKey(key: ByteArray, offset: Int) {
        System.arraycopy(publicKey, 0, key, offset, 32)
    }

    override fun setPublicKey(key: ByteArray, offset: Int) {
        System.arraycopy(key, offset, publicKey, 0, 32)
        privateKey.fill(0)
        mode = 0x01
    }

    override fun getPrivateKey(key: ByteArray, offset: Int) {
        System.arraycopy(privateKey, 0, key, offset, 32)
    }

    override fun setPrivateKey(key: ByteArray, offset: Int) {
        System.arraycopy(key, offset, privateKey, 0, 32)
        Curve25519.eval(publicKey, 0, privateKey, null)
        mode = 0x03
    }

    override fun setToNullPublicKey() {
        publicKey.fill(0)
        privateKey.fill(0)
        mode = 0x01
    }

    override fun clearKey() {
        destroy(publicKey)
        destroy(privateKey)
        mode = 0
    }

    override fun hasPublicKey(): Boolean = (mode and 0x01) != 0

    override fun hasPrivateKey(): Boolean = (mode and 0x02) != 0

    override fun isNullPublicKey(): Boolean {
        if ((mode and 0x01) == 0) return false
        var temp = 0
        for (index in 0..31) temp = temp or publicKey[index].toInt()
        return temp == 0
    }

    override fun calculate(sharedKey: ByteArray, offset: Int, publicDH: DHState) {
        require(publicDH is Curve25519DHState) { "Incompatible DH algorithms" }
        Curve25519.eval(sharedKey, offset, privateKey, publicDH.publicKey)
    }

    override fun copyFrom(other: DHState) {
        check(other is Curve25519DHState) { "Mismatched DH key objects" }
        if (other === this) return
        System.arraycopy(other.privateKey, 0, privateKey, 0, 32)
        System.arraycopy(other.publicKey, 0, publicKey, 0, 32)
        mode = other.mode
    }
}
