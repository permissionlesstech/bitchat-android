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

import java.util.Arrays;

import com.bitchat.android.noise.southernstorm.protocol.Destroyable;

/**
 * Simple implementation of the Poly1305 message authenticator.
 */
public final class Poly1305 implements Destroyable {

	// The 130-bit intermediate values are broken up into five 26-bit words.
	private byte[] nonce;
	private byte[] block;
	private int[] h;
	private int[] r;
	private int[] c;
	private long[] t;
	private int posn;

	/**
	 * Constructs a new Poly1305 message authenticator.
	 */
	public Poly1305()
	{
		nonce = new byte [16];
		block = new byte [16];
		h = new int [5];
		r = new int [5];
		c = new int [5];
		t = new long [10];
		posn = 0;
	}

	/**
	 * Resets the message authenticator with a new key.
	 * 
	 * @param key The buffer containing the 32 byte key.
	 * @param offset The offset into the buffer of the first key byte.
	 */
	public void reset(byte[] key, int offset)
	{
		System.arraycopy(key, offset + 16, nonce, 0, 16);
		Arrays.fill(h, 0);
		posn = 0;
		
		// Convert the first 16 bytes of the key into a 130-bit
		// "r" value while masking off the bits that we don't need.
		r[0] = ((key[offset] & 0xFF)) |
			   ((key[offset + 1] & 0xFF) << 8) |
			   ((key[offset + 2] & 0xFF) << 16) |
			   ((key[offset + 3] & 0x03) << 24);
		r[1] = ((key[offset + 3] & 0x0C) >> 2) |
			   ((key[offset + 4] & 0xFC) << 6) |
			   ((key[offset + 5] & 0xFF) << 14) |
			   ((key[offset + 6] & 0x0F) << 22);
		r[2] = ((key[offset + 6] & 0xF0) >> 4) |
			   ((key[offset + 7] & 0x0F) << 4) |
			   ((key[offset + 8] & 0xFC) << 12) |
			   ((key[offset + 9] & 0x3F) << 20);
		r[3] = ((key[offset + 9] & 0xC0) >> 6) |
			   ((key[offset + 10] & 0xFF) << 2) |
			   ((key[offset + 11] & 0x0F) << 10) |
			   ((key[offset + 12] & 0xFC) << 18);
		r[4] = ((key[offset + 13] & 0xFF)) |
			   ((key[offset + 14] & 0xFF) << 8) |
			   ((key[offset + 15] & 0x0F) << 16);
	}

	/**
	 * Updates the message authenticator with more input data.
	 * 
	 * @param data The buffer containing the input data.
	 * @param offset The offset of the first byte of input.
	 * @param length The number of bytes of input.
	 */
	public void update(byte[] data, int offset, int length)
	{
		while (length > 0) {
			if (posn == 0 && length >= 16) {
				// We can process the chunk directly out of the input buffer.
				processChunk(data, offset, false);
				offset += 16;
				length -= 16;
			} else {
				// Collect up partial bytes in the block buffer.
				int temp = 16 - posn;
				if (temp > length)
					temp = length;
				System.arraycopy(data, offset, block, posn, temp);
				offset += temp;
				length -= temp;
				posn += temp;
				if (posn >= 16) {
					processChunk(block, 0, false);
					posn = 0;
				}
			}
		}
	}

	/**
	 * Pads the input with zeroes to a multiple of 16 bytes.
	 */
	public void pad()
	{
		if (posn != 0) {
			Arrays.fill(block, posn, 16, (byte)0);
			processChunk(block, 0, false);
			posn = 0;
		}
	}

