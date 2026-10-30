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

import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.ShortBufferException;

import com.bitchat.android.noise.southernstorm.crypto.GHASH;
import com.bitchat.android.noise.southernstorm.crypto.RijndaelAES;

/**
 * Fallback implementation of "AESGCM" on platforms where
 * the JCA/JCE does not have a suitable GCM or CTR provider.
 */
class AESGCMFallbackCipherState implements CipherState {

	private RijndaelAES aes;
	private long n;
	private byte[] iv;
	private byte[] enciv;
	private byte[] hashKey;
	private GHASH ghash;
	private boolean haskey;

	/**
	 * Constructs a new cipher state for the "AESGCM" algorithm.
	 */
	public AESGCMFallbackCipherState()
	{
		aes = new RijndaelAES();
		n = 0;
		iv = new byte [16];
		enciv = new byte [16];
		hashKey = new byte [16];
		ghash = new GHASH();
		haskey = false;
	}

	@Override
	public void destroy() {
		aes.destroy();
		ghash.destroy();
		Noise.destroy(hashKey);
		Noise.destroy(iv);
		Noise.destroy(enciv);
	}

	@Override
	public String getCipherName() {
		return "AESGCM";
	}

	@Override
	public int getKeyLength() {
		return 32;
	}

	@Override
	public int getMACLength() {
		return haskey ? 16 : 0;
	}

	@Override
	public void initializeKey(byte[] key, int offset) {
		// Set up the AES key.
		aes.setupEnc(key, offset, 256);
		haskey = true;

		// Generate the hashing key by encrypting a block of zeroes.
		Arrays.fill(hashKey, (byte)0);
		aes.encrypt(hashKey, 0, hashKey, 0);
		ghash.reset(hashKey, 0);
		
		// Reset the nonce.
		n = 0;
	}

	@Override
	public boolean hasKey() {
		return haskey;
	}
	
	/**
	 * Set up to encrypt or decrypt the next packet.
	 * 
	 * @param ad The associated data for the packet.
	 */
	private void setup(byte[] ad)
	{
		// Check for nonce wrap-around.
		if (n == -1L)
			throw new IllegalStateException("Nonce has wrapped around");
		
		// Format the counter/IV block.
		iv[0] = 0;
		iv[1] = 0;
		iv[2] = 0;
		iv[3] = 0;
		iv[4] = (byte)(n >> 56);
		iv[5] = (byte)(n >> 48);
		iv[6] = (byte)(n >> 40);
		iv[7] = (byte)(n >> 32);
		iv[8] = (byte)(n >> 24);
		iv[9] = (byte)(n >> 16);
		iv[10] = (byte)(n >> 8);
		iv[11] = (byte)n;
		iv[12] = 0;
		iv[13] = 0;
		iv[14] = 0;
		iv[15] = 1;
		++n;
		
		// Encrypt a block of zeroes to generate the hash key to XOR
		// the GHASH tag with at the end of the encrypt/decrypt operation.
		Arrays.fill(hashKey, (byte)0);
		aes.encrypt(iv, 0, hashKey, 0);
		
		// Initialize the GHASH with the associated data value.
		ghash.reset();
		if (ad != null) {
			ghash.update(ad, 0, ad.length);
			ghash.pad();
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
	private void encryptCTR(byte[] plaintext, int plaintextOffset, byte[] ciphertext, int ciphertextOffset, int length)
	{
		while (length > 0) {
			// Increment the IV and encrypt it to get the next keystream block.
			if (++(iv[15]) == 0)
				if (++(iv[14]) == 0)
					if (++(iv[13]) == 0)
						++(iv[12]);
			aes.encrypt(iv, 0, enciv, 0);
			
			// XOR the keystream block with the plaintext to create the ciphertext.
			int temp = length;
			if (temp > 16)
				temp = 16;
			for (int index = 0; index < temp; ++index)
				ciphertext[ciphertextOffset + index] = (byte)(plaintext[plaintextOffset + index] ^ enciv[index]);
			
			// Advance to the next block.
			plaintextOffset += temp;
			ciphertextOffset += temp;
			length -= temp;
		}
	}

	@Override
	public int encryptWithAd(byte[] ad, byte[] plaintext, int plaintextOffset,
			byte[] ciphertext, int ciphertextOffset, int length)
			throws ShortBufferException {
		int space;
		if (ciphertextOffset < 0 || ciphertextOffset > ciphertext.length)
			throw new IllegalArgumentException();
    if (length < 0 || plaintextOffset < 0 || plaintextOffset > plaintext.length || length > plaintext.length || (plaintext.length - plaintextOffset) < length)
			throw new IllegalArgumentException();
		space = ciphertext.length - ciphertextOffset;
		if (!haskey) {
			// The key is not set yet - return the plaintext as-is.
			if (length > space)
				throw new ShortBufferException();
			if (plaintext != ciphertext || plaintextOffset != ciphertextOffset)
				System.arraycopy(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
			return length;
		}
		if (space < 16 || length > (space - 16))
			throw new ShortBufferException();
		setup(ad);
		encryptCTR(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
		ghash.update(ciphertext, ciphertextOffset, length);
		ghash.pad(ad != null ? ad.length : 0, length);
		ghash.finish(ciphertext, ciphertextOffset + length, 16);
		for (int index = 0; index < 16; ++index)
			ciphertext[ciphertextOffset + length + index] ^= hashKey[index];
		return length + 16;
	}

	@Override
	public int decryptWithAd(byte[] ad, byte[] ciphertext,
			int ciphertextOffset, byte[] plaintext, int plaintextOffset,
			int length) throws ShortBufferException, BadPaddingException {
		int space;
		if (ciphertextOffset < 0 || ciphertextOffset > ciphertext.length)
			throw new IllegalArgumentException();
		else
			space = ciphertext.length - ciphertextOffset;
		if (length > space)
			throw new ShortBufferException();
		if (length < 0 || plaintextOffset < 0 || plaintextOffset > plaintext.length || length > ciphertext.length || (ciphertext.length - ciphertextOffset) < length)
			throw new IllegalArgumentException();
		space = plaintext.length - plaintextOffset;
		if (!haskey) {
			// The key is not set yet - return the ciphertext as-is.
			if (length > space)
				throw new ShortBufferException();
			if (plaintext != ciphertext || plaintextOffset != ciphertextOffset)
				System.arraycopy(ciphertext, ciphertextOffset, plaintext, plaintextOffset, length);
			return length;
		}
		if (length < 16)
			Noise.throwBadTagException();
		int dataLen = length - 16;
		if (dataLen > space)
			throw new ShortBufferException();
		setup(ad);
		ghash.update(ciphertext, ciphertextOffset, dataLen);
		ghash.pad(ad != null ? ad.length : 0, dataLen);
		ghash.finish(enciv, 0, 16);
		int temp = 0;
		for (int index = 0; index < 16; ++index)
			temp |= (hashKey[index] ^ enciv[index] ^ ciphertext[ciphertextOffset + dataLen + index]);
		if ((temp & 0xFF) != 0)
			Noise.throwBadTagException();
		encryptCTR(ciphertext, ciphertextOffset, plaintext, plaintextOffset, dataLen);
		return dataLen;
	}

	@Override
	public CipherState fork(byte[] key, int offset) {
		CipherState cipher;
		cipher = new AESGCMFallbackCipherState();
		cipher.initializeKey(key, offset);
		return cipher;
	}

	@Override
	public void setNonce(long nonce) {
		n = nonce;
	}
}
