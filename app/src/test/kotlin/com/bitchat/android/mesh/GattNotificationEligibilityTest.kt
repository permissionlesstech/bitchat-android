package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class GattNotificationEligibilityTest {
    @Test
    fun `subscription is deferred before a verified announce`() {
        assertEquals(
            GattSubscriptionAction.DEFER,
            GattNotificationEligibility.action(announcePeerId = null, authenticatedPeerId = null)
        )
    }

    @Test
    fun `subscription is granted when the announce and session name the same peer`() {
        assertEquals(
            GattSubscriptionAction.GRANT,
            GattNotificationEligibility.action(announcePeerId = "peer", authenticatedPeerId = "peer")
        )
    }

    @Test
    fun `a replayed announce from a different peer stays deferred`() {
        assertEquals(
            GattSubscriptionAction.DEFER,
            GattNotificationEligibility.action(announcePeerId = "victim", authenticatedPeerId = "central")
        )
    }
}
