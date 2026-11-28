package com.bitchat.android.noise

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {
    private fun accept(nonce: Long, highest: Long, window: ByteArray): Pair<Long, ByteArray> {
        assertTrue(NoiseSession.isValidNonce(nonce, highest, window))
        return NoiseSession.markNonceAsSeen(nonce, highest, window)
    }

    @Test
    fun `every nonce inside the window is rejected on replay`() {
        var highest = 0L
        var window = ByteArray(128)
        for (nonce in 0L..100L) {
            val marked = accept(nonce, highest, window)
            highest = marked.first
            window = marked.second
        }

        for (nonce in 0L..100L) {
            assertFalse(NoiseSession.isValidNonce(nonce, highest, window))
        }
    }

    @Test
    fun `a late unseen nonce inside the window is still accepted`() {
        var highest = 0L
        var window = ByteArray(128)
        for (nonce in listOf(0L, 1L, 2L, 3L, 4L, 6L, 7L, 8L, 9L, 10L, 5L, 13L, 11L, 12L)) {
            val marked = accept(nonce, highest, window)
            highest = marked.first
            window = marked.second
            assertFalse(NoiseSession.isValidNonce(nonce, highest, window))
        }
    }
}
