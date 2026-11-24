package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class RelayDirectoryTest {

    @Test
    fun normalizeRelayUrl_lowercasesAndStripsDefaultPortAndTrailingSlash() {
        assertEquals("wss://relay.example.com", RelayDirectory.normalizeRelayUrl("Relay.Example.com:443/"))
        assertEquals("wss://relay.example.com", RelayDirectory.normalizeRelayUrl("WSS://RELAY.EXAMPLE.COM:443"))
        assertEquals("wss://relay.example.com:8443", RelayDirectory.normalizeRelayUrl("wss://relay.example.com:8443/"))
        assertEquals("ws://relay.example.com", RelayDirectory.normalizeRelayUrl("ws://relay.example.com:80/"))
    }

    @Test
    fun parseCsv_deduplicatesEquivalentRelayUrls() {
        val csv = """
            Relay URL,Latitude,Longitude
            relay.example.com,37.77,-122.41
            wss://RELAY.EXAMPLE.COM:443/,37.77,-122.41
            relay.other.com,37.77,-122.41
        """.trimIndent()

        val parsed = RelayDirectory.parseCsv(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(2, parsed.size)
        assertEquals(listOf("wss://relay.example.com", "wss://relay.other.com"), parsed.map { it.url })
    }
}
