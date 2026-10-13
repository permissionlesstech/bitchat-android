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

package com.bitchat.android.noise.southernstorm.crypto;

/**
 * Implementation of the ChaCha20 core hash transformation.
 */
public final class ChaChaCore {

	private ChaChaCore() {}
	
	/**
	 * Hashes an input block with ChaCha20.
	 * 
	 * @param output The output block, which must contain at least 16
	 * elements and must not overlap with the input.
	 * @param input The input block, which must contain at least 16
	 * elements.
	 */
	public static void hash(int[] output, int[] input)
	{
		int index;
		
		// Copy the input to the output to start with.
		for (index = 0; index < 16; ++index)
			output[index] = input[index];
		
		// Perform the 20 ChaCha rounds in groups of two.
		for (index = 0; index < 20; index += 2) {
	        // Column round.
	        quarterRound(output, 0, 4, 8,  12);
	        quarterRound(output, 1, 5, 9,  13);
	        quarterRound(output, 2, 6, 10, 14);
	        quarterRound(output, 3, 7, 11, 15);

	        // Diagonal round.
	        quarterRound(output, 0, 5, 10, 15);
	        quarterRound(output, 1, 6, 11, 12);
	        quarterRound(output, 2, 7, 8,  13);
	        quarterRound(output, 3, 4, 9,  14);
		}

		// Add the input block to the output.
		for (index = 0; index < 16; ++index)
			output[index] += input[index];
	}

	private static int char4(char c1, char c2, char c3, char c4)
	{
		return (((int)c1) & 0xFF) | ((((int)c2) & 0xFF) << 8) | ((((int)c3) & 0xFF) << 16) | ((((int)c4) & 0xFF) << 24);
	}

	private static int fromLittleEndian(byte[] key, int offset)
	{
		return (key[offset] & 0xFF) | ((key[offset + 1] & 0xFF) << 8) | ((key[offset + 2] & 0xFF) << 16) | ((key[offset + 3] & 0xFF) << 24);
	}

	/**
	 * Initializes a ChaCha20 block with a 128-bit key.
	 * 
	 * @param output The output block, which must consist of at
	 * least 16 words.
	 * @param key The buffer containing the key.
	 * @param offset Offset of the key in the buffer.
	 */
	public static void initKey128(int[] output, byte[] key, int offset)
	{
		output[0] = char4('e', 'x', 'p', 'a');
		output[1] = char4('n', 'd', ' ', '1');
		output[2] = char4('6', '-', 'b', 'y');
		output[3] = char4('t', 'e', ' ', 'k');
		output[4] = fromLittleEndian(key, offset);
		output[5] = fromLittleEndian(key, offset + 4);
		output[6] = fromLittleEndian(key, offset + 8);
		output[7] = fromLittleEndian(key, offset + 12);
		output[8] = output[4];
		output[9] = output[5];
		output[10] = output[6];
		output[11] = output[7];
		output[12] = 0;
		output[13] = 0;
		output[14] = 0;
		output[15] = 0;
	}

	/**
	 * Initializes a ChaCha20 block with a 256-bit key.
	 * 
	 * @param output The output block, which must consist of at
	 * least 16 words.
	 * @param key The buffer containing the key.
	 * @param offset Offset of the key in the buffer.
	 */
	public static void initKey256(int[] output, byte[] key, int offset)
	{
		output[0] = char4('e', 'x', 'p', 'a');
		output[1] = char4('n', 'd', ' ', '3');
		output[2] = char4('2', '-', 'b', 'y');
		output[3] = char4('t', 'e', ' ', 'k');
		output[4] = fromLittleEndian(key, offset);
		output[5] = fromLittleEndian(key, offset + 4);
		output[6] = fromLittleEndian(key, offset + 8);
		output[7] = fromLittleEndian(key, offset + 12);
		output[8] = fromLittleEndian(key, offset + 16);
		output[9] = fromLittleEndian(key, offset + 20);
		output[10] = fromLittleEndian(key, offset + 24);
		output[11] = fromLittleEndian(key, offset + 28);
		output[12] = 0;
		output[13] = 0;
		output[14] = 0;
		output[15] = 0;
	}

	/**
	 * Initializes the 64-bit initialization vector in a ChaCha20 block.
	 * 
	 * @param output The output block, which must consist of at
	 * least 16 words and must have been initialized by initKey256()
	 * or initKey128().
	 * @param iv The 64-bit initialization vector value.
	 * 
	 * The counter portion of the output block is set to zero.
	 */
	public static void initIV(int[] output, long iv)
	{
		output[12] = 0;
		output[13] = 0;
		output[14] = (int)iv;
		output[15] = (int)(iv >> 32);
	}
	
	/**
	 * Initializes the 64-bit initialization vector and counter in a ChaCha20 block.
	 * 
	 * @param output The output block, which must consist of at
	 * least 16 words and must have been initialized by initKey256()
	 * or initKey128().
	 * @param iv The 64-bit initialization vector value.
	 * @param counter The 64-bit counter value.
	 */
	public static void initIV(int[] output, long iv, long counter)
	{
		output[12] = (int)counter;
		output[13] = (int)(counter >> 32);
		output[14] = (int)iv;
		output[15] = (int)(iv >> 32);
	}
	
	private static int leftRotate16(int v)
	{
		return v << 16 | (v >>> 16);
	}

	private static int leftRotate12(int v)
	{
		return v << 12 | (v >>> 20);
	}

	private static int leftRotate8(int v)
	{
		return v << 8 | (v >>> 24);
	}

	private static int leftRotate7(int v)
	{
		return v << 7 | (v >>> 25);
	}

	private static void quarterRound(int[] v, int a, int b, int c, int d)
	{
		v[a] += v[b];
		v[d] = leftRotate16(v[d] ^ v[a]);
		v[c] += v[d];
		v[b] = leftRotate12(v[b] ^ v[c]);
		v[a] += v[b];
		v[d] = leftRotate8(v[d] ^ v[a]);
		v[c] += v[d];
		v[b] = leftRotate7(v[b] ^ v[c]);
	}
}
