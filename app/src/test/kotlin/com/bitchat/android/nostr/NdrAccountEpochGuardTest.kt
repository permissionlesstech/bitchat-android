package com.bitchat.android.nostr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NdrAccountEpochGuardTest {
    @Test
    fun `panic invalidation rejects old account mutations`() {
        val guard = NdrAccountEpochGuard()
        val oldEpoch = guard.begin("aa".repeat(32))

        guard.invalidate()
        val newEpoch = guard.begin("bb".repeat(32))

        assertFalse(guard.runIfCurrent(oldEpoch) {})
        assertTrue(guard.runIfCurrent(newEpoch) {})
    }

    @Test
    fun `invalidation waits for an in-flight mutation before advancing epoch`() {
        val guard = NdrAccountEpochGuard()
        val epoch = guard.begin("aa".repeat(32))
        val mutationEntered = CountDownLatch(1)
        val releaseMutation = CountDownLatch(1)
        val invalidationFinished = CountDownLatch(1)

        val mutationThread = thread {
            guard.runIfCurrent(epoch) {
                mutationEntered.countDown()
                releaseMutation.await(2, TimeUnit.SECONDS)
            }
        }
        assertTrue(mutationEntered.await(2, TimeUnit.SECONDS))

        val invalidationThread = thread {
            guard.invalidate()
            invalidationFinished.countDown()
        }
        try {
            assertFalse(invalidationFinished.await(150, TimeUnit.MILLISECONDS))
        } finally {
            releaseMutation.countDown()
            mutationThread.join(2_000)
            invalidationThread.join(2_000)
        }

        assertTrue(invalidationFinished.count == 0L)
        assertFalse(guard.isCurrent(epoch))
    }
}
