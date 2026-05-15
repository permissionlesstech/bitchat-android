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

/**
 * Interface to a Diffie-Hellman algorithm for the Noise protocol.
 */
interface DHState : Destroyable {
    /**
     * Gets the Noise protocol name for this Diffie-Hellman algorithm.
     * 
     * @return The algorithm name.
     */
    val dHName: String?

    /**
     * Gets the length of public keys for this algorithm.
     * 
     * @return The length of public keys in bytes.
     */
    val publicKeyLength: Int

    /**
     * Gets the length of private keys for this algorithm.
     * 
     * @return The length of private keys in bytes.
     */
    val privateKeyLength: Int

    /**
     * Gets the length of shared keys for this algorithm.
     * 
     * @return The length of shared keys in bytes.
     */
    val sharedKeyLength: Int

    /**
     * Generates a new random keypair.
     */
    fun generateKeyPair()

    /**
     * Gets the public key associated with this object.
     * 
     * @param key The buffer to copy the public key to.
     * @param offset The first offset in the key buffer to copy to.
     */
    fun getPublicKey(key: ByteArray?, offset: Int)

    /**
     * Sets the public key for this object.
     * 
     * @param key The buffer containing the public key.
     * @param offset The first offset in the buffer that contains the key.
     * 
     * If this object previously held a key pair, then this function
     * will change it into a public key only object.
     */
    fun setPublicKey(key: ByteArray?, offset: Int)

    /**
     * Gets the private key associated with this object.
     * 
     * @param key The buffer to copy the private key to.
     * @param offset The first offset in the key buffer to copy to.
     */
    fun getPrivateKey(key: ByteArray?, offset: Int)

    /**
     * Sets the private key for this object.
     * 
     * @param key The buffer containing the [rivate key.
     * @param offset The first offset in the buffer that contains the key.
     * 
     * If this object previously held only a public key, then
     * this function will change it into a key pair.
     */
    fun setPrivateKey(key: ByteArray?, offset: Int)

    /**
     * Sets this object to the null public key and clears the private key.
     */
    fun setToNullPublicKey()

    /**
     * Clears the key pair.
     */
    fun clearKey()

    /**
     * Determine if this object contains a public key.
     * 
     * @return Returns true if this object contains a public key,
     * or false if the public key has not yet been set.
     */
    fun hasPublicKey(): Boolean

    /**
     * Determine if this object contains a private key.
     * 
     * @return Returns true if this object contains a private key,
     * or false if the private key has not yet been set.
     */
    fun hasPrivateKey(): Boolean

    /**
     * Determine if the public key in this object is the special null value.
     * 
     * @return Returns true if the public key is the special null value,
     * or false otherwise.
     */
    val isNullPublicKey: Boolean

    /**
     * Performs a Diffie-Hellman calculation with this object as the private key.
     * 
     * @param sharedKey Buffer to put the shared key into.
     * @param offset Offset of the first byte for the shared key.
     * @param publicDH Object that contains the public key for the calculation.
     * 
     * @throws IllegalArgumentException The publicDH object is not the same
     * type as this object, or one of the objects does not contain a valid key.
     */
    fun calculate(sharedKey: ByteArray?, offset: Int, publicDH: DHState?)

    /**
     * Copies the key values from another DH object of the same type.
     * 
     * @param other The other DH object to copy from
     * 
     * @throws IllegalStateException The other DH object does not have
     * the same type as this object.
     */
    fun copyFrom(other: DHState?)
}
