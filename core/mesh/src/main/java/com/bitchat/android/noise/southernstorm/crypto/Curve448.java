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

/*

Portions of this code were extracted from the p448/arch_32 field
arithmetic implementation in Ed448-Goldilocks and converted from
C into Java.  The LICENSE.txt file for the imported code follows:

----
The MIT License (MIT)

Copyright (c) 2011 Stanford University.
Copyright (c) 2014 Cryptography Research, Inc.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
----

*/

package com.bitchat.android.noise.southernstorm.crypto;

import java.util.Arrays;

/**
 * Implementation of the Curve448 elliptic curve algorithm.
 * 
 * Reference: RFC 7748
 */
public final class Curve448 {

	// Numbers modulo 2^448 - 2^224 - 1 are broken up into sixteen 28-bit words.
	private int[] x_1;
	private int[] x_2;
	private int[] x_3;
	private int[] z_2;
	private int[] z_3;
	private int[] A;
	private int[] B;
	private int[] C;
	private int[] D;
	private int[] E;
	private int[] AA;
	private int[] BB;
	private int[] DA;
	private int[] CB;
	private int[] aa;
	private int[] bb;

	/**
	 * Constructs the temporary state holder for Curve448 evaluation.
	 */
	private Curve448()
	{
		// Allocate memory for all of the temporary variables we will need.
		x_1 = new int [16];
		x_2 = new int [16];
		x_3 = new int [16];
		z_2 = new int [16];
		z_3 = new int [16];
		A = new int [16];
		B = new int [16];
		C = new int [16];
		D = new int [16];
		E = new int [16];
		AA = new int [16];
		BB = new int [16];
		DA = new int [16];
		CB = new int [16];
		aa = new int [8];
		bb = new int [8];
	}

	/**
	 * Destroy all sensitive data in this object.
	 */
	private void destroy() {
		// Destroy all temporary variables.
		Arrays.fill(x_1, 0);
		Arrays.fill(x_2, 0);
		Arrays.fill(x_3, 0);
		Arrays.fill(z_2, 0);
		Arrays.fill(z_3, 0);
		Arrays.fill(A, 0);
		Arrays.fill(B, 0);
		Arrays.fill(C, 0);
		Arrays.fill(D, 0);
		Arrays.fill(E, 0);
		Arrays.fill(AA, 0);
		Arrays.fill(BB, 0);
		Arrays.fill(DA, 0);
		Arrays.fill(CB, 0);
		Arrays.fill(aa, 0);
		Arrays.fill(bb, 0);
	}

	/* Beginning of code imported from Ed448-Goldilocks */
	
	private static long widemul_32(int a, int b)
	{
		return ((long)a) * b;
	}

	// p448_mul()
	private void mul(int[] c, int[] a, int[] b)
	{
	    long accum0 = 0, accum1 = 0, accum2 = 0;
	    int mask = (1<<28) - 1;

	    int i,j;
	    for (i=0; i<8; i++) {
	        aa[i] = a[i] + a[i+8];
	        bb[i] = b[i] + b[i+8];
	    }

	    for (j=0; j<8; j++) {
	        accum2 = 0;

	        for (i=0; i<=j; i++) {
	            accum2 += widemul_32(a[j-i],b[i]);
	            accum1 += widemul_32(aa[j-i],bb[i]);
	            accum0 += widemul_32(a[8+j-i], b[8+i]);
	        }

	        accum1 -= accum2;
	        accum0 += accum2;
	        accum2 = 0;

	        for (; i<8; i++) {
	            accum0 -= widemul_32(a[8+j-i], b[i]);
	            accum2 += widemul_32(aa[8+j-i], bb[i]);
	            accum1 += widemul_32(a[16+j-i], b[8+i]);
	        }

	        accum1 += accum2;
	        accum0 += accum2;

	        c[j] = ((int)(accum0)) & mask;
	        c[j+8] = ((int)(accum1)) & mask;

	        accum0 >>>= 28;
	        accum1 >>>= 28;
	    }

	    accum0 += accum1;
	    accum0 += c[8];
	    accum1 += c[0];
	    c[8] = ((int)(accum0)) & mask;
	    c[0] = ((int)(accum1)) & mask;

	    accum0 >>>= 28;
	    accum1 >>>= 28;
	    c[9] += ((int)(accum0));
	    c[1] += ((int)(accum1));
	}

