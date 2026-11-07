package com.bitchat.android.nostr

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Covers bounded whole-file validation, endpoint keys and download retention. */
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
    fun `full and abbreviated header examples are accepted`() {
        assertNotNull(validate("Relay URL,Latitude,Longitude\nrelay-a.example,1.0,2.0\n"))
        assertNotNull(validate("relay url,lat,lon\nrelay-a.example,1.0,2.0\n"))
    }

    @Test
    fun `unsupported header examples reject the file`() {
        assertNull(validate("Relay URL,Lat,Long\nrelay-a.example,1.0,2.0\n"))
        assertNull(validate("url,latitude,longitude\nrelay-a.example,1.0,2.0\n"))
    }

    @Test
    fun `one leading byte order mark is accepted and two are rejected`() {
        // The local Foundation comparison accepts one BOM and rejects two.
        assertNotNull(validate("﻿" + header + "relay-a.example,1.0,2.0\n"))
        assertNull(validate("﻿﻿" + header + "relay-a.example,1.0,2.0\n"))
    }

    @Test
    fun `host percent-escape examples normalize or reject as expected`() {
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
    fun `hex coordinate examples retain mantissa digits`() {
        val plain = validate(header + "relay-a.example,0x10,2.0\n")
        assertNotNull(plain)
        assertEquals(16.0, plain!![0].latitude, 0.0)
        val hexDigitTail = validate(header + "relay-a.example,0x1d,2.0\n")
        assertNotNull("hex mantissa digit d remains accepted", hexDigitTail)
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
    fun `punycode host examples are rejected`() {
        // Rejecting the label form is stricter than Foundation for some inputs.
        assertNull(RelayDirectory.validatedDirectoryAddress("xn--bcher-kva.example"))
        assertNull(RelayDirectory.validatedDirectoryAddress("xn--x.example"))
    }

    @Test
    fun `fallback accepts numeric-leading host label examples`() {
        // These plain authorities exercise the fallback host and port screens.
        assertEquals("b.08relay", RelayDirectory.validatedDirectoryAddress("https://b.08relay"))
        assertEquals("relay.example.1", RelayDirectory.validatedDirectoryAddress("relay.example.1:"))
        assertEquals("d.7ex:8443", RelayDirectory.validatedDirectoryAddress("wss://d.7ex:8443"))
    }

    @Test
    fun `strict decoder and directory validator reject malformed utf8 example`() {
        val decode = RelayDirectory::class.java.getDeclaredMethod("decodeUtf8Strict", ByteArray::class.java)
            .apply { isAccessible = true }
        val valid = header + "relay-a.example,1,2\n"
        assertEquals(valid, decode.invoke(RelayDirectory, valid.toByteArray()))
        val malformed = valid.toByteArray() + byteArrayOf(0xFF.toByte())
        assertNull(decode.invoke(RelayDirectory, malformed))
        assertNull(RelayDirectory.validatedEntries(malformed, minimumEntries = 1))
    }

    @Test
    fun `a file over the byte cap is rejected`() {
        val valid = (header + "relay-a.example,1.0,2.0\n").toByteArray()
        val atLimit = ByteArray(RelayDirectory.MAX_DIRECTORY_BYTES) { ' '.code.toByte() }
        valid.copyInto(atLimit)
        assertNotNull(RelayDirectory.validatedEntries(atLimit, minimumEntries = 1))
        assertNull(RelayDirectory.validatedEntries(atLimit + byteArrayOf(' '.code.toByte()), minimumEntries = 1))
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
    fun `coordinate examples with Java float and double suffixes reject the file`() {
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
    fun `invalid address examples are rejected`() {
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

    // MARK: entry floors and retention checks

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

    @Test
    fun `an odd baseline requires the rounded up half of its entries`() {
        val baseline = (1..3).map { RelayDirectory.RelayInfo("wss://base-$it.example", 0.0, 0.0) }.toSet()
        assertNull(validate(header + "base-1.example,0,0\n", baseline = baseline))
        assertNotNull(validate(header + "base-1.example,0,0\nbase-2.example,0,0\n", baseline = baseline))
    }

    @Test
    fun `retention compares coordinates as well as endpoints`() {
        val baseline = setOf(RelayDirectory.RelayInfo("wss://base.example", 0.0, 0.0))
        assertNotNull(validate(header + "base.example,0,0\n", baseline = baseline))
        assertNull(validate(header + "base.example,1,0\n", baseline = baseline))
        assertNull(validate(header + "base.example,0,1\n", baseline = baseline))
    }

    // MARK: the shipped asset

    @Test
    fun `the bundled asset passes format checks and the remote entry floor`() {
        // The weekly job updates the asset. This check uses the remote entry
        // floor, but has no prior directory for a download-overlap comparison.
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
    fun `a synthetic directory fixture yields the expected distinct endpoint count`() {
        // Synthetic duplicates exercise the distinct endpoint count independently of the asset.
        val snapshot = listOf(
            File("src/test/resources/nostr_relays_snapshot.csv"),
            File("app/src/test/resources/nostr_relays_snapshot.csv")
        ).firstOrNull { it.isFile } ?: error("relay snapshot fixture not found")
        val entries = RelayDirectory.validatedEntries(snapshot.readBytes(), minimumEntries = 1)
        assertNotNull(entries)
        assertEquals(326, entries!!.size)
    }
}
