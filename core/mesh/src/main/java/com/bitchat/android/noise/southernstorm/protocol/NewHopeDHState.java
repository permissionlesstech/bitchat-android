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

import com.bitchat.android.noise.southernstorm.crypto.NewHope;
import com.bitchat.android.noise.southernstorm.crypto.NewHopeTor;

/**
 * Implementation of the New Hope post-quantum algorithm for the Noise protocol.
 */
final class NewHopeDHState implements DHStateHybrid {

	enum KeyType
	{
		None,
		AlicePrivate,
		AlicePublic,
		BobPrivate,
		BobPublic,
		BobCalculated;
	}

	private NewHopeTor nh;
	private byte[] publicKey;
	private byte[] privateKey;
	private KeyType keyType;
	
	/**
	 * Special version of NewHopeTor that allows explicit random data
	 * to be specified for test vectors.
	 */
	private class NewHopeWithPrivateKey extends NewHopeTor {

		byte[] randomData;
		
		public NewHopeWithPrivateKey(byte[] randomData)
		{
			this.randomData = randomData;
		}

		@Override
		protected void randombytes(byte[] buffer)
		{
			System.arraycopy(randomData, 0, buffer, 0, buffer.length);
		}
	}

	/**
	 * Constructs a new key exchange object for New Hope.
	 */
	public NewHopeDHState() {
		nh = null;
		publicKey = null;
		privateKey = null;
		keyType = KeyType.None;
	}
	
	private boolean isAlice() {
		return keyType == KeyType.AlicePrivate || keyType == KeyType.AlicePublic;
	}

	@Override
	public void destroy() {
		clearKey();
	}

	@Override
	public String getDHName() {
		return "NewHope";
	}

	@Override
	public int getPublicKeyLength() {
		if (isAlice())
			return NewHope.SENDABYTES;
		else
			return NewHope.SENDBBYTES;
	}

	@Override
	public int getPrivateKeyLength() {
		// New Hope doesn't have private keys in the same sense as
		// Curve25519 and Curve448.  Instead return the number of
		// random bytes that we need to generate each key type.
		if (isAlice())
			return 64;
		else
			return 32;
	}

	@Override
	public int getSharedKeyLength() {
		return NewHope.SHAREDBYTES;
	}

	@Override
	public void generateKeyPair() {
		clearKey();
		keyType = KeyType.AlicePrivate;
		nh = new NewHopeTor();
		publicKey = new byte [NewHope.SENDABYTES];
		nh.keygen(publicKey, 0);
	}

	@Override
	public void generateKeyPair(DHState remote) {
		if (remote == null) {
			// No remote public key, so always generate in Alice mode.
			generateKeyPair();
			return;
		} else if (!(remote instanceof NewHopeDHState)) {
			throw new IllegalStateException("Mismatched DH objects");
		}
		NewHopeDHState r = (NewHopeDHState)remote;
		if (r.isAlice() && r.publicKey != null) {
			// We have a remote public key for Alice, so generate in Bob mode.
			clearKey();
			keyType = KeyType.BobCalculated;
			nh = new NewHopeTor();
			publicKey = new byte [NewHope.SENDBBYTES];
			privateKey = new byte [NewHope.SHAREDBYTES];
			nh.sharedb(privateKey, 0, publicKey, 0, r.publicKey, 0);
		} else {
			generateKeyPair();
		}
	}

	@Override
	public void getPublicKey(byte[] key, int offset) {
		if (publicKey != null)
			System.arraycopy(publicKey, 0, key, offset, getPublicKeyLength());
		else
			Arrays.fill(key, 0, getPublicKeyLength(), (byte)0);
	}

	@Override
	public void setPublicKey(byte[] key, int offset) {
		if (publicKey != null)
			Noise.destroy(publicKey);
		publicKey = new byte [getPublicKeyLength()];
		System.arraycopy(key, 0, publicKey, 0, publicKey.length);
	}

	@Override
	public void getPrivateKey(byte[] key, int offset) {
		if (privateKey != null)
			System.arraycopy(privateKey, 0, key, offset, getPrivateKeyLength());
		else
			Arrays.fill(key, 0, getPrivateKeyLength(), (byte)0);
	}

	@Override
	public void setPrivateKey(byte[] key, int offset) {
		clearKey();
		// Guess the key type from the length of the test data.
		if (offset == 0 && key.length == 64)
			keyType = KeyType.AlicePrivate;
		else
			keyType = KeyType.BobPrivate;
		privateKey = new byte [getPrivateKeyLength()];
		System.arraycopy(key, 0, privateKey, 0, privateKey.length);
	}

	@Override
	public void setToNullPublicKey() {
		// Null public keys are not supported by New Hope.
		// Destroy the current values but otherwise ignore.
		clearKey();
	}

