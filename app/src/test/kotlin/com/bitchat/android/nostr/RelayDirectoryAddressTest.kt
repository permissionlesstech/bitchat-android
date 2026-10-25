package com.bitchat.android.nostr

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayDirectoryAddressTest {
    private fun assertRejectedBetweenValidRows(address: String) {
        val csv = "Relay URL,Latitude,Longitude\n" +
            "first.example,1,2\n$address,3,4\nlast.example:8443,5,6\n"
        val entries = RelayDirectory.parseCsv(ByteArrayInputStream(csv.toByteArray()))
        assertEquals(address, listOf("wss://first.example", "wss://last.example:8443"), entries.map { it.url })
        assertEquals(listOf(1.0 to 2.0, 5.0 to 6.0), entries.map { it.latitude to it.longitude })
    }

    @Test
    fun `rejects paths and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("wss://relay.example/a")
    }

    @Test
    fun `rejects nonsecure websocket schemes and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("ws://relay.example:80")
    }

    @Test
    fun `rejects http schemes and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("http://relay.example")
    }

    @Test
    fun `rejects userinfo and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("wss://user@relay.example")
    }

    @Test
    fun `rejects queries and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("wss://relay.example?query=1")
    }

    @Test
    fun `rejects fragments and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("wss://relay.example#fragment")
    }

    @Test
    fun `rejects nonnumeric ports and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("wss://relay.example:invalid")
    }

    @Test
    fun `rejects zero ports and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("wss://relay.example:0")
    }

    @Test
    fun `rejects ports above range and preserves neighboring rows`() {
        assertRejectedBetweenValidRows("wss://relay.example:65536")
    }

    @Test
    fun `secure schemes and bare hosts retain endpoint normalization`() {
        for (address in listOf("relay.example", "wss://RELAY.example:443/", "https://relay.example/")) {
            assertEquals(address, "relay.example", RelayDirectory.canonicalHost(address))
        }
        for (port in listOf(1, 8443, 65535)) {
            assertEquals("relay.example:$port", RelayDirectory.canonicalHost("wss://relay.example:$port/"))
        }
    }
}
