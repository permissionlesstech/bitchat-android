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
package com.bitchat.android.noise.southernstorm.crypto


/**
 * Implementation of the Curve448 elliptic curve algorithm.
 * 
 * Reference: RFC 7748
 */
class Curve448 private constructor() {
    // Numbers modulo 2^448 - 2^224 - 1 are broken up into sixteen 28-bit words.
    private val x_1 = IntArray(16)
    private val x_2 = IntArray(16)
    private val x_3 = IntArray(16)
    private val z_2 = IntArray(16)
    private val z_3 = IntArray(16)
    private val A = IntArray(16)
    private val B = IntArray(16)
    private val C = IntArray(16)
    private val D = IntArray(16)
    private val E = IntArray(16)
    private val AA = IntArray(16)
    private val BB = IntArray(16)
    private val DA = IntArray(16)
    private val CB = IntArray(16)
    private val aa = IntArray(8)
    private val bb = IntArray(8)


    /**
     * Destroy all sensitive data in this object.
     */
    private fun destroy() {
        // Destroy all temporary variables.
        x_1.fill(0)
        x_2.fill(0)
        x_3.fill(0)
        z_2.fill(0)
        z_3.fill(0)
        A.fill(0)
        B.fill(0)
        C.fill(0)
        D.fill(0)
        E.fill(0)
        AA.fill(0)
        BB.fill(0)
        DA.fill(0)
        CB.fill(0)
        aa.fill(0)
        bb.fill(0)
    }

    // p448_mul()
    private fun mul(c: IntArray, a: IntArray, b: IntArray) {
        var accum0 = 0L
        var accum1 = 0L
        val mask = (1 shl 28) - 1

        for (i in 0 until 8) {
            aa[i] = a[i] + a[i + 8]
            bb[i] = b[i] + b[i + 8]
        }

        for (j in 0 until 8) {
            var accum2 = 0L

            for (i in 0..j) {
                accum2 += widemul_32(a[j - i], b[i])
                accum1 += widemul_32(aa[j - i], bb[i])
                accum0 += widemul_32(a[8 + j - i], b[8 + i])
            }

            accum1 -= accum2
            accum0 += accum2
            accum2 = 0

            for (i in (j + 1) until 8) {
                accum0 -= widemul_32(a[8 + j - i], b[i])
                accum2 += widemul_32(aa[8 + j - i], bb[i])
                accum1 += widemul_32(a[16 + j - i], b[8 + i])
            }

            accum1 += accum2
            accum0 += accum2

            c[j] = accum0.toInt() and mask
            c[j + 8] = accum1.toInt() and mask

            accum0 = accum0 ushr 28
            accum1 = accum1 ushr 28
        }

        accum0 += accum1
        accum0 += c[8].toLong()
        accum1 += c[0].toLong()
        c[8] = accum0.toInt() and mask
        c[0] = accum1.toInt() and mask

        accum0 = accum0 ushr 28
        accum1 = accum1 ushr 28
        c[9] += accum0.toInt()
        c[1] += accum1.toInt()
    }

    /* End of code imported from Ed448-Goldilocks */
    /**
     * Squares a number modulo 2^448 - 2^224 - 1.
     * 
     * @param result The result.
     * @param x The number to square.
     */
    private fun square(result: IntArray, x: IntArray) {
        mul(result, x, x)
    }

