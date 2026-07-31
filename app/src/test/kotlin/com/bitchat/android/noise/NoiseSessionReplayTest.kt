package com.bitchat.android.noise

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseSessionReplayTest {

    @Test
    fun testReplayWindowRejectsDuplicatesWhenNonceAdvances() {
        var highestNonce = 0L
        var window = ByteArray(128)

        // 1. Process Nonce 0
        assertTrue(NoiseSession.isValidNonce(0, highestNonce, window))
        val (h0, w0) = NoiseSession.markNonceAsSeen(0, highestNonce, window)
        highestNonce = h0
        window = w0

        // Duplicate Nonce 0 should be rejected
        assertFalse(NoiseSession.isValidNonce(0, highestNonce, window))

        // 2. Process Nonce 1 (advances highest received nonce)
        assertTrue(NoiseSession.isValidNonce(1, highestNonce, window))
        val (h1, w1) = NoiseSession.markNonceAsSeen(1, highestNonce, window)
        highestNonce = h1
        window = w1

        // Both Nonce 1 and Nonce 0 must be marked as seen and rejected if replayed
        assertFalse(NoiseSession.isValidNonce(1, highestNonce, window))
        assertFalse("Nonce 0 must be rejected as duplicate after Nonce 1 advances window", NoiseSession.isValidNonce(0, highestNonce, window))

        // 3. Advance to Nonce 10
        assertTrue(NoiseSession.isValidNonce(10, highestNonce, window))
        val (h10, w10) = NoiseSession.markNonceAsSeen(10, highestNonce, window)
        highestNonce = h10
        window = w10

        // Replayed nonces 0, 1, 10 must all be rejected
        assertFalse(NoiseSession.isValidNonce(10, highestNonce, window))
        assertFalse(NoiseSession.isValidNonce(1, highestNonce, window))
        assertFalse(NoiseSession.isValidNonce(0, highestNonce, window))

        // Unseen out-of-order nonce 5 within window should be accepted first time
        assertTrue(NoiseSession.isValidNonce(5, highestNonce, window))
        val (h5, w5) = NoiseSession.markNonceAsSeen(5, highestNonce, window)
        highestNonce = h5
        window = w5

        // Duplicate Nonce 5 should now be rejected
        assertFalse(NoiseSession.isValidNonce(5, highestNonce, window))
    }
}