	/**
	 * Finishes the message authenticator and returns the 16-byte token.
	 * 
	 * @param token The buffer to receive the token.
	 * @param offset The offset of the token in the buffer.
	 */
	public void finish(byte[] token, int offset)
	{
		// Pad and flush the final chunk.
		if (posn != 0) {
			block[posn] = (byte)1;
			Arrays.fill(block, posn + 1, 16, (byte)0);
			processChunk(block, 0, true);
		}
		
	    // At this point, processChunk() has left h as a partially reduced
	    // result that is less than (2^130 - 5) * 6.  Perform one more
	    // reduction and a trial subtraction to produce the final result.

	    // Multiply the high bits of h by 5 and add them to the 130 low bits.
		int carry = (h[4] >> 26) * 5 + h[0];
		h[0] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[1];
		h[1] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[2];
		h[2] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[3];
		h[3] = carry & 0x03FFFFFF;
		h[4] = (carry >> 26) + (h[4] & 0x03FFFFFF);

	    // Subtract (2^130 - 5) from h by computing c = h + 5 - 2^130.
	    // The "minus 2^130" step is implicit.
		carry = 5 + h[0];
		c[0] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[1];
		c[1] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[2];
		c[2] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[3];
		c[3] = carry & 0x03FFFFFF;
		c[4] = (carry >> 26) + h[4];

	    // Borrow occurs if bit 2^130 of the previous c result is zero.
	    // Carefully turn this into a selection mask so we can select either
	    // h or c as the final result.
		int mask = -((c[4] >> 26) & 0x01);
		int nmask = ~mask;
		h[0] = (h[0] & nmask) | (c[0] & mask);
		h[1] = (h[1] & nmask) | (c[1] & mask);
		h[2] = (h[2] & nmask) | (c[2] & mask);
		h[3] = (h[3] & nmask) | (c[3] & mask);
		h[4] = (h[4] & nmask) | (c[4] & mask);
		
		// Convert h into little-endian in the block buffer.
		block[0] = (byte)(h[0]);
		block[1] = (byte)(h[0] >> 8);
		block[2] = (byte)(h[0] >> 16);
		block[3] = (byte)((h[0] >> 24) | (h[1] << 2));
		block[4] = (byte)(h[1] >> 6);
		block[5] = (byte)(h[1] >> 14);
		block[6] = (byte)((h[1] >> 22) | (h[2] << 4));
		block[7] = (byte)(h[2] >> 4);
		block[8] = (byte)(h[2] >> 12);
		block[9] = (byte)((h[2] >> 20) | (h[3] << 6));
		block[10] = (byte)(h[3] >> 2);
		block[11] = (byte)(h[3] >> 10);
		block[12] = (byte)(h[3] >> 18);
		block[13] = (byte)(h[4]);
		block[14] = (byte)(h[4] >> 8);
		block[15] = (byte)(h[4] >> 16);
		
		// Add the nonce and write the final result to the token.
		carry = (nonce[0] & 0xFF) + (block[0] & 0xFF);
		token[offset] = (byte)carry;
		for (int x = 1; x < 16; ++x) {
			carry = (carry >> 8) + (nonce[x] & 0xFF) + (block[x] & 0xFF);
			token[offset + x] = (byte)carry;
		}
	}