    /**
     * Computes the reciprocal of a number modulo 2^448 - 2^224 - 1.
     * 
     * @param result The result.  Must not overlap with z_2.
     * @param z_2 The argument.
     */
    private fun recip(result: IntArray, z_2: IntArray) {
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
        square(B, z_2) /* Set A to a 4 bit pattern */
        mul(A, B, z_2)
        square(B, A)
        mul(A, B, z_2)
        square(B, A)
        mul(A, B, z_2)
        square(B, A) /* Set C to a 6 bit pattern */
        mul(C, B, z_2)
        square(B, C)
        mul(C, B, z_2)
        square(B, C) /* Set A to a 8 bit pattern */
        mul(A, B, z_2)
        square(B, A)
        mul(A, B, z_2)
        square(E, A) /* Set E to a 16 bit pattern */
        square(B, E)
        repeat(3) {
            square(E, B)
            square(B, E)
        }
        mul(E, B, A)
        square(AA, E) /* Set AA to a 32 bit pattern */
        square(B, AA)
        repeat(7) {
            square(AA, B)
            square(B, AA)
        }
        mul(AA, B, E)
        square(BB, AA) /* Set BB to a 64 bit pattern */
        square(B, BB)
        repeat(15) {
            square(BB, B)
            square(B, BB)
        }
        mul(BB, B, AA)
        square(DA, BB) /* Set DA to a 128 bit pattern */
        square(B, DA)
        repeat(31) {
            square(DA, B)
            square(B, DA)
        }
        mul(DA, B, BB)
        square(CB, DA) /* Set CB to a 192 bit pattern */
        square(B, CB) /* 192 = 128 + 64 */
        repeat(31) {
            square(CB, B)
            square(B, CB)
        }
        mul(CB, B, BB)
        square(DA, CB) /* Set DA to a 208 bit pattern */
        square(B, DA) /* 208 = 128 + 64 + 16 */
        repeat(7) {
            square(DA, B)
            square(B, DA)
        }
        mul(DA, B, E)
        square(CB, DA) /* Set CB to a 216 bit pattern */
        square(B, CB) /* 216 = 128 + 64 + 16 + 8 */
        repeat(3) {
            square(CB, B)
            square(B, CB)
        }
        mul(CB, B, A)
        square(DA, CB) /* Set DA to a 222 bit pattern */
        square(B, DA) /* 222 = 128 + 64 + 16 + 8 + 6 */
        repeat(2) {
            square(DA, B)
            square(B, DA)
        }
        mul(DA, B, C)
        square(CB, DA) /* Set CB to a 224 bit pattern */
        mul(B, CB, z_2) /* CB = DA|1|0 */
        square(CB, B)
        square(BB, CB) /* Set BB to a 446 bit pattern */
        square(B, BB) /* BB = DA|1|0|DA */
        repeat(110) {
            square(BB, B)
            square(B, BB)
        }
        mul(BB, B, DA)
        square(B, BB) /* Set result to a 448 bit pattern */
        square(BB, B) /* result = DA|1|0|DA|01 */
        mul(result, BB, z_2)
    }

    /**
     * Evaluates the curve for every bit in a secret key.
     * 
     * @param s The 56-byte secret key.
     */
    private fun evalCurve(s: ByteArray) {
        var sposn = 55
        var sbit = 7
        var svalue = s[sposn].toInt() or 0x80
        var swap = 0

        // Iterate over all 448 bits of "s" from the highest to the lowest.
        while (true) {
            // Conditional swaps on entry to this bit but only if we
            // didn't swap on the previous bit.
            val select = (svalue shr sbit) and 0x01
            swap = swap xor select
            cswap(swap, x_2, x_3)
            cswap(swap, z_2, z_3)
            swap = select

            // Evaluate the curve.
            add(A, x_2, z_2) // A = x_2 + z_2
            square(AA, A) // AA = A^2
            sub(B, x_2, z_2) // B = x_2 - z_2
            square(BB, B) // BB = B^2
            sub(E, AA, BB) // E = AA - BB
            add(C, x_3, z_3) // C = x_3 + z_3
            sub(D, x_3, z_3) // D = x_3 - z_3
            mul(DA, D, A) // DA = D * A
            mul(CB, C, B) // CB = C * B
            add(z_2, DA, CB) // x_3 = (DA + CB)^2
            square(x_3, z_2)
            sub(z_2, DA, CB) // z_3 = x_1 * (DA - CB)^2
            square(x_2, z_2)
            mul(z_3, x_1, x_2)
            mul(x_2, AA, BB) // x_2 = AA * BB
            mulw(z_2, E, 39081) // z_2 = E * (AA + a24 * E)
            add(A, AA, z_2)
            mul(z_2, E, A)

            // Move onto the next lower bit of "s".
            if (sbit > 0) {
                --sbit
            } else if (sposn == 0) {
                break
            } else if (sposn == 1) {
                --sposn
                svalue = s[sposn].toInt() and 0xFC
                sbit = 7
            } else {
                --sposn
                svalue = s[sposn].toInt()
                sbit = 7
            }
        }

        // Final conditional swaps.
        cswap(swap, x_2, x_3)
        cswap(swap, z_2, z_3)
    }

