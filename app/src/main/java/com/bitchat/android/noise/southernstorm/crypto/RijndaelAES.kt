// This implementation is a straight C-to-Java port of the
// public domain code from the original Rijndael authors:
// http://web.cs.ucdavis.edu/~rogaway/ocb/ocb-ref/
// The original license declaration follows (all modifications
// are released under the same terms):
/*
 * rijndael-alg-fst.c
 *
 * @version 3.0 (December 2000)
 *
 * Optimised ANSI C code for the Rijndael cipher (now AES)
 *
 * @author Vincent Rijmen <vincent.rijmen@esat.kuleuven.ac.be>
 * @author Antoon Bosselaers <antoon.bosselaers@esat.kuleuven.ac.be>
 * @author Paulo Barreto <paulo.barreto@terra.com.br>
 *
 * This code is hereby placed in the public domain.
 *
 * THIS SOFTWARE IS PROVIDED BY THE AUTHORS ''AS IS'' AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE AUTHORS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE,
 * EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.bitchat.android.noise.southernstorm.crypto


/**
 * Public domain fallback implementation of AES in ECB mode.
 */
class RijndaelAES {
    private val rk = IntArray(60)
    private var Nr = 14

    /**
     * Destroys the sensitive state in this key schedule.
     */
    fun destroy() {
        rk.fill(0)
    }

    /**
     * Expand the cipher key into the encryption key schedule.
     * 
     * @return      the number of rounds for the given cipher key size.
     */
    fun setupEnc(cipherKey: ByteArray, offset: Int, keyBits: Int): Int {
        var i = 0
        var temp: Int

        rk[0] = GETU32(cipherKey, offset)
        rk[1] = GETU32(cipherKey, offset + 4)
        rk[2] = GETU32(cipherKey, offset + 8)
        rk[3] = GETU32(cipherKey, offset + 12)
        var rkoffset = 0

        if (keyBits == 128) {
            while (true) {
                temp = rk[rkoffset + 3]
                rk[rkoffset + 4] = rk[rkoffset] xor
                        (Te4[(temp shr 16) and 0xff] and -0x1000000) xor
                        (Te4[(temp shr 8) and 0xff] and 0x00ff0000) xor
                        (Te4[temp and 0xff] and 0x0000ff00) xor
                        (Te4[(temp shr 24) and 0xff] and 0x000000ff) xor
                        rcon[i]
                rk[rkoffset + 5] = rk[rkoffset + 1] xor rk[rkoffset + 4]
                rk[rkoffset + 6] = rk[rkoffset + 2] xor rk[rkoffset + 5]
                rk[rkoffset + 7] = rk[rkoffset + 3] xor rk[rkoffset + 6]
                if (++i == 10) {
                    Nr = 10
                    return Nr
                }
                rkoffset += 4
            }
        }

        rk[rkoffset + 4] = GETU32(cipherKey, offset + 16)
        rk[rkoffset + 5] = GETU32(cipherKey, offset + 20)

        if (keyBits == 192) {
            while (true) {
                temp = rk[rkoffset + 5]
                rk[rkoffset + 6] = rk[rkoffset] xor
                        (Te4[(temp shr 16) and 0xff] and -0x1000000) xor
                        (Te4[(temp shr 8) and 0xff] and 0x00ff0000) xor
                        (Te4[temp and 0xff] and 0x0000ff00) xor
                        (Te4[(temp shr 24) and 0xff] and 0x000000ff) xor
                        rcon[i]
                rk[rkoffset + 7] = rk[rkoffset + 1] xor rk[rkoffset + 6]
                rk[rkoffset + 8] = rk[rkoffset + 2] xor rk[rkoffset + 7]
                rk[rkoffset + 9] = rk[rkoffset + 3] xor rk[rkoffset + 8]
                if (++i == 8) {
                    Nr = 12
                    return Nr
                }
                rk[rkoffset + 10] = rk[rkoffset + 4] xor rk[rkoffset + 9]
                rk[rkoffset + 11] = rk[rkoffset + 5] xor rk[rkoffset + 10]
                rkoffset += 6
            }
        }

        rk[rkoffset + 6] = GETU32(cipherKey, offset + 24)
        rk[rkoffset + 7] = GETU32(cipherKey, offset + 28)

        if (keyBits == 256) {
            while (true) {
                temp = rk[rkoffset + 7]
                rk[rkoffset + 8] = rk[rkoffset + 0] xor
                        (Te4[(temp shr 16) and 0xff] and -0x1000000) xor
                        (Te4[(temp shr 8) and 0xff] and 0x00ff0000) xor
                        (Te4[(temp) and 0xff] and 0x0000ff00) xor
                        (Te4[(temp shr 24) and 0xff] and 0x000000ff) xor
                        rcon[i]
                rk[rkoffset + 9] = rk[rkoffset + 1] xor rk[rkoffset + 8]
                rk[rkoffset + 10] = rk[rkoffset + 2] xor rk[rkoffset + 9]
                rk[rkoffset + 11] = rk[rkoffset + 3] xor rk[rkoffset + 10]
                if (++i == 7) {
                    Nr = 14
                    return Nr
                }
                temp = rk[rkoffset + 11]
                rk[rkoffset + 12] = rk[rkoffset + 4] xor
                        (Te4[(temp shr 24) and 0xff] and -0x1000000) xor
                        (Te4[(temp shr 16) and 0xff] and 0x00ff0000) xor
                        (Te4[(temp shr 8) and 0xff] and 0x0000ff00) xor
                        (Te4[(temp) and 0xff] and 0x000000ff)
                rk[rkoffset + 13] = rk[rkoffset + 5] xor rk[rkoffset + 12]
                rk[rkoffset + 14] = rk[rkoffset + 6] xor rk[rkoffset + 13]
                rk[rkoffset + 15] = rk[rkoffset + 7] xor rk[rkoffset + 14]

                rkoffset += 8
            }
        }
        return 0
    }

    /**
     * Expand the cipher key into the decryption key schedule.
     * 
     * @return      the number of rounds for the given cipher key size.
     */
    fun setupDec(cipherKey: ByteArray, offset: Int, keyBits: Int): Int {
        val Nr = setupEnc(cipherKey, offset, keyBits)
        var i = 0
        var j = 4 * Nr

        while (i < j) {
            var temp = rk[i]
            rk[i] = rk[j]
            rk[j] = temp

            temp = rk[i + 1]
            rk[i + 1] = rk[j + 1]
            rk[j + 1] = temp

            temp = rk[i + 2]
            rk[i + 2] = rk[j + 2]
            rk[j + 2] = temp

            temp = rk[i + 3]
            rk[i + 3] = rk[j + 3]
            rk[j + 3] = temp

            i += 4
            j -= 4
        }

        /* apply the inverse MixColumn transform to all round keys but the first and the last: */
        var rkoffset = 0

        for (round in 1 until Nr) {
            rkoffset += 4
            rk[rkoffset + 0] =
                Td0[Te4[(rk[rkoffset] shr 24) and 0xff] and 0xff] xor
                        Td1[Te4[(rk[rkoffset] shr 16) and 0xff] and 0xff] xor
                        Td2[Te4[(rk[rkoffset] shr 8) and 0xff] and 0xff] xor
                        Td3[Te4[rk[rkoffset] and 0xff] and 0xff]
            rk[rkoffset + 1] =
                Td0[Te4[(rk[rkoffset + 1] shr 24) and 0xff] and 0xff] xor
                        Td1[Te4[(rk[rkoffset + 1] shr 16) and 0xff] and 0xff] xor
                        Td2[Te4[(rk[rkoffset + 1] shr 8) and 0xff] and 0xff] xor
                        Td3[Te4[rk[rkoffset + 1] and 0xff] and 0xff]
            rk[rkoffset + 2] =
                Td0[Te4[(rk[rkoffset + 2] shr 24) and 0xff] and 0xff] xor
                        Td1[Te4[(rk[rkoffset + 2] shr 16) and 0xff] and 0xff] xor
                        Td2[Te4[(rk[rkoffset + 2] shr 8) and 0xff] and 0xff] xor
                        Td3[Te4[rk[rkoffset + 2] and 0xff] and 0xff]
            rk[rkoffset + 3] =
                Td0[Te4[(rk[rkoffset + 3] shr 24) and 0xff] and 0xff] xor
                        Td1[Te4[(rk[rkoffset + 3] shr 16) and 0xff] and 0xff] xor
                        Td2[Te4[(rk[rkoffset + 3] shr 8) and 0xff] and 0xff] xor
                        Td3[Te4[rk[rkoffset + 3] and 0xff] and 0xff]
        }
        this.Nr = Nr
        return Nr
    }

