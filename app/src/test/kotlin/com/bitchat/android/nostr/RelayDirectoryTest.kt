package com.bitchat.android.nostr

import java.io.ByteArrayInputStream
import org.junit.Assert.assertEquals
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
 */
class RelayDirectoryTest {

    private fun parse(csv: String) =
        RelayDirectory.parseCsv(ByteArrayInputStream(csv.toByteArray()))

    private val header = "Relay URL,Latitude,Longitude\n"

    @Test
    fun `fetch url is the file ios reads`() {
        assertEquals(
            "https://raw.githubusercontent.com/permissionlesstech/bitchat/refs/heads/main/relays/online_relays_gps.csv",
            RelayDirectory.ASSET_FILE_URL
        )
    }

    @Test
    fun `canonical host matches the key ios builds`() {
        assertEquals("relay.example.com", RelayDirectory.canonicalHost("wss://relay.example.com"))
        assertEquals("relay.example.com", RelayDirectory.canonicalHost("wss://relay.example.com:443"))
        assertEquals("relay.example.com:8443", RelayDirectory.canonicalHost("wss://relay.example.com:8443"))
        assertEquals("relay.example.com", RelayDirectory.canonicalHost("wss://Relay.Example.Com/"))
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
        // Real case from the live directory: bendernostur.duckdns.org is listed bare
        // and on 8443. iOS keeps both too; it drops only an explicit 443.
        val entries = parse(
            header +
                "bendernostur.duckdns.org,1.0,1.0\n" +
                "bendernostur.duckdns.org:8443,1.0,1.0\n"
        )
        assertEquals(2, entries.size)
        assertEquals("wss://bendernostur.duckdns.org", entries[0].url)
        assertEquals("wss://bendernostur.duckdns.org:8443", entries[1].url)
    }

    @Test
    fun `first row wins when an endpoint is listed twice`() {
        val entries = parse(
            header +
                "relay.example.com,10.0,20.0\n" +
                "relay.example.com:443,50.0,60.0\n"
        )
        assertEquals(1, entries.size)
        assertEquals(10.0, entries[0].latitude, 0.0)
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
        // The directory's dominant shape: a whole tie group at one city centroid
        // (the live file has 137 rows at a single coordinate). File order is
        // deliberately not alphabetical; the selection must not depend on it.
        val csv = header +
            "delta.example.com,43.6532,-79.3832\n" +
            "foxtrot.example.com,43.6532,-79.3832\n" +
            "alpha.example.com:443,43.6532,-79.3832\n" +
            "echo.example.com,43.6532,-79.3832\n" +
            "bravo.example.com,43.6532,-79.3832\n" +
            "charlie.example.com,43.6532,-79.3832\n"
        val five = RelayDirectory.closestRelays(parse(csv), 43.6532, -79.3832, 5)
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
        // The shape measured for London on Aug 11 2026: the nearest relay listed twice
        // (bare and :443), which used to occupy two of the five selection slots and
        // push out the fifth distinct server.
        val csv = header +
            "nearest.example.com,51.50,-0.12\n" +
            "nearest.example.com:443,51.50,-0.12\n" +
            "second.example.com,51.60,-0.10\n" +
            "third.example.com,51.70,-0.10\n" +
            "fourth.example.com,51.80,-0.10\n" +
            "fifth.example.com,51.90,-0.10\n" +
            "faraway.example.com,40.0,30.0\n"
        val five = RelayDirectory.closestRelays(parse(csv), 51.5074, -0.1278, 5)
        assertEquals(5, five.size)
        assertEquals("every selected relay is a distinct server", 5, five.toSet().size)
        assertTrue("the fifth distinct server makes the cut", five.contains("wss://fifth.example.com"))
    }
}
