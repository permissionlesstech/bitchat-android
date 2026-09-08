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
        val filter = NostrFilter.geohashNotes(geohash = "u4pruyd")

        assertTrue(filter.matches(event(listOf(listOf("g", "u4pruyd")))))
        // iOS has no client-side filter and lowercases where it reads the tag,
        // so this note is visible there; it has to reach the handler here too.
        assertTrue(filter.matches(event(listOf(listOf("G", "U4PRUYD")))))
    }

    @Test
    fun `a different geohash is still rejected`() {
        val filter = NostrFilter.geohashNotes(geohash = "u4pruyd")

        assertFalse(filter.matches(event(listOf(listOf("g", "u4pruye")))))
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
}
