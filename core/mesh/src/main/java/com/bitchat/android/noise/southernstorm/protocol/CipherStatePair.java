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
 * Class that contains a pair of CipherState objects.
 * 
 * CipherState pairs typically arise when HandshakeState.split() is called.
 */
public final class CipherStatePair implements Destroyable {

	private CipherState send;
	private CipherState recv;

	/**
	 * Constructs a pair of CipherState objects.
	 * 
	 * @param sender The CipherState to use to send packets to the remote party.
	 * @param receiver The CipherState to use to receive packets from the remote party.
	 */
	public CipherStatePair(CipherState sender, CipherState receiver)
	{
		send = sender;
		recv = receiver;
	}

	/**
	 * Gets the CipherState to use to send packets to the remote party.
	 * 
	 * @return The sending CipherState.
	 */
	public CipherState getSender() {
		return send;
	}
	
	/**
	 * Gets the CipherState to use to receive packets from the remote party.
	 * 
	 * @return The receiving CipherState.
	 */
	public CipherState getReceiver() {
		return recv;
	}

	/**
	 * Destroys the receiving CipherState and retains only the sending CipherState.
	 * 
	 * This function is intended for use with one-way handshake patterns.
	 */
	public void senderOnly()
	{
		if (recv != null) {
			recv.destroy();
			recv = null;
		}
	}
	
	/**
	 * Destroys the sending CipherState and retains only the receiving CipherState.
	 * 
	 * This function is intended for use with one-way handshake patterns.
	 */
	public void receiverOnly()
	{
		if (send != null) {
			send.destroy();
			send = null;
		}
	}
	
	/**
	 * Swaps the sender and receiver.
	 */
	public void swap()
	{
		CipherState temp = send;
		send = recv;
		recv = temp;
	}

	@Override
	public void destroy() {
		senderOnly();
		receiverOnly();
	}
}
