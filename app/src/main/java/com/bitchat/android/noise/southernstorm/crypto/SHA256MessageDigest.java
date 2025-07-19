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
 * Fallback implementation of SHA256.
 */
public class SHA256MessageDigest extends MessageDigest implements Destroyable {

	private int[] h;
	private byte[] block;
	private int[] w;
	private long length;
	private int posn;

	/**
	 * Constructs a new SHA256 message digest object.
	 */
	public SHA256MessageDigest() {
		super("SHA-256");
		h = new int [8];
		block = new byte [64];
		w = new int [64];
		engineReset();
	}

	@Override
	public void destroy() {
		Arrays.fill(h, (int)0);
		Arrays.fill(block, (byte)0);
		Arrays.fill(w, (int)0);
	}

	private static void writeBE32(byte[] buf, int offset, int value)
	{
		buf[offset] = (byte)(value >> 24);
		buf[offset + 1] = (byte)(value >> 16);
		buf[offset + 2] = (byte)(value >> 8);
		buf[offset + 3] = (byte)value;
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
			throw new DigestException("Invalid digest length for SHA256");
		if (posn <= (64 - 9)) {
			block[posn] = (byte)0x80;
			Arrays.fill(block, posn + 1, 64 - 8, (byte)0);
		} else {
			block[posn] = (byte)0x80;
			Arrays.fill(block, posn + 1, 64, (byte)0);
			transform(block, 0);
			Arrays.fill(block, 0, 64 - 8, (byte)0);
		}
		writeBE32(block, 64 - 8, (int)(length >> 32));
		writeBE32(block, 64 - 4, (int)length);
		transform(block, 0);
		posn = 0;
		for (int index = 0; index < 8; ++index)
			writeBE32(buf, offset + index * 4, h[index]);
		return 32;
	}

	@Override
	protected int engineGetDigestLength() {
		return 32;
	}

	@Override
	protected void engineReset() {
		h[0] = 0x6A09E667;
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
		block[posn++] = input;
		length += 8;
		if (posn >= 64) {
			transform(block, 0);
			posn = 0;
		}
	}

	@Override
	protected void engineUpdate(byte[] input, int offset, int len) {
		while (len > 0) {
			if (posn == 0 && len >= 64) {
				transform(input, offset);
				offset += 64;
				len -= 64;
				length += 64 * 8;
			} else {
				int temp = 64 - posn;
				if (temp > len)
					temp = len;
				System.arraycopy(input, offset, block, posn, temp);
				posn += temp;
				length += temp * 8;
				if (posn >= 64) {
					transform(block, 0);
					posn = 0;
				}
				offset += temp;
				len -= temp;
			}
		}
	}

    private static final int[] k = {
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5,
        0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
        0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc,
        0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
        0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3,
        0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
        0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
        0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
    };

    private static int rightRotate(int value, int n)
    {
    	return (value >>> n) | (value << (32 - n));
    }

	private void transform(byte[] m, int offset)
	{
		int a, b, c, d, e, f, g, h;
		int temp1, temp2;
		int index;

		// Initialize working variables to the current hash value.
		a = this.h[0];
		b = this.h[1];
		c = this.h[2];
		d = this.h[3];
		e = this.h[4];
		f = this.h[5];
		g = this.h[6];
		h = this.h[7];
		
		// Convert the 16 input message words from big endian to host byte order.
		for (index = 0; index < 16; ++index) {
			w[index] = ((m[offset] & 0xFF) << 24) |
					   ((m[offset + 1] & 0xFF) << 16) |
					   ((m[offset + 2] & 0xFF) << 8) |
					    (m[offset + 3] & 0xFF);
			offset += 4;
		}
		
	    // Extend the first 16 words to 64.
	    for (index = 16; index < 64; ++index) {
	        w[index] = w[index - 16] + w[index - 7] +
	            (rightRotate(w[index - 15], 7) ^
	             rightRotate(w[index - 15], 18) ^
	             (w[index - 15] >>> 3)) +
	            (rightRotate(w[index - 2], 17) ^
	             rightRotate(w[index - 2], 19) ^
	             (w[index - 2] >>> 10));
	    }
	    
	    // Compression function main loop.
	    for (index = 0; index < 64; ++index) {
	        temp1 = (h) + k[index] + w[index] +
	                (rightRotate((e), 6) ^ rightRotate((e), 11) ^ rightRotate((e), 25)) +
	                (((e) & (f)) ^ ((~(e)) & (g)));
            temp2 = (rightRotate((a), 2) ^ rightRotate((a), 13) ^ rightRotate((a), 22)) +
                    (((a) & (b)) ^ ((a) & (c)) ^ ((b) & (c)));
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
	    }

		// Add the compressed chunk to the current hash value.
		this.h[0] += a;
		this.h[1] += b;
		this.h[2] += c;
		this.h[3] += d;
		this.h[4] += e;
		this.h[5] += f;
		this.h[6] += g;
		this.h[7] += h;
	}
}
