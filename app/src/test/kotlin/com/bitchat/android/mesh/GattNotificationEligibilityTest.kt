package com.bitchat.android.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class GattNotificationEligibilityTest {
    @Test
    fun `subscription is deferred before a verified announce`() {
        assertEquals(
            GattSubscriptionAction.DEFER,
            GattNotificationEligibility.action(hasVerifiedAnnounce = false)
        )
    }

    @Test
    fun `subscription is granted after a verified announce`() {
        assertEquals(
            GattSubscriptionAction.GRANT,
            GattNotificationEligibility.action(hasVerifiedAnnounce = true)
        )
    }
}
