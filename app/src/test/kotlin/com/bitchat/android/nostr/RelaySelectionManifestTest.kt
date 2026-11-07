package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelaySelectionManifestTest {
    private val header = "id\tcsv\tlatitude\tlongitude\tcount\texpected\n"
    private val row = "one\tties.csv\t0\t0\t1\twss://alpha.example\n"

    @Test
    fun `valid manifest provides a named case`() {
        assertEquals("one", RelaySelectionVectorTest.parseCases(header + row).single()[0])
    }

    @Test
    fun `empty manifest is rejected`() {
        for (text in listOf("", header)) {
            assertThrows(IllegalArgumentException::class.java) { RelaySelectionVectorTest.parseCases(text) }
        }
    }

    @Test
    fun `wrong header and duplicate IDs are rejected`() {
        for (text in listOf("wrong\n" + row, header + row + row)) {
            assertThrows(IllegalArgumentException::class.java) { RelaySelectionVectorTest.parseCases(text) }
        }
    }

    @Test
    fun `malformed fields are rejected`() {
        for (bad in listOf(
            row.replace("ties.csv", "../ties.csv"),
            row.replace("\t0\t0\t", "\tNaN\t0\t"),
            row.replace("\t1\t", "\t2147483648\t"),
            row.replace("wss://alpha.example", "")
        )) {
            assertThrows(IllegalArgumentException::class.java) { RelaySelectionVectorTest.parseCases(header + bad) }
        }
    }
}
