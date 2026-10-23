package com.bitchat.android.geohash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveLocationPrivacyGateTest {
    @Test
    fun `enabling location does not fire revocation listeners`() {
        var revoked = 0
        val listener = { revoked += 1 }
        LiveLocationPrivacyGate.addRevocationListener(listener)
        try {
            LiveLocationPrivacyGate.update(false)
            val afterDisable = revoked
            assertTrue("disable should revoke", afterDisable >= 1)

            LiveLocationPrivacyGate.update(true)
            assertEquals(
                "enable must not cancel in-flight location work",
                afterDisable,
                revoked
            )

            LiveLocationPrivacyGate.update(false)
            assertTrue(revoked > afterDisable)
        } finally {
            LiveLocationPrivacyGate.removeRevocationListener(listener)
            LiveLocationPrivacyGate.update(false)
        }
    }
}
