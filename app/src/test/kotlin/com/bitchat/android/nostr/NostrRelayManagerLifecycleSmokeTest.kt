package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class NostrRelayManagerLifecycleSmokeTest {
    @Test
    fun `owner teardown is synchronous and preserves other subscription owners`() {
        val manager = NostrRelayManager.shared
        manager.disconnect()
        manager.clearAllSubscriptions()
        val filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE))

        manager.subscribe(
            filter = filter,
            id = "background-contract",
            handler = {},
            targetRelayUrls = emptyList(),
            owner = NostrRelayManager.OWNER_BACKGROUND
        )
        manager.subscribe(
            filter = filter,
            id = "ui-contract",
            handler = {},
            targetRelayUrls = emptyList(),
            owner = "test-ui"
        )

        manager.unsubscribeOwner("test-ui")

        assertEquals(setOf("background-contract"), manager.getActiveSubscriptions().keys)
        manager.clearAllSubscriptions()
    }

    @Test
    fun `disconnected manager maintains subscription and empty publish invariants locally`() {
        val manager = NostrRelayManager.shared
        manager.disconnect()
        manager.clearAllSubscriptions()

        val id = manager.subscribe(
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
            id = "local-contract",
            handler = {},
            targetRelayUrls = emptyList()
        )

        assertEquals("local-contract", id)
        assertEquals(1, manager.getActiveSubscriptionCount())
        assertTrue(manager.getActiveSubscriptions().containsKey(id))
        assertTrue(manager.validateSubscriptionConsistency().isConsistent)
        manager.sendEvent(signedEvent(), relayUrls = emptyList())
        manager.retryConnection("wss://not-configured.example")

        manager.unsubscribe(id)
        assertEquals(0, manager.getActiveSubscriptionCount())
        assertFalse(manager.isConnected.value)
        assertTrue(manager.getRelayStatuses().none { it.isConnected })

        manager.disconnect()
        assertFalse(manager.isConnected.value)
    }

    private fun signedEvent(): NostrEvent {
        val privateKey = "0".repeat(63) + "1"
        return NostrEvent(
            pubkey = NostrCrypto.derivePublicKey(privateKey),
            createdAt = 1,
            kind = NostrKind.TEXT_NOTE,
            tags = emptyList(),
            content = "local"
        ).sign(privateKey)
    }
}
