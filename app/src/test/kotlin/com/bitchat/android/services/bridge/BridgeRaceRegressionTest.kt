package com.bitchat.android.services.bridge

import com.bitchat.android.mesh.BridgeOutboundPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeRaceRegressionTest {
    @Test
    fun `later opt in cannot authorize a send that was previously denied`() {
        val policyAtSend = BridgeOutboundPolicy.capture(enabled = false, nearbyOnly = false)
        val policyAtPublish = BridgeOutboundPolicy.capture(enabled = true, nearbyOnly = false)

        assertFalse(policyAtSend.permitsPublication(policyAtPublish))
    }

    @Test
    fun `later opt out still blocks a send that was previously allowed`() {
        val policyAtSend = BridgeOutboundPolicy.capture(enabled = true, nearbyOnly = false)
        val policyAtPublish = BridgeOutboundPolicy.capture(enabled = false, nearbyOnly = false)

        assertFalse(policyAtSend.permitsPublication(policyAtPublish))
    }

    @Test
    fun `publication requires bridge permission at send and publish time`() {
        val allowed = BridgeOutboundPolicy.capture(enabled = true, nearbyOnly = false)
        val nearbyOnly = BridgeOutboundPolicy.capture(enabled = true, nearbyOnly = true)

        assertTrue(allowed.permitsPublication(allowed))
        assertFalse(nearbyOnly.permitsPublication(allowed))
        assertFalse(allowed.permitsPublication(nearbyOnly))
    }

    @Test
    fun `delayed close targets the retired subscription generation`() {
        val slot = RelaySubscriptionSlot("bridge")
        val activeOnRelay = mutableSetOf<String>()
        val delayedCloses = mutableListOf<String>()

        val first = slot.replace(
            close = delayedCloses::add,
            open = activeOnRelay::add
        )
        val second = slot.replace(
            close = delayedCloses::add,
            open = activeOnRelay::add
        )

        delayedCloses.forEach(activeOnRelay::remove)

        assertNotEquals(first, second)
        assertFalse(first in activeOnRelay)
        assertTrue(second in activeOnRelay)
    }
}
