package com.bitchat.android.nostr

import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayDirectoryAddressTest {
    private fun assertInvalidDirectoryRejected(address: String) {
        val csv = "Relay URL,Latitude,Longitude\n" +
            "first.example,1,2\n$address,3,4\nlast.example:8443,5,6\n"
        val entries = RelayDirectory.validatedEntries(csv.toByteArray(), minimumEntries = 1)
        assertNull(address, entries)
    }

    @Test
    fun `rejects paths by rejecting the directory`() {
        assertInvalidDirectoryRejected("wss://relay.example/a")
    }

    @Test
    fun `rejects nonsecure websocket schemes by rejecting the directory`() {
        assertInvalidDirectoryRejected("ws://relay.example:80")
    }

    @Test
    fun `rejects http schemes by rejecting the directory`() {
        assertInvalidDirectoryRejected("http://relay.example")
    }

    @Test
    fun `rejects userinfo by rejecting the directory`() {
        assertInvalidDirectoryRejected("wss://user@relay.example")
    }

    @Test
    fun `rejects queries by rejecting the directory`() {
        assertInvalidDirectoryRejected("wss://relay.example?query=1")
    }

    @Test
    fun `rejects fragments by rejecting the directory`() {
        assertInvalidDirectoryRejected("wss://relay.example#fragment")
    }

    @Test
    fun `rejects nonnumeric ports by rejecting the directory`() {
        assertInvalidDirectoryRejected("wss://relay.example:invalid")
    }

    @Test
    fun `rejects zero ports by rejecting the directory`() {
        assertInvalidDirectoryRejected("wss://relay.example:0")
    }

    @Test
    fun `rejects ports above range by rejecting the directory`() {
        assertInvalidDirectoryRejected("wss://relay.example:65536")
    }

    @Test
    fun `secure schemes and bare hosts retain endpoint normalization`() {
        for (address in listOf("relay.example", "wss://RELAY.example:443/", "https://relay.example/")) {
            assertEquals(address, "relay.example", RelayDirectory.validatedDirectoryAddress(address))
        }
        for (port in listOf(1, 8443, 65535)) {
            assertEquals("relay.example:$port", RelayDirectory.validatedDirectoryAddress("wss://relay.example:$port/"))
        }
    }
}
