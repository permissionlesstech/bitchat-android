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
 * Additional API for DH objects that need special handling for
 * hybrid operations.
 */
public interface DHStateHybrid extends DHState {

	/**
	 * Generates a new random keypair relative to the parameters
	 * in another object.
	 * 
	 * @param remote The remote party in this communication to obtain parameters.
	 * 
	 * @throws IllegalStateException The other or remote DH object does not have
	 * the same type as this object.
	 */
	void generateKeyPair(DHState remote);

	/**
	 * Copies the key values from another DH object of the same type.
	 * 
	 * @param other The other DH object to copy from
	 * @param remote The remote party in this communication to obtain parameters.
	 * 
	 * @throws IllegalStateException The other or remote DH object does not have
	 * the same type as this object.
	 */
	void copyFrom(DHState other, DHState remote);
	
	/**
	 * Specifies the local peer object prior to setting a public key
	 * on a remote object.
	 * 
	 * @param local The local peer object.
	 */
	void specifyPeer(DHState local);
}
