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

package com.bitchat.android.noise.southernstorm.protocol;

/**
 * Information about all supported handshake patterns.
 */
class Pattern {
	
	private Pattern() {}

	// Token codes.
	public static final short S = 1;
	public static final short E = 2;
	public static final short EE = 3;
	public static final short ES = 4;
	public static final short SE = 5;
	public static final short SS = 6;
	public static final short F = 7;
	public static final short FF = 8;
	public static final short FLIP_DIR = 255;
	
	// Pattern flag bits.
	public static final short FLAG_LOCAL_STATIC = 0x0001;
	public static final short FLAG_LOCAL_EPHEMERAL = 0x0002;
	public static final short FLAG_LOCAL_REQUIRED = 0x0004;
	public static final short FLAG_LOCAL_EPHEM_REQ = 0x0008;
	public static final short FLAG_LOCAL_HYBRID = 0x0010;
	public static final short FLAG_LOCAL_HYBRID_REQ = 0x0020;
	public static final short FLAG_REMOTE_STATIC = 0x0100;
	public static final short FLAG_REMOTE_EPHEMERAL = 0x0200;
	public static final short FLAG_REMOTE_REQUIRED = 0x0400;
	public static final short FLAG_REMOTE_EPHEM_REQ = 0x0800;
	public static final short FLAG_REMOTE_HYBRID = 0x1000;
	public static final short FLAG_REMOTE_HYBRID_REQ = 0x2000;

