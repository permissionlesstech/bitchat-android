package com.bitchat.android.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPreferenceManagerTest {
    @Test
    fun `flag secure is disabled when preference is off`() {
        assertFalse(PrivacyPreferenceManager.shouldApplyFlagSecure(false, "peer123"))
        assertFalse(PrivacyPreferenceManager.shouldApplyFlagSecure(false, null))
    }

    @Test
    fun `flag secure is applied only in active private chats when enabled`() {
        assertTrue(PrivacyPreferenceManager.shouldApplyFlagSecure(true, "peer123"))
        assertFalse(PrivacyPreferenceManager.shouldApplyFlagSecure(true, null))
        assertFalse(PrivacyPreferenceManager.shouldApplyFlagSecure(true, "   "))
    }
}
