package com.bitchat.android.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GattNotificationEligibilityTest {
    @Test
    fun `subscription denied before a verified announce`() {
        assertFalse(GattNotificationEligibility.maySubscribe(hasVerifiedAnnounce = false))
    }

    @Test
    fun `subscription allowed after a verified announce`() {
        assertTrue(GattNotificationEligibility.maySubscribe(hasVerifiedAnnounce = true))
    }
}