    companion object {
        /* Beginning of code imported from Ed448-Goldilocks */
        private fun widemul_32(a: Int, b: Int): Long = (a.toLong()) * b

        // p448_mulw()
        private fun mulw(c: IntArray, a: IntArray, b: Long) {
            val bhi = (b shr 28).toInt()
            val blo = b.toInt() and ((1 shl 28) - 1)

            var accum0 = widemul_32(blo, a[0])
            var accum8 = widemul_32(blo, a[8])
            accum0 += widemul_32(bhi, a[15])
            accum8 += widemul_32(bhi, a[15] + a[7])

            val mask = (1 shl 28) - 1

            c[0] = accum0.toInt() and mask
            accum0 = accum0 ushr 28
            c[8] = accum8.toInt() and mask
            accum8 = accum8 ushr 28

            for (i in 1 until 8) {
                accum0 += widemul_32(blo, a[i])
                accum8 += widemul_32(blo, a[i + 8])

                accum0 += widemul_32(bhi, a[i - 1])
                accum8 += widemul_32(bhi, a[i + 7])

                c[i] = accum0.toInt() and mask
                accum0 = accum0 ushr 28
                c[i + 8] = accum8.toInt() and mask
                accum8 = accum8 ushr 28
            }

            accum0 += accum8 + c[8]
            c[8] = accum0.toInt() and mask
            c[9] += (accum0 ushr 28).toInt()

            accum8 += c[0].toLong()
            c[0] = accum8.toInt() and mask
            c[1] += (accum8 ushr 28).toInt()
        }


        // p448_weak_reduce
        private fun weak_reduce(a: IntArray) {
            val mask = (1 shl 28) - 1
            val tmp = a[15] ushr 28
            a[8] += tmp
            for (i in 15 downTo 1) {
                a[i] = (a[i] and mask) + (a[i - 1] ushr 28)
            }
            a[0] = (a[0] and mask) + tmp
        }

        // p448_strong_reduce
        private fun strong_reduce(a: IntArray) {
            val mask = (1 shl 28) - 1

            /* first, clear high */
            a[8] += a[15] ushr 28
            a[0] += a[15] ushr 28
            a[15] = a[15] and mask

            var scarry = 0L
            for (i in 0 until 16) {
                scarry = scarry + (a[i].toLong() and 0xFFFFFFFFL) - (if (i == 8) mask - 1 else mask)
                a[i] = (scarry and mask.toLong()).toInt()
                scarry = scarry shr 28
            }

            val scarryMask = (scarry and mask.toLong()).toInt()
            var carry = 0L

            for (i in 0 until 16) {
                carry += (a[i].toLong() and 0xFFFFFFFFL) + (if (i == 8) scarryMask and 1.inv() else scarryMask)
                a[i] = (carry and mask.toLong()).toInt()
                carry = carry ushr 28
            }
        }


        // field_add()
        private fun add(out: IntArray, a: IntArray, b: IntArray) {
            for (i in 0..15) out[i] = a[i] + b[i]
            weak_reduce(out)
        }

        // field_sub()
        private fun sub(out: IntArray, a: IntArray, b: IntArray) {
            for (i in 0 until 16) {
                out[i] = a[i] - b[i]
            }

            val co1 = ((1 shl 28) - 1) * 2
            val co2 = co1 - 2
            for (i in 0 until 16) {
                if (i != 8) out[i] += co1 else out[i] += co2
            }

            weak_reduce(out)
        }

        // p448_serialize()
        private fun serialize(serial: ByteArray, offset: Int, x: IntArray) {
            for (i in 0 until 8) {
                var limb = x[2 * i].toLong() + (x[2 * i + 1].toLong() shl 28)
                for (j in 0 until 7) {
                    serial[offset + 7 * i + j] = limb.toByte()
                    limb = limb shr 8
                }
            }
        }


        private fun is_zero(x: Int): Int {
            var xx = x.toLong() and 0xFFFFFFFFL
            xx--
            return (xx shr 32).toInt()
        }

        // p448_deserialize()
        private fun deserialize(x: IntArray, serial: ByteArray, offset: Int): Int {
            for (i in 0 until 8) {
                var out = 0L
                for (j in 0 until 7) {
                    out = out or ((serial[offset + 7 * i + j].toLong() and 0xFFL) shl (8 * j))
                }
                x[2 * i] = out.toInt() and ((1 shl 28) - 1)
                x[2 * i + 1] = (out ushr 28).toInt()
            }

            /* Check for reduction.
	     *
	     * The idea is to create a variable ge which is all ones (rather, 56 ones)
	     * if and only if the low $i$ words of $x$ are >= those of p.
	     *
	     * Remember p = little_endian(1111,1111,1111,1111,1110,1111,1111,1111)
	     */
            var ge = -1
            val mask = (1 shl 28) - 1
            for (i in 0 until 8) {
                ge = ge and x[i]
            }

            /* At this point, ge = 1111 iff bottom are all 1111.  Now propagate if 1110, or set if 1111 */
            ge = (ge and (x[8] + 1)) or is_zero(x[8] xor mask)

            /* Propagate the rest */
            for (i in 9 until 16) {
                ge = ge and x[i]
            }


            return is_zero(ge xor mask).inv()
        }

        /**
         * Conditional swap of two values.
         * 
         * @param select Set to 1 to swap, 0 to leave as-is.
         * @param x The first value.
         * @param y The second value.
         */
        private fun cswap(select: Int, x: IntArray, y: IntArray) {
            val s = -select
            for (index in 0..15) {
                val dummy = s and (x[index] xor y[index])
                x[index] = x[index] xor dummy
                y[index] = y[index] xor dummy
            }
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
        @JvmStatic
        fun eval(
            result: ByteArray,
            offset: Int,
            privateKey: ByteArray,
            publicKey: ByteArray?
        ): Boolean {
            val state = Curve448()
            var success = -1
            try {
                // Unpack the public key value.  If null, use 5 as the base point.
                state.x_1.fill(0)
                if (publicKey != null) {
                    // Convert the input value from little-endian into 28-bit limbs.
                    // It is possible that the public key is out of range.  If so,
                    // delay reporting that state until the function completes.
                    success = deserialize(state.x_1, publicKey, 0)
                } else {
                    state.x_1[0] = 5
                }

                // Initialize the other temporary variables.
                state.x_2.fill(0)
                state.x_2[0] = 1
                state.z_2.fill(0)
                state.x_1.copyInto(state.x_3)
                state.z_3.fill(0)
                state.z_3[0] = 1


                // Evaluate the curve for every bit of the private key.
                state.evalCurve(privateKey)

                // Compute x_2 * (z_2 ^ (p - 2)) where p = 2^448 - 2^224 - 1.
                state.recip(state.z_3, state.z_2)
                state.mul(state.x_1, state.x_2, state.z_3)

                // Convert x_2 into little-endian in the result buffer.
                strong_reduce(state.x_1)
                serialize(result, offset, state.x_1)
            } finally {
                // Clean up all temporary state before we exit.
                state.destroy()
            }
            return (success and 0x01) != 0
        }
    }
}
