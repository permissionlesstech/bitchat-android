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

    @Test
    fun nip09DeletionHasStableEventId() {
        val target = "b".repeat(64)
        val spec = LocationNoteDeletionBuilder.build(
            eventIds = listOf(target),
            reason = "removed location note",
            noteKind = 1
        )
        val event = NostrEvent(
            pubkey = "a".repeat(64),
            createdAt = 1_700_000_000,
            kind = spec.kind,
            tags = spec.tags,
            content = spec.content
        )
        val serialized = "[0,\"${"a".repeat(64)}\",1700000000,5,[[\"e\",\"$target\"],[\"k\",\"1\"]],\"removed location note\"]"
        assertEquals(serialized, nip01Serialization(event))
        assertEquals(
            "acdbb5997452dee0a0070223a678788adf7dd850641f95c83d2e63184fec4bd1",
            event.computeEventIdHex()
        )
    }

    private fun nip01Serialization(event: NostrEvent): String {
        val gson = com.google.gson.GsonBuilder().disableHtmlEscaping().create()
        return gson.toJson(listOf(0, event.pubkey, event.createdAt, event.kind, event.tags, event.content))
    }
}
