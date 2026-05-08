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
 * Fallback implementation of BLAKE2b for the Noise library.
 * 
 * This implementation only supports message digesting with an output
 * length of 64 bytes and a limit of 2^64 - 1 bytes of input.
 * Keyed hashing and variable-length digests are not supported.
 */
public class Blake2bMessageDigest extends MessageDigest implements Destroyable {

	private long[] h;
	private byte[] block;
	private long[] m;
	private long[] v;
	private long length;
	private int posn;

	/**
	 * Constructs a new BLAKE2b message digest object.
	 */
	public Blake2bMessageDigest() {
		super("BLAKE2B-512");
		h = new long [8];
		block = new byte [128];
		m = new long [16];
		v = new long [16];
		engineReset();
	}

	@Override
	protected byte[] engineDigest() {
		byte[] digest = new byte [64];
		try {
			engineDigest(digest, 0, 64);
		} catch (DigestException e) {
			// Shouldn't happen, but just in case.
			Arrays.fill(digest, (byte)0);
		}
		return digest;
	}
	
	@Override
	protected int engineDigest(byte[] buf, int offset, int len) throws DigestException
	{
		if (len < 64)
			throw new DigestException("Invalid digest length for BLAKE2b");
		Arrays.fill(block, posn, 128, (byte)0);
		transform(-1);
		for (int index = 0; index < 8; ++index) {
			long value = h[index];
			buf[offset++] = (byte)value;
			buf[offset++] = (byte)(value >> 8);
			buf[offset++] = (byte)(value >> 16);
			buf[offset++] = (byte)(value >> 24);
			buf[offset++] = (byte)(value >> 32);
			buf[offset++] = (byte)(value >> 40);
			buf[offset++] = (byte)(value >> 48);
			buf[offset++] = (byte)(value >> 56);
		}
		return 32;
	}

	@Override
	protected int engineGetDigestLength() {
		return 64;
	}

	@Override
	protected void engineReset() {
		h[0] = 0x6a09e667f3bcc908L ^ 0x01010040;
		h[1] = 0xbb67ae8584caa73bL;
		h[2] = 0x3c6ef372fe94f82bL;
		h[3] = 0xa54ff53a5f1d36f1L;
		h[4] = 0x510e527fade682d1L;
		h[5] = 0x9b05688c2b3e6c1fL;
		h[6] = 0x1f83d9abfb41bd6bL;
		h[7] = 0x5be0cd19137e2179L;
		length = 0;
		posn = 0;
	}

	@Override
	protected void engineUpdate(byte input) {
		if (posn >= 128) {
			transform(0);
			posn = 0;
		}
		block[posn++] = input;
		++length;
	}

	@Override
	protected void engineUpdate(byte[] input, int offset, int len) {
		while (len > 0) {
			if (posn >= 128) {
				transform(0);
				posn = 0;
			}
			int temp = (128 - posn);
			if (temp > len)
				temp = len;
			System.arraycopy(input, offset, block, posn, temp);
			posn += temp;
			length += temp;
			offset += temp;
			len -= temp;
		}
	}

	// Permutation on the message input state for BLAKE2b.
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
	    {10,  2,  8,  4,  7,  6,  1,  5, 15, 11,  9, 14,  3, 12, 13 , 0},
	    { 0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15},
	    {14, 10,  4,  8,  9, 15, 13,  6,  1, 12,  0,  2, 11,  7,  5,  3},
	};
	
	private void transform(long f0)
	{
		int index;
		int offset;
		
		// Unpack the input block from little-endian into host-endian.
		for (index = 0, offset = 0; index < 16; ++index, offset += 8) {
			m[index] = (block[offset] & 0xFFL) |
					   ((block[offset + 1] & 0xFFL) << 8) |
					   ((block[offset + 2] & 0xFFL) << 16) |
					   ((block[offset + 3] & 0xFFL) << 24) |
					   ((block[offset + 4] & 0xFFL) << 32) |
					   ((block[offset + 5] & 0xFFL) << 40) |
					   ((block[offset + 6] & 0xFFL) << 48) |
					   ((block[offset + 7] & 0xFFL) << 56);
		}
		
		// Format the block to be hashed.
		for (index = 0; index < 8; ++index)
			v[index] = h[index];
		v[8] = 0x6a09e667f3bcc908L;
		v[9] = 0xbb67ae8584caa73bL;
		v[10] = 0x3c6ef372fe94f82bL;
		v[11] = 0xa54ff53a5f1d36f1L;
		v[12] = 0x510e527fade682d1L ^ length;
		v[13] = 0x9b05688c2b3e6c1fL;
		v[14] = 0x1f83d9abfb41bd6bL ^ f0;
		v[15] = 0x5be0cd19137e2179L;
		
		// Perform the 12 BLAKE2b rounds.
		for (index = 0; index < 12; ++index) {
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

	private static long rightRotate32(long v)
	{
		return v << 32 | (v >>> 32);
	}

	private static long rightRotate24(long v)
	{
		return v << 40 | (v >>> 24);
	}

	private static long rightRotate16(long v)
	{
		return v << 48 | (v >>> 16);
	}

	private static long rightRotate63(long v)
	{
		return v << 1 | (v >>> 63);
	}

	private void quarterRound(int a, int b, int c, int d, int i, int row)
	{
		v[a] += v[b] + m[sigma[row][2 * i]];
		v[d] = rightRotate32(v[d] ^ v[a]);
		v[c] += v[d];
		v[b] = rightRotate24(v[b] ^ v[c]);
		v[a] += v[b] + m[sigma[row][2 * i + 1]];
		v[d] = rightRotate16(v[d] ^ v[a]);
		v[c] += v[d];
		v[b] = rightRotate63(v[b] ^ v[c]);
	}

	@Override
	public void destroy() {
		Arrays.fill(h, (long)0);
		Arrays.fill(block, (byte)0);
		Arrays.fill(m, (long)0);
		Arrays.fill(v, (long)0);
	}
}
