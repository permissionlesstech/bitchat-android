package com.bitchat.android.nostr

import javax.inject.Provider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The relay manager outlives an identity, so it asks for the deduplicator each time it uses one
 * and never keeps the one it was first given.
 */
@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerDeduplicatorTest {

    private class CountingProvider(var current: NostrEventDeduplicator) : Provider<NostrEventDeduplicator> {
        var asked = 0
        override fun get(): NostrEventDeduplicator {
            asked++
            return current
        }
    }

    @Test
    fun `the deduplicator is asked for at use time and not at construction`() {
        val provider = CountingProvider(NostrEventDeduplicator())
        val manager = newRelayManager(deduplicator = provider)
        assertEquals(0, provider.asked)

        manager.getDeduplicationStats()
        manager.clearDeduplicationCache()

        assertEquals(2, provider.asked)
    }

    @Test
    fun `a replaced deduplicator is the one used next`() {
        val first = NostrEventDeduplicator()
        val second = NostrEventDeduplicator()
        first.isDuplicate("event-1")
        val provider = CountingProvider(first)
        val manager = newRelayManager(deduplicator = provider)
        assertNotSame(first, second)
        assertEquals(1, manager.getDeduplicationStats().currentSize)

        provider.current = second

        assertEquals(0, manager.getDeduplicationStats().currentSize)
        assertSame(second, provider.get())
    }
}
