package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterizes the geohash people card (inventory P26-P33). `sectionGeohashPeople` itself is
 * covered by [GeohashPresenceGroupingTest].
 */
class GeohashPeopleRowsTest {

    private val me = "0123456789abcdef".repeat(4)
    private val bob = "fedcba9876543210".repeat(4)
    private val carl = "aaaaaaaaaaaaaaaa".repeat(4)
    private val now = PEER_LIST_NOW_MILLIS

    private fun person(id: String, name: String, lastSeen: Long = now) =
        GeoPerson(id = id, displayName = name, lastSeen = Date(lastSeen))

    private fun build(
        people: List<GeoPerson>,
        myHex: String? = null,
        nickname: String = "alice",
        isTeleported: Boolean = false,
        teleportedGeo: Set<String> = emptySet(),
        unread: Set<String> = emptySet(),
        excluded: Set<String> = emptySet(),
    ) = buildGeohashPeopleRows(
        geohashPeople = people,
        myHex = myHex,
        nickname = nickname,
        isTeleported = isTeleported,
        teleportedGeo = teleportedGeo,
        unreadPrivateMessages = unread,
        excludedIdentityAliases = excluded,
    )

    // P26

    @Test
    fun myHex_isLowercasedKeyForTheSelectedGeohash_elseNull() {
        val location = ChannelID.Location(GeohashChannel(GeohashChannelLevel.BUILDING, "s0000000"))
        val lookups = FakePeerListLookups(geohashIdentityHex = me.uppercase())

        assertEquals(me, myGeohashHex(location, lookups))
        assertNull(myGeohashHex(ChannelID.Mesh, lookups))
        assertNull(myGeohashHex(null, lookups))
        assertNull(myGeohashHex(location, FakePeerListLookups(geohashIdentityHex = null)))
    }

    // P27

    @Test
    fun self_addedFirst_asAnonUntilNamed_onlyWhenMissing() {
        val others = listOf(person(bob, "bob"))

        assertEquals(
            listOf(GeoPerson(me, "anon", Date(0))) + others,
            geohashPeopleIncludingSelf(others, me, nickname = " ")
        )
        assertEquals("alice", geohashPeopleIncludingSelf(others, me, "alice").first().displayName)
        val alreadyThere = listOf(person(me.uppercase(), "alice")) + others
        assertEquals(alreadyThere, geohashPeopleIncludingSelf(alreadyThere, me, "alice"))
        assertEquals(others, geohashPeopleIncludingSelf(others, null, "alice"))
    }

    @Test
    fun self_keptEvenAsBareAnon_andFirst() {
        val ui = build(
            people = listOf(person(bob, "bob", lastSeen = now)),
            myHex = me,
            nickname = "",
        )

        assertEquals(listOf(me, bob), ui.onLocation.map { it.id })
        assertTrue(ui.onLocation.first().isMe)
        assertEquals("anon", ui.onLocation.first().displayName)
    }

    // P28

    @Test
    fun peopleShownAsConversations_areDropped() {
        val people = listOf(person(bob, "bob"), person(carl.uppercase(), "carl"))

        val visible = visibleGeohashPeople(
            people,
            excludedIdentityAliases = setOf("nostr_${carl.take(16)}")
        )

        assertEquals(listOf(bob), visible.map { it.id })
    }

    // P30 / Q-P1

    @Test
    fun headerCount_isDisplayedPeopleSize_currentBehaviour_QP1() {
        val geohashPeople = listOf(
            person(bob, "bob"),
            person(carl, "carl"),
            person("bbbbbbbbbbbbbbbb".repeat(4), "anon"),
            person("cccccccccccccccc".repeat(4), "dan"),
        )

        val ui = build(
            people = geohashPeople,
            myHex = me,
            excluded = setOf("nostr_cccccccccccccccc"),
        )

        // Self added; bare anon and the conversation dropped. Not geohashPeople.size (4).
        assertEquals(3, ui.count)
        assertEquals(ui.onLocation.size + ui.teleportedIn.size, ui.count)
    }

    @Test
    fun empty_countsZero() {
        val ui = build(people = emptyList())

        assertEquals(GeohashPeopleUi(0, emptyList(), emptyList()), ui)
    }

    // P29 / P31 / P33

    @Test
    fun teleported_selfFollowsOwnFlag_othersFollowTeleportedGeo() {
        val ui = build(
            people = listOf(person(bob, "bob", now - 1_000), person(carl, "carl", now)),
            myHex = me,
            isTeleported = true,
            teleportedGeo = setOf(bob.uppercase()),
        )

        assertEquals(listOf(carl), ui.onLocation.map { it.id })
        assertEquals(listOf(me, bob), ui.teleportedIn.map { it.id })
        assertTrue(ui.teleportedIn.all { it.isTeleported })
        assertFalse(ui.onLocation.single().isTeleported)
    }

    @Test
    fun duplicateNames_getIdSuffix_acrossSections() {
        val ui = build(
            people = listOf(person(bob, "alice"), person(carl, "Alice"), person(me, "dan")),
            teleportedGeo = setOf(carl),
        )

        val rows = (ui.onLocation + ui.teleportedIn).associateBy { it.id }
        assertEquals("#3210", rows.getValue(bob).suffix)
        assertEquals("#aaaa", rows.getValue(carl).suffix)
        assertEquals("", rows.getValue(me).suffix)
        assertEquals("Alice", rows.getValue(carl).baseName)
    }

    @Test
    fun announcedSuffix_isSplitFromBaseName() {
        val row = build(people = listOf(person(bob, "bob#04af"))).onLocation.single()

        assertEquals("bob", row.baseName)
        assertEquals("", row.suffix)
        assertEquals("bob#04af", row.displayName)
    }

    @Test
    fun unreadDm_matchesNostrAliasWithoutLowercasing_currentBehaviour_QP17() {
        val upper = bob.uppercase()
        val ui = build(
            people = listOf(person(upper, "bob"), person(carl, "carl")),
            unread = setOf("nostr_${bob.take(16)}", "nostr_${carl.take(16)}"),
        )

        val rows = ui.onLocation.associateBy { it.id }
        assertFalse(rows.getValue(upper).hasUnreadDM)
        assertTrue(rows.getValue(carl).hasUnreadDM)
    }

    @Test
    fun colourKey_isNostrIdentity() {
        val row = build(people = listOf(person(bob, "bob"))).onLocation.single()

        assertEquals(PeerIdentity.nostr(bob).stableKey, row.colorKey)
        assertFalse(row.isMe)
    }
}
