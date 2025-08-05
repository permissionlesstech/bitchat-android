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
 * Fallback implementation of SHA512.
 * 
 * Note: This implementation is limited to a maximum 2^56 - 1 bytes of input.
 * That is, we don't bother trying to implement 128-bit length values.
 */
public class SHA512MessageDigest extends MessageDigest implements Destroyable {

	private long[] h;
	private byte[] block;
	private long[] w;
	private long length;
	private int posn;

	/**
	 * Constructs a new SHA512 message digest object.
	 */
	public SHA512MessageDigest() {
		super("SHA-512");
		h = new long [8];
		block = new byte [128];
		w = new long [80];
		engineReset();
	}

	@Override
	public void destroy() {
		Arrays.fill(h, (long)0);
		Arrays.fill(block, (byte)0);
		Arrays.fill(w, (long)0);
	}

	private static void writeBE64(byte[] buf, int offset, long value)
	{
		buf[offset] = (byte)(value >> 56);
		buf[offset + 1] = (byte)(value >> 48);
		buf[offset + 2] = (byte)(value >> 40);
		buf[offset + 3] = (byte)(value >> 32);
		buf[offset + 4] = (byte)(value >> 24);
		buf[offset + 5] = (byte)(value >> 16);
		buf[offset + 6] = (byte)(value >> 8);
		buf[offset + 7] = (byte)value;
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
			throw new DigestException("Invalid digest length for SHA512");
		if (posn <= (128 - 17)) {
			block[posn] = (byte)0x80;
			Arrays.fill(block, posn + 1, 128 - 8, (byte)0);
		} else {
			block[posn] = (byte)0x80;
			Arrays.fill(block, posn + 1, 128, (byte)0);
			transform(block, 0);
			Arrays.fill(block, 0, 128 - 8, (byte)0);
		}
		writeBE64(block, 128 - 8, length);
		transform(block, 0);
		posn = 0;
		for (int index = 0; index < 8; ++index)
			writeBE64(buf, offset + index * 8, h[index]);
		return 64;
	}

	@Override
	protected int engineGetDigestLength() {
		return 64;
	}

	@Override
	protected void engineReset() {
		h[0] = 0x6a09e667f3bcc908L;
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
		block[posn++] = input;
		length += 8;
		if (posn >= 128) {
			transform(block, 0);
			posn = 0;
		}
	}

	@Override
	protected void engineUpdate(byte[] input, int offset, int len) {
		while (len > 0) {
			if (posn == 0 && len >= 128) {
				transform(input, offset);
				offset += 128;
				len -= 128;
				length += 128 * 8;
			} else {
				int temp = 128 - posn;
				if (temp > len)
					temp = len;
				System.arraycopy(input, offset, block, posn, temp);
				posn += temp;
				length += temp * 8;
				if (posn >= 128) {
					transform(block, 0);
					posn = 0;
				}
				offset += temp;
				len -= temp;
			}
		}
	}

