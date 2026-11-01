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

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

import javax.crypto.BadPaddingException;

import com.bitchat.android.noise.southernstorm.crypto.Blake2bMessageDigest;
import com.bitchat.android.noise.southernstorm.crypto.Blake2sMessageDigest;
import com.bitchat.android.noise.southernstorm.crypto.SHA256MessageDigest;
import com.bitchat.android.noise.southernstorm.crypto.SHA512MessageDigest;

/**
 * Utility functions for the Noise protocol library.
 */
public final class Noise {

	/**
	 * Maximum length for Noise packets.
	 */
	public static final int MAX_PACKET_LEN = 65535;
	
	private static SecureRandom random = new SecureRandom();
	
	/**
	 * Generates random data using the system random number generator.
	 * 
	 * @param data The data buffer to fill with random data.
	 */
	public static void random(byte[] data)
	{
		random.nextBytes(data);
	}

	private static boolean forceFallbacks = false;
	
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
	public static void setForceFallbacks(boolean force)
	{
		forceFallbacks = force;
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
	public static DHState createDH(String name) throws NoSuchAlgorithmException
	{
		if (name.equals("25519"))
			return new Curve25519DHState();
		if (name.equals("448"))
			return new Curve448DHState();
		if (name.equals("NewHope"))
			return new NewHopeDHState();
		throw new NoSuchAlgorithmException("Unknown Noise DH algorithm name: " + name);
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
	public static CipherState createCipher(String name) throws NoSuchAlgorithmException
	{
		if (name.equals("AESGCM")) {
			if (forceFallbacks)
				return new AESGCMFallbackCipherState();
			// "AES/GCM/NoPadding" exists in some recent JDK's but it is flaky
			// to use and not easily back-portable to older Android versions.
			// We instead emulate AESGCM on top of "AES/CTR/NoPadding".
			try {
				return new AESGCMOnCtrCipherState();
			} catch (NoSuchAlgorithmException e1) {
				// Could not find anything useful in the JCA/JCE so
				// use the pure Java fallback implementation instead.
				return new AESGCMFallbackCipherState();
			}
		} else if (name.equals("ChaChaPoly")) {
			return new ChaChaPolyCipherState();
		}
		throw new NoSuchAlgorithmException("Unknown Noise cipher algorithm name: " + name);
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
	public static MessageDigest createHash(String name) throws NoSuchAlgorithmException
	{
		// Look for a JCA/JCE provider first and if that doesn't work,
		// use the fallback implementations in this library instead.
		// The only algorithm that is required to be implemented by a
		// JDK is "SHA-256", although "SHA-512" is fairly common as well.
		if (name.equals("SHA256")) {
			if (forceFallbacks)
				return new SHA256MessageDigest();
			try {
				return MessageDigest.getInstance("SHA-256");
			} catch (NoSuchAlgorithmException e) {
				return new SHA256MessageDigest();
			}
		} else if (name.equals("SHA512")) {
			if (forceFallbacks)
				return new SHA512MessageDigest();
			try {
				return MessageDigest.getInstance("SHA-512");
			} catch (NoSuchAlgorithmException e) {
				return new SHA512MessageDigest();
			}
		} else if (name.equals("BLAKE2b")) {
			// Bouncy Castle registers the BLAKE2b variant we
			// want under the name "BLAKE2B-512".
			if (forceFallbacks)
				return new Blake2bMessageDigest();
			try {
				return MessageDigest.getInstance("BLAKE2B-512");
			} catch (NoSuchAlgorithmException e) {
				return new Blake2bMessageDigest();
			}
		} else if (name.equals("BLAKE2s")) {
			// Bouncy Castle doesn't currently (June 2016) have an
			// implementation of BLAKE2s, but look for the most
			// obvious provider name in case one is added in the future.
			if (forceFallbacks)
				return new Blake2sMessageDigest();
			try {
				return MessageDigest.getInstance("BLAKE2S-256");
			} catch (NoSuchAlgorithmException e) {
				return new Blake2sMessageDigest();
			}
		}
		throw new NoSuchAlgorithmException("Unknown Noise hash algorithm name: " + name);
	}

	// The rest of this class consists of internal utility functions
	// that are not part of the public API.

	/**
	 * Destroys the contents of a byte array.
	 * 
	 * @param array The array whose contents should be destroyed.
	 */
	static void destroy(byte[] array)
	{
		Arrays.fill(array, (byte)0);
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
	static byte[] copySubArray(byte[] data, int offset, int length)
	{
		byte[] copy = new byte [length];
		System.arraycopy(data, offset, copy, 0, length);
		return copy;
	}
	
	/**
	 * Throws an instance of AEADBadTagException.
	 * 
	 * @throws BadPaddingException The AEAD exception.
	 * 
	 * If the underlying JDK does not have the AEADBadTagException
	 * class, then this function will instead throw an instance of
	 * the superclass BadPaddingException.
	 */
	static void throwBadTagException() throws BadPaddingException
	{
		try {
			Class<?> c = Class.forName("javax.crypto.AEADBadTagException");
			throw (BadPaddingException)(c.newInstance());
		} catch (ClassNotFoundException e) {
		} catch (InstantiationException e) {
		} catch (IllegalAccessException e) {
		}
		throw new BadPaddingException();
	}
}