	// p448_mulw()
	private static void mulw(int[] c, int[] a, long b)
	{
	    int bhi = (int)(b>>28), blo = ((int)b) & ((1<<28)-1);

	    long accum0, accum8;
	    int mask = (1<<28) - 1;

	    int i;

	    accum0 = widemul_32(blo, a[0]);
	    accum8 = widemul_32(blo, a[8]);
	    accum0 += widemul_32(bhi, a[15]);
	    accum8 += widemul_32(bhi, a[15] + a[7]);

	    c[0] = ((int)accum0) & mask; accum0 >>>= 28;
	    c[8] = ((int)accum8) & mask; accum8 >>>= 28;

	    for (i=1; i<8; i++) {
	        accum0 += widemul_32(blo, a[i]);
	        accum8 += widemul_32(blo, a[i+8]);

	        accum0 += widemul_32(bhi, a[i-1]);
	        accum8 += widemul_32(bhi, a[i+7]);

	        c[i] = ((int)accum0) & mask; accum0 >>>= 28;
	        c[i+8] = ((int)accum8) & mask; accum8 >>>= 28;
	    }

	    accum0 += accum8 + c[8];
	    c[8] = ((int)accum0) & mask;
	    c[9] += accum0 >>> 28;

	    accum8 += c[0];
	    c[0] = ((int)accum8) & mask;
	    c[1] += accum8 >>> 28;
	}

	// p448_weak_reduce
	private static void weak_reduce(int[] a)
	{
	    int mask = (1<<28) - 1;
	    int tmp = a[15] >>> 28;
	    int i;
	    a[8] += tmp;
	    for (i=15; i>0; i--) {
	        a[i] = (a[i] & mask) + (a[i-1]>>>28);
	    }
	    a[0] = (a[0] & mask) + tmp;
	}

	// p448_strong_reduce
	private static void strong_reduce(int[] a)
	{
	    int mask = (1<<28) - 1;

	    /* first, clear high */
	    a[8] += a[15]>>>28;
	    a[0] += a[15]>>>28;
	    a[15] &= mask;

	    /* now the total is less than 2^448 - 2^(448-56) + 2^(448-56+8) < 2p */

	    /* compute total_value - p.  No need to reduce mod p. */

	    long scarry = 0;
	    int i;
	    for (i=0; i<16; i++) {
	        scarry = scarry + (a[i] & 0xFFFFFFFFL) - ((i==8)?mask-1:mask);
	        a[i] = (int)(scarry & mask);
	        scarry >>= 28;
	    }

	    /* uncommon case: it was >= p, so now scarry = 0 and this = x
	     * common case: it was < p, so now scarry = -1 and this = x - p + 2^448
	     * so let's add back in p.  will carry back off the top for 2^448.
	     */

	     int scarry_mask = (int)(scarry & mask);
	     long carry = 0;

	     /* add it back */
	     for (i=0; i<16; i++) {
	         carry = carry + (a[i] & 0xFFFFFFFFL) + ((i==8)?(scarry_mask&~1):scarry_mask);
	         a[i] = (int)(carry & mask);
	         carry >>>= 28;
	     }
	}

	// field_add()
	private static void add(int[] out, int[] a, int[] b)
	{
		for (int i = 0; i < 16; ++i)
			out[i] = a[i] + b[i];
		weak_reduce(out);
	}
	
