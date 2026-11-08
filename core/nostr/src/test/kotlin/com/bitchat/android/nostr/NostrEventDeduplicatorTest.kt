package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NostrEventDeduplicatorTest {
    @Test
    fun `isDuplicate flags a repeated event id`() {
        val deduplicator = NostrEventDeduplicator(maxCapacity = 10)

        assertEquals(false, deduplicator.isDuplicate("event-1"))
        assertEquals(true, deduplicator.isDuplicate("event-1"))
    }

    @Test
    fun `capacity evicts the least recently used event id`() {
        val deduplicator = NostrEventDeduplicator(maxCapacity = 2)

        deduplicator.isDuplicate("a")
        deduplicator.isDuplicate("b")
        deduplicator.isDuplicate("c") // evicts "a"

        assertTrue(deduplicator.contains("b"))
        assertTrue(deduplicator.contains("c"))
        assertEquals(false, deduplicator.contains("a"))
    }

    /**
     * totalChecks used to be incremented outside the lruLock that guards every other
     * mutation in this class, so concurrent callers could race on the read-modify-write
     * and lose increments. Every other counter (duplicateCount, evictionCount) was
     * already incremented under the lock, which is why only this one drifted.
     */
    @Test
    fun `totalChecks counts every call exactly once under concurrent access`() {
        val deduplicator = NostrEventDeduplicator(maxCapacity = 10_000)
        val threadCount = 8
        val checksPerThread = 2_000
        val executor = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)

        repeat(threadCount) { threadIndex ->
            executor.submit {
                start.await()
                repeat(checksPerThread) { callIndex ->
                    deduplicator.isDuplicate("thread-$threadIndex-event-$callIndex")
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(
            (threadCount * checksPerThread).toLong(),
            deduplicator.getStats().totalChecks
        )
    }
}
