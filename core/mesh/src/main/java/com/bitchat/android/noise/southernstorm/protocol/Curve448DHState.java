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

import java.util.Arrays;

import com.bitchat.android.noise.southernstorm.crypto.Curve448;

/**
 * Implementation of the Curve448 algorithm for the Noise protocol.
 */
class Curve448DHState implements DHState {

	private byte[] publicKey;
	private byte[] privateKey;
	private int mode;

	/**
	 * Constructs a new Diffie-Hellman object for Curve448.
	 */
	public Curve448DHState()
	{
		publicKey = new byte [56];
		privateKey = new byte [56];
		mode = 0;
	}

	@Override
	public void destroy() {
		clearKey();
	}

	@Override
	public String getDHName() {
		return "448";
	}

	@Override
	public int getPublicKeyLength() {
		return 56;
	}

	@Override
	public int getPrivateKeyLength() {
		return 56;
	}

	@Override
	public int getSharedKeyLength() {
		return 56;
	}

	@Override
	public void generateKeyPair() {
		Noise.random(privateKey);
		Curve448.eval(publicKey, 0, privateKey, null);
		mode = 0x03;
	}

	@Override
	public void getPublicKey(byte[] key, int offset) {
		System.arraycopy(publicKey, 0, key, offset, 56);
	}

	@Override
	public void setPublicKey(byte[] key, int offset) {
		System.arraycopy(key, offset, publicKey, 0, 56);
		Arrays.fill(privateKey, (byte)0);
		mode = 0x01;
	}

	@Override
	public void getPrivateKey(byte[] key, int offset) {
		System.arraycopy(privateKey, 0, key, offset, 56);
	}

	@Override
	public void setPrivateKey(byte[] key, int offset) {
		System.arraycopy(key, offset, privateKey, 0, 56);
		Curve448.eval(publicKey, 0, privateKey, null);
		mode = 0x03;
	}

	@Override
	public void setToNullPublicKey() {
		Arrays.fill(publicKey, (byte)0);
		Arrays.fill(privateKey, (byte)0);
		mode = 0x01;
	}

	@Override
	public void clearKey() {
		Noise.destroy(publicKey);
		Noise.destroy(privateKey);
		mode = 0;
	}

	@Override
	public boolean hasPublicKey() {
		return (mode & 0x01) != 0;
	}

	@Override
	public boolean hasPrivateKey() {
		return (mode & 0x02) != 0;
	}

	@Override
	public boolean isNullPublicKey() {
		if ((mode & 0x01) == 0)
			return false;
		int temp = 0;
		for (int index = 0; index < 56; ++index)
			temp |= publicKey[index];
		return temp == 0;
	}

	@Override
	public void calculate(byte[] sharedKey, int offset, DHState publicDH) {
		if (!(publicDH instanceof Curve448DHState))
			throw new IllegalArgumentException("Incompatible DH algorithms");
		Curve448.eval(sharedKey, offset, privateKey, ((Curve448DHState)publicDH).publicKey);
	}

	@Override
	public void copyFrom(DHState other) {
		if (!(other instanceof Curve448DHState))
			throw new IllegalStateException("Mismatched DH key objects");
		if (other == this)
			return;
		Curve448DHState dh = (Curve448DHState)other;
		System.arraycopy(dh.privateKey, 0, privateKey, 0, 56);
		System.arraycopy(dh.publicKey, 0, publicKey, 0, 56);
		mode = dh.mode;
	}
}