	// field_sub()
	private static void sub(int[] out, int[] a, int[] b)
	{
		int i;
		
		// p448_sub_RAW(out, a, b)
		for (i = 0; i < 16; ++i)
			out[i] = a[i] - b[i];
		
		// p448_bias(out, 2)
		int co1 = ((1 << 28) - 1) * 2;
		int co2 = co1 - 2;
		for (i = 0; i < 16; ++i) {
			if (i != 8)
				out[i] += co1;
			else
				out[i] += co2;
		}
		
		weak_reduce(out);
	}

	// p448_serialize()
	private static void serialize(byte[] serial, int offset, int[] x)
	{
	    int i,j;
	    for (i=0; i<8; i++) {
	        long limb = x[2*i] + (((long)x[2*i+1])<<28);
	        for (j=0; j<7; j++) {
	            serial[offset+7*i+j] = (byte)limb;
	            limb >>= 8;
	        }
	    }
	}

	private static int is_zero(int x)
	{
	    long xx = x & 0xFFFFFFFFL;
	    xx--;
	    return (int)(xx >> 32);
	}

	// p448_deserialize()
	private static int deserialize(int[] x, byte[] serial, int offset)
	{
	    int i,j;
	    for (i=0; i<8; i++) {
	        long out = 0;
	        for (j=0; j<7; j++) {
	            out |= (serial[offset+7*i+j] & 0xFFL)<<(8*j);
	        }
	        x[2*i] = ((int)out) & ((1<<28)-1);
	        x[2*i+1] = (int)(out >>> 28);
	    }

	    /* Check for reduction.
	     *
	     * The idea is to create a variable ge which is all ones (rather, 56 ones)
	     * if and only if the low $i$ words of $x$ are >= those of p.
	     *
	     * Remember p = little_endian(1111,1111,1111,1111,1110,1111,1111,1111)
	     */
	    int ge = -1, mask = (1<<28)-1;
	    for (i=0; i<8; i++) {
	        ge &= x[i];
	    }

	    /* At this point, ge = 1111 iff bottom are all 1111.  Now propagate if 1110, or set if 1111 */
	    ge = (ge & (x[8] + 1)) | is_zero(x[8] ^ mask);

	    /* Propagate the rest */
	    for (i=9; i<16; i++) {
	        ge &= x[i];
	    }

	    return ~is_zero(ge ^ mask);
	}

	/* End of code imported from Ed448-Goldilocks */
	
	/**
	 * Squares a number modulo 2^448 - 2^224 - 1.
	 * 
	 * @param result The result.
	 * @param x The number to square.
	 */
	private void square(int[] result, int[] x)
	{
		mul(result, x, x);
	}

	/**
	 * Conditional swap of two values.
	 * 
	 * @param select Set to 1 to swap, 0 to leave as-is.
	 * @param x The first value.
	 * @param y The second value.
	 */
	private static void cswap(int select, int[] x, int[] y)
	{
		int dummy;
		select = -select;
		for (int index = 0; index < 16; ++index) {
			dummy = select & (x[index] ^ y[index]);
			x[index] ^= dummy;
			y[index] ^= dummy;
		}
	}

