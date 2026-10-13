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

import java.security.DigestException;
import java.security.MessageDigest;
import java.util.Arrays;

import com.bitchat.android.noise.southernstorm.protocol.Destroyable;

/**
 * Fallback implementation of BLAKE2s for the Noise library.
 * 
 * This implementation only supports message digesting with an output
 * length of 32 bytes.  Keyed hashing and variable-length digests are
 * not supported.
 */
public class Blake2sMessageDigest extends MessageDigest implements Destroyable {
	
	private int[] h;
	private byte[] block;
	private int[] m;
	private int[] v;
	private long length;
	private int posn;

	/**
	 * Constructs a new BLAKE2s message digest object.
	 */
	public Blake2sMessageDigest() {
		super("BLAKE2S-256");
		h = new int [8];
		block = new byte [64];
		m = new int [16];
		v = new int [16];
		engineReset();
	}
	
	@Override
	protected byte[] engineDigest() {
		byte[] digest = new byte [32];
		try {
			engineDigest(digest, 0, 32);
		} catch (DigestException e) {
			// Shouldn't happen, but just in case.
			Arrays.fill(digest, (byte)0);
		}
		return digest;
	}
	
	@Override
	protected int engineDigest(byte[] buf, int offset, int len) throws DigestException
	{
		if (len < 32)
			throw new DigestException("Invalid digest length for BLAKE2s");
		Arrays.fill(block, posn, 64, (byte)0);
		transform(-1);
		for (int index = 0; index < 8; ++index) {
			int value = h[index];
			buf[offset++] = (byte)value;
			buf[offset++] = (byte)(value >> 8);
			buf[offset++] = (byte)(value >> 16);
			buf[offset++] = (byte)(value >> 24);
		}
		return 32;
	}

	@Override
	protected int engineGetDigestLength() {
		return 32;
	}

	@Override
	protected void engineReset() {
		h[0] = 0x6A09E667 ^ 0x01010020;
		h[1] = 0xBB67AE85;
		h[2] = 0x3C6EF372;
		h[3] = 0xA54FF53A;
		h[4] = 0x510E527F;
		h[5] = 0x9B05688C;
		h[6] = 0x1F83D9AB;
		h[7] = 0x5BE0CD19;
		length = 0;
		posn = 0;
	}

	@Override
	protected void engineUpdate(byte input) {
		if (posn >= 64) {
			transform(0);
			posn = 0;
		}
		block[posn++] = input;
		++length;
	}

	@Override
	protected void engineUpdate(byte[] input, int offset, int len) {
		while (len > 0) {
			if (posn >= 64) {
				transform(0);
				posn = 0;
			}
			int temp = (64 - posn);
			if (temp > len)
				temp = len;
			System.arraycopy(input, offset, block, posn, temp);
			posn += temp;
			length += temp;
			offset += temp;
			len -= temp;
		}
	}

	// Permutation on the message input state for BLAKE2s.
	static final byte[][] sigma = {
	    { 0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15},
	    {14, 10,  4,  8,  9, 15, 13,  6,  1, 12,  0,  2, 11,  7,  5,  3},
	    {11,  8, 12,  0,  5,  2, 15, 13, 10, 14,  3,  6,  7,  1,  9,  4},
	    { 7,  9,  3,  1, 13, 12, 11, 14,  2,  6,  5, 10,  4,  0, 15,  8},
	    { 9,  0,  5,  7,  2,  4, 10, 15, 14,  1, 11, 12,  6,  8,  3, 13},
	    { 2, 12,  6, 10,  0, 11,  8,  3,  4, 13,  7,  5, 15, 14,  1,  9},
	    {12,  5,  1, 15, 14, 13,  4, 10,  0,  7,  6,  3,  9,  2,  8, 11},
	    {13, 11,  7, 14, 12,  1,  3,  9,  5,  0, 15,  4,  8,  6,  2, 10},
	    { 6, 15, 14,  9, 11,  3,  0,  8, 12,  2, 13,  7,  1,  4, 10,  5},
	    {10,  2,  8,  4,  7,  6,  1,  5, 15, 11,  9, 14,  3, 12, 13 , 0}
	};
	
	private void transform(int f0)
	{
		int index;
		int offset;
		
		// Unpack the input block from little-endian into host-endian.
		for (index = 0, offset = 0; index < 16; ++index, offset += 4) {
			m[index] = (block[offset] & 0xFF) |
					   ((block[offset + 1] & 0xFF) << 8) |
					   ((block[offset + 2] & 0xFF) << 16) |
					   ((block[offset + 3] & 0xFF) << 24);
		}
		
		// Format the block to be hashed.
		for (index = 0; index < 8; ++index)
			v[index] = h[index];
		v[8] = 0x6A09E667;
		v[9] = 0xBB67AE85;
		v[10] = 0x3C6EF372;
		v[11] = 0xA54FF53A;
		v[12] = 0x510E527F ^ (int)length;
		v[13] = 0x9B05688C ^ (int)(length >> 32);
		v[14] = 0x1F83D9AB ^ f0;
		v[15] = 0x5BE0CD19;
		
		// Perform the 10 BLAKE2s rounds.
		for (index = 0; index < 10; ++index) {
			// Column round.
	        quarterRound(0, 4, 8,  12, 0, index);
	        quarterRound(1, 5, 9,  13, 1, index);
	        quarterRound(2, 6, 10, 14, 2, index);
	        quarterRound(3, 7, 11, 15, 3, index);

	        // Diagonal round.
	        quarterRound(0, 5, 10, 15, 4, index);
	        quarterRound(1, 6, 11, 12, 5, index);
	        quarterRound(2, 7, 8,  13, 6, index);
	        quarterRound(3, 4, 9,  14, 7, index);
		}
		
		// Combine the new and old hash values.
		for (index = 0; index < 8; ++index)
			h[index] ^= (v[index] ^ v[index + 8]);
	}
	
	private static int rightRotate16(int v)
	{
		return v << 16 | (v >>> 16);
	}

	private static int rightRotate12(int v)
	{
		return v << 20 | (v >>> 12);
	}

	private static int rightRotate8(int v)
	{
		return v << 24 | (v >>> 8);
	}

	private static int rightRotate7(int v)
	{
		return v << 25 | (v >>> 7);
	}

	private void quarterRound(int a, int b, int c, int d, int i, int row)
	{
		v[a] += v[b] + m[sigma[row][2 * i]];
		v[d] = rightRotate16(v[d] ^ v[a]);
		v[c] += v[d];
		v[b] = rightRotate12(v[b] ^ v[c]);
		v[a] += v[b] + m[sigma[row][2 * i + 1]];
		v[d] = rightRotate8(v[d] ^ v[a]);
		v[c] += v[d];
		v[b] = rightRotate7(v[b] ^ v[c]);
	}

	@Override
	public void destroy() {
		Arrays.fill(h, (int)0);
		Arrays.fill(block, (byte)0);
		Arrays.fill(m, (int)0);
		Arrays.fill(v, (int)0);
	}
}
