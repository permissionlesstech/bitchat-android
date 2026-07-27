package com.bitchat.android.ui

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A busy geohash is mostly anonymous drive-by participants. If they sort by recency alongside
 * everyone else they bury the handful of people worth recognising, so they are pushed to the end of
 * the list and capped.
 */
class GeohashAnonOrderingTest {

    private fun person(name: String, secondsAgo: Long = 0) = GeoPerson(
        id = "id-$name",
        displayName = name,
        lastSeen = Date(1_000_000L - secondsAgo * 1000L)
    )

    @Test
    fun `bare anon is anonymous`() {
        assertTrue(person("anon").isAnonymous())
        assertTrue(person("anon#04af").isAnonymous())
    }

    @Test
    fun `numbered anon is anonymous`() {
        assertTrue(person("anon7674").isAnonymous())
        assertTrue(person("anon7674#df5b").isAnonymous())
    }

    @Test
    fun `a chosen nickname is not anonymous`() {
        assertFalse(person("alice").isAnonymous())
        assertFalse(person("alice#1234").isAnonymous())
    }

    @Test
    fun `a nickname that merely starts with anon is not anonymous`() {
        // "anonymous" and "anonracer" are deliberate names, not the generated placeholder.
        assertFalse(person("anonymous").isAnonymous())
        assertFalse(person("anonracer#04af").isAnonymous())
    }

    @Test
    fun `named participants sort ahead of anons regardless of recency`() {
        val me = person("me")
        val people = listOf(
            person("anon1", secondsAgo = 0),      // most recent of all
            person("zoe", secondsAgo = 500),      // stalest named user
            person("anon2", secondsAgo = 10),
            person("alice", secondsAgo = 200)
        )

        val ordered = (people + me).sortedWith(
            compareByDescending<GeoPerson> { it.id == me.id }
                .thenBy { it.isAnonymous() }
                .thenByDescending { it.lastSeen }
        )

        assertEquals(
            listOf("me", "alice", "zoe", "anon1", "anon2"),
            ordered.map { it.displayName }
        )
    }

    @Test
    fun `recency still orders within each group`() {
        val people = listOf(
            person("bob", secondsAgo = 100),
            person("alice", secondsAgo = 10),
            person("anon9", secondsAgo = 300),
            person("anon1", secondsAgo = 5)
        )

        val ordered = people.sortedWith(
            compareBy<GeoPerson> { it.isAnonymous() }.thenByDescending { it.lastSeen }
        )

        assertEquals(
            listOf("alice", "bob", "anon1", "anon9"),
            ordered.map { it.displayName }
        )
    }

    @Test
    fun `named participants are never trimmed`() {
        val named = (1..20).map { person("user$it") }
        assertEquals(20, named.filterNot { it.isAnonymous() }.size)
    }

    @Test
    fun `anons beyond the cap are counted as hidden`() {
        val people = (1..12).map { person("anon$it") } + person("alice")

        val anons = people.filter { it.isAnonymous() }
        val visible = anons.take(MaxVisibleAnons)

        assertEquals(MaxVisibleAnons, visible.size)
        assertEquals(12 - MaxVisibleAnons, anons.size - visible.size)
    }

    @Test
    fun `a short anon list is shown in full with nothing hidden`() {
        val people = listOf(person("alice"), person("anon1"), person("anon2"))

        val anons = people.filter { it.isAnonymous() }
        assertEquals(2, anons.take(MaxVisibleAnons).size)
        assertEquals(0, anons.size - anons.take(MaxVisibleAnons).size)
    }
}