	/**
	 * Computes the reciprocal of a number modulo 2^448 - 2^224 - 1.
	 * 
	 * @param result The result.  Must not overlap with z_2.
	 * @param z_2 The argument.
	 */
	private void recip(int[] result, int[] z_2)
	{
		int posn;
		
	    /* Compute z_2 ^ (p - 2)

	       The value p - 2 is: FF...FEFF...FD, which from highest to lowest is
	       223 one bits, followed by a zero bit, followed by 222 one bits,
	       followed by another zero bit, and a final one bit.

	       The naive implementation that squares for every bit and multiplies
	       for every 1 bit requires 893 multiplications.  The following can
	       do the same operation in 483 multiplications.  The basic idea is to
	       create bit patterns and then "shift" them into position.  We start
	       with a 4 bit pattern 1111, which we can square 4 times to get
	       11110000 and then multiply by the 1111 pattern to get 11111111.
	       We then repeat that to turn 11111111 into 1111111111111111, etc.
	    */
	    square(B, z_2);                 /* Set A to a 4 bit pattern */
	    mul(A, B, z_2);
	    square(B, A);
	    mul(A, B, z_2);
	    square(B, A);
	    mul(A, B, z_2);
	    square(B, A);                   /* Set C to a 6 bit pattern */
	    mul(C, B, z_2);
	    square(B, C);
	    mul(C, B, z_2);
	    square(B, C);                   /* Set A to a 8 bit pattern */
	    mul(A, B, z_2);
	    square(B, A);
	    mul(A, B, z_2);
	    square(E, A);                   /* Set E to a 16 bit pattern */
	    square(B, E);
	    for (posn = 1; posn < 4; ++posn) {
	        square(E, B);
	        square(B, E);
	    }
	    mul(E, B, A);
	    square(AA, E);                  /* Set AA to a 32 bit pattern */
	    square(B, AA);
	    for (posn = 1; posn < 8; ++posn) {
	        square(AA, B);
	        square(B, AA);
	    }
	    mul(AA, B, E);
	    square(BB, AA);                 /* Set BB to a 64 bit pattern */
	    square(B, BB);
	    for (posn = 1; posn < 16; ++posn) {
	        square(BB, B);
	        square(B, BB);
	    }
	    mul(BB, B, AA);
	    square(DA, BB);                 /* Set DA to a 128 bit pattern */
	    square(B, DA);
	    for (posn = 1; posn < 32; ++posn) {
	        square(DA, B);
	        square(B, DA);
	    }
	    mul(DA, B, BB);
	    square(CB, DA);                 /* Set CB to a 192 bit pattern */
	    square(B, CB);                  /* 192 = 128 + 64 */
	    for (posn = 1; posn < 32; ++posn) {
	        square(CB, B);
	        square(B, CB);
	    }
	    mul(CB, B, BB);
	    square(DA, CB);                 /* Set DA to a 208 bit pattern */
	    square(B, DA);                  /* 208 = 128 + 64 + 16 */
	    for (posn = 1; posn < 8; ++posn) {
	        square(DA, B);
	        square(B, DA);
	    }
	    mul(DA, B, E);
	    square(CB, DA);                 /* Set CB to a 216 bit pattern */
	    square(B, CB);                  /* 216 = 128 + 64 + 16 + 8 */
	    for (posn = 1; posn < 4; ++posn) {
	        square(CB, B);
	        square(B, CB);
	    }
	    mul(CB, B, A);
	    square(DA, CB);                 /* Set DA to a 222 bit pattern */
	    square(B, DA);                  /* 222 = 128 + 64 + 16 + 8 + 6 */
	    for (posn = 1; posn < 3; ++posn) {
	        square(DA, B);
	        square(B, DA);
	    }
	    mul(DA, B, C);
	    square(CB, DA);                 /* Set CB to a 224 bit pattern */
	    mul(B, CB, z_2);                /* CB = DA|1|0 */
	    square(CB, B);
	    square(BB, CB);                 /* Set BB to a 446 bit pattern */
	    square(B, BB);                  /* BB = DA|1|0|DA */
	    for (posn = 1; posn < 111; ++posn) {
	        square(BB, B);
	        square(B, BB);
	    }
	    mul(BB, B, DA);
	    square(B, BB);                  /* Set result to a 448 bit pattern */
	    square(BB, B);                  /* result = DA|1|0|DA|01 */
	    mul(result, BB, z_2);
	}

