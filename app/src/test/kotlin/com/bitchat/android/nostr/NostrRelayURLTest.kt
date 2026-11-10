package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NostrRelayURLTest {

    @Test
    fun `bare hostname gets wss`() {
        assertEquals("wss://relay.example.com", NostrRelayURL.normalized("relay.example.com", "wss"))
    }

    @Test
    fun `https upgrades to wss and drops default port`() {
        assertEquals(
            "wss://relay.example.com",
            NostrRelayURL.normalized("https://Relay.Example.com:443")
        )
    }

    @Test
    fun `http upgrades to ws and drops default port`() {
        assertEquals(
            "ws://relay.example.com",
            NostrRelayURL.normalized("http://relay.example.com:80")
        )
    }

    @Test
    fun `lone slash path is stripped`() {
        assertEquals("wss://relay.example.com", NostrRelayURL.normalized("wss://relay.example.com/"))
    }

    @Test
    fun `non websocket schemes are rejected`() {
        assertNull(NostrRelayURL.normalized("ftp://relay.example.com"))
        assertNull(NostrRelayURL.normalized(""))
        assertNull(NostrRelayURL.normalized("   "))
    }

    @Test
    fun `non default port is kept`() {
        assertEquals(
            "wss://relay.example.com:8443",
            NostrRelayURL.normalized("wss://relay.example.com:8443")
        )
    }
}
