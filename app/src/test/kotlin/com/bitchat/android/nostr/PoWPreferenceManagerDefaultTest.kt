package com.bitchat.android.nostr

import org.junit.Assert.assertTrue
import org.junit.Test

class PoWPreferenceManagerDefaultTest {
    @Test
    fun `pow is enabled by default for fresh installs`() {
        assertTrue(PoWPreferenceManager.powEnabled.value)
    }
}
