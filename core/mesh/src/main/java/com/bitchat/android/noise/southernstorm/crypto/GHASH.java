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
 * Implementation of the GHASH primitive for GCM.
 */
public final class GHASH implements Destroyable {

	private long[] H;
	private byte[] Y;
	int posn;
	
	/**
	 * Constructs a new GHASH object.
	 */
	public GHASH()
	{
		H = new long [2];
		Y = new byte [16];
		posn = 0;
	}

	/**
	 * Resets this GHASH object with a new key.
	 * 
	 * @param key The key, which must contain at least 16 bytes.
	 * @param offset The offset of the first key byte.
	 */
	public void reset(byte[] key, int offset)
	{
		H[0] = readBigEndian(key, offset);
		H[1] = readBigEndian(key, offset + 8);
		Arrays.fill(Y, (byte)0);
		posn = 0;
	}

	/**
	 * Resets the GHASH object but retains the previous key.
	 */
	public void reset()
	{
		Arrays.fill(Y, (byte)0);
		posn = 0;
	}

	/**
	 * Updates this GHASH object with more data.
	 * 
	 * @param data Buffer containing the data.
	 * @param offset Offset of the first data byte in the buffer.
	 * @param length The number of bytes from the buffer to hash.
	 */
	public void update(byte[] data, int offset, int length)
	{
		while (length > 0) {
			int size = 16 - posn;
			if (size > length)
				size = length;
			for (int index = 0; index < size; ++index)
				Y[posn + index] ^= data[offset + index];
			posn += size;
			length -= size;
			offset += size;
			if (posn == 16) {
				GF128_mul(Y, H);
				posn = 0;
			}
		}
	}
	
	/**
	 * Finishes the GHASH process and returns the tag.
	 * 
	 * @param tag Buffer to receive the tag.
	 * @param offset Offset of the first byte of the tag.
	 * @param length The length of the tag, which must be less
	 * than or equal to 16.
	 */
	public void finish(byte[] tag, int offset, int length)
	{
		pad();
		System.arraycopy(Y, 0, tag, offset, length);
	}
	
	/**
	 * Pads the input to a 16-byte boundary.
	 */
	public void pad()
	{
	    if (posn != 0) {
	        // Padding involves XOR'ing the rest of state->Y with zeroes,
	        // which does nothing.  Immediately process the next chunk.
	        GF128_mul(Y, H);
	        posn = 0;
	    }
	}

	/**
	 * Pads the input to a 16-byte boundary and then adds a block
	 * containing the AD and data lengths.
	 * 
	 * @param adLen Length of the associated data in bytes.
	 * @param dataLen Length of the data in bytes.
	 */
	public void pad(long adLen, long dataLen)
	{
		byte[] temp = new byte [16];
		try {
			pad();
			writeBigEndian(temp, 0, adLen * 8);
			writeBigEndian(temp, 8, dataLen * 8);
			update(temp, 0, 16);
		} finally {
			Arrays.fill(temp, (byte)0);
		}
	}

	@Override
	public void destroy() {
		Arrays.fill(H, 0L);
		Arrays.fill(Y, (byte)0);
	}

	private static long readBigEndian(byte[] buf, int offset)
	{
		return ((buf[offset] & 0xFFL) << 56) |
			   ((buf[offset + 1] & 0xFFL) << 48) |
			   ((buf[offset + 2] & 0xFFL) << 40) |
			   ((buf[offset + 3] & 0xFFL) << 32) |
			   ((buf[offset + 4] & 0xFFL) << 24) |
			   ((buf[offset + 5] & 0xFFL) << 16) |
			   ((buf[offset + 6] & 0xFFL) << 8)  |
			    (buf[offset + 7] & 0xFFL);
	}

	private static void writeBigEndian(byte[] buf, int offset, long value)
	{
		buf[offset]     = (byte)(value >> 56);
		buf[offset + 1] = (byte)(value >> 48);
		buf[offset + 2] = (byte)(value >> 40);
		buf[offset + 3] = (byte)(value >> 32);
		buf[offset + 4] = (byte)(value >> 24);
		buf[offset + 5] = (byte)(value >> 16);
		buf[offset + 6] = (byte)(value >> 8);
		buf[offset + 7] = (byte)value;
	}

	private static void GF128_mul(byte[] Y, long[] H)
	{
	    long Z0 = 0;		// Z = 0
	    long Z1 = 0;
	    long V0 = H[0];		// V = H
	    long V1 = H[1];

	    // Multiply Z by V for the set bits in Y, starting at the top.
	    // This is a very simple bit by bit version that may not be very
	    // fast but it should be resistant to cache timing attacks.
	    for (int posn = 0; posn < 16; ++posn) {
	        int value = Y[posn] & 0xFF;
	        for (int bit = 7; bit >= 0; --bit) {
	            // Extract the high bit of "value" and turn it into a mask.
	            long mask = -((long)((value >> bit) & 0x01));

	            // XOR V with Z if the bit is 1.
	            Z0 ^= (V0 & mask);
	            Z1 ^= (V1 & mask);

	            // Rotate V right by 1 bit.
	            mask = ((~(V1 & 0x01)) + 1) & 0xE100000000000000L;
	            V1 = (V1 >>> 1) | (V0 << 63);
	            V0 = (V0 >>> 1) ^ mask;
	        }
	    }

	    // We have finished the block so copy Z into Y and byte-swap.
	    writeBigEndian(Y, 0, Z0);
	    writeBigEndian(Y, 8, Z1);
	}
}
