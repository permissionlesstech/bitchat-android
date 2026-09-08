package com.bitchat.android.nostr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the directory validation ported from GeoRelayDirectory.validatedEntries and
 * validatedDirectoryAddress. The rules are part of the cross-client contract: a file
 * one platform accepts and the other rejects splits the two relay selections at every
 * geohash at once, which is a larger divergence than the two-file split #914 closed.
 * Every rejection here is a whole-file rejection; the caller keeps its previous copy.
 */
class RelayDirectoryValidationTest {

    private val header = "Relay URL,Latitude,Longitude\n"

    private fun validate(csv: String, minimumEntries: Int = 1, baseline: Set<RelayDirectory.RelayInfo>? = null) =
        RelayDirectory.validatedEntries(csv.toByteArray(), minimumEntries, baseline)

    // MARK: file-level rules

    @Test
    fun `an empty file is rejected`() {
        assertNull(RelayDirectory.validatedEntries(ByteArray(0), minimumEntries = 1))
    }

    @Test
    fun `a file with only a header is rejected`() {
        assertNull(validate(header))
    }

    @Test
    fun `both header forms ios accepts parse here`() {
        assertNotNull(validate("Relay URL,Latitude,Longitude\nrelay-a.example,1.0,2.0\n"))
        assertNotNull(validate("relay url,lat,lon\nrelay-a.example,1.0,2.0\n"))
    }

    @Test
    fun `a header ios rejects rejects the file here`() {
        assertNull(validate("Relay URL,Lat,Long\nrelay-a.example,1.0,2.0\n"))
        assertNull(validate("url,latitude,longitude\nrelay-a.example,1.0,2.0\n"))
    }

    @Test
    fun `a single byte order mark is stripped the way ios strips it`() {
        // Foundation's UTF-8 decode removes one leading BOM before iOS's BOM
        // check runs; only a doubled BOM reaches the check. Verified against the
        // real Swift code over these exact bytes.
        assertNotNull(validate("﻿" + header + "relay-a.example,1.0,2.0\n"))
        assertNull(validate("﻿﻿" + header + "relay-a.example,1.0,2.0\n"))
    }

    @Test
    fun `percent escapes decode into the host key the way ios decodes them`() {
        assertEquals("relay.example", RelayDirectory.validatedDirectoryAddress("re%6Cay.example"))
        assertEquals("a.b.example", RelayDirectory.validatedDirectoryAddress("a%2Eb.example"))
        assertNull("invalid escape", RelayDirectory.validatedDirectoryAddress("wss://h%GGx.example"))
        assertNull("truncated escape", RelayDirectory.validatedDirectoryAddress("wss://hx.example%2"))
        assertNull("escape decoding to a query", RelayDirectory.validatedDirectoryAddress("wss://h%3Fx.example"))
        assertNull("escape decoding to non-ascii", RelayDirectory.validatedDirectoryAddress("re%C3%A9seau.example"))
        // iOS decodes after structural parsing, so a decoded colon stays in the
        // host and fails the label screen; decoded first it would become a port.
        assertNull("escape decoding to a colon", RelayDirectory.validatedDirectoryAddress("relay-a.example%3A8443"))
    }

    @Test
    fun `hex coordinates parse the way swift parses them`() {
        val plain = validate(header + "relay-a.example,0x10,2.0\n")
        assertNotNull(plain)
        assertEquals(16.0, plain!![0].latitude, 0.0)
        val hexDigitTail = validate(header + "relay-a.example,0x1d,2.0\n")
        assertNotNull("d is a hex digit, not a suffix", hexDigitTail)
        assertEquals(29.0, hexDigitTail!![0].latitude, 0.0)
    }

    @Test
    fun `signed zero is one coordinate and the last row's bits are kept`() {
        val entries = validate(header + "z.example,0.0,1.0\n" + "z.example,-0.0,1.0\n")
        assertNotNull("swift's == treats -0.0 and 0.0 as the same coordinate", entries)
        assertEquals(1, entries!!.size)
        assertEquals((-0.0).toRawBits(), entries[0].latitude.toRawBits())
    }

    @Test
    fun `every punycode label is rejected`() {
        // iOS IDNA-decodes xn-- labels: valid punycode becomes non-ASCII and is
        // rejected, and most invalid forms fail its parse, both pinned in the
        // battery. Foundation lets a few exotic invalid forms through
        // literally; this port rejects every xn-- label instead, stricter in
        // the safe direction, measured by the fuzz round.
        assertNull(RelayDirectory.validatedDirectoryAddress("xn--bcher-kva.example"))
        assertNull(RelayDirectory.validatedDirectoryAddress("xn--x.example"))
    }

    @Test
    fun `hosts uri refuses but ios accepts are salvaged by the fallback`() {
        // java.net.URI follows RFC 2396 and returns no host when the final
        // label starts with a digit, or when a trailing colon carries no port;
        // iOS's RFC 3986 parser accepts both. The plain-authority fallback
        // covers exactly these shapes.
        assertEquals("b.08relay", RelayDirectory.validatedDirectoryAddress("https://b.08relay"))
        assertEquals("relay.example.1", RelayDirectory.validatedDirectoryAddress("relay.example.1:"))
        assertEquals("d.7ex:8443", RelayDirectory.validatedDirectoryAddress("wss://d.7ex:8443"))
    }

