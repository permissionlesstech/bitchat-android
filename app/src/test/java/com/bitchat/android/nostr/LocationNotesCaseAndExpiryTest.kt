package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two places Android disagreed with iOS on the same note event: the case of the
 * geohash tag, and a NIP-40 expiry that passes while the note is displayed.
 */
class LocationNotesCaseAndExpiryTest {
    // Arbitrary synthetic geohashes, never obtained from a device or location.

    private fun note(id: String, expiresAtSeconds: Long?) = LocationNotesManager.Note(
        id = id,
        pubkey = "a".repeat(64),
        content = "hi",
        createdAt = 1_700_000_000,
        nickname = null,
        expiresAtSeconds = expiresAtSeconds
    )

    private fun event(tags: List<List<String>>) = NostrEvent(
        id = "b".repeat(64),
        pubkey = "a".repeat(64),
        createdAt = 1_700_000_000,
        kind = NostrKind.TEXT_NOTE,
        tags = tags,
        content = "hi"
    )

    @Test
    fun `an uppercase geohash tag passes the subscription filter`() {
        val filter = NostrFilter.geohashNotes(geohash = "00bcdef")

        assertTrue(filter.matches(event(listOf(listOf("g", "00bcdef")))))
        // iOS has no client-side filter and lowercases where it reads the tag,
        // so this note is visible there; it has to reach the handler here too.
        assertTrue(filter.matches(event(listOf(listOf("G", "00BCDEF")))))
    }

    @Test
    fun `a different geohash is still rejected`() {
        val filter = NostrFilter.geohashNotes(geohash = "00bcdef")

        assertFalse(filter.matches(event(listOf(listOf("g", "00bcdeg")))))
        assertFalse(filter.matches(event(listOf(listOf("g")))))
    }

    @Test
    fun `a note is dropped once its expiry passes`() {
        val now = 1_700_000_000_000L
        val notes = listOf(
            note("plain", expiresAtSeconds = null),
            note("later", expiresAtSeconds = 1_700_000_060L),
            note("gone", expiresAtSeconds = 1_699_999_940L)
        )

        val remaining = LocationNotesManager.pruneExpired(notes, now)

        assertEquals(listOf("plain", "later"), remaining.map { it.id })
    }

    @Test
    fun `pruning at the expiry instant drops the note`() {
        val notes = listOf(note("edge", expiresAtSeconds = 1_700_000_000L))

        assertTrue(LocationNotesManager.pruneExpired(notes, 1_699_999_999_000L).isNotEmpty())
        assertTrue(LocationNotesManager.pruneExpired(notes, 1_700_000_000_000L).isEmpty())
    }

    @Test
    fun `non-geohash tag names and values retain exact matching`() {
        val filter = NostrFilter(tagFilters = mapOf("d" to listOf("SyntheticAddress")))
        assertTrue(filter.matches(event(listOf(listOf("d", "SyntheticAddress")))))
        assertFalse(filter.matches(event(listOf(listOf("D", "SyntheticAddress")))))
        assertFalse(filter.matches(event(listOf(listOf("d", "syntheticaddress")))))
        val recipient = "a".repeat(64)
        assertFalse(NostrFilter(tagFilters = mapOf("p" to listOf(recipient)))
            .matches(event(listOf(listOf("P", recipient)))))
    }

    @Test
    fun `extreme expiration timestamps cannot overflow`() {
        val now = 1_700_000_000_000L
        assertEquals(listOf("future"), LocationNotesManager.pruneExpired(
            listOf(note("future", Long.MAX_VALUE), note("past", Long.MIN_VALUE)), now
        ).map { it.id })
    }
}