   private static final long[] k = {
        0x428A2F98D728AE22L, 0x7137449123EF65CDL, 0xB5C0FBCFEC4D3B2FL,
        0xE9B5DBA58189DBBCL, 0x3956C25BF348B538L, 0x59F111F1B605D019L,
        0x923F82A4AF194F9BL, 0xAB1C5ED5DA6D8118L, 0xD807AA98A3030242L,
        0x12835B0145706FBEL, 0x243185BE4EE4B28CL, 0x550C7DC3D5FFB4E2L,
        0x72BE5D74F27B896FL, 0x80DEB1FE3B1696B1L, 0x9BDC06A725C71235L,
        0xC19BF174CF692694L, 0xE49B69C19EF14AD2L, 0xEFBE4786384F25E3L,
        0x0FC19DC68B8CD5B5L, 0x240CA1CC77AC9C65L, 0x2DE92C6F592B0275L,
        0x4A7484AA6EA6E483L, 0x5CB0A9DCBD41FBD4L, 0x76F988DA831153B5L,
        0x983E5152EE66DFABL, 0xA831C66D2DB43210L, 0xB00327C898FB213FL,
        0xBF597FC7BEEF0EE4L, 0xC6E00BF33DA88FC2L, 0xD5A79147930AA725L,
        0x06CA6351E003826FL, 0x142929670A0E6E70L, 0x27B70A8546D22FFCL,
        0x2E1B21385C26C926L, 0x4D2C6DFC5AC42AEDL, 0x53380D139D95B3DFL,
        0x650A73548BAF63DEL, 0x766A0ABB3C77B2A8L, 0x81C2C92E47EDAEE6L,
        0x92722C851482353BL, 0xA2BFE8A14CF10364L, 0xA81A664BBC423001L,
        0xC24B8B70D0F89791L, 0xC76C51A30654BE30L, 0xD192E819D6EF5218L,
        0xD69906245565A910L, 0xF40E35855771202AL, 0x106AA07032BBD1B8L,
        0x19A4C116B8D2D0C8L, 0x1E376C085141AB53L, 0x2748774CDF8EEB99L,
        0x34B0BCB5E19B48A8L, 0x391C0CB3C5C95A63L, 0x4ED8AA4AE3418ACBL,
        0x5B9CCA4F7763E373L, 0x682E6FF3D6B2B8A3L, 0x748F82EE5DEFB2FCL,
        0x78A5636F43172F60L, 0x84C87814A1F0AB72L, 0x8CC702081A6439ECL,
        0x90BEFFFA23631E28L, 0xA4506CEBDE82BDE9L, 0xBEF9A3F7B2C67915L,
        0xC67178F2E372532BL, 0xCA273ECEEA26619CL, 0xD186B8C721C0C207L,
        0xEADA7DD6CDE0EB1EL, 0xF57D4F7FEE6ED178L, 0x06F067AA72176FBAL,
        0x0A637DC5A2C898A6L, 0x113F9804BEF90DAEL, 0x1B710B35131C471BL,
        0x28DB77F523047D84L, 0x32CAAB7B40C72493L, 0x3C9EBE0A15C9BEBCL,
        0x431D67C49C100D4CL, 0x4CC5D4BECB3E42B6L, 0x597F299CFC657E2AL,
        0x5FCB6FAB3AD6FAECL, 0x6C44198C4A475817L
    };

	private static long rightRotate(long value, int n)
    {
    	return (value >>> n) | (value << (64 - n));
    }

	private void transform(byte[] m, int offset)
	{
		long a, b, c, d, e, f, g, h;
		long temp1, temp2;
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
			w[index] = ((m[offset] & 0xFFL) << 56) |
					   ((m[offset + 1] & 0xFFL) << 48) |
					   ((m[offset + 2] & 0xFFL) << 40) |
					   ((m[offset + 3] & 0xFFL) << 32) |
					   ((m[offset + 4] & 0xFFL) << 24) |
					   ((m[offset + 5] & 0xFFL) << 16) |
					   ((m[offset + 6] & 0xFFL) << 8) |
					    (m[offset + 7] & 0xFFL);
			offset += 8;
		}
		
	    // Extend the first 16 words to 80.
	    for (index = 16; index < 80; ++index) {
	        w[index] = w[index - 16] + w[index - 7] +
	            (rightRotate(w[index - 15], 1) ^
	             rightRotate(w[index - 15], 8) ^
	             (w[index - 15] >>> 7)) +
	            (rightRotate(w[index - 2], 19) ^
	             rightRotate(w[index - 2], 61) ^
	             (w[index - 2] >>> 6));
	    }
	    
	    // Compression function main loop.
	    for (index = 0; index < 80; ++index) {
	        temp1 = (h) + k[index] + w[index] +
	                (rightRotate((e), 14) ^ rightRotate((e), 18) ^ rightRotate((e), 41)) +
	                (((e) & (f)) ^ ((~(e)) & (g)));
            temp2 = (rightRotate((a), 28) ^ rightRotate((a), 34) ^ rightRotate((a), 39)) +
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