	/**
	 * Processes the next chunk of input data.
	 * 
	 * @param chunk Buffer containing the input data chunk.
	 * @param offset Offset of the first byte of the 16-byte chunk.
	 * @param finalChunk Set to true if this is the final chunk.
	 */
	private void processChunk(byte[] chunk, int offset, boolean finalChunk)
	{
		int x;
		
		// Unpack the 128-bit chunk into a 130-bit value in "c".
		c[0] = ((chunk[offset] & 0xFF)) |
			   ((chunk[offset + 1] & 0xFF) << 8) |
			   ((chunk[offset + 2] & 0xFF) << 16) |
			   ((chunk[offset + 3] & 0x03) << 24);
		c[1] = ((chunk[offset + 3] & 0xFC) >> 2) |
			   ((chunk[offset + 4] & 0xFF) << 6) |
			   ((chunk[offset + 5] & 0xFF) << 14) |
			   ((chunk[offset + 6] & 0x0F) << 22);
		c[2] = ((chunk[offset + 6] & 0xF0) >> 4) |
			   ((chunk[offset + 7] & 0xFF) << 4) |
			   ((chunk[offset + 8] & 0xFF) << 12) |
			   ((chunk[offset + 9] & 0x3F) << 20);
		c[3] = ((chunk[offset + 9] & 0xC0) >> 6) |
			   ((chunk[offset + 10] & 0xFF) << 2) |
			   ((chunk[offset + 11] & 0xFF) << 10) |
			   ((chunk[offset + 12] & 0xFF) << 18);
		c[4] = ((chunk[offset + 13] & 0xFF)) |
			   ((chunk[offset + 14] & 0xFF) << 8) |
			   ((chunk[offset + 15] & 0xFF) << 16);
		if (!finalChunk)
			c[4] |= (1 << 24);
		
		// Compute h = ((h + c) * r) mod (2^130 - 5)
		
		// Start with h += c.  We assume that h is less than (2^130 - 5) * 6
		// and that c is less than 2^129, so the result will be less than 2^133.
		h[0] += c[0];
		h[1] += c[1];
		h[2] += c[2];
		h[3] += c[3];
		h[4] += c[4];

		// Multiply h by r.  We know that r is less than 2^124 because the
	    // top 4 bits were AND-ed off by reset().  That makes h * r less
	    // than 2^257.  Which is less than the (2^130 - 6)^2 we want for
	    // the modulo reduction step that follows.  The intermediate limbs
		// are 52 bits in size, which allows us to collect up carries in the
		// extra bits of the 64 bit longs and propagate them later.
		long hv = h[0];
		t[0] = hv * r[0];
		t[1] = hv * r[1];
		t[2] = hv * r[2];
		t[3] = hv * r[3];
		t[4] = hv * r[4];
		for (x = 1; x < 5; ++x) {
			hv = h[x];
			t[x]     += hv * r[0];
			t[x + 1] += hv * r[1];
			t[x + 2] += hv * r[2];
			t[x + 3] += hv * r[3];
			t[x + 4]  = hv * r[4];
		}
		
		// Propagate carries to convert the t limbs from 52-bit back to 26-bit.
		// The low bits are placed into h and the high bits are placed into c.
		h[0] = ((int)t[0]) & 0x03FFFFFF;
		hv = t[1] + (t[0] >> 26);
		h[1] = ((int)hv) & 0x03FFFFFF;
		hv = t[2] + (hv >> 26);
		h[2] = ((int)hv) & 0x03FFFFFF;
		hv = t[3] + (hv >> 26);
		h[3] = ((int)hv) & 0x03FFFFFF;
		hv = t[4] + (hv >> 26);
		h[4] = ((int)hv) & 0x03FFFFFF;
		hv = t[5] + (hv >> 26);
		c[0] = ((int)hv) & 0x03FFFFFF;
		hv = t[6] + (hv >> 26);
		c[1] = ((int)hv) & 0x03FFFFFF;
		hv = t[7] + (hv >> 26);
		c[2] = ((int)hv) & 0x03FFFFFF;
		hv = t[8] + (hv >> 26);
		c[3] = ((int)hv) & 0x03FFFFFF;
		hv = t[9] + (hv >> 26);
		c[4] = ((int)hv);
		
		// Reduce h * r modulo (2^130 - 5) by multiplying the high 130 bits by 5
		// and adding them to the low 130 bits.  This will leave the result at
		// most 5 subtractions away from the answer we want.
		int carry = h[0] + c[0] * 5;
		h[0] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[1] + c[1] * 5;
		h[1] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[2] + c[2] * 5;
		h[2] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[3] + c[3] * 5;
		h[3] = carry & 0x03FFFFFF;
		carry = (carry >> 26) + h[4] + c[4] * 5;
		h[4] = carry;
	}

	@Override
	public void destroy() {
		Arrays.fill(nonce, (byte)0);
		Arrays.fill(block, (byte)0);
		Arrays.fill(h, (int)0);
		Arrays.fill(r, (int)0);
		Arrays.fill(c, (int)0);
		Arrays.fill(t, (long)0);
	}
}
