package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Shared literal expectations for accepted directory rows, not parser equivalence. */
@RunWith(Parameterized::class)
class RelaySelectionVectorTest(private val id: String, private val fields: List<String>) {
    @Test
    fun `selection matches the shared vector`() {
        val entries = resource(fields[1]).byteInputStream().use { RelayDirectory.parseCsv(it) }
        val expected = if (fields[5] == "-") emptyList() else fields[5].split('|')
        assertEquals(id, expected, RelayDirectory.closestRelays(
            entries, fields[2].toDouble(), fields[3].toDouble(), fields[4].toInt()
        ))
    }

    companion object {
        private fun resource(name: String): String = requireNotNull(
            RelaySelectionVectorTest::class.java.getResourceAsStream("/contracts/relay-selection-v1/$name")
        ).bufferedReader().use { it.readText() }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = parseCases(resource("cases.tsv"))

        internal fun parseCases(text: String): List<Array<Any>> {
            val lines = text.lineSequence().filter { it.isNotBlank() }.toList()
            require(lines.firstOrNull() == "id\tcsv\tlatitude\tlongitude\tcount\texpected") { "Invalid vector header" }
            require(lines.size > 1) { "No selection vectors" }
            val ids = mutableSetOf<String>()
            return lines.drop(1).map { line ->
                val fields = line.split('\t')
                require(fields.size == 6) { "Invalid vector fields" }
                require(fields[0].isNotBlank() && ids.add(fields[0])) { "Invalid or duplicate vector ID" }
                require(Regex("[a-z0-9-]+\\.csv").matches(fields[1])) { "Invalid vector filename" }
                require(fields[2].toDouble().isFinite() && fields[2].toDouble() in -90.0..90.0)
                require(fields[3].toDouble().isFinite() && fields[3].toDouble() in -180.0..180.0)
                fields[4].toInt()
                require(fields[5] == "-" || fields[5].split('|').all { it.startsWith("wss://") && it.length > 6 })
                arrayOf<Any>(fields[0], fields)
            }
        }
    }
}
