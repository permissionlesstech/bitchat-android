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

import com.bitchat.android.noise.southernstorm.crypto.ChaChaCore;
import com.bitchat.android.noise.southernstorm.crypto.Poly1305;

/**
 * Implements the ChaChaPoly cipher for Noise.
 */
class ChaChaPolyCipherState implements CipherState {

	private Poly1305 poly;
	private int[] input;
	private int[] output;
	private byte[] polyKey;
	long n;
	private boolean haskey;
	
	/**
	 * Constructs a new cipher state for the "ChaChaPoly" algorithm.
	 */
	public ChaChaPolyCipherState()
	{
		poly = new Poly1305();
		input = new int [16];
		output = new int [16];
		polyKey = new byte [32];
		n = 0;
		haskey = false;
	}

	@Override
	public void destroy() {
		poly.destroy();
		Arrays.fill(input, 0);
		Arrays.fill(output, 0);
		Noise.destroy(polyKey);
	}

	@Override
	public String getCipherName() {
		return "ChaChaPoly";
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
		ChaChaCore.initKey256(input, key, offset);
		n = 0;
		haskey = true;
	}

	@Override
	public boolean hasKey() {
		return haskey;
	}

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
	private static void xorBlock(byte[] input, int inputOffset, byte[] output, int outputOffset, int length, int[] block)
	{
		int posn = 0;
		int value;
		while (length >= 4) {
			value = block[posn++];
			output[outputOffset] = (byte)(input[inputOffset] ^ value);
			output[outputOffset + 1] = (byte)(input[inputOffset + 1] ^ (value >> 8));
			output[outputOffset + 2] = (byte)(input[inputOffset + 2] ^ (value >> 16));
			output[outputOffset + 3] = (byte)(input[inputOffset + 3] ^ (value >> 24));
			inputOffset += 4;
			outputOffset += 4;
			length -= 4;
		}
		if (length == 3) {
			value = block[posn];
			output[outputOffset] = (byte)(input[inputOffset] ^ value);
			output[outputOffset + 1] = (byte)(input[inputOffset + 1] ^ (value >> 8));
			output[outputOffset + 2] = (byte)(input[inputOffset + 2] ^ (value >> 16));
		} else if (length == 2) {
			value = block[posn];
			output[outputOffset] = (byte)(input[inputOffset] ^ value);
			output[outputOffset + 1] = (byte)(input[inputOffset + 1] ^ (value >> 8));
		} else if (length == 1) {
			value = block[posn];
			output[outputOffset] = (byte)(input[inputOffset] ^ value);
		}
	}
	
	/**
	 * Set up to encrypt or decrypt the next packet.
	 * 
	 * @param ad The associated data for the packet.
	 */
	private void setup(byte[] ad)
	{
		if (n == -1L)
			throw new IllegalStateException("Nonce has wrapped around");
		ChaChaCore.initIV(input, n++);
		ChaChaCore.hash(output, input);
		Arrays.fill(polyKey, (byte)0);
		xorBlock(polyKey, 0, polyKey, 0, 32, output);
		poly.reset(polyKey, 0);
		if (ad != null) {
			poly.update(ad, 0, ad.length);
			poly.pad();
		}
		if (++(input[12]) == 0)
			++(input[13]);
	}

	/**
	 * Puts a 64-bit integer into a buffer in little-endian order.
	 * 
	 * @param output The output buffer.
	 * @param offset The offset into the output buffer.
	 * @param value The 64-bit integer value.
	 */
	private static void putLittleEndian64(byte[] output, int offset, long value)
	{
		output[offset] = (byte)value;
		output[offset + 1] = (byte)(value >> 8);
		output[offset + 2] = (byte)(value >> 16);
		output[offset + 3] = (byte)(value >> 24);
		output[offset + 4] = (byte)(value >> 32);
		output[offset + 5] = (byte)(value >> 40);
		output[offset + 6] = (byte)(value >> 48);
		output[offset + 7] = (byte)(value >> 56);
	}

	/**
	 * Finishes up the authentication tag for a packet.
	 * 
	 * @param ad The associated data.
	 * @param length The length of the plaintext data.
	 */
	private void finish(byte[] ad, int length)
	{
		poly.pad();
		putLittleEndian64(polyKey, 0, ad != null ? ad.length : 0);
		putLittleEndian64(polyKey, 8, length);
		poly.update(polyKey, 0, 16);
		poly.finish(polyKey, 0);
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
	private void encrypt(byte[] plaintext, int plaintextOffset,
			byte[] ciphertext, int ciphertextOffset, int length) {
		while (length > 0) {
			int tempLen = 64;
			if (tempLen > length)
				tempLen = length;
			ChaChaCore.hash(output, input);
			xorBlock(plaintext, plaintextOffset, ciphertext, ciphertextOffset, tempLen, output);
			if (++(input[12]) == 0)
				++(input[13]);
			plaintextOffset += tempLen;
			ciphertextOffset += tempLen;
			length -= tempLen;
		}
	}

	@Override
	public int encryptWithAd(byte[] ad, byte[] plaintext, int plaintextOffset,
			byte[] ciphertext, int ciphertextOffset, int length) throws ShortBufferException {
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
		encrypt(plaintext, plaintextOffset, ciphertext, ciphertextOffset, length);
		poly.update(ciphertext, ciphertextOffset, length);
		finish(ad, length);
		System.arraycopy(polyKey, 0, ciphertext, ciphertextOffset + length, 16);
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
		poly.update(ciphertext, ciphertextOffset, dataLen);
		finish(ad, dataLen);
		int temp = 0;
		for (int index = 0; index < 16; ++index)
			temp |= (polyKey[index] ^ ciphertext[ciphertextOffset + dataLen + index]);
		if ((temp & 0xFF) != 0)
			Noise.throwBadTagException();
		encrypt(ciphertext, ciphertextOffset, plaintext, plaintextOffset, dataLen);
		return dataLen;
	}

	@Override
	public CipherState fork(byte[] key, int offset) {
		CipherState cipher = new ChaChaPolyCipherState();
		cipher.initializeKey(key, offset);
		return cipher;
	}

	@Override
	public void setNonce(long nonce) {
		n = nonce;
	}
}