	/**
	 * Evaluates the curve for every bit in a secret key.
	 * 
	 * @param s The 56-byte secret key.
	 */
	private void evalCurve(byte[] s)
	{
		int sposn = 55;
		int sbit = 7;
		int svalue = s[sposn] | 0x80;
		int swap = 0;
		int select;

	    // Iterate over all 448 bits of "s" from the highest to the lowest.
		for (;;) {
	        // Conditional swaps on entry to this bit but only if we
	        // didn't swap on the previous bit.
			select = (svalue >> sbit) & 0x01;
			swap ^= select;
	        cswap(swap, x_2, x_3);
	        cswap(swap, z_2, z_3);
	        swap = select;

	        // Evaluate the curve.
	        add(A, x_2, z_2);               // A = x_2 + z_2
	        square(AA, A);                  // AA = A^2
	        sub(B, x_2, z_2);               // B = x_2 - z_2
	        square(BB, B);                  // BB = B^2
	        sub(E, AA, BB);                 // E = AA - BB
	        add(C, x_3, z_3);               // C = x_3 + z_3
	        sub(D, x_3, z_3);               // D = x_3 - z_3
	        mul(DA, D, A);                  // DA = D * A
	        mul(CB, C, B);                  // CB = C * B
	        add(z_2, DA, CB);               // x_3 = (DA + CB)^2
	        square(x_3, z_2);
	        sub(z_2, DA, CB);               // z_3 = x_1 * (DA - CB)^2
	        square(x_2, z_2);
	        mul(z_3, x_1, x_2);
	        mul(x_2, AA, BB);               // x_2 = AA * BB
	        mulw(z_2, E, 39081);            // z_2 = E * (AA + a24 * E)
	        add(A, AA, z_2);
	        mul(z_2, E, A);

	        // Move onto the next lower bit of "s".
	        if (sbit > 0) {
	        	--sbit;
	        } else if (sposn == 0) {
	        	break;
	        } else if (sposn == 1) {
	        	--sposn;
	        	svalue = s[sposn] & 0xFC;
	        	sbit = 7;
	        } else {
	        	--sposn;
	        	svalue = s[sposn];
	        	sbit = 7;
	        }
		}

	    // Final conditional swaps.
	    cswap(swap, x_2, x_3);
	    cswap(swap, z_2, z_3);
	}

	/**
	 * Evaluates the Curve448 curve.
	 * 
	 * @param result Buffer to place the result of the evaluation into.
	 * @param offset Offset into the result buffer.
	 * @param privateKey The private key to use in the evaluation.
	 * @param publicKey The public key to use in the evaluation, or null
	 * if the base point of the curve should be used.
	 * @return Returns true if the curve evaluation was successful,
	 * false if the publicKey value is out of range.
	 */
	public static boolean eval(byte[] result, int offset, byte[] privateKey, byte[] publicKey)
	{
		Curve448 state = new Curve448();
		int success = -1;
		try {
			// Unpack the public key value.  If null, use 5 as the base point.
			Arrays.fill(state.x_1, 0);
			if (publicKey != null) {
				// Convert the input value from little-endian into 28-bit limbs.
				// It is possible that the public key is out of range.  If so,
				// delay reporting that state until the function completes.
			    success = deserialize(state.x_1, publicKey, 0);
			} else {
				state.x_1[0] = 5;
			}

			// Initialize the other temporary variables.
			Arrays.fill(state.x_2, 0);			// x_2 = 1
			state.x_2[0] = 1;
			Arrays.fill(state.z_2, 0);			// z_2 = 0
			System.arraycopy(state.x_1, 0, state.x_3, 0, state.x_1.length);  // x_3 = x_1
			Arrays.fill(state.z_3, 0);			// z_3 = 1
			state.z_3[0] = 1;
			
			// Evaluate the curve for every bit of the private key.
			state.evalCurve(privateKey);

		    // Compute x_2 * (z_2 ^ (p - 2)) where p = 2^448 - 2^224 - 1.
		    state.recip(state.z_3, state.z_2);
		    state.mul(state.x_1, state.x_2, state.z_3);

		    // Convert x_2 into little-endian in the result buffer.
		    strong_reduce(state.x_1);
		    serialize(result, offset, state.x_1);
		} finally {
			// Clean up all temporary state before we exit.
			state.destroy();
		}
		return (success & 0x01) != 0;
	}
}
