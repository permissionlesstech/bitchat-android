package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the configured source URL, endpoint deduplication and deterministic distance ties. */
class RelayDirectoryTest {

    private fun parse(csv: String) =
        requireNotNull(RelayDirectory.validatedEntries(csv.toByteArray(), minimumEntries = 1)) {
            "fixture unexpectedly rejected"
        }

    private val header = "Relay URL,Latitude,Longitude\n"

    @Test
    fun `fetch url points to online_relays_gps csv`() {
        assertEquals(
            "https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv",
            RelayDirectory.ASSET_FILE_URL
        )
    }

    @Test
    fun `address examples normalize host case and default port`() {
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("wss://relay.example.com"))
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("wss://relay.example.com:443"))
        assertEquals("relay.example.com:8443", RelayDirectory.validatedDirectoryAddress("wss://relay.example.com:8443"))
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("wss://Relay.Example.Com/"))
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("relay.example.com"))
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("https://relay.example.com"))
    }

    @Test
    fun `port 443 variant of a host is the same endpoint key and is not listed twice`() {
        val entries = parse(
            header +
                "relay.example.com,10.0,20.0\n" +
                "relay.example.com:443,10.0,20.0\n"
        )
        assertEquals(1, entries.size)
        assertEquals("wss://relay.example.com", entries[0].url)
    }

    @Test
    fun `a nonstandard port is a distinct endpoint and stays`() {
        // A nondefault port has a distinct endpoint key.
        val entries = parse(
            header +
                "port-variant.relay.example,1.0,1.0\n" +
                "port-variant.relay.example:8443,1.0,1.0\n"
        )
        assertEquals(2, entries.size)
        assertEquals("wss://port-variant.relay.example", entries[0].url)
        assertEquals("wss://port-variant.relay.example:8443", entries[1].url)
    }

    @Test
    fun `an endpoint listed at two different coordinates rejects the file`() {
        // Conflicting coordinates for one endpoint key reject the file.
        val rejected = RelayDirectory.validatedEntries(
            (header +
                "relay.example.com,10.0,20.0\n" +
                "relay.example.com:443,50.0,60.0\n").toByteArray(),
            minimumEntries = 1
        )
        assertNull(rejected)
    }

    @Test
    fun `host case does not create a second endpoint`() {
        val entries = parse(
            header +
                "Relay.Example.Com:443,10.0,20.0\n" +
                "relay.example.com,10.0,20.0\n"
        )
        assertEquals(1, entries.size)
        assertEquals("wss://relay.example.com", entries[0].url)
    }

    @Test
    fun `equal-coordinate examples select five endpoints in key order`() {
        // Shared coordinates exercise selection order after directory validation.
        val csv = header +
            "delta.example.com,12.34,56.78\n" +
            "foxtrot.example.com,12.34,56.78\n" +
            "alpha.example.com:443,12.34,56.78\n" +
            "echo.example.com,12.34,56.78\n" +
            "bravo.example.com,12.34,56.78\n" +
            "charlie.example.com,12.34,56.78\n"
        val five = RelayDirectory.closestRelays(parse(csv), 12.34, 56.78, 5)
        assertEquals(
            listOf(
                "wss://alpha.example.com",
                "wss://bravo.example.com",
                "wss://charlie.example.com",
                "wss://delta.example.com",
                "wss://echo.example.com"
            ),
            five
        )
    }

    @Test
    fun `five nearest means five distinct endpoint keys`() {
        // The nearest relay is listed twice
        // (bare and :443), which used to occupy two of the five selection slots and
        // push out the fifth distinct endpoint.
        val csv = header +
            "nearest.example.com,10.10,20.20\n" +
            "nearest.example.com:443,10.10,20.20\n" +
            "second.example.com,10.20,20.20\n" +
            "third.example.com,10.30,20.20\n" +
            "fourth.example.com,10.40,20.20\n" +
            "fifth.example.com,10.50,20.20\n" +
            "faraway.example.com,80.0,120.0\n"
        val five = RelayDirectory.closestRelays(parse(csv), 10.10, 20.20, 5)
        assertEquals(5, five.size)
        assertEquals("selected endpoint keys are distinct", 5, five.toSet().size)
        assertTrue("the fifth distinct endpoint makes the cut", five.contains("wss://fifth.example.com"))
        assertEquals(
            listOf(
                "wss://nearest.example.com",
                "wss://second.example.com",
                "wss://third.example.com",
                "wss://fourth.example.com",
                "wss://fifth.example.com"
            ),
            five
        )
    }
    @Test
    fun `equal distance entries are ordered by endpoint key from unsorted input`() {
        val entries = listOf("charlie", "alpha", "bravo").map {
            RelayDirectory.RelayInfo("wss://$it.example", 0.0, 0.0)
        }
        assertEquals(
            listOf("wss://alpha.example", "wss://bravo.example", "wss://charlie.example"),
            RelayDirectory.closestRelays(entries, 0.0, 0.0, 3)
        )
    }

}
