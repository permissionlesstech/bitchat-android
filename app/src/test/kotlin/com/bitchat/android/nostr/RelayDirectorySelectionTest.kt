package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayDirectorySelectionTest {

    @Test
    fun `canonical url drops the default port and lowercases the host`() {
        assertEquals(
            "wss://relay.example.com",
            RelayDirectory.canonicalRelayUrl("relay.example.com:443")
        )
        assertEquals(
            "wss://relay.example.com",
            RelayDirectory.canonicalRelayUrl("WSS://Relay.Example.com:443")
        )
        assertEquals(
            "wss://relay.example.com:8443",
            RelayDirectory.canonicalRelayUrl("relay.example.com:8443")
        )
    }

    @Test
    fun `duplicate host and port do not take two of the five slots`() {
        val entries = listOf(
            RelayDirectory.RelayInfo("wss://b.example", 0.0, 0.0),
            RelayDirectory.RelayInfo("b.example:443", 0.0, 0.0),
            RelayDirectory.RelayInfo("a.example", 0.0, 0.0),
            RelayDirectory.RelayInfo("c.example", 1.0, 1.0)
        )

        val picked = RelayDirectory.selectClosest(entries, lat = 0.0, lon = 0.0, count = 2)

        assertEquals(listOf("wss://a.example", "wss://b.example"), picked)
    }
}