	private static final short[] noise_pattern_N = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES
	};

	private static final short[] noise_pattern_K = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    SS
	};

	private static final short[] noise_pattern_X = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    S,
	    SS
	};

	private static final short[] noise_pattern_NN = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    EE
	};

	private static final short[] noise_pattern_NK = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    FLIP_DIR,
	    E,
	    EE
	};

	private static final short[] noise_pattern_NX = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    EE,
	    S,
	    ES
	};

	private static final short[] noise_pattern_XN = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    EE,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_XK = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    FLIP_DIR,
	    E,
	    EE,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_XX = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    EE,
	    S,
	    ES,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_KN = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    EE,
	    SE
	};

	private static final short[] noise_pattern_KK = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    SS,
	    FLIP_DIR,
	    E,
	    EE,
	    SE
	};

	private static final short[] noise_pattern_KX = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    EE,
	    SE,
	    S,
	    ES
	};

	private static final short[] noise_pattern_IN = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    S,
	    FLIP_DIR,
	    E,
	    EE,
	    SE
	};

	private static final short[] noise_pattern_IK = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    ES,
	    S,
	    SS,
	    FLIP_DIR,
	    E,
	    EE,
	    SE
	};

	private static final short[] noise_pattern_IX = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    S,
	    FLIP_DIR,
	    E,
	    EE,
	    SE,
	    S,
	    ES
	};

	private static final short[] noise_pattern_XXfallback = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_EPHEM_REQ,

	    E,
	    EE,
	    S,
	    SE,
	    FLIP_DIR,
	    S,
	    ES
	};

	private static final short[] noise_pattern_Xnoidh = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    S,
	    ES,
	    SS
	};

	private static final short[] noise_pattern_NXnoidh = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    S,
	    EE,
	    ES
	};

	private static final short[] noise_pattern_XXnoidh = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    S,
	    EE,
	    ES,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_KXnoidh = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    FLIP_DIR,
	    E,
	    S,
	    EE,
	    SE,
	    ES
	};

	private static final short[] noise_pattern_IKnoidh = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    S,
	    ES,
	    SS,
	    FLIP_DIR,
	    E,
	    EE,
	    SE
	};

	private static final short[] noise_pattern_IXnoidh = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL,

	    E,
	    S,
	    FLIP_DIR,
	    E,
	    S,
	    EE,
	    SE,
	    ES
	};

	private static final short[] noise_pattern_NNhfs = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF
	};

	private static final short[] noise_pattern_NKhfs = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    F,
	    ES,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF
	};

	private static final short[] noise_pattern_NXhfs = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    S,
	    ES
	};

	private static final short[] noise_pattern_XNhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_XKhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    F,
	    ES,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_XXhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    S,
	    ES,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_KNhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    SE
	};

	private static final short[] noise_pattern_KKhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    F,
	    ES,
	    SS,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    SE
	};

	private static final short[] noise_pattern_KXhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    SE,
	    S,
	    ES
	};

	private static final short[] noise_pattern_INhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    S,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    SE
	};

	private static final short[] noise_pattern_IKhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID |
	    FLAG_REMOTE_REQUIRED,

	    E,
	    F,
	    ES,
	    S,
	    SS,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    SE
	};

	private static final short[] noise_pattern_IXhfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    S,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    SE,
	    S,
	    ES
	};

	private static final short[] noise_pattern_XXfallback_hfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_EPHEM_REQ |
	    FLAG_REMOTE_HYBRID |
	    FLAG_REMOTE_HYBRID_REQ,

	    E,
	    F,
	    EE,
	    FF,
	    S,
	    SE,
	    FLIP_DIR,
	    S,
	    ES
	};

	private static final short[] noise_pattern_NXnoidh_hfs = {
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    S,
	    EE,
	    FF,
	    ES
	};

	private static final short[] noise_pattern_XXnoidh_hfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    S,
	    EE,
	    FF,
	    ES,
	    FLIP_DIR,
	    S,
	    SE
	};

	private static final short[] noise_pattern_KXnoidh_hfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_REQUIRED |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    FLIP_DIR,
	    E,
	    F,
	    S,
	    EE,
	    FF,
	    SE,
	    ES
	};

	private static final short[] noise_pattern_IKnoidh_hfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    S,
	    ES,
	    SS,
	    FLIP_DIR,
	    E,
	    F,
	    EE,
	    FF,
	    SE
	};

	private static final short[] noise_pattern_IXnoidh_hfs = {
	    FLAG_LOCAL_STATIC |
	    FLAG_LOCAL_EPHEMERAL |
	    FLAG_LOCAL_HYBRID |
	    FLAG_REMOTE_STATIC |
	    FLAG_REMOTE_EPHEMERAL |
	    FLAG_REMOTE_HYBRID,

	    E,
	    F,
	    S,
	    FLIP_DIR,
	    E,
	    F,
	    S,
	    EE,
	    FF,
	    SE,
	    ES
	};

	/**
	 * Look up the description information for a pattern.
	 * 
	 * @param name The name of the pattern.
	 * @return The pattern description or null.
	 */
	public static short[] lookup(String name)
	{
		if (name.equals("N"))
			return noise_pattern_N;
		else if (name.equals("K"))
			return noise_pattern_K;
		else if (name.equals("X"))
			return noise_pattern_X;
		else if (name.equals("NN"))
			return noise_pattern_NN;
		else if (name.equals("NK"))
			return noise_pattern_NK;
		else if (name.equals("NX"))
			return noise_pattern_NX;
		else if (name.equals("XN"))
			return noise_pattern_XN;
		else if (name.equals("XK"))
			return noise_pattern_XK;
		else if (name.equals("XX"))
			return noise_pattern_XX;
		else if (name.equals("KN"))
			return noise_pattern_KN;
		else if (name.equals("KK"))
			return noise_pattern_KK;
		else if (name.equals("KX"))
			return noise_pattern_KX;
		else if (name.equals("IN"))
			return noise_pattern_IN;
		else if (name.equals("IK"))
			return noise_pattern_IK;
		else if (name.equals("IX"))
			return noise_pattern_IX;
		else if (name.equals("XXfallback"))
			return noise_pattern_XXfallback;
		else if (name.equals("Xnoidh"))
			return noise_pattern_Xnoidh;
		else if (name.equals("NXnoidh"))
			return noise_pattern_NXnoidh;
		else if (name.equals("XXnoidh"))
			return noise_pattern_XXnoidh;
		else if (name.equals("KXnoidh"))
			return noise_pattern_KXnoidh;
		else if (name.equals("IKnoidh"))
			return noise_pattern_IKnoidh;
		else if (name.equals("IXnoidh"))
			return noise_pattern_IXnoidh;
		else if (name.equals("NNhfs"))
			return noise_pattern_NNhfs;
		else if (name.equals("NKhfs"))
			return noise_pattern_NKhfs;
		else if (name.equals("NXhfs"))
			return noise_pattern_NXhfs;
		else if (name.equals("XNhfs"))
			return noise_pattern_XNhfs;
		else if (name.equals("XKhfs"))
			return noise_pattern_XKhfs;
		else if (name.equals("XXhfs"))
			return noise_pattern_XXhfs;
		else if (name.equals("KNhfs"))
			return noise_pattern_KNhfs;
		else if (name.equals("KKhfs"))
			return noise_pattern_KKhfs;
		else if (name.equals("KXhfs"))
			return noise_pattern_KXhfs;
		else if (name.equals("INhfs"))
			return noise_pattern_INhfs;
		else if (name.equals("IKhfs"))
			return noise_pattern_IKhfs;
		else if (name.equals("IXhfs"))
			return noise_pattern_IXhfs;
		else if (name.equals("XXfallback+hfs"))
			return noise_pattern_XXfallback_hfs;
		else if (name.equals("NXnoidh+hfs"))
			return noise_pattern_NXnoidh_hfs;
		else if (name.equals("XXnoidh+hfs"))
			return noise_pattern_XXnoidh_hfs;
		else if (name.equals("KXnoidh+hfs"))
			return noise_pattern_KXnoidh_hfs;
		else if (name.equals("IKnoidh+hfs"))
			return noise_pattern_IKnoidh_hfs;
		else if (name.equals("IXnoidh+hfs"))
			return noise_pattern_IXnoidh_hfs;
		return null;
	}

	/**
	 * Reverses the local and remote flags for a pattern.
	 * 
	 * @param flags The flags, assuming that the initiator is "local".
	 * @return The reversed flags, with the responder now being "local".
	 */
	public static short reverseFlags(short flags)
	{
		return (short)(((flags >> 8) & 0x00FF) | ((flags << 8) & 0xFF00));
	}
}
