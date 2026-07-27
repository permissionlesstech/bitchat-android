package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MentionSuggestionsTest {

    @Test
    fun `only users without an announced nickname are excluded from mentions`() {
        val suggestions = filterMentionCandidates(
            candidates = listOf(
                "anon",
                "anon#04af",
                "anon7674#df5b",
                "alice#1234",
                "anonymous",
                "anonracer#04af"
            ),
            query = ""
        )

        assertEquals(
            listOf("alice#1234", "anon7674#df5b", "anonracer#04af", "anonymous"),
            suggestions
        )
        assertTrue(suggestions.none(::isUnannouncedNickname))
    }

    @Test
    fun `mention filtering is case insensitive and removes duplicates`() {
        val suggestions = filterMentionCandidates(
            candidates = listOf("Bob#1234", "bob#1234", "bobby#5678", "alice#9999"),
            query = "BO"
        )

        assertEquals(listOf("Bob#1234", "bobby#5678"), suggestions)
    }

    @Test
    fun `announced names beginning with anon stay mentionable`() {
        assertTrue(isUnannouncedNickname("anon"))
        assertTrue(isUnannouncedNickname("anon#04af"))
        assertFalse(isUnannouncedNickname("anon1234#04af"))
        assertFalse(isUnannouncedNickname("anonymous#04af"))
        assertFalse(isUnannouncedNickname("anonracer"))
    }

    @Test
    fun `mention popup viewport is capped at five rows`() {
        assertEquals(5, MaxVisibleMentionSuggestions)
    }
}
