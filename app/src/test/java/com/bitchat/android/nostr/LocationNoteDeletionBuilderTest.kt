package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Test

class LocationNoteDeletionBuilderTest {

    @Test
    fun buildsNip09Kind5TagsForLocationNote() {
        val spec = LocationNoteDeletionBuilder.build(
            eventIds = listOf("abc123", "abc123", "def456"),
            reason = "user deleted note",
            noteKind = 1
        )

        assertEquals(5, spec.kind)
        assertEquals(
            listOf(
                listOf("e", "abc123"),
                listOf("e", "def456"),
                listOf("k", "1")
            ),
            spec.tags
        )
        assertEquals("user deleted note", spec.content)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsEmptyEventIdList() {
        LocationNoteDeletionBuilder.build(emptyList())
    }
}
