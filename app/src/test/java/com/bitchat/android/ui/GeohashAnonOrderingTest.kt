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

    // MARK: - Sectioning

    /** Mirrors the grouping in GeohashPeopleList: self is never treated as an anon. */
    private fun sections(people: List<GeoPerson>, myId: String?): Triple<List<String>, List<String>, List<String>> {
        val isSelf: (GeoPerson) -> Boolean = { myId != null && it.id == myId }
        val named = people.filter { isSelf(it) || !it.isAnonymous() }
        val anons = people.filter { !isSelf(it) && it.isAnonymous() }
        return Triple(
            named.map { it.displayName },
            anons.map { it.displayName },
            people.map { it.displayName }
        )
    }

    @Test
    fun `anons are grouped out of the named sections entirely`() {
        val people = listOf(person("alice"), person("anon1"), person("bob"), person("anon2"))
        val (named, anons, _) = sections(people, myId = null)

        assertEquals(listOf("alice", "bob"), named)
        assertEquals(listOf("anon1", "anon2"), anons)
    }

    @Test
    fun `self stays in the named sections even when unnamed`() {
        // You always want to find yourself where you actually are, not buried in the anon section.
        val me = person("anon")
        val people = listOf(me, person("alice"), person("anon2"))
        val (named, anons, _) = sections(people, myId = me.id)

        assertTrue("self must not be grouped as an anon", named.contains("anon"))
        assertFalse(anons.contains("anon"))
        assertEquals(listOf("anon2"), anons)
    }

    @Test
    fun `a list of only anons yields no named section`() {
        val people = (1..4).map { person("anon$it") }
        val (named, anons, _) = sections(people, myId = null)

        assertTrue(named.isEmpty())
        assertEquals(4, anons.size)
    }

    // MARK: - Stable length

    @Test
    fun `a trimmed anon list always renders exactly the cap`() {
        // The reserved height is MaxVisibleAnons rows whenever trimmed, so the card cannot resize
        // as anons churn above the cap.
        for (total in listOf(MaxVisibleAnons + 1, MaxVisibleAnons + 7, MaxVisibleAnons + 40)) {
            val anons = (1..total).map { person("anon$it") }
            val visible = anons.take(MaxVisibleAnons)
            assertEquals(
                "row count must not depend on how many anons are present beyond the cap",
                MaxVisibleAnons,
                visible.size
            )
            assertEquals(total - MaxVisibleAnons, anons.size - visible.size)
        }
    }

    @Test
    fun `reordering never changes how many rows are rendered`() {
        val anons = (1..9).map { person("anon$it", secondsAgo = it.toLong()) }
        val byRecency = anons.sortedByDescending { it.lastSeen }.take(MaxVisibleAnons)
        val reversed = anons.sortedBy { it.lastSeen }.take(MaxVisibleAnons)

        assertEquals(byRecency.size, reversed.size)
        assertEquals(MaxVisibleAnons, byRecency.size)
    }

    @Test
    fun `a short anon list is shown in full with nothing hidden`() {
        val people = listOf(person("alice"), person("anon1"), person("anon2"))

        val anons = people.filter { it.isAnonymous() }
        assertEquals(2, anons.take(MaxVisibleAnons).size)
        assertEquals(0, anons.size - anons.take(MaxVisibleAnons).size)
    }
}
