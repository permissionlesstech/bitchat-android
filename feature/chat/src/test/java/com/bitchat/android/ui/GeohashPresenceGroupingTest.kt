package com.bitchat.android.ui

import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeohashPresenceGroupingTest {

    private fun person(id: String, name: String = id, lastSeen: Long = 0) = GeoPerson(
        id = id,
        displayName = name,
        lastSeen = Date(lastSeen)
    )

    @Test
    fun `heartbeat anon is hidden while announced anon name remains`() {
        val people = listOf(
            person("local-heartbeat", "anon"),
            person("named", "alice"),
            person("teleported-named", "anon7674"),
            person("teleported-heartbeat", "anon#04af")
        )

        val sections = sectionGeohashPeople(
            people = people,
            myId = null,
            selfIsTeleported = false,
            teleportedIds = setOf("teleported-named", "teleported-heartbeat")
        )

        assertEquals(listOf("alice"), sections.onLocation.map { it.displayName })
        assertEquals(listOf("anon7674"), sections.teleportedIn.map { it.displayName })
        assertEquals(2, sections.onLocation.size + sections.teleportedIn.size)
    }

    @Test
    fun `self is first in on-location section when not teleported`() {
        val sections = sectionGeohashPeople(
            people = listOf(
                person("recent", lastSeen = 3_000),
                person("me", name = "anon", lastSeen = 0),
                person("older", lastSeen = 1_000)
            ),
            myId = "me",
            selfIsTeleported = false,
            teleportedIds = emptySet()
        )

        assertEquals(listOf("me", "recent", "older"), sections.onLocation.map { it.id })
        assertTrue(sections.teleportedIn.isEmpty())
    }

    @Test
    fun `self is first in teleported section when teleported`() {
        val sections = sectionGeohashPeople(
            people = listOf(
                person("remote-teleport", lastSeen = 3_000),
                person("me", lastSeen = 0),
                person("local", lastSeen = 2_000)
            ),
            myId = "ME",
            selfIsTeleported = true,
            teleportedIds = setOf("remote-teleport")
        )

        assertEquals(listOf("local"), sections.onLocation.map { it.id })
        assertEquals(listOf("me", "remote-teleport"), sections.teleportedIn.map { it.id })
    }

    @Test
    fun `remote teleport matching is case insensitive`() {
        val sections = sectionGeohashPeople(
            people = listOf(person("ABCDEF")),
            myId = null,
            selfIsTeleported = false,
            teleportedIds = setOf("abcdef")
        )

        assertTrue(sections.onLocation.isEmpty())
        assertEquals(listOf("ABCDEF"), sections.teleportedIn.map { it.id })
    }

    @Test
    fun `duplicate nicknames are detected across case and sections`() {
        val duplicates = duplicateGeohashBaseNames(
            listOf(
                person("first", "Alice"),
                person("second", "alice"),
                person("third", "bob")
            )
        )

        assertEquals(setOf("alice"), duplicates)
    }

    @Test
    fun `duplicate nickname gets the same last-four ID suffix as chat`() {
        assertEquals(
            "#cdef",
            geohashIdentitySuffix(person("0123456789abcdef", "alice"), showHashSuffix = true)
        )
        assertEquals(
            "",
            geohashIdentitySuffix(person("0123456789abcdef", "alice"), showHashSuffix = false)
        )
    }

    @Test
    fun `existing chat-style suffix is preserved`() {
        assertEquals(
            "#04af",
            geohashIdentitySuffix(person("0123456789abcdef", "alice#04af"), showHashSuffix = true)
        )
    }

    @Test
    fun `disambiguated display name matches the mention token used by chat`() {
        val alice = person("0123456789abcdef", "alice")

        assertEquals(
            "alice#cdef",
            disambiguatedGeohashDisplayName(alice, duplicateBaseNames = setOf("alice"))
        )
        assertEquals(
            "alice",
            disambiguatedGeohashDisplayName(alice, duplicateBaseNames = emptySet())
        )
    }
}
