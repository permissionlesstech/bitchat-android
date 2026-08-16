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
        val setupReset = manager.discardForAccountReset()
        assertTrue(manager.completeAccountReset(setupReset))
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
        assertFalse(manager.sendEvent(signedEvent(), relayUrls = emptyList()))
        manager.retryConnection("wss://not-configured.example")

        manager.unsubscribe(id)
        assertEquals(0, manager.getActiveSubscriptionCount())
        assertFalse(manager.isConnected.value)
        assertTrue(manager.getRelayStatuses().none { it.isConnected })

        manager.disconnect()
        assertFalse(manager.isConnected.value)
    }

    @Test
    fun `account reset advances generation and discards queued legacy gift wraps`() {
        val manager = NostrRelayManager.shared
        val setupReset = manager.discardForAccountReset()
        assertTrue(manager.completeAccountReset(setupReset))
        val oldGeneration = manager.accountGenerationForTesting()
        val event = signedEvent()
        assertTrue(manager.registerPendingGiftWrap(event.id, oldGeneration))
        manager.sendEvent(event, relayUrls = targetRelays)

        assertEquals(1, manager.queuedEventCountForTesting())
        assertEquals(1, manager.pendingGiftWrapCountForTesting())

        val resetToken = manager.discardForAccountReset()

        assertEquals(oldGeneration + 1, manager.accountGenerationForTesting())
        assertFalse(manager.isAccountGenerationCurrent(oldGeneration))
        assertFalse(
            manager.isAccountGenerationCurrent(
                manager.accountGenerationForTesting()
            )
        )
        assertEquals(0, manager.queuedEventCountForTesting())
        assertEquals(0, manager.pendingGiftWrapCountForTesting())
        assertTrue(manager.completeAccountReset(resetToken))
    }

    @Test
    fun `new relay work is rejected during reset and admitted after completion`() {
        val manager = NostrRelayManager.shared
        val resetToken = manager.discardForAccountReset()
        val event = signedEvent()

        assertFalse(manager.sendEvent(event, relayUrls = targetRelays))
        assertEquals(0, manager.queuedEventCountForTesting())

        assertTrue(manager.completeAccountReset(resetToken))
        assertTrue(manager.sendEvent(event, relayUrls = targetRelays))
        assertEquals(1, manager.queuedEventCountForTesting())

        val cleanupReset = manager.discardForAccountReset()
        assertTrue(manager.completeAccountReset(cleanupReset))
    }

    @Test
    fun `stale reset cannot clear or reopen a newer relay reset`() {
        val manager = NostrRelayManager.shared
        val firstReset = manager.beginAccountReset()
        val secondReset = manager.beginAccountReset()
        val event = signedEvent()

        assertFalse(manager.discardForAccountReset(firstReset))
        assertFalse(manager.completeAccountReset(firstReset))
        assertFalse(manager.sendEvent(event, relayUrls = targetRelays))
        assertEquals(0, manager.queuedEventCountForTesting())

        assertTrue(manager.discardForAccountReset(secondReset))
        assertTrue(manager.completeAccountReset(secondReset))
        assertTrue(manager.sendEvent(event, relayUrls = targetRelays))

        val cleanupReset = manager.discardForAccountReset()
        assertTrue(manager.completeAccountReset(cleanupReset))
    }

    @Test
    fun `stale geohash wrapper cannot recapture a replacement generation`() {
        val manager = NostrRelayManager.shared
        val setupReset = manager.discardForAccountReset()
        assertTrue(manager.completeAccountReset(setupReset))
        val oldGeneration = manager.captureAccountGeneration()
        val replacementReset = manager.discardForAccountReset()
        assertTrue(manager.completeAccountReset(replacementReset))
        val event = signedEvent()

        manager.sendEventToGeohash(
            event = event,
            geohash = "u4pruydq",
            expectedAccountGeneration = oldGeneration
        )
        manager.subscribe(
            filter = NostrFilter(kinds = listOf(NostrKind.TEXT_NOTE)),
            id = "stale-wrapper",
            handler = {},
            targetRelayUrls = emptyList(),
            expectedAccountGeneration = oldGeneration
        )

        assertEquals(0, manager.queuedEventCountForTesting())
        assertFalse(manager.getActiveSubscriptions().containsKey("stale-wrapper"))

        val cleanupReset = manager.discardForAccountReset()
        assertTrue(manager.completeAccountReset(cleanupReset))
    }

    @Test
    fun `generation captured during reset never becomes replacement work`() {
        val manager = NostrRelayManager.shared
        val resetToken = manager.beginAccountReset()
        val parkedGeneration = manager.captureAccountGeneration()

        assertTrue(manager.discardForAccountReset(resetToken))
        assertTrue(manager.completeAccountReset(resetToken))
        assertFalse(manager.isAccountGenerationCurrent(parkedGeneration))

        val cleanupReset = manager.discardForAccountReset()
        assertTrue(manager.completeAccountReset(cleanupReset))
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

    private val targetRelays = listOf(NostrRelayManager.defaultRelays().first())
}
