package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayDirectoryPortTest {
    @Test
    fun `an oversized numeric port does not discard surrounding valid rows`() {
        val csv = "Relay URL,Latitude,Longitude\n" +
            "first.example,0,0\n" +
            "oversized.example:999999999999999999999,1,2\n" +
            "last.example:8443,3,4\n"
        val entries = RelayDirectory.parseCsv(csv.byteInputStream())
        assertEquals(listOf("wss://first.example", "wss://last.example:8443"), entries.map { it.url })
    }

    @Test
    fun `ordinary numeric ports retain their endpoint keys`() {
        assertEquals("relay.example", RelayDirectory.canonicalHost("wss://relay.example:443"))
        assertEquals("relay.example:8443", RelayDirectory.canonicalHost("wss://relay.example:8443"))
    }
}
