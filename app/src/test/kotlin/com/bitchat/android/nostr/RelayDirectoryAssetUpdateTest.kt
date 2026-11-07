package com.bitchat.android.nostr

import java.io.File
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayDirectoryAssetUpdateTest {
    @Test
    fun `bundled directory passes startup validation`() {
        assertNotNull(
            "Bundled relay directory must be accepted at startup",
            RelayDirectory.validatedEntries(bundledDirectory().readBytes(), minimumEntries = 1)
        )
    }

    @Test
    fun `asset replacement passes download validation before bundling`() {
        val baseline = RelayDirectory.validatedEntries(bundledDirectory().readBytes(), minimumEntries = 1)
        requireNotNull(baseline) { "Bundled relay directory must be valid before an update" }
        val candidate = System.getProperty("relayDirectoryCandidate")?.let(::File) ?: bundledDirectory()
        assertTrue(
            "Relay update must fit the directory byte limit",
            candidate.length() in 1..RelayDirectory.MAX_DIRECTORY_BYTES.toLong()
        )
        assertNotNull(
            "Relay update must pass format, size, entry minimum and retention checks",
            RelayDirectory.validatedEntries(
                candidate.readBytes(),
                minimumEntries = RelayDirectory.MIN_REMOTE_ENTRIES,
                baselineEntries = baseline.toSet()
            )
        )
    }

    private fun bundledDirectory() = File("src/main/assets/nostr_relays.csv")
}
