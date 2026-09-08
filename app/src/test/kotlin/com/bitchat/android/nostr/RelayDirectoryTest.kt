package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the three properties cross-platform geohash delivery depends on. Both platforms
 * take the 5 relays nearest to a geohash and use them without the default relays, so a
 * geohash message crosses platforms only if the two selections intersect.
 *
 * 1. Android reads the SAME directory file iOS reads.
 * 2. Rows are deduplicated and emitted by the same key iOS builds: host lowercased,
 *    an explicit port kept unless it is 443. The directory lists many relays twice,
 *    once bare and once as host:443, and both forms are one server over wss.
 * 3. Distance ties order by that key, the way iOS orders them. Rows are geocoded to
 *    city centroids, so whole tie groups sit at one coordinate and tie order decides
 *    most selections.
 *
 * Parsing goes through the validator ported from GeoRelayDirectory.validatedEntries;
 * RelayDirectoryValidationTest pins its acceptance rules. These tests parse files the
 * validator accepts.
 */
class RelayDirectoryTest {

    private fun parse(csv: String) =
        requireNotNull(RelayDirectory.validatedEntries(csv.toByteArray(), minimumEntries = 1)) {
            "fixture unexpectedly rejected"
        }

    private val header = "Relay URL,Latitude,Longitude\n"

    @Test
    fun `fetch url is the file ios reads`() {
        assertEquals(
            "https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv",
            RelayDirectory.ASSET_FILE_URL
        )
    }

    @Test
    fun `validated addresses match the key ios builds`() {
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("wss://relay.example.com"))
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("wss://relay.example.com:443"))
        assertEquals("relay.example.com:8443", RelayDirectory.validatedDirectoryAddress("wss://relay.example.com:8443"))
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("wss://Relay.Example.Com/"))
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("relay.example.com"))
        assertEquals("relay.example.com", RelayDirectory.validatedDirectoryAddress("https://relay.example.com"))
    }

    @Test
    fun `port 443 variant of a host is the same server and is not listed twice`() {
        val entries = parse(
            header +
                "relay.example.com,10.0,20.0\n" +
                "relay.example.com:443,10.0,20.0\n"
        )
        assertEquals(1, entries.size)
        assertEquals("wss://relay.example.com", entries[0].url)
    }

    @Test
    fun `a nonstandard port is a different server and stays`() {
        // The live directory lists some relays bare and on a nonstandard port.
        // iOS keeps both too; it drops only an explicit 443.
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
        // One endpoint cannot truthfully occupy two coordinates. iOS rejects the
        // whole file rather than letting row order choose which location clients
        // trust; the earlier first-row-wins behavior is gone with it.
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
    fun `distance ties order by host the way ios orders them`() {
        // The directory's dominant shape: a whole tie group at one shared coordinate
        // (the live file has 137 rows at a single point). File order is
        // deliberately not alphabetical; the selection must not depend on it.
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
    fun `five nearest means five distinct servers`() {
        // A shape the live directory produces: the nearest relay listed twice
        // (bare and :443), which used to occupy two of the five selection slots and
        // push out the fifth distinct server.
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
        assertEquals("every selected relay is a distinct server", 5, five.toSet().size)
        assertTrue("the fifth distinct server makes the cut", five.contains("wss://fifth.example.com"))
    }
}
