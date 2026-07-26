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
package com.bitchat.android.noise.southernstorm.protocol

/**
 * Information about all supported handshake patterns.
 */
internal object Pattern {
    // Token codes.
    const val S: Short = 1
    const val E: Short = 2
    const val EE: Short = 3
    const val ES: Short = 4
    const val SE: Short = 5
    const val SS: Short = 6
    const val F: Short = 7
    const val FF: Short = 8
    const val FLIP_DIR: Short = 255

    // Pattern flag bits.
    const val FLAG_LOCAL_STATIC: Short = 0x0001
    const val FLAG_LOCAL_EPHEMERAL: Short = 0x0002
    const val FLAG_LOCAL_REQUIRED: Short = 0x0004
    const val FLAG_LOCAL_EPHEM_REQ: Short = 0x0008
    const val FLAG_LOCAL_HYBRID: Short = 0x0010
    const val FLAG_LOCAL_HYBRID_REQ: Short = 0x0020
    const val FLAG_REMOTE_STATIC: Short = 0x0100
    const val FLAG_REMOTE_EPHEMERAL: Short = 0x0200
    const val FLAG_REMOTE_REQUIRED: Short = 0x0400
    const val FLAG_REMOTE_EPHEM_REQ: Short = 0x0800
    const val FLAG_REMOTE_HYBRID: Short = 0x1000
    const val FLAG_REMOTE_HYBRID_REQ: Short = 0x2000

    private fun flags(vararg bits: Short): Short {
        var v = 0
        for (b in bits) v = v or b.toInt()
        return v.toShort()
    }

    private val patterns: Map<String, ShortArray> = mapOf(
        "N" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_REMOTE_STATIC, FLAG_REMOTE_REQUIRED),
            E, ES
        ),
        "K" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_REQUIRED),
            E, ES, SS
        ),
        "X" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_REQUIRED),
            E, ES, S, SS
        ),
        "NN" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, EE
        ),
        "NK" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL,
                FLAG_REMOTE_REQUIRED),
            E, ES, FLIP_DIR, E, EE
        ),
        "NX" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, EE, S, ES
        ),
        "XN" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, EE, FLIP_DIR, S, SE
        ),
        "XK" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_REQUIRED),
            E, ES, FLIP_DIR, E, EE, FLIP_DIR, S, SE
        ),
        "XX" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, EE, S, ES, FLIP_DIR, S, SE
        ),
        "KN" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, EE, SE
        ),
        "KK" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_REQUIRED),
            E, ES, SS, FLIP_DIR, E, EE, SE
        ),
        "KX" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, EE, SE, S, ES
        ),
        "IN" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_REMOTE_EPHEMERAL),
            E, S, FLIP_DIR, E, EE, SE
        ),
        "IK" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_REQUIRED),
            E, ES, S, SS, FLIP_DIR, E, EE, SE
        ),
        "IX" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL),
            E, S, FLIP_DIR, E, EE, SE, S, ES
        ),
        "XXfallback" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_EPHEM_REQ),
            E, EE, S, SE, FLIP_DIR, S, ES
        ),
        "Xnoidh" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_REQUIRED),
            E, S, ES, SS
        ),
        "NXnoidh" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, S, EE, ES
        ),
        "XXnoidh" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, S, EE, ES, FLIP_DIR, S, SE
        ),
        "KXnoidh" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL),
            E, FLIP_DIR, E, S, EE, SE, ES
        ),
        "IKnoidh" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_REQUIRED),
            E, S, ES, SS, FLIP_DIR, E, EE, SE
        ),
        "IXnoidh" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL),
            E, S, FLIP_DIR, E, S, EE, SE, ES
        ),
        "NNhfs" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, EE, FF
        ),
        "NKhfs" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID,
                FLAG_REMOTE_REQUIRED),
            E, F, ES, FLIP_DIR, E, F, EE, FF
        ),
        "NXhfs" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, EE, FF, S, ES
        ),
        "XNhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, EE, FF, FLIP_DIR, S, SE
        ),
        "XKhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID,
                FLAG_REMOTE_REQUIRED),
            E, F, ES, FLIP_DIR, E, F, EE, FF, FLIP_DIR, S, SE
        ),
        "XXhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, EE, FF, S, ES, FLIP_DIR, S, SE
        ),
        "KNhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_LOCAL_HYBRID, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, EE, FF, SE
        ),
        "KKhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_LOCAL_HYBRID, FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL,
                FLAG_REMOTE_HYBRID, FLAG_REMOTE_REQUIRED),
            E, F, ES, SS, FLIP_DIR, E, F, EE, FF, SE
        ),
        "KXhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_LOCAL_HYBRID, FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL,
                FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, EE, FF, SE, S, ES
        ),
        "INhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, S, FLIP_DIR, E, F, EE, FF, SE
        ),
        "IKhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID,
                FLAG_REMOTE_REQUIRED),
            E, F, ES, S, SS, FLIP_DIR, E, F, EE, FF, SE
        ),
        "IXhfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, S, FLIP_DIR, E, F, EE, FF, SE, S, ES
        ),
        "XXfallback+hfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_EPHEM_REQ,
                FLAG_REMOTE_HYBRID, FLAG_REMOTE_HYBRID_REQ),
            E, F, EE, FF, S, SE, FLIP_DIR, S, ES
        ),
        "NXnoidh+hfs" to shortArrayOf(
            flags(FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, S, EE, FF, ES
        ),
        "XXnoidh+hfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, S, EE, FF, ES, FLIP_DIR, S, SE
        ),
        "KXnoidh+hfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_REQUIRED,
                FLAG_LOCAL_HYBRID, FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL,
                FLAG_REMOTE_HYBRID),
            E, F, FLIP_DIR, E, F, S, EE, FF, SE, ES
        ),
        // Note: FLAG_REMOTE_EPHEMERAL is listed twice in the original Java
        // pattern definition. Kept here verbatim — the duplicate or-bit is a
        // no-op, so behavior matches the upstream implementation.
        "IKnoidh+hfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_EPHEMERAL,
                FLAG_REMOTE_HYBRID),
            E, F, S, ES, SS, FLIP_DIR, E, F, EE, FF, SE
        ),
        "IXnoidh+hfs" to shortArrayOf(
            flags(FLAG_LOCAL_STATIC, FLAG_LOCAL_EPHEMERAL, FLAG_LOCAL_HYBRID,
                FLAG_REMOTE_STATIC, FLAG_REMOTE_EPHEMERAL, FLAG_REMOTE_HYBRID),
            E, F, S, FLIP_DIR, E, F, S, EE, FF, SE, ES
        ),
    )

    /**
     * Look up the description information for a pattern.
     *
     * @param name The name of the pattern.
     * @return The pattern description or null.
     */
    @JvmStatic
    fun lookup(name: String): ShortArray? = patterns[name]

    /**
     * Reverses the local and remote flags for a pattern.
     *
     * @param flags The flags, assuming that the initiator is "local".
     * @return The reversed flags, with the responder now being "local".
     */
    @JvmStatic
    fun reverseFlags(flags: Short): Short {
        val v = flags.toInt() and 0xFFFF
        return (((v shr 8) and 0x00FF) or ((v shl 8) and 0xFF00)).toShort()
    }
}