    fun encrypt(pt: ByteArray, ptoffset: Int, ct: ByteArray, ctoffset: Int) {
        var s0 = GETU32(pt, ptoffset) xor rk[0]
        var s1 = GETU32(pt, ptoffset + 4) xor rk[1]
        var s2 = GETU32(pt, ptoffset + 8) xor rk[2]
        var s3 = GETU32(pt, ptoffset + 12) xor rk[3]

        var t0: Int; var t1: Int; var t2: Int; var t3: Int
        var r = Nr shr 1
        var rkoffset = 0

        while (true) {
            t0 =
                Te0[(s0 shr 24) and 0xff] xor
                        Te1[(s1 shr 16) and 0xff] xor
                        Te2[(s2 shr 8) and 0xff] xor
                        Te3[(s3) and 0xff] xor
                        rk[rkoffset + 4]
            t1 =
                Te0[(s1 shr 24) and 0xff] xor
                        Te1[(s2 shr 16) and 0xff] xor
                        Te2[(s3 shr 8) and 0xff] xor
                        Te3[(s0) and 0xff] xor
                        rk[rkoffset + 5]
            t2 =
                Te0[(s2 shr 24) and 0xff] xor
                        Te1[(s3 shr 16) and 0xff] xor
                        Te2[(s0 shr 8) and 0xff] xor
                        Te3[(s1) and 0xff] xor
                        rk[rkoffset + 6]
            t3 =
                Te0[(s3 shr 24) and 0xff] xor
                        Te1[(s0 shr 16) and 0xff] xor
                        Te2[(s1 shr 8) and 0xff] xor
                        Te3[(s2) and 0xff] xor
                        rk[rkoffset + 7]

            rkoffset += 8
            if (--r == 0) {
                break
            }

            s0 =
                Te0[(t0 shr 24) and 0xff] xor
                        Te1[(t1 shr 16) and 0xff] xor
                        Te2[(t2 shr 8) and 0xff] xor
                        Te3[(t3) and 0xff] xor
                        rk[rkoffset]
            s1 =
                Te0[(t1 shr 24) and 0xff] xor
                        Te1[(t2 shr 16) and 0xff] xor
                        Te2[(t3 shr 8) and 0xff] xor
                        Te3[(t0) and 0xff] xor
                        rk[rkoffset + 1]
            s2 =
                Te0[(t2 shr 24) and 0xff] xor
                        Te1[(t3 shr 16) and 0xff] xor
                        Te2[(t0 shr 8) and 0xff] xor
                        Te3[(t1) and 0xff] xor
                        rk[rkoffset + 2]
            s3 =
                Te0[(t3 shr 24) and 0xff] xor
                        Te1[(t0 shr 16) and 0xff] xor
                        Te2[(t1 shr 8) and 0xff] xor
                        Te3[(t2) and 0xff] xor
                        rk[rkoffset + 3]
        }

        /*
         * apply last round and
         * map cipher state to byte array block:
         */
        s0 =
            (Te4[(t0 shr 24) and 0xff] and -0x1000000) xor
                    (Te4[(t1 shr 16) and 0xff] and 0x00ff0000) xor
                    (Te4[(t2 shr 8) and 0xff] and 0x0000ff00) xor
                    (Te4[(t3) and 0xff] and 0x000000ff) xor
                    rk[rkoffset]
        PUTU32(ct, ctoffset, s0)
        s1 =
            (Te4[(t1 shr 24) and 0xff] and -0x1000000) xor
                    (Te4[(t2 shr 16) and 0xff] and 0x00ff0000) xor
                    (Te4[(t3 shr 8) and 0xff] and 0x0000ff00) xor
                    (Te4[(t0) and 0xff] and 0x000000ff) xor
                    rk[rkoffset + 1]
        PUTU32(ct, ctoffset + 4, s1)
        s2 =
            (Te4[(t2 shr 24) and 0xff] and -0x1000000) xor
                    (Te4[(t3 shr 16) and 0xff] and 0x00ff0000) xor
                    (Te4[(t0 shr 8) and 0xff] and 0x0000ff00) xor
                    (Te4[(t1) and 0xff] and 0x000000ff) xor
                    rk[rkoffset + 2]
        PUTU32(ct, ctoffset + 8, s2)
        s3 =
            (Te4[(t3 shr 24) and 0xff] and -0x1000000) xor
                    (Te4[(t0 shr 16) and 0xff] and 0x00ff0000) xor
                    (Te4[(t1 shr 8) and 0xff] and 0x0000ff00) xor
                    (Te4[(t2) and 0xff] and 0x000000ff) xor
                    rk[rkoffset + 3]
        PUTU32(ct, ctoffset + 12, s3)
    }

    fun decrypt(ct: ByteArray, ctoffset: Int, pt: ByteArray, ptoffset: Int) {
        var s0 = GETU32(ct, ctoffset) xor rk[0]
        var s1 = GETU32(ct, ctoffset + 4) xor rk[1]
        var s2 = GETU32(ct, ctoffset + 8) xor rk[2]
        var s3 = GETU32(ct, ctoffset + 12) xor rk[3]

        var t0: Int; var t1: Int; var t2: Int; var t3: Int
        var r = Nr shr 1
        var rkoffset = 0

        while (true) {
            t0 =
                Td0[(s0 shr 24) and 0xff] xor
                        Td1[(s3 shr 16) and 0xff] xor
                        Td2[(s2 shr 8) and 0xff] xor
                        Td3[(s1) and 0xff] xor
                        rk[rkoffset + 4]
            t1 =
                Td0[(s1 shr 24) and 0xff] xor
                        Td1[(s0 shr 16) and 0xff] xor
                        Td2[(s3 shr 8) and 0xff] xor
                        Td3[(s2) and 0xff] xor
                        rk[rkoffset + 5]
            t2 =
                Td0[(s2 shr 24) and 0xff] xor
                        Td1[(s1 shr 16) and 0xff] xor
                        Td2[(s0 shr 8) and 0xff] xor
                        Td3[(s3) and 0xff] xor
                        rk[rkoffset + 6]
            t3 =
                Td0[(s3 shr 24) and 0xff] xor
                        Td1[(s2 shr 16) and 0xff] xor
                        Td2[(s1 shr 8) and 0xff] xor
                        Td3[(s0) and 0xff] xor
                        rk[rkoffset + 7]

            rkoffset += 8
            if (--r == 0) {
                break
            }

            s0 =
                Td0[(t0 shr 24) and 0xff] xor
                        Td1[(t3 shr 16) and 0xff] xor
                        Td2[(t2 shr 8) and 0xff] xor
                        Td3[(t1) and 0xff] xor
                        rk[rkoffset]
            s1 =
                Td0[(t1 shr 24) and 0xff] xor
                        Td1[(t0 shr 16) and 0xff] xor
                        Td2[(t3 shr 8) and 0xff] xor
                        Td3[(t2) and 0xff] xor
                        rk[rkoffset + 1]
            s2 =
                Td0[(t2 shr 24) and 0xff] xor
                        Td1[(t1 shr 16) and 0xff] xor
                        Td2[(t0 shr 8) and 0xff] xor
                        Td3[(t3) and 0xff] xor
                        rk[rkoffset + 2]
            s3 =
                Td0[(t3 shr 24) and 0xff] xor
                        Td1[(t2 shr 16) and 0xff] xor
                        Td2[(t1 shr 8) and 0xff] xor
                        Td3[(t0) and 0xff] xor
                        rk[rkoffset + 3]
        }

        /*
         * apply last round and
         * map cipher state to byte array block:
         */
        s0 =
            (Td4[(t0 shr 24) and 0xff] and -0x1000000) xor
                    (Td4[(t3 shr 16) and 0xff] and 0x00ff0000) xor
                    (Td4[(t2 shr 8) and 0xff] and 0x0000ff00) xor
                    (Td4[(t1) and 0xff] and 0x000000ff) xor
                    rk[rkoffset]
        PUTU32(pt, ptoffset, s0)
        s1 =
            (Td4[(t1 shr 24) and 0xff] and -0x1000000) xor
                    (Td4[(t0 shr 16) and 0xff] and 0x00ff0000) xor
                    (Td4[(t3 shr 8) and 0xff] and 0x0000ff00) xor
                    (Td4[(t2) and 0xff] and 0x000000ff) xor
                    rk[rkoffset + 1]
        PUTU32(pt, ptoffset + 4, s1)
        s2 =
            (Td4[(t2 shr 24) and 0xff] and -0x1000000) xor
                    (Td4[(t1 shr 16) and 0xff] and 0x00ff0000) xor
                    (Td4[(t0 shr 8) and 0xff] and 0x0000ff00) xor
                    (Td4[(t3) and 0xff] and 0x000000ff) xor
                    rk[rkoffset + 2]
        PUTU32(pt, ptoffset + 8, s2)
        s3 =
            (Td4[(t3 shr 24) and 0xff] and -0x1000000) xor
                    (Td4[(t2 shr 16) and 0xff] and 0x00ff0000) xor
                    (Td4[(t1 shr 8) and 0xff] and 0x0000ff00) xor
                    (Td4[(t0) and 0xff] and 0x000000ff) xor
                    rk[rkoffset + 3]
        PUTU32(pt, ptoffset + 12, s3)
    }

