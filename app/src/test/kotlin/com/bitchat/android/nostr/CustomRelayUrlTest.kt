package com.bitchat.android.nostr

import org.junit.Assert.*
import org.junit.Test

class CustomRelayUrlTest {
    @Test fun `normalizes secure relay without dropping path or port`() {
        assertEquals("wss://relay.example", CustomRelayUrl.normalize(" WSS://RELAY.EXAMPLE/ "))
        assertEquals("wss://relay.example:8443/nostr", CustomRelayUrl.normalize("wss://relay.example:8443/nostr"))
    }
    @Test fun `rejects insecure credentials queries and invalid endpoints`() {
        listOf("ws://relay.example", "https://relay.example", "wss://user:secret@relay.example",
            "wss://relay.example?secret=x", "wss://relay.example#fragment", "wss://relay.example:0",
            "wss://relay.example:65536", "wss:///nostr", "not a URL").forEach {
            assertNull(it, CustomRelayUrl.normalize(it))
        }
    }
}