	@Override
	public void clearKey() {
		if (nh != null) {
			nh.destroy();
			nh = null;
		}
		if (publicKey != null) {
			Noise.destroy(publicKey);
			publicKey = null;
		}
		if (privateKey != null) {
			Noise.destroy(privateKey);
			privateKey = null;
		}
		keyType = KeyType.None;
	}

	@Override
	public boolean hasPublicKey() {
		return publicKey != null;
	}

	@Override
	public boolean hasPrivateKey() {
		return privateKey != null;
	}

	@Override
	public boolean isNullPublicKey() {
		return false;
	}

	@Override
	public void calculate(byte[] sharedKey, int offset, DHState publicDH) {
		if (!(publicDH instanceof NewHopeDHState))
			throw new IllegalArgumentException("Incompatible DH algorithms");
		NewHopeDHState other = (NewHopeDHState)publicDH;
		if (keyType == KeyType.AlicePrivate) {
			// Compute the shared key for Alice.
			nh.shareda(sharedKey, 0, other.publicKey, 0);
		} else if (keyType == KeyType.BobCalculated) {
			// The shared key for Bob was already computed when the key was generated.
			System.arraycopy(privateKey, 0, sharedKey, 0, NewHope.SHAREDBYTES);
		} else {
			throw new IllegalStateException("Cannot calculate with this DH object");
		}
	}

	@Override
	public void copyFrom(DHState other) {
		if (!(other instanceof NewHopeDHState))
			throw new IllegalStateException("Mismatched DH key objects");
		if (other == this)
			return;
		NewHopeDHState dh = (NewHopeDHState)other;
		clearKey();
		switch (dh.keyType) {
		case None:
			break;

		case AlicePrivate:
			if (dh.privateKey != null) {
				keyType = KeyType.AlicePrivate;
				privateKey = new byte [dh.privateKey.length];
				System.arraycopy(dh.privateKey, 0, privateKey, 0, privateKey.length);
			} else {
				throw new IllegalStateException("Cannot copy generated key for Alice");
			}
			break;

		case BobPrivate:
		case BobCalculated:
			throw new IllegalStateException("Cannot copy private key for Bob without public key for Alice");

		case AlicePublic:
		case BobPublic:
			keyType = dh.keyType;
			publicKey = new byte [dh.publicKey.length];
			System.arraycopy(dh.publicKey, 0, publicKey, 0, publicKey.length);
			break;
		}
	}

	@Override
	public void copyFrom(DHState other, DHState remote) {
		if (remote == null) {
			copyFrom(other);
			return;
		}
		if (!(other instanceof NewHopeDHState) || !(remote instanceof NewHopeDHState))
			throw new IllegalStateException("Mismatched DH key objects");
		if (other == this)
			return;
		NewHopeDHState dh = (NewHopeDHState)other;
		NewHopeDHState remotedh = (NewHopeDHState)remote;
		clearKey();
		switch (dh.keyType) {
		case None:
			break;

		case AlicePrivate:
			if (dh.privateKey != null) {
				// Generate Alice's public and private key now.
				keyType = KeyType.AlicePrivate;
				nh = new NewHopeWithPrivateKey(dh.privateKey);
				publicKey = new byte [NewHope.SENDABYTES];
				nh.keygen(publicKey, 0);
			} else {
				throw new IllegalStateException("Cannot copy generated key for Alice");
			}
			break;

		case BobPrivate:
			if (dh.privateKey != null && remotedh.keyType == KeyType.AlicePublic) {
				// Now we know the public key for Alice, we can calculate Bob's public and shared keys.
				keyType = KeyType.BobCalculated;
				nh = new NewHopeWithPrivateKey(dh.privateKey);
				publicKey = new byte [NewHope.SENDBBYTES];
				privateKey = new byte [NewHope.SHAREDBYTES];
				nh.sharedb(privateKey, 0, publicKey, 0, remotedh.publicKey, 0);
			} else {
				throw new IllegalStateException("Cannot copy private key for Bob without public key for Alice");
			}
			break;

		case BobCalculated:
			throw new IllegalStateException("Cannot copy generated key for Bob");

		case AlicePublic:
		case BobPublic:
			keyType = dh.keyType;
			publicKey = new byte [dh.publicKey.length];
			System.arraycopy(dh.publicKey, 0, publicKey, 0, publicKey.length);
			break;
		}
	}

	@Override
	public void specifyPeer(DHState local) {
		if (!(local instanceof NewHopeDHState))
			return;
		clearKey();
		if (((NewHopeDHState)local).keyType == KeyType.AlicePrivate)
			keyType = KeyType.BobPublic;
		else
			keyType = KeyType.AlicePublic;
	}
}