    companion object {
        private fun GETU32(buf: ByteArray, offset: Int): Int {
            return ((buf[offset].toInt() and 0xFF) shl 24) or
                    ((buf[offset + 1].toInt() and 0xFF) shl 16) or
                    ((buf[offset + 2].toInt() and 0xFF) shl 8) or
                    (buf[offset + 3].toInt() and 0xFF)
        }

        private fun PUTU32(buf: ByteArray, offset: Int, value: Int) {
            buf[offset] = (value shr 24).toByte()
            buf[offset + 1] = (value shr 16).toByte()
            buf[offset + 2] = (value shr 8).toByte()
            buf[offset + 3] = value.toByte()
        }

        private val Te0 = intArrayOf(
            -0x399c9c5b, -0x783837c, -0x11888867, -0x9848473,
            -0xd0df3, -0x29949443, -0x2190904f, -0x6e3a3aac,
            0x60303050, 0x02010103, -0x31989857, 0x562b2b7d,
            -0x180101e7, -0x4a28289e, 0x4dababe6, -0x13898966,
            -0x703535bb, 0x1f82829d, -0x763636c0, -0x5828279,
            -0x100505eb, -0x4da6a615, -0x71b8b837, -0x40f0ff5,
            0x41adadec, -0x4c2b2b99, 0x5fa2a2fd, 0x45afafea,
            0x239c9cbf, 0x53a4a4f7, -0x1b8d8d6a, -0x643f3fa5,
            0x75b7b7c2, -0x1e0202e4, 0x3d9393ae, 0x4c26266a,
            0x6c36365a, 0x7e3f3f41, -0xa0808fe, -0x7c3333b1,
            0x6834345c, 0x51a5a5f4, -0x2e1a1acc, -0x60e0ef8,
            -0x1d8e8e6d, -0x5427278d, 0x62313153, 0x2a15153f,
            0x0804040c, -0x6a3838ae, 0x46232365, -0x623c3ca2,
            0x30181828, 0x379696a1, 0x0a05050f, 0x2f9a9ab5,
            0x0e070709, 0x24121236, 0x1b80809b, -0x201d1dc3,
            -0x321414da, 0x4e272769, 0x7fb2b2cd, -0x158a8a61,
            0x1209091b, 0x1d83839e, 0x582c2c74, 0x341a1a2e,
            0x361b1b2d, -0x2391914e, -0x4ba5a512, 0x5ba0a0fb,
            -0x5badad0a, 0x763b3b4d, -0x4829299f, 0x7db3b3ce,
            0x5229297b, -0x221c1cc2, 0x5e2f2f71, 0x13848497,
            -0x59acac0b, -0x462e2e98, 0x00000000, -0x3e1212d4,
            0x40202060, -0x1c0303e1, 0x79b1b1c8, -0x49a4a413,
            -0x2b959542, -0x723434ba, 0x67bebed9, 0x7239394b,
            -0x6bb5b522, -0x67b3b32c, -0x4fa7a718, -0x7a3030b6,
            -0x442f2f95, -0x3a1010d6, 0x4faaaae5, -0x120404ea,
            -0x79bcbc3b, -0x65b2b229, 0x66333355, 0x11858594,
            -0x75baba31, -0x160606f0, 0x04020206, -0x180807f,
            -0x5fafaf10, 0x783c3c44, 0x259f9fba, 0x4ba8a8e3,
            -0x5daeae0d, 0x5da3a3fe, -0x7fbfbf40, 0x058f8f8a,
            0x3f9292ad, 0x219d9dbc, 0x70383848, -0xe0a0afc,
            0x63bcbcdf, 0x77b6b6c1, -0x5025258b, 0x42212163,
            0x20101030, -0x1a0000e6, -0x20c0cf2, -0x402d2d93,
            -0x7e3232b4, 0x180c0c14, 0x26131335, -0x3c1313d1,
            -0x41a0a01f, 0x359797a2, -0x77bbbb34, 0x2e171739,
            -0x6c3b3ba9, 0x55a7a7f2, -0x381817e, 0x7a3d3d47,
            -0x379b9b54, -0x45a2a219, 0x3219192b, -0x198c8c6b,
            -0x3f9f9f60, 0x19818198, -0x61b0b02f, -0x5c232381,
            0x44222266, 0x542a2a7e, 0x3b9090ab, 0x0b888883,
            -0x73b9b936, -0x381111d7, 0x6bb8b8d3, 0x2814143c,
            -0x58212187, -0x43a1a11e, 0x160b0b1d, -0x5224248a,
            -0x241f1fc5, 0x64323256, 0x743a3a4e, 0x140a0a1e,
            -0x6db6b625, 0x0c06060a, 0x4824246c, -0x47a3a31c,
            -0x603d3da3, -0x422c2c92, 0x43acacef, -0x3b9d9d5a,
            0x399191a8, 0x319595a4, -0x2c1b1bc9, -0xd868675,
            -0x2a1818ce, -0x743737bd, 0x6e373759, -0x25929249,
            0x018d8d8c, -0x4e2a2a9c, -0x63b1b12e, 0x49a9a9e0,
            -0x2793934c, -0x53a9a906, -0xc0b0bf9, -0x301515db,
            -0x359a9a51, -0xb858572, 0x47aeaee9, 0x10080818,
            0x6fbabad5, -0xf878778, 0x4a25256f, 0x5c2e2e72,
            0x381c1c24, 0x57a6a6f1, 0x73b4b4c7, -0x683939af,
            -0x341717dd, -0x5e222284, -0x178b8b64, 0x3e1f1f21,
            -0x69b4b423, 0x61bdbddc, 0x0d8b8b86, 0x0f8a8a85,
            -0x1f8f8f70, 0x7c3e3e42, 0x71b5b5c4, -0x33999956,
            -0x6fb7b728, 0x06030305, -0x80909ff, 0x1c0e0e12,
            -0x3d9e9e5d, 0x6a35355f, -0x51a8a807, 0x69b9b9d0,
            0x17868691, -0x663e3ea8, 0x3a1d1d27, 0x279e9eb9,
            -0x261e1ec8, -0x140707ed, 0x2b9898b3, 0x22111133,
            -0x2d969645, -0x56262690, 0x078e8e89, 0x339494a7,
            0x2d9b9bb6, 0x3c1e1e22, 0x15878792, -0x361616e0,
            -0x783131b7, -0x55aaaa01, 0x50282878, -0x5a202086,
            0x038c8c8f, 0x59a1a1f8, 0x09898980, 0x1a0d0d17,
            0x65bfbfda, -0x281919cf, -0x7bbdbd3a, -0x2f979748,
            -0x7dbebe3d, 0x299999b0, 0x5a2d2d77, 0x1e0f0f11,
            0x7bb0b0cb, -0x57abab04, 0x6dbbbbd6, 0x2c16163a,
        )
        private val Te1 = intArrayOf(
            -0x5a399c9d, -0x7b078384, -0x66118889, -0x72098485,
            0x0dfff2f2, -0x42299495, -0x4e219091, 0x5491c5c5,
            0x50603030, 0x03020101, -0x56319899, 0x7d562b2b,
            0x19e7fefe, 0x62b5d7d7, -0x19b25455, -0x6513898a,
            0x458fcaca, -0x62e07d7e, 0x4089c9c9, -0x78058283,
            0x15effafa, -0x144da6a7, -0x3671b8b9, 0x0bfbf0f0,
            -0x13be5253, 0x67b3d4d4, -0x2a05d5e, -0x15ba5051,
            -0x40dc6364, -0x8ac5b5c, -0x691b8d8e, 0x5b9bc0c0,
            -0x3d8a4849, 0x1ce1fdfd, -0x51c26c6d, 0x6a4c2626,
            0x5a6c3636, 0x417e3f3f, 0x02f5f7f7, 0x4f83cccc,
            0x5c683434, -0xbae5a5b, 0x34d1e5e5, 0x08f9f1f1,
            -0x6c1d8e8f, 0x73abd8d8, 0x53623131, 0x3f2a1515,
            0x0c080404, 0x5295c7c7, 0x65462323, 0x5e9dc3c3,
            0x28301818, -0x5ec8696a, 0x0f0a0505, -0x4ad06566,
            0x090e0707, 0x36241212, -0x64e47f80, 0x3ddfe2e2,
            0x26cdebeb, 0x694e2727, -0x32804d4e, -0x60158a8b,
            0x1b120909, -0x61e27c7d, 0x74582c2c, 0x2e341a1a,
            0x2d361b1b, -0x4d239192, -0x114ba5a6, -0x4a45f60,
            -0x95badae, 0x4d763b3b, 0x61b7d6d6, -0x31824c4d,
            0x7b522929, 0x3edde3e3, 0x715e2f2f, -0x68ec7b7c,
            -0xa59acad, 0x68b9d1d1, 0x00000000, 0x2cc1eded,
            0x60402020, 0x1fe3fcfc, -0x37864e4f, -0x1249a4a5,
            -0x412b9596, 0x468dcbcb, -0x26984142, 0x4b723939,
            -0x216bb5b6, -0x2b67b3b4, -0x174fa7a8, 0x4a85cfcf,
            0x6bbbd0d0, 0x2ac5efef, -0x1ab05556, 0x16edfbfb,
            -0x3a79bcbd, -0x2865b2b3, 0x55663333, -0x6bee7a7b,
            -0x3075babb, 0x10e9f9f9, 0x06040202, -0x7e018081,
            -0xf5fafb0, 0x44783c3c, -0x45da6061, -0x1cb45758,
            -0xc5daeaf, -0x1a25c5d, -0x3f7fbfc0, -0x75fa7071,
            -0x52c06d6e, -0x43de6263, 0x48703838, 0x04f1f5f5,
            -0x209c4344, -0x3e88494a, 0x75afdada, 0x63422121,
            0x30201010, 0x1ae5ffff, 0x0efdf3f3, 0x6dbfd2d2,
            0x4c81cdcd, 0x14180c0c, 0x35261313, 0x2fc3ecec,
            -0x1e41a0a1, -0x5dca6869, -0x3377bbbc, 0x392e1717,
            0x5793c4c4, -0xdaa5859, -0x7d038182, 0x477a3d3d,
            -0x53379b9c, -0x1845a2a3, 0x2b321919, -0x6a198c8d,
            -0x5f3f9fa0, -0x67e67e7f, -0x2e61b0b1, 0x7fa3dcdc,
            0x66442222, 0x7e542a2a, -0x54c46f70, -0x7cf47778,
            -0x3573b9ba, 0x29c7eeee, -0x2c944748, 0x3c281414,
            0x79a7dede, -0x1d43a1a2, 0x1d160b0b, 0x76addbdb,
            0x3bdbe0e0, 0x56643232, 0x4e743a3a, 0x1e140a0a,
            -0x246db6b7, 0x0a0c0606, 0x6c482424, -0x1b47a3a4,
            0x5d9fc2c2, 0x6ebdd3d3, -0x10bc5354, -0x593b9d9e,
            -0x57c66e6f, -0x5bce6a6b, 0x37d3e4e4, -0x740d8687,
            0x32d5e7e7, 0x438bc8c8, 0x596e3737, -0x48259293,
            -0x73fe7273, 0x64b1d5d5, -0x2d63b1b2, -0x1fb65657,
            -0x4b279394, -0x553a9aa, 0x07f3f4f4, 0x25cfeaea,
            -0x50359a9b, -0x710b8586, -0x16b85152, 0x18100808,
            -0x2a904546, -0x770f8788, 0x6f4a2525, 0x725c2e2e,
            0x24381c1c, -0xea8595a, -0x388c4b4c, 0x5197c6c6,
            0x23cbe8e8, 0x7ca1dddd, -0x63178b8c, 0x213e1f1f,
            -0x2269b4b5, -0x239e4243, -0x79f27475, -0x7af07576,
            -0x6f1f8f90, 0x427c3e3e, -0x3b8e4a4b, -0x5533999a,
            -0x276fb7b8, 0x05060303, 0x01f7f6f6, 0x121c0e0e,
            -0x5c3d9e9f, 0x5f6a3535, -0x651a8a9, -0x2f964647,
            -0x6ee8797a, 0x5899c1c1, 0x273a1d1d, -0x46d86162,
            0x38d9e1e1, 0x13ebf8f8, -0x4cd46768, 0x33221111,
            -0x442d9697, 0x70a9d9d9, -0x76f87172, -0x58cc6b6c,
            -0x49d26465, 0x223c1e1e, -0x6dea7879, 0x20c9e9e9,
            0x4987cece, -0x55aaab, 0x78502828, 0x7aa5dfdf,
            -0x70fc7374, -0x7a65e5f, -0x7ff67677, 0x171a0d0d,
            -0x259a4041, 0x31d7e6e6, -0x397bbdbe, -0x472f9798,
            -0x3c7dbebf, -0x4fd66667, 0x775a2d2d, 0x111e0f0f,
            -0x34844f50, -0x357abac, -0x29924445, 0x3a2c1616,
        )
        private val Te2 = intArrayOf(
            0x63a5c663, 0x7c84f87c, 0x7799ee77, 0x7b8df67b,
            -0xdf2000e, 0x6bbdd66b, 0x6fb1de6f, -0x3aab6e3b,
            0x30506030, 0x01030201, 0x67a9ce67, 0x2b7d562b,
            -0x1e61802, -0x289d4a29, -0x5419b255, 0x769aec76,
            -0x35ba7036, -0x7d62e07e, -0x36bf7637, 0x7d87fa7d,
            -0x5ea1006, 0x59ebb259, 0x47c98e47, -0xff40410,
            -0x5213be53, -0x2b984c2c, -0x5d02a05e, -0x5015ba51,
            -0x6340dc64, -0x5b08ac5c, 0x7296e472, -0x3fa46440,
            -0x483d8a49, -0x2e31e03, -0x6c51c26d, 0x266a4c26,
            0x365a6c36, 0x3f417e3f, -0x8fd0a09, -0x33b07c34,
            0x345c6834, -0x5a0bae5b, -0x1acb2e1b, -0xef7060f,
            0x7193e271, -0x278c5428, 0x31536231, 0x153f2a15,
            0x040c0804, -0x38ad6a39, 0x23654623, -0x3ca1623d,
            0x18283018, -0x695ec86a, 0x050f0a05, -0x654ad066,
            0x07090e07, 0x12362412, -0x7f64e480, -0x1dc2201e,
            -0x14d93215, 0x27694e27, -0x4d32804e, 0x759fea75,
            0x091b1209, -0x7c61e27d, 0x2c74582c, 0x1a2e341a,
            0x1b2d361b, 0x6eb2dc6e, 0x5aeeb45a, -0x5f04a460,
            0x52f6a452, 0x3b4d763b, -0x299e482a, -0x4c31824d,
            0x297b5229, -0x1cc1221d, 0x2f715e2f, -0x7b68ec7c,
            0x53f5a653, -0x2e97462f, 0x00000000, -0x12d33e13,
            0x20604020, -0x3e01c04, -0x4e37864f, 0x5bedb65b,
            0x6abed46a, -0x34b97235, -0x41269842, 0x394b7239,
            0x4ade944a, 0x4cd4984c, 0x58e8b058, -0x30b57a31,
            -0x2f944430, -0x10d53a11, -0x551ab056, -0x4e91205,
            0x43c58643, 0x4dd79a4d, 0x33556633, -0x7a6bee7b,
            0x45cf8a45, -0x6ef1607, 0x02060402, 0x7f81fe7f,
            0x50f0a050, 0x3c44783c, -0x6045da61, -0x571cb458,
            0x51f3a251, -0x5c01a25d, 0x40c08040, -0x7075fa71,
            -0x6d52c06e, -0x6243de63, 0x38487038, -0xafb0e0b,
            -0x43209c44, -0x493e884a, -0x258a5026, 0x21634221,
            0x10302010, -0xe51a01, -0xcf1020d, -0x2d92402e,
            -0x32b37e33, 0x0c14180c, 0x13352613, -0x13d03c14,
            0x5fe1be5f, -0x685dca69, 0x44cc8844, 0x17392e17,
            -0x3ba86c3c, -0x580daa59, 0x7e82fc7e, 0x3d477a3d,
            0x64acc864, 0x5de7ba5d, 0x192b3219, 0x7395e673,
            0x60a0c060, -0x7e67e67f, 0x4fd19e4f, -0x23805c24,
            0x22664422, 0x2a7e542a, -0x6f54c470, -0x777cf478,
            0x46ca8c46, -0x11d63812, -0x472c9448, 0x143c2814,
            -0x21865822, 0x5ee2bc5e, 0x0b1d160b, -0x24895225,
            -0x1fc42420, 0x32566432, 0x3a4e743a, 0x0a1e140a,
            0x49db9249, 0x060a0c06, 0x246c4824, 0x5ce4b85c,
            -0x3da2603e, -0x2c91422d, -0x5310bc54, 0x62a6c462,
            -0x6e57c66f, -0x6a5bce6b, -0x1bc82c1c, 0x798bf279,
            -0x18cd2a19, -0x37bc7438, 0x37596e37, 0x6db7da6d,
            -0x7273fe73, -0x2a9b4e2b, 0x4ed29c4e, -0x561fb657,
            0x6cb4d86c, 0x56faac56, -0xbf80c0c, -0x15da3016,
            0x65afca65, 0x7a8ef47a, -0x5116b852, 0x08181008,
            -0x452a9046, 0x7888f078, 0x256f4a25, 0x2e725c2e,
            0x1c24381c, -0x590ea85a, -0x4b388c4c, -0x39ae683a,
            -0x17dc3418, -0x22835e23, 0x749ce874, 0x1f213e1f,
            0x4bdd964b, -0x42239e43, -0x7479f275, -0x757af076,
            0x7090e070, 0x3e427c3e, -0x4a3b8e4b, 0x66aacc66,
            0x48d89048, 0x03050603, -0x9fe080a, 0x0e121c0e,
            0x61a3c261, 0x355f6a35, 0x57f9ae57, -0x462f9647,
            -0x796ee87a, -0x3ea7663f, 0x1d273a1d, -0x6146d862,
            -0x1ec7261f, -0x7ec1408, -0x674cd468, 0x11332211,
            0x69bbd269, -0x268f5627, -0x7176f872, -0x6b58cc6c,
            -0x6449d265, 0x1e223c1e, -0x786dea79, -0x16df3617,
            -0x31b67832, 0x55ffaa55, 0x28785028, -0x20855a21,
            -0x7370fc74, -0x5e07a65f, -0x767ff677, 0x0d171a0d,
            -0x40259a41, -0x19ce281a, 0x42c68442, 0x68b8d068,
            0x41c38241, -0x664fd667, 0x2d775a2d, 0x0f111e0f,
            -0x4f348450, 0x54fca854, -0x44299245, 0x163a2c16,
        )
        private val Te3 = intArrayOf(
            0x6363a5c6, 0x7c7c84f8, 0x777799ee, 0x7b7b8df6,
            -0xd0df201, 0x6b6bbdd6, 0x6f6fb1de, -0x3a3aab6f,
            0x30305060, 0x01010302, 0x6767a9ce, 0x2b2b7d56,
            -0x101e619, -0x28289d4b, -0x545419b3, 0x76769aec,
            -0x3535ba71, -0x7d7d62e1, -0x3636bf77, 0x7d7d87fa,
            -0x505ea11, 0x5959ebb2, 0x4747c98e, -0xf0ff405,
            -0x525213bf, -0x2b2b984d, -0x5d5d02a1, -0x505015bb,
            -0x636340dd, -0x5b5b08ad, 0x727296e4, -0x3f3fa465,
            -0x48483d8b, -0x202e31f, -0x6c6c51c3, 0x26266a4c,
            0x36365a6c, 0x3f3f417e, -0x808fd0b, -0x3333b07d,
            0x34345c68, -0x5a5a0baf, -0x1a1acb2f, -0xe0ef707,
            0x717193e2, -0x27278c55, 0x31315362, 0x15153f2a,
            0x04040c08, -0x3838ad6b, 0x23236546, -0x3c3ca163,
            0x18182830, -0x69695ec9, 0x05050f0a, -0x65654ad1,
            0x0707090e, 0x12123624, -0x7f7f64e5, -0x1d1dc221,
            -0x1414d933, 0x2727694e, -0x4d4d3281, 0x75759fea,
            0x09091b12, -0x7c7c61e3, 0x2c2c7458, 0x1a1a2e34,
            0x1b1b2d36, 0x6e6eb2dc, 0x5a5aeeb4, -0x5f5f04a5,
            0x5252f6a4, 0x3b3b4d76, -0x29299e49, -0x4c4c3183,
            0x29297b52, -0x1c1cc123, 0x2f2f715e, -0x7b7b68ed,
            0x5353f5a6, -0x2e2e9747, 0x00000000, -0x1212d33f,
            0x20206040, -0x303e01d, -0x4e4e3787, 0x5b5bedb6,
            0x6a6abed4, -0x3434b973, -0x41412699, 0x39394b72,
            0x4a4ade94, 0x4c4cd498, 0x5858e8b0, -0x3030b57b,
            -0x2f2f9445, -0x1010d53b, -0x55551ab1, -0x404e913,
            0x4343c586, 0x4d4dd79a, 0x33335566, -0x7a7a6bef,
            0x4545cf8a, -0x606ef17, 0x02020604, 0x7f7f81fe,
            0x5050f0a0, 0x3c3c4478, -0x606045db, -0x57571cb5,
            0x5151f3a2, -0x5c5c01a3, 0x4040c080, -0x707075fb,
            -0x6d6d52c1, -0x626243df, 0x38384870, -0xa0afb0f,
            -0x4343209d, -0x49493e89, -0x25258a51, 0x21216342,
            0x10103020, -0xe51b, -0xc0cf103, -0x2d2d9241,
            -0x3232b37f, 0x0c0c1418, 0x13133526, -0x1313d03d,
            0x5f5fe1be, -0x68685dcb, 0x4444cc88, 0x1717392e,
            -0x3b3ba86d, -0x58580dab, 0x7e7e82fc, 0x3d3d477a,
            0x6464acc8, 0x5d5de7ba, 0x19192b32, 0x737395e6,
            0x6060a0c0, -0x7e7e67e7, 0x4f4fd19e, -0x2323805d,
            0x22226644, 0x2a2a7e54, -0x6f6f54c5, -0x77777cf5,
            0x4646ca8c, -0x1111d639, -0x47472c95, 0x14143c28,
            -0x21218659, 0x5e5ee2bc, 0x0b0b1d16, -0x24248953,
            -0x1f1fc425, 0x32325664, 0x3a3a4e74, 0x0a0a1e14,
            0x4949db92, 0x06060a0c, 0x24246c48, 0x5c5ce4b8,
            -0x3d3da261, -0x2c2c9143, -0x535310bd, 0x6262a6c4,
            -0x6e6e57c7, -0x6a6a5bcf, -0x1b1bc82d, 0x79798bf2,
            -0x1818cd2b, -0x3737bc75, 0x3737596e, 0x6d6db7da,
            -0x727273ff, -0x2a2a9b4f, 0x4e4ed29c, -0x56561fb7,
            0x6c6cb4d8, 0x5656faac, -0xb0bf80d, -0x1515da31,
            0x6565afca, 0x7a7a8ef4, -0x515116b9, 0x08081810,
            -0x45452a91, 0x787888f0, 0x25256f4a, 0x2e2e725c,
            0x1c1c2438, -0x59590ea9, -0x4b4b388d, -0x3939ae69,
            -0x1717dc35, -0x2222835f, 0x74749ce8, 0x1f1f213e,
            0x4b4bdd96, -0x4242239f, -0x747479f3, -0x75757af1,
            0x707090e0, 0x3e3e427c, -0x4a4a3b8f, 0x6666aacc,
            0x4848d890, 0x03030506, -0x909fe09, 0x0e0e121c,
            0x6161a3c2, 0x35355f6a, 0x5757f9ae, -0x46462f97,
            -0x79796ee9, -0x3e3ea767, 0x1d1d273a, -0x616146d9,
            -0x1e1ec727, -0x707ec15, -0x67674cd5, 0x11113322,
            0x6969bbd2, -0x26268f57, -0x717176f9, -0x6b6b58cd,
            -0x646449d3, 0x1e1e223c, -0x78786deb, -0x1616df37,
            -0x3131b679, 0x5555ffaa, 0x28287850, -0x2020855b,
            -0x737370fd, -0x5e5e07a7, -0x76767ff7, 0x0d0d171a,
            -0x4040259b, -0x1919ce29, 0x4242c684, 0x6868b8d0,
            0x4141c382, -0x66664fd7, 0x2d2d775a, 0x0f0f111e,
            -0x4f4f3485, 0x5454fca8, -0x44442993, 0x16163a2c,
        )
        private val Te4 = intArrayOf(
            0x63636363, 0x7c7c7c7c, 0x77777777, 0x7b7b7b7b,
            -0xd0d0d0e, 0x6b6b6b6b, 0x6f6f6f6f, -0x3a3a3a3b,
            0x30303030, 0x01010101, 0x67676767, 0x2b2b2b2b,
            -0x1010102, -0x28282829, -0x54545455, 0x76767676,
            -0x35353536, -0x7d7d7d7e, -0x36363637, 0x7d7d7d7d,
            -0x5050506, 0x59595959, 0x47474747, -0xf0f0f10,
            -0x52525253, -0x2b2b2b2c, -0x5d5d5d5e, -0x50505051,
            -0x63636364, -0x5b5b5b5c, 0x72727272, -0x3f3f3f40,
            -0x48484849, -0x2020203, -0x6c6c6c6d, 0x26262626,
            0x36363636, 0x3f3f3f3f, -0x8080809, -0x33333334,
            0x34343434, -0x5a5a5a5b, -0x1a1a1a1b, -0xe0e0e0f,
            0x71717171, -0x27272728, 0x31313131, 0x15151515,
            0x04040404, -0x38383839, 0x23232323, -0x3c3c3c3d,
            0x18181818, -0x6969696a, 0x05050505, -0x65656566,
            0x07070707, 0x12121212, -0x7f7f7f80, -0x1d1d1d1e,
            -0x14141415, 0x27272727, -0x4d4d4d4e, 0x75757575,
            0x09090909, -0x7c7c7c7d, 0x2c2c2c2c, 0x1a1a1a1a,
            0x1b1b1b1b, 0x6e6e6e6e, 0x5a5a5a5a, -0x5f5f5f60,
            0x52525252, 0x3b3b3b3b, -0x2929292a, -0x4c4c4c4d,
            0x29292929, -0x1c1c1c1d, 0x2f2f2f2f, -0x7b7b7b7c,
            0x53535353, -0x2e2e2e2f, 0x00000000, -0x12121213,
            0x20202020, -0x3030304, -0x4e4e4e4f, 0x5b5b5b5b,
            0x6a6a6a6a, -0x34343435, -0x41414142, 0x39393939,
            0x4a4a4a4a, 0x4c4c4c4c, 0x58585858, -0x30303031,
            -0x2f2f2f30, -0x10101011, -0x55555556, -0x4040405,
            0x43434343, 0x4d4d4d4d, 0x33333333, -0x7a7a7a7b,
            0x45454545, -0x6060607, 0x02020202, 0x7f7f7f7f,
            0x50505050, 0x3c3c3c3c, -0x60606061, -0x57575758,
            0x51515151, -0x5c5c5c5d, 0x40404040, -0x70707071,
            -0x6d6d6d6e, -0x62626263, 0x38383838, -0xa0a0a0b,
            -0x43434344, -0x4949494a, -0x25252526, 0x21212121,
            0x10101010, -0x1, -0xc0c0c0d, -0x2d2d2d2e,
            -0x32323233, 0x0c0c0c0c, 0x13131313, -0x13131314,
            0x5f5f5f5f, -0x68686869, 0x44444444, 0x17171717,
            -0x3b3b3b3c, -0x58585859, 0x7e7e7e7e, 0x3d3d3d3d,
            0x64646464, 0x5d5d5d5d, 0x19191919, 0x73737373,
            0x60606060, -0x7e7e7e7f, 0x4f4f4f4f, -0x23232324,
            0x22222222, 0x2a2a2a2a, -0x6f6f6f70, -0x77777778,
            0x46464646, -0x11111112, -0x47474748, 0x14141414,
            -0x21212122, 0x5e5e5e5e, 0x0b0b0b0b, -0x24242425,
            -0x1f1f1f20, 0x32323232, 0x3a3a3a3a, 0x0a0a0a0a,
            0x49494949, 0x06060606, 0x24242424, 0x5c5c5c5c,
            -0x3d3d3d3e, -0x2c2c2c2d, -0x53535354, 0x62626262,
            -0x6e6e6e6f, -0x6a6a6a6b, -0x1b1b1b1c, 0x79797979,
            -0x18181819, -0x37373738, 0x37373737, 0x6d6d6d6d,
            -0x72727273, -0x2a2a2a2b, 0x4e4e4e4e, -0x56565657,
            0x6c6c6c6c, 0x56565656, -0xb0b0b0c, -0x15151516,
            0x65656565, 0x7a7a7a7a, -0x51515152, 0x08080808,
            -0x45454546, 0x78787878, 0x25252525, 0x2e2e2e2e,
            0x1c1c1c1c, -0x5959595a, -0x4b4b4b4c, -0x3939393a,
            -0x17171718, -0x22222223, 0x74747474, 0x1f1f1f1f,
            0x4b4b4b4b, -0x42424243, -0x74747475, -0x75757576,
            0x70707070, 0x3e3e3e3e, -0x4a4a4a4b, 0x66666666,
            0x48484848, 0x03030303, -0x909090a, 0x0e0e0e0e,
            0x61616161, 0x35353535, 0x57575757, -0x46464647,
            -0x7979797a, -0x3e3e3e3f, 0x1d1d1d1d, -0x61616162,
            -0x1e1e1e1f, -0x7070708, -0x67676768, 0x11111111,
            0x69696969, -0x26262627, -0x71717172, -0x6b6b6b6c,
            -0x64646465, 0x1e1e1e1e, -0x78787879, -0x16161617,
            -0x31313132, 0x55555555, 0x28282828, -0x20202021,
            -0x73737374, -0x5e5e5e5f, -0x76767677, 0x0d0d0d0d,
            -0x40404041, -0x1919191a, 0x42424242, 0x68686868,
            0x41414141, -0x66666667, 0x2d2d2d2d, 0x0f0f0f0f,
            -0x4f4f4f50, 0x54545454, -0x44444445, 0x16161616,
        )
        private val Td0 = intArrayOf(
            0x51f4a750, 0x7e416553, 0x1a17a4c3, 0x3a275e96,
            0x3bab6bcb, 0x1f9d45f1, -0x5305a755, 0x4be30393,
            0x2030fa55, -0x5289920a, -0x7733896f, -0xafdb3db,
            0x4fe5d7fc, -0x3ad53429, 0x26354480, -0x4a9d5c71,
            -0x214ea5b7, 0x25ba1b67, 0x45ea0e98, 0x5dfec0e1,
            -0x3cd08afe, -0x7eb30fee, -0x72b9685d, 0x6bd3f9c6,
            0x038f5fe7, 0x15929c95, -0x40928515, -0x6aada626,
            -0x2b417cd3, 0x587421d3, 0x49e06929, -0x713637bc,
            0x75c2896a, -0xb718688, -0x66a7c195, 0x27b971dd,
            -0x411eb04a, -0xf7752e9, -0x36df539a, 0x7dce3ab4,
            0x63df4a18, -0x1ae5ce7e, -0x68aecca0, 0x62537f45,
            -0x4e9b8820, -0x4494517c, -0x17e5fe4, -0x6f7d46c,
            0x70486858, -0x70ba02e7, -0x6b219379, 0x527bf8b7,
            -0x548c2cdd, 0x724b02e2, -0x1ce070a9, 0x6655ab2a,
            -0x4d14d7f9, 0x2fb5c203, -0x793a8466, -0x2cc8f75b,
            0x302887f2, 0x23bfa5b2, 0x02036aba, -0x12e97da4,
            -0x7530e3d5, -0x58864b6e, -0xcf80d10, 0x4e69e2a1,
            0x65daf4cd, 0x0605bed5, -0x2ecb9de1, -0x3b590176,
            0x342e539d, -0x5d0caa60, 0x058ae132, -0x5b09148b,
            0x0b83ec39, 0x4060efaa, 0x5e719f06, -0x4291efaf,
            0x3e218af9, -0x6922f9c3, -0x22c1fa52, 0x4de6bd46,
            -0x6eab724b, 0x71c45d05, 0x0406d46f, 0x605015ff,
            0x1998fb24, -0x29421669, -0x76bfbc34, 0x67d99e77,
            -0x4f17bd43, 0x07898b88, -0x18e6a4c8, 0x79c8eedb,
            -0x5e83f5b9, 0x7c420fe9, -0x77be137, 0x00000000,
            0x09808683, 0x322bed48, 0x1e1170ac, 0x6c5a724e,
            -0x2f10005, 0x0f853856, 0x3daed51e, 0x362d3927,
            0x0a0fd964, 0x685ca621, -0x64a4ab2f, 0x24362e3a,
            0x0c0a67b1, -0x6ca818f1, -0x4b11692e, 0x1b9b919e,
            -0x7f3f3ab1, 0x61dc20a2, 0x5a774b69, 0x1c121a16,
            -0x1d6c45f6, -0x3f5fd51b, 0x3c22e043, 0x121b171d,
            0x0e090d0b, -0xd743853, 0x2db6a8b9, 0x141ea9c8,
            0x57f11985, -0x508af8b4, -0x11662245, -0x5c809f03,
            -0x8fed961, 0x5c72f5bc, 0x44663bc5, 0x5bfb7e34,
            -0x74bcd68a, -0x34dc3924, -0x49120398, -0x471b0e9d,
            -0x28ce2336, 0x42638510, 0x13972240, -0x7b39eee0,
            -0x7ab5db83, -0x2d44c208, -0x5106cdef, -0x38d65e93,
            0x1d9e2f4b, -0x234dcf0d, 0x0d8652ec, 0x77c1e3d0,
            0x2bb3166c, -0x568f4667, 0x119448fa, 0x47e96422,
            -0x5703733c, -0x5f0fc0e6, 0x567d2cd8, 0x223390ef,
            -0x78b6b139, -0x26c72e3f, -0x73355d02, -0x672bf4ca,
            -0x590a7e31, -0x5a8521d8, -0x254871da, 0x3fadbfa4,
            0x2c3a9de4, 0x5078920d, 0x6a5fcc9b, 0x547e4662,
            -0x972ec3e, -0x6f274718, 0x2e39f75e, -0x7d3c500b,
            -0x60a27f42, 0x69d0937c, 0x6fd52da9, -0x30daed4d,
            -0x375366c5, 0x10187da7, -0x17639c92, -0x24c44485,
            -0x32d987f7, 0x6e5918f4, -0x136548ff, -0x7cb06558,
            -0x196a919b, -0x55001982, 0x21bccf08, -0x10ea171a,
            -0x45186427, 0x4a6f36ce, -0x1560f62c, 0x29b07cd6,
            0x31a4b2af, 0x2a3f2331, -0x395a6bd0, 0x35a266c0,
            0x744ebc37, -0x37d355a, -0x1f6f2f50, 0x33a7d815,
            -0xefb67b6, 0x41ecdaf7, 0x7fcd500e, 0x1791f62f,
            0x764dd68d, 0x43efb04d, -0x3355b2ac, -0x1b69fb21,
            -0x612e4a1d, 0x4c6a881b, -0x3ed3e048, 0x4665517f,
            -0x62a115fc, 0x018c355d, -0x5788b8d, -0x4f4bed2,
            -0x4c98e2a6, -0x6d242dae, -0x16efa9cd, 0x6dd64713,
            -0x65289e74, 0x37a10c7a, 0x59f8148e, -0x14ecc377,
            -0x3156d812, -0x489e36cb, -0x1ee31a13, 0x7a47b13c,
            -0x632d20a7, 0x55f2733f, 0x1814ce79, 0x73c737bf,
            0x53f7cdea, 0x5ffdaa5b, -0x20c290ec, 0x7844db86,
            -0x35500c7f, -0x46973bc2, 0x3824342c, -0x3d5cbfa1,
            0x161dc372, -0x431ddaf4, 0x283c498b, -0xf26abf,
            0x39a80171, 0x080cb3de, -0x274b1b64, 0x6456c190,
            0x7bcb8461, -0x2acd4990, 0x486c5c74, -0x2f47a8be,
        )
        private val Td1 = intArrayOf(
            0x5051f4a7, 0x537e4165, -0x3ce5e85c, -0x69c5d8a2,
            -0x34c45495, -0xee062bb, -0x545305a8, -0x6cb41cfd,
            0x552030fa, -0x9528993, -0x6e77338a, 0x25f5024c,
            -0x3b01a29, -0x283ad535, -0x7fd9cabc, -0x704a9d5d,
            0x49deb15a, 0x6725ba1b, -0x67ba15f2, -0x1ea20140,
            0x02c32f75, 0x12814cf0, -0x5c72b969, -0x39942c07,
            -0x18fc70a1, -0x6aea6d64, -0x14409286, -0x256aada7,
            0x2dd4be83, -0x2ca78bdf, 0x2949e069, 0x448ec9c8,
            0x6a75c289, 0x78f48e79, 0x6b99583e, -0x22d8468f,
            -0x49411eb1, 0x17f088ad, 0x66c920ac, -0x4b8231c6,
            0x1863df4a, -0x7d1ae5cf, 0x60975133, 0x4562537f,
            -0x1f4e9b89, -0x7b449452, 0x1cfe81a0, -0x6b06f7d5,
            0x58704868, 0x198f45fd, -0x786b2194, -0x48ad8408,
            0x23ab73d3, -0x1d8db4fe, 0x57e31f8f, 0x2a6655ab,
            0x07b2eb28, 0x032fb5c2, -0x65793a85, -0x5a2cc8f8,
            -0xdcfd779, -0x4ddc405b, -0x45fdfc96, 0x5ced1682,
            0x2b8acf1c, -0x6d58864c, -0xf0cf80e, -0x5eb1961e,
            -0x329a250c, -0x2af9fa42, 0x1fd13462, -0x753b5902,
            -0x62cbd1ad, -0x5f5d0cab, 0x32058ae1, 0x75a4f6eb,
            0x390b83ec, -0x55bf9f11, 0x065e719f, 0x51bd6e10,
            -0x6c1de76, 0x3d96dd06, -0x5122c1fb, 0x464de6bd,
            -0x4a6eab73, 0x0571c45d, 0x6f0406d4, -0x9fafeb,
            0x241998fb, -0x68294217, -0x3376bfbd, 0x7767d99e,
            -0x424f17be, -0x77f87675, 0x38e7195b, -0x24863712,
            0x47a17c0a, -0x1683bdf1, -0x36077be2, 0x00000000,
            -0x7cf67f7a, 0x48322bed, -0x53e1ee90, 0x4e6c5a72,
            -0x402f101, 0x560f8538, 0x1e3daed5, 0x27362d39,
            0x640a0fd9, 0x21685ca6, -0x2e64a4ac, 0x3a24362e,
            -0x4ef3f599, 0x0f9357e7, -0x2d4b116a, -0x61e4646f,
            0x4f80c0c5, -0x5d9e23e0, 0x695a774b, 0x161c121a,
            0x0ae293ba, -0x1a3f5fd6, 0x433c22e0, 0x1d121b17,
            0x0b0e090d, -0x520d7439, -0x46d24958, -0x37ebe157,
            -0x7aa80ee7, 0x4caf7507, -0x44116623, -0x25c80a0,
            -0x6008feda, -0x43a38d0b, -0x3abb99c5, 0x345bfb7e,
            0x768b4329, -0x2334dc3a, 0x68b6edfc, 0x63b8e4f1,
            -0x3528ce24, 0x10426385, 0x40139722, 0x2084c611,
            0x7d854a24, -0x72d44c3, 0x11aef932, 0x6dc729a1,
            0x4b1d9e2f, -0xc234dd0, -0x13f279ae, -0x2f883e1d,
            0x6c2bb316, -0x66568f47, -0x5ee6bb8, 0x2247e964,
            -0x3b570374, 0x1aa0f03f, -0x27a982d4, -0x10ddcc70,
            -0x3878b6b2, -0x3e26c72f, -0x173355e, 0x3698d40b,
            -0x30590a7f, 0x28a57ade, 0x26dab78e, -0x5bc05241,
            -0x1bd3c563, 0x0d507892, -0x6495a034, 0x62547e46,
            -0x3d0972ed, -0x176f2748, 0x5e2e39f7, -0xa7d3c51,
            -0x4160a280, 0x7c69d093, -0x56902ad3, -0x4c30daee,
            0x3bc8ac99, -0x58efe783, 0x6ee89c63, 0x7bdb3bbb,
            0x09cd2678, -0xb91a6e8, 0x01ec9ab7, -0x577cb066,
            0x65e6956e, 0x7eaaffe6, 0x0821bccf, -0x1910ea18,
            -0x26451865, -0x31b590ca, -0x2b1560f7, -0x29d64f84,
            -0x50ce5b4e, 0x312a3f23, 0x30c6a594, -0x3fca5d9a,
            0x37744ebc, -0x59037d36, -0x4f1f6f30, 0x1533a7d8,
            0x4af10498, -0x8be1326, 0x0e7fcd50, 0x2f1791f6,
            -0x7289b22a, 0x4d43efb0, 0x54ccaa4d, -0x201b69fc,
            -0x1c612e4b, 0x1b4c6a88, -0x473ed3e1, 0x7f466551,
            0x049d5eea, 0x5d018c35, 0x73fa8774, 0x2efb0b41,
            0x5ab3671d, 0x5292dbd2, 0x33e91056, 0x136dd647,
            -0x7365289f, 0x7a37a10c, -0x71a607ec, -0x7614ecc4,
            -0x113156d9, 0x35b761c9, -0x121ee31b, 0x3c7a47b1,
            0x599cd2df, 0x3f55f273, 0x791814ce, -0x408c38c9,
            -0x15ac0833, 0x5b5ffdaa, 0x14df3d6f, -0x7987bb25,
            -0x7e35500d, 0x3eb968c4, 0x2c382434, 0x5fc2a340,
            0x72161dc3, 0x0cbce225, -0x74d7c3b7, 0x41ff0d95,
            0x7139a801, -0x21f7f34d, -0x63274b1c, -0x6f9ba93f,
            0x617bcb84, 0x70d532b6, 0x74486c5c, 0x42d0b857,
        )
        private val Td2 = intArrayOf(
            -0x58afae0c, 0x65537e41, -0x5b3ce5e9, 0x5e963a27,
            0x6bcb3bab, 0x45f11f9d, 0x58abacfa, 0x03934be3,
            -0x5aadfd0, 0x6df6ad76, 0x769188cc, 0x4c25f502,
            -0x2803b01b, -0x34283ad6, 0x44802635, -0x5c704a9e,
            0x5a49deb1, 0x1b6725ba, 0x0e9845ea, -0x3f1ea202,
            0x7502c32f, -0xfed7eb4, -0x685c72ba, -0x639942d,
            0x5fe7038f, -0x636aea6e, 0x7aebbf6d, 0x59da9552,
            -0x7cd22b42, 0x21d35874, 0x692949e0, -0x37bb7137,
            -0x76958a3e, 0x7978f48e, 0x3e6b9958, 0x71dd27b9,
            0x4fb6bee1, -0x52e80f78, -0x539936e0, 0x3ab47dce,
            0x4a1863df, 0x3182e51a, 0x33609751, 0x7f456253,
            0x77e0b164, -0x517b4495, -0x5fe3017f, 0x2b94f908,
            0x68587048, -0x2e670bb, 0x6c8794de, -0x748ad85,
            -0x2cdc548d, 0x02e2724b, -0x70a81ce1, -0x54d599ab,
            0x2807b2eb, -0x3dfcd04b, 0x7b9a86c5, 0x08a5d337,
            -0x780dcfd8, -0x5a4ddc41, 0x6aba0203, -0x7da312ea,
            0x1c2b8acf, -0x4b6d5887, -0xd0f0cf9, -0x1d5eb197,
            -0xb329a26, -0x412af9fb, 0x621fd134, -0x1753b5a,
            0x539d342e, 0x55a0a2f3, -0x1ecdfa76, -0x148a5b0a,
            -0x13c6f47d, -0x1055bfa0, -0x60f9a18f, 0x1051bd6e,

            -0x7506c1df, 0x063d96dd, 0x05aedd3e, -0x42b9b21a,
            -0x724a6eac, 0x5d0571c4, -0x2b90fbfa, 0x15ff6050,
            -0x4dbe668, -0x16682943, 0x43cc8940, -0x61889827,
            0x42bdb0e8, -0x7477f877, 0x5b38e719, -0x11248638,
            0x0a47a17c, 0x0fe97c42, 0x1ec9f884, 0x00000000,
            -0x797cf680, -0x12b7cdd5, 0x70ac1e11, 0x724e6c5a,
            -0x402f2, 0x38560f85, -0x2ae1c252, 0x3927362d,
            -0x269bf5f1, -0x59de97a4, 0x54d19b5b, 0x2e3a2436,
            0x67b10c0a, -0x18f06ca9, -0x692d4b12, -0x6e61e465,
            -0x3ab07f40, 0x20a261dc, 0x4b695a77, 0x1a161c12,
            -0x45f51d6d, 0x2ae5c0a0, -0x1fbcc3de, 0x171d121b,
            0x0d0b0e09, -0x38520d75, -0x5746d24a, -0x5637ebe2,
            0x198557f1, 0x074caf75, -0x22441167, 0x60fda37f,
            0x269ff701, -0xa43a38e, 0x3bc54466, 0x7e345bfb,
            0x29768b43, -0x392334dd, -0x3974913, -0xe9c471c,
            -0x233528cf, -0x7aefbd9d, 0x22401397, 0x112084c6,
            0x247d854a, 0x3df8d2bb, 0x3211aef9, -0x5e9238d7,
            0x2f4b1d9e, 0x30f3dcb2, 0x52ec0d86, -0x1c2f883f,
            0x166c2bb3, -0x46665690, 0x48fa1194, 0x642247e9,
            -0x733b5704, 0x3f1aa0f0, 0x2cd8567d, -0x6f10ddcd,
            0x4ec78749, -0x2e3e26c8, -0x5d017336, 0x0b3698d4,
            -0x7e30590b, -0x21d75a86, -0x71d92549, -0x405bc053,
            -0x621bd3c6, -0x6df2af88, -0x336495a1, 0x4662547e,
            0x13c2f68d, -0x47176f28, -0x8a1d1c7, -0x500a7d3d,
            -0x7f4160a3, -0x6c839630, 0x2da96fd5, 0x12b3cf25,
            -0x66c43754, 0x7da71018, 0x636ee89c, -0x448424c5,
            0x7809cd26, 0x18f46e59, -0x48fe1366, -0x65577cb1,
            0x6e65e695, -0x19815501, -0x30f7de44, -0x171910eb,
            -0x64264519, 0x36ce4a6f, 0x09d4ea9f, 0x7cd629b0,
            -0x4d50ce5c, 0x23312a3f, -0x6bcf395b, 0x66c035a2,
            -0x43c88bb2, -0x3559037e, -0x2f4f1f70, -0x27eacc59,
            -0x67b50efc, -0x2508be14, 0x500e7fcd, -0x9d0e86f,
            -0x297289b3, -0x4fb2bc11, 0x4d54ccaa, 0x04dfe496,
            -0x4a1c612f, -0x77e4b396, 0x1fb8c12c, 0x517f4665,
            -0x15fb62a2, 0x355d018c, 0x7473fa87, 0x412efb0b,
            0x1d5ab367, -0x2dad6d25, 0x5633e910, 0x47136dd6,
            0x618c9ad7, 0x0c7a37a1, 0x148e59f8, 0x3c89eb13,
            0x27eecea9, -0x36ca489f, -0x1a121ee4, -0x4ec385b9,
            -0x20a6632e, 0x733f55f2, -0x3186e7ec, 0x37bf73c7,
            -0x3215ac09, -0x55a4a003, 0x6f14df3d, -0x247987bc,
            -0xc7e3551, -0x3bc14698, 0x342c3824, 0x405fc2a3,
            -0x3c8de9e3, 0x250cbce2, 0x498b283c, -0x6abe00f3,
            0x017139a8, -0x4c21f7f4, -0x1b63274c, -0x3e6f9baa,
            -0x7b9e8435, -0x498f2ace, 0x5c74486c, 0x5742d0b8,
        )
        private val Td3 = intArrayOf(
            -0xb58afaf, 0x4165537e, 0x17a4c31a, 0x275e963a,
            -0x549434c5, -0x62ba0ee1, -0x5a75454, -0x1cfc6cb5,
            0x30fa5520, 0x766df6ad, -0x33896e78, 0x024c25f5,
            -0x1a2803b1, 0x2acbd7c5, 0x35448026, 0x62a38fb5,
            -0x4ea5b622, -0x45e498db, -0x15f167bb, -0x13f1ea3,
            0x2f7502c3, 0x4cf01281, 0x4697a38d, -0x2c063995,
            -0x70a018fd, -0x6d636aeb, 0x6d7aebbf, 0x5259da95,
            -0x417cd22c, 0x7421d358, -0x1f96d6b7, -0x3637bb72,
            -0x3d76958b, -0x7186870c, 0x583e6b99, -0x468e22d9,
            -0x1eb04942, -0x7752e810, 0x20ac66c9, -0x31c54b83,
            -0x20b5e79d, 0x1a3182e5, 0x51336097, 0x537f4562,
            0x6477e0b1, 0x6bae84bb, -0x7e5fe302, 0x082b94f9,
            0x48685870, 0x45fd198f, -0x2193786c, 0x7bf8b752,
            0x73d323ab, 0x4b02e272, 0x1f8f57e3, 0x55ab2a66,
            -0x14d7f84e, -0x4a3dfcd1, -0x3a84657a, 0x3708a5d3,
            0x2887f230, -0x405a4ddd, 0x036aba02, 0x16825ced,
            -0x30e3d476, 0x79b492a7, 0x07f2f0f3, 0x69e2a14e,
            -0x250b329b, 0x05bed506, 0x34621fd1, -0x5901753c,
            0x2e539d34, -0xcaa5f5e, -0x751ecdfb, -0x9148a5c,
            -0x7c13c6f5, 0x60efaa40, 0x719f065e, 0x6e1051bd,
            0x218af93e, -0x22f9c26a, 0x3e05aedd, -0x1942b9b3,
            0x548db591, -0x3ba2fa8f, 0x06d46f04, 0x5015ff60,
            -0x6704dbe7, -0x4216682a, 0x4043cc89, -0x26618899,
            -0x17bd4250, -0x767477f9, 0x195b38e7, -0x37112487,
            0x7c0a47a1, 0x420fe97c, -0x7be13608, 0x00000000,
            -0x7f797cf7, 0x2bed4832, 0x1170ac1e, 0x5a724e6c,
            0x0efffbfd, -0x7ac7a9f1, -0x512ae1c3, 0x2d392736,
            0x0fd9640a, 0x5ca62168, 0x5b54d19b, 0x362e3a24,
            0x0a67b10c, 0x57e70f93, -0x11692d4c, -0x646e61e5,
            -0x3f3ab080, -0x23df5d9f, 0x774b695a, 0x121a161c,
            -0x6c45f51e, -0x5fd51a40, 0x22e0433c, 0x1b171d12,
            0x090d0b0e, -0x7438520e, -0x495746d3, 0x1ea9c814,
            -0xee67aa9, 0x75074caf, -0x66224412, 0x7f60fda3,
            0x01269ff7, 0x72f5bc5c, 0x663bc544, -0x481cba5,
            0x4329768b, 0x23c6dccb, -0x1203974a, -0x1b0e9c48,
            0x31dccad7, 0x63851042, -0x68ddbfed, -0x39eedf7c,
            0x4a247d85, -0x44c2072e, -0x6cdee52, 0x29a16dc7,
            -0x61d0b4e3, -0x4dcf0c24, -0x79ad13f3, -0x3e1c2f89,
            -0x4ce993d5, 0x70b999a9, -0x6bb705ef, -0x169bddb9,
            -0x3733b58, -0xfc0e560, 0x7d2cd856, 0x3390ef22,
            0x494ec787, 0x38d1c1d9, -0x355d0174, -0x2bf4c968,
            -0xa7e305a, 0x7ade28a5, -0x4871d926, -0x52405bc1,
            0x3a9de42c, 0x78920d50, 0x5fcc9b6a, 0x7e466254,
            -0x72ec3d0a, -0x27471770, 0x39f75e2e, -0x3c500a7e,
            0x5d80be9f, -0x2f6c8397, -0x2ad25691, 0x2512b3cf,
            -0x5366c438, 0x187da710, -0x639c9118, 0x3bbb7bdb,
            0x267809cd, 0x5918f46e, -0x6548fe14, 0x4f9aa883,
            -0x6a919a1a, -0x198156, -0x4330f7df, 0x15e8e6ef,
            -0x18642646, 0x6f36ce4a, -0x60f62b16, -0x4f8329d7,
            -0x5b4d50cf, 0x3f23312a, -0x5a6bcf3a, -0x5d993fcb,
            0x4ebc3774, -0x7d355904, -0x6f2f4f20, -0x5827eacd,
            0x04984af1, -0x132508bf, -0x32aff181, -0x6e09d0e9,
            0x4dd68d76, -0x104fb2bd, -0x55b2ab34, -0x69fb201c,
            -0x2e4a1c62, 0x6a881b4c, 0x2c1fb8c1, 0x65517f46,
            0x5eea049d, -0x73caa2ff, -0x788b8c06, 0x0b412efb,
            0x671d5ab3, -0x242dad6e, 0x105633e9, -0x29b8ec93,
            -0x289e7366, -0x5ef385c9, -0x7eb71a7, 0x133c89eb,
            -0x56d81132, 0x61c935b7, 0x1ce5ede1, 0x47b13c7a,
            -0x2d20a664, -0xd8cc0ab, 0x14ce7918, -0x38c8408d,
            -0x83215ad, -0x255a4a1, 0x3d6f14df, 0x44db8678,
            -0x500c7e36, 0x68c43eb9, 0x24342c38, -0x5cbfa03e,
            0x1dc37216, -0x1ddaf344, 0x3c498b28, 0x0d9541ff,
            -0x57fe8ec7, 0x0cb3de08, -0x4b1b6328, 0x56c19064,
            -0x347b9e85, 0x32b670d5, 0x6c5c7448, -0x47a8bd30,
        )
        private val Td4 = intArrayOf(
            0x52525252, 0x09090909, 0x6a6a6a6a, -0x2a2a2a2b,
            0x30303030, 0x36363636, -0x5a5a5a5b, 0x38383838,
            -0x40404041, 0x40404040, -0x5c5c5c5d, -0x61616162,
            -0x7e7e7e7f, -0xc0c0c0d, -0x28282829, -0x4040405,
            0x7c7c7c7c, -0x1c1c1c1d, 0x39393939, -0x7d7d7d7e,
            -0x64646465, 0x2f2f2f2f, -0x1, -0x78787879,
            0x34343434, -0x71717172, 0x43434343, 0x44444444,
            -0x3b3b3b3c, -0x21212122, -0x16161617, -0x34343435,
            0x54545454, 0x7b7b7b7b, -0x6b6b6b6c, 0x32323232,
            -0x5959595a, -0x3d3d3d3e, 0x23232323, 0x3d3d3d3d,
            -0x11111112, 0x4c4c4c4c, -0x6a6a6a6b, 0x0b0b0b0b,
            0x42424242, -0x5050506, -0x3c3c3c3d, 0x4e4e4e4e,
            0x08080808, 0x2e2e2e2e, -0x5e5e5e5f, 0x66666666,
            0x28282828, -0x26262627, 0x24242424, -0x4d4d4d4e,
            0x76767676, 0x5b5b5b5b, -0x5d5d5d5e, 0x49494949,
            0x6d6d6d6d, -0x74747475, -0x2e2e2e2f, 0x25252525,
            0x72727272, -0x7070708, -0x909090a, 0x64646464,
            -0x7979797a, 0x68686868, -0x67676768, 0x16161616,
            -0x2b2b2b2c, -0x5b5b5b5c, 0x5c5c5c5c, -0x33333334,
            0x5d5d5d5d, 0x65656565, -0x4949494a, -0x6d6d6d6e,
            0x6c6c6c6c, 0x70707070, 0x48484848, 0x50505050,
            -0x2020203, -0x12121213, -0x46464647, -0x25252526,
            0x5e5e5e5e, 0x15151515, 0x46464646, 0x57575757,
            -0x58585859, -0x72727273, -0x62626263, -0x7b7b7b7c,
            -0x6f6f6f70, -0x27272728, -0x54545455, 0x00000000,
            -0x73737374, -0x43434344, -0x2c2c2c2d, 0x0a0a0a0a,
            -0x8080809, -0x1b1b1b1c, 0x58585858, 0x05050505,
            -0x47474748, -0x4c4c4c4d, 0x45454545, 0x06060606,
            -0x2f2f2f30, 0x2c2c2c2c, 0x1e1e1e1e, -0x70707071,
            -0x35353536, 0x3f3f3f3f, 0x0f0f0f0f, 0x02020202,
            -0x3e3e3e3f, -0x50505051, -0x42424243, 0x03030303,
            0x01010101, 0x13131313, -0x75757576, 0x6b6b6b6b,
            0x3a3a3a3a, -0x6e6e6e6f, 0x11111111, 0x41414141,
            0x4f4f4f4f, 0x67676767, -0x23232324, -0x15151516,
            -0x68686869, -0xd0d0d0e, -0x30303031, -0x31313132,
            -0xf0f0f10, -0x4b4b4b4c, -0x1919191a, 0x73737373,
            -0x6969696a, -0x53535354, 0x74747474, 0x22222222,
            -0x18181819, -0x52525253, 0x35353535, -0x7a7a7a7b,
            -0x1d1d1d1e, -0x6060607, 0x37373737, -0x17171718,
            0x1c1c1c1c, 0x75757575, -0x20202021, 0x6e6e6e6e,
            0x47474747, -0xe0e0e0f, 0x1a1a1a1a, 0x71717171,
            0x1d1d1d1d, 0x29292929, -0x3a3a3a3b, -0x76767677,
            0x6f6f6f6f, -0x48484849, 0x62626262, 0x0e0e0e0e,
            -0x55555556, 0x18181818, -0x41414142, 0x1b1b1b1b,
            -0x3030304, 0x56565656, 0x3e3e3e3e, 0x4b4b4b4b,
            -0x3939393a, -0x2d2d2d2e, 0x79797979, 0x20202020,
            -0x65656566, -0x24242425, -0x3f3f3f40, -0x1010102,
            0x78787878, -0x32323233, 0x5a5a5a5a, -0xb0b0b0c,
            0x1f1f1f1f, -0x22222223, -0x57575758, 0x33333333,
            -0x77777778, 0x07070707, -0x38383839, 0x31313131,
            -0x4e4e4e4f, 0x12121212, 0x10101010, 0x59595959,
            0x27272727, -0x7f7f7f80, -0x13131314, 0x5f5f5f5f,
            0x60606060, 0x51515151, 0x7f7f7f7f, -0x56565657,
            0x19191919, -0x4a4a4a4b, 0x4a4a4a4a, 0x0d0d0d0d,
            0x2d2d2d2d, -0x1a1a1a1b, 0x7a7a7a7a, -0x60606061,
            -0x6c6c6c6d, -0x36363637, -0x63636364, -0x10101011,
            -0x5f5f5f60, -0x1f1f1f20, 0x3b3b3b3b, 0x4d4d4d4d,
            -0x51515152, 0x2a2a2a2a, -0xa0a0a0b, -0x4f4f4f50,
            -0x37373738, -0x14141415, -0x44444445, 0x3c3c3c3c,
            -0x7c7c7c7d, 0x53535353, -0x66666667, 0x61616161,
            0x17171717, 0x2b2b2b2b, 0x04040404, 0x7e7e7e7e,
            -0x45454546, 0x77777777, -0x2929292a, 0x26262626,
            -0x1e1e1e1f, 0x69696969, 0x14141414, 0x63636363,
            0x55555555, 0x21212121, 0x0c0c0c0c, 0x7d7d7d7d,
        )
        private val rcon = intArrayOf(
            0x01000000,
            0x02000000,
            0x04000000,
            0x08000000,
            0x10000000,
            0x20000000,
            0x40000000,
            -0x80000000,
            0x1B000000,
            0x36000000,  /* for 128-bit blocks, Rijndael never uses more than 10 rcon values */
        )
    }
}
