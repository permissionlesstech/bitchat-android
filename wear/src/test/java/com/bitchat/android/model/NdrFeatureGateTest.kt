package com.bitchat.android.model

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Test

class NdrFeatureGateTest {
    @After
    fun resetGate() {
        NdrFeatureGate.setEnabledForTests(false)
    }

    @Test
    fun `wear does not advertise NDR by default`() {
        NdrFeatureGate.setEnabledForTests(false)

        assertFalse(NdrFeatureGate.isEnabled())
        assertFalse(PeerCapabilities.LOCAL_SUPPORTED.contains(PeerCapabilities.NOSTR_DOUBLE_RATCHET))
    }
}