    @Test
    fun `invalid utf8 rejects the file`() {
        assertNull(RelayDirectory.validatedEntries(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41), minimumEntries = 1))
    }

    @Test
    fun `a file over the byte cap is rejected`() {
        val oversized = ByteArray(RelayDirectory.MAX_DIRECTORY_BYTES + 1) { 'a'.code.toByte() }
        assertNull(RelayDirectory.validatedEntries(oversized, minimumEntries = 1))
    }

    @Test
    fun `more rows than the row cap rejects the file`() {
        val rows = buildString {
            append(header)
            repeat(RelayDirectory.MAX_DIRECTORY_ROWS + 1) { append("relay-a.example,1.0,2.0\n") }
        }
        assertNull(validate(rows))
    }

    // MARK: row-level rules, each rejecting the whole file

    @Test
    fun `one malformed row rejects the whole file`() {
        val csv = header +
            "relay-a.example,1.0,2.0\n" +
            "relay-b.example,2.0\n" +
            "relay-c.example,3.0,4.0\n"
        assertNull(validate(csv))
    }

    @Test
    fun `an out of range coordinate rejects the whole file`() {
        assertNull(validate(header + "relay-a.example,91.0,2.0\n"))
        assertNull(validate(header + "relay-a.example,1.0,181.0\n"))
        assertNull(validate(header + "relay-a.example,abc,2.0\n"))
    }

    @Test
    fun `a coordinate spelling only java parses rejects the file`() {
        // Double.parseDouble takes "1.5f"; Swift's Double(String) does not. Both
        // platforms must refuse the row, or one keeps a file the other drops.
        assertNull(validate(header + "relay-a.example,1.5f,2.0\n"))
        assertNull(validate(header + "relay-a.example,1.0,2.0d\n"))
    }

    @Test
    fun `a row with a rejected host rejects the whole file`() {
        val csv = header +
            "relay-a.example,1.0,2.0\n" +
            "localhost,1.0,2.0\n"
        assertNull(validate(csv))
    }

    @Test
    fun `hosts ios rejects are rejected here`() {
        val rejected = mapOf(
            "wss://relay.example.com/path" to "path beyond /",
            "wss://user@relay.example.com" to "userinfo",
            "wss://relay.example.com?x=1" to "query",
            "wss://relay.example.com#frag" to "fragment",
            "ws://relay.example.com" to "scheme other than wss or https",
            "localhost" to "localhost",
            "node.local" to ".local",
            "svc.internal" to ".internal",
            "a.localhost" to ".localhost",
            "singlelabel" to "single label",
            "192.0.2.7" to "all-numeric labels",
            "réseau.example" to "non-ascii",
            "-bad.example" to "label starting with hyphen",
            "bad-.example" to "label ending with hyphen",
            "${"a".repeat(64)}.example" to "label over 63 chars",
            "${(1..4).joinToString(".") { "a".repeat(63) }}.ex" to "host over 253 chars",
            "relay.example.com:0" to "port below 1",
            "relay.example.com:70000" to "port above 65535",
            "relay.example." to "trailing dot"
        )
        for ((raw, reason) in rejected) {
            assertNull(reason, RelayDirectory.validatedDirectoryAddress(raw))
        }
    }

    // MARK: floors and the hijack guard

    @Test
    fun `a remote file below the entry floor is rejected`() {
        val csv = header + "relay-a.example,1.0,2.0\n"
        assertNull(validate(csv, minimumEntries = RelayDirectory.MIN_REMOTE_ENTRIES))
        assertNotNull(validate(csv, minimumEntries = 1))
    }

    @Test
    fun `a download keeping less than half of the known entries is rejected`() {
        val baseline = setOf(
            RelayDirectory.RelayInfo("wss://base-a.example", 1.0, 1.0),
            RelayDirectory.RelayInfo("wss://base-b.example", 2.0, 2.0),
            RelayDirectory.RelayInfo("wss://base-c.example", 3.0, 3.0),
            RelayDirectory.RelayInfo("wss://base-d.example", 4.0, 4.0)
        )
        val keepsTwo = header +
            "base-a.example,1.0,1.0\n" +
            "base-b.example,2.0,2.0\n" +
            "fresh-a.example,5.0,5.0\n"
        assertNotNull(validate(keepsTwo, baseline = baseline))

        val keepsOne = header +
            "base-a.example,1.0,1.0\n" +
            "fresh-a.example,5.0,5.0\n" +
            "fresh-b.example,6.0,6.0\n"
        assertNull(validate(keepsOne, baseline = baseline))
    }

    // MARK: the shipped asset

    @Test
    fun `the bundled asset passes the same validation a download must pass`() {
        // fetch-georelays.yml rewrites the bundled asset every week, so this
        // test checks validity, not contents: whatever the job ships must pass
        // the same validation a download must pass. The exact-count check
        // lives on the fixed snapshot below.
        val asset = listOf(
            File("src/main/assets/nostr_relays.csv"),
            File("app/src/main/assets/nostr_relays.csv")
        ).firstOrNull { it.isFile } ?: error("bundled relay asset not found")
        val entries = RelayDirectory.validatedEntries(
            asset.readBytes(),
            minimumEntries = RelayDirectory.MIN_REMOTE_ENTRIES
        )
        assertNotNull("the bundled asset must pass validation", entries)
    }

    @Test
    fun `a fixed snapshot of the directory yields the exact entry count`() {
        // A copy of online_relays_gps.csv taken 2026-08-30, 441 rows collapsing
        // to 326 entries. This file never changes, so a different count here is
        // a change in the validator, never a change in the data.
        val snapshot = listOf(
            File("src/test/resources/nostr_relays_snapshot.csv"),
            File("app/src/test/resources/nostr_relays_snapshot.csv")
        ).firstOrNull { it.isFile } ?: error("relay snapshot fixture not found")
        val entries = RelayDirectory.validatedEntries(snapshot.readBytes(), minimumEntries = 1)
        assertNotNull(entries)
        assertEquals(326, entries!!.size)
    }
}
