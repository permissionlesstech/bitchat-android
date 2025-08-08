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

package com.bitchat.android.noise.southernstorm.protocol;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

/**
 * Interface to an authenticated cipher for use in the Noise protocol.
 *
 * CipherState objects are used to encrypt or decrypt data during a
 * session.  Once the handshake has completed, HandshakeState.split()
 * will create two CipherState objects for encrypting packets sent to
 * the other party, and decrypting packets received from the other party.
 */
public interface CipherState extends Destroyable {

	/**
	 * Gets the Noise protocol name for this cipher.
	 * 
	 * @return The cipher name.
	 */
	String getCipherName();

	/**
	 * Gets the length of the key values for this cipher.
	 * 
	 * @return The length of the key in bytes; usually 32.
	 */
	int getKeyLength();
	
	/**
	 * Gets the length of the MAC values for this cipher.
	 * 
	 * @return The length of MAC values in bytes, or zero if the
	 * key has not yet been initialized.
	 */
	int getMACLength();

	/**
	 * Initializes the key on this cipher object.
	 * 
	 * @param key Points to a buffer that contains the key.
	 * @param offset The offset of the key in the key buffer.
	 * 
	 * The key buffer must contain at least getKeyLength() bytes
	 * starting at offset.
	 * 
	 * @see #hasKey()
	 */
	void initializeKey(byte[] key, int offset);

	/**
	 * Determine if this cipher object has been configured with a key.
	 * 
	 * @return true if this cipher object has a key; false if the
	 * key has not yet been set with initializeKey().
	 * 
	 * @see #initializeKey(byte[], int)
	 */
	boolean hasKey();
	
	/**
	 * Encrypts a plaintext buffer using the cipher and a block of associated data.
	 * 
	 * @param ad The associated data, or null if there is none.
	 * @param plaintext The buffer containing the plaintext to encrypt.
	 * @param plaintextOffset The offset within the plaintext buffer of the
	 * first byte or plaintext data.
	 * @param ciphertext The buffer to place the ciphertext in.  This can
	 * be the same as the plaintext buffer.
	 * @param ciphertextOffset The first offset within the ciphertext buffer
	 * to place the ciphertext and the MAC tag.
	 * @param length The length of the plaintext.
	 * @return The length of the ciphertext plus the MAC tag, or -1 if the
	 * ciphertext buffer is not large enough to hold the result.
	 * 
	 * @throws ShortBufferException The ciphertext buffer does not have
	 * enough space to hold the ciphertext plus MAC.
	 * 
	 * @throws IllegalStateException The nonce has wrapped around.
	 * 
	 * @throws IllegalArgumentException One of the parameters is out of range.
	 *
	 * The plaintext and ciphertext buffers can be the same for in-place
	 * encryption.  In that case, plaintextOffset must be identical to
	 * ciphertextOffset.
	 * 
	 * There must be enough space in the ciphertext buffer to accomodate
	 * length + getMACLength() bytes of data starting at ciphertextOffset.
	 */
	int encryptWithAd(byte[] ad, byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException;

	/**
	 * Decrypts a ciphertext buffer using the cipher and a block of associated data.
	 * 
	 * @param ad The associated data, or null if there is none.
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
	 * @throws ShortBufferException The plaintext buffer does not have
	 * enough space to store the decrypted data.
	 * 
	 * @throws BadPaddingException The MAC value failed to verify.
	 * 
	 * @throws IllegalStateException The nonce has wrapped around.
	 * 
	 * @throws IllegalArgumentException One of the parameters is out of range.
	 *
	 * The plaintext and ciphertext buffers can be the same for in-place
	 * decryption.  In that case, ciphertextOffset must be identical to
	 * plaintextOffset.
	 */
	int decryptWithAd(byte[] ad, byte[] ciphertext, int ciphertextOffset, byte[] plaintext, int plaintextOffset, int length) throws ShortBufferException, BadPaddingException;

	/**
	 * Creates a new instance of this cipher and initializes it with a key.
	 * 
	 * @param key The buffer containing the key.
	 * @param offset The offset into the key buffer of the first key byte.
	 * @return A new CipherState of the same class as this one.
	 */
	CipherState fork(byte[] key, int offset);
	
	/**
	 * Sets the nonce value.
	 * 
	 * @param nonce The new nonce value, which must be greater than or equal
	 * to the current value.
	 * 
	 * This function is intended for testing purposes only.  If the nonce
	 * value goes backwards then security may be compromised.
	 */
	void setNonce(long nonce);
}
