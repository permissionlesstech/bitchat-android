package com.bitchat.android.nostr

import javax.inject.Provider
import org.junit.Assert.assertEquals
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
    fun `the deduplicator is asked for again on every event the manager receives`() {
        val provider = CountingProvider(NostrEventDeduplicator())
        val manager = newRelayManager(deduplicator = provider)
        // A subscription as subscribe() would record it, without opening a socket.
        @Suppress("UNCHECKED_CAST")
        val active = NostrRelayManager::class.java.getDeclaredField("activeSubscriptions")
            .apply { isAccessible = true }
            .get(manager) as MutableMap<String, NostrRelayManager.SubscriptionInfo>
        active["sub"] = NostrRelayManager.SubscriptionInfo(id = "sub", filter = NostrFilter(), handler = { })
        fun message(id: String) =
            """["EVENT","sub",{"id":"$id","pubkey":"pk","created_at":1,"kind":1,"tags":[],"content":"x","sig":"s"}]"""

        manager.handleMessage(message("event-1"), "wss://relay.invalid")
        assertEquals(1, provider.asked)
        manager.handleMessage(message("event-2"), "wss://relay.invalid")
        assertEquals(2, provider.asked)

        // A replaced deduplicator is the one that sees the next event.
        val replacement = NostrEventDeduplicator()
        provider.current = replacement
        manager.handleMessage(message("event-1"), "wss://relay.invalid")
        assertEquals(3, provider.asked)
        assertEquals(1, replacement.getStats().totalChecks)
    }
}
