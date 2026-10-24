package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RelayDirectoryBoundaryTest {
    private val header = "Relay URL,Latitude,Longitude\n"
    private fun validate(csv: String) = RelayDirectory.validatedEntries(csv.toByteArray(), 1)

    @Test
    fun `escaped port digits reject otherwise retained updates`() {
        val rows = (0 until 50).map { "relay-$it.example,1,2" }
        val baseline = requireNotNull(validate(header + rows.joinToString("\n"))).toSet()
        for (address in listOf(
            "wss://relay-0.example:%34%34%33", "wss://relay-0.example:4%34%33",
            "relay-0.example:%38%34%34%33", "https://relay-0.example:84%34%33/"
        )) {
            val csv = header + (listOf("$address,1,2") + rows.drop(1)).joinToString("\n")
            assertNull(address, RelayDirectory.validatedEntries(csv.toByteArray(), 50, baseline))
        }
        for ((address, key) in mapOf(
            "wss://relay-0.example:443" to "relay-0.example",
            "wss://%72elay-0.example:443" to "relay-0.example",
            "https://relay-0.example:8443/" to "relay-0.example:8443",
            "%72elay-0.example:8443" to "relay-0.example:8443"
        )) {
            assertEquals(address, key, RelayDirectory.validatedDirectoryAddress(address))
            val csv = header + (listOf("$address,1,2") + rows.drop(1)).joinToString("\n")
            assertNotNull(address, RelayDirectory.validatedEntries(csv.toByteArray(), 50, baseline))
        }
    }

    @Test
    fun `escaped scheme letters reject the directory`() {
        for (scheme in listOf("w%73s", "%77ss", "h%74tps", "%68ttps")) {
            assertNull(scheme, validate(header + "$scheme://relay.example,0,0\n"))
        }
    }

    @Test
    fun `literal schemes and escaped host letters retain their keys`() {
        for (address in listOf("relay.example", "wss://relay.example", "HTTPS://relay.example/",
            "WsS://re%6Cay.example:443", "a%2Eb.example:8443")) {
            val expected = if (address.startsWith("a%")) "a.b.example:8443" else "relay.example"
            assertEquals(address, expected, RelayDirectory.validatedDirectoryAddress(address))
        }
    }

    @Test
    fun `delimiter and non ascii escapes reject the directory`() {
        for (escape in listOf("%3A8443", "%2F", "%3Fq", "%23f", "%40x", "%C3%A9", "%GG", "%2")) {
            assertNull(escape, validate(header + "relay.example$escape,0,0\n"))
        }
    }

    @Test
    fun `separator controls cannot disappear at csv trim boundaries`() {
        for (control in '\u001C'..'\u001F') {
            for (csv in listOf(
                "$control$header" + "relay.example,0,0\n",
                "Relay URL,Latitude,Longitude$control\nrelay.example,0,0\n",
                header + "$control" + "relay.example,0,0\n",
                header + "relay.example$control,0,0\n",
                header + "relay.example,${control}0,0\n",
                header + "relay.example,0$control,0\n",
                header + "relay.example,0,${control}0\n",
                header + "relay.example,0,0$control\n",
                header + "$control\nrelay.example,0,0\n"
            )) assertNull("control ${control.code}", validate(csv))
            assertNull(RelayDirectory.validatedDirectoryAddress("${control}relay.example"))
        }
    }

    @Test
    fun `supported whitespace padding and crlf remain accepted`() {
        for (space in listOf(" ", "\t", "\u00A0", "\u202F")) {
            assertNotNull(validate("${space}Relay URL,Latitude,Longitude$space\r\n" +
                "${space}relay.example$space,${space}0$space,${space}0$space\r\n"))
        }
        assertNull(validate(header + "\u200Brelay.example,0,0\n"))
    }

    @Test
    fun `java suffixes after hexadecimal exponents reject the directory`() {
        for (number in listOf("0x1p0f", "0x1p0d", "0x1.8p1F", "+0X1P0D", "-0x1p0f")) {
            assertNull(number, validate(header + "relay.example,$number,0\n"))
            assertNull(number, validate(header + "relay.example,0,$number\n"))
        }
    }

    @Test
    fun `hexadecimal mantissa digits and unsuffixed exponents retain their values`() {
        for ((number, expected) in mapOf("0x1d" to 29.0, "0x1f" to 31.0, "0x1p0" to 1.0,
            "0x1.8p1" to 3.0, "+0X1DP0" to 29.0, "-0x1f" to -31.0)) {
            assertEquals(number, expected, requireNotNull(validate(header + "relay.example,$number,0\n"))[0].latitude, 0.0)
        }
        assertNull(validate(header + "relay.example,1.0f,0\n"))
    }

    @Test
    fun `malformed spellings reject otherwise retained fifty entry updates`() {
        val rows = (0 until 50).map { "relay-$it.example,0,0" }
        val baseline = requireNotNull(validate(header + rows.joinToString("\n"))).toSet()
        for (first in listOf("w%73s://relay-0.example,0,0", "relay-0.example\u001C,0,0", "relay-0.example,0x0p0f,0")) {
            val csv = header + (listOf(first) + rows.drop(1)).joinToString("\n")
            assertNull(first, RelayDirectory.validatedEntries(csv.toByteArray(), 50, baseline))
        }
        assertNotNull(RelayDirectory.validatedEntries((header + rows.joinToString("\n")).toByteArray(), 50, baseline))
    }
    @Test
    fun `an oversized numeric port rejects the whole directory`() {
        val csv = "Relay URL,Latitude,Longitude\n" +
            "first.example,0,0\n" +
            "oversized.example:999999999999999999999,1,2\n" +
            "last.example:8443,3,4\n"
        assertNull(RelayDirectory.validatedEntries(csv.toByteArray(), minimumEntries = 1))
    }

}
