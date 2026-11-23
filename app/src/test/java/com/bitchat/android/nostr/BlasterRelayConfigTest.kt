package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class BlasterRelayConfigTest {

    @Test
    fun appendsBlasterRelayWhenEnabledAndAbsent() {
        val base = listOf("wss://relay.damus.io", "wss://nos.lol")
        val result = BlasterRelayConfig.applyBlasterRelay(base, enabled = true)

        assertEquals(
            listOf("wss://relay.damus.io", "wss://nos.lol", BlasterRelayConfig.DEFAULT_BLASTER_RELAY_URL),
            result
        )
    }

    @Test
    fun avoidsDuplicateWhenAlreadyPresentWithTrailingSlash() {
        val base = listOf("wss://nostr.mutinywallet.com/")
        val result = BlasterRelayConfig.applyBlasterRelay(base, enabled = true)

        assertEquals(base, result)
    }

    @Test
    fun leavesListUnchangedWhenDisabled() {
        val base = listOf("wss://relay.damus.io")
        val result = BlasterRelayConfig.applyBlasterRelay(base, enabled = false)

        assertEquals(base, result)
    }
}
