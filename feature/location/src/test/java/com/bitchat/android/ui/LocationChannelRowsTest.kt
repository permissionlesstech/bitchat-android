package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.nostr.LocationNotesManager
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Synthetic geohashes and names only; never a real place. */
class LocationChannelRowsTest {

    private lateinit var savedLocale: Locale

    @Before
    fun pinLocale() {
        savedLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
    }

    @After
    fun restoreLocale() {
        Locale.setDefault(savedLocale)
    }

    private val building = GeohashChannel(GeohashChannelLevel.BUILDING, "s0000000")
    private val block = GeohashChannel(GeohashChannelLevel.BLOCK, "s000000")
    private val neighborhood = GeohashChannel(GeohashChannelLevel.NEIGHBORHOOD, "s00000")
    private val city = GeohashChannel(GeohashChannelLevel.CITY, "s0000")

    /** Stands in for the `people_count` plural. */
    private val peopleCount: (Int) -> String = { n -> if (n == 1) "1 person" else "$n people" }

    private fun nearby(
        channels: List<GeohashChannel> = listOf(block, neighborhood),
        locationNames: Map<GeohashChannelLevel, String> = emptyMap(),
        counts: Map<String, Int> = emptyMap(),
        selected: ChannelID? = ChannelID.Mesh,
        permission: PermissionState = PermissionState.AUTHORIZED,
        enabled: Boolean = true,
        isBookmarked: (String) -> Boolean = { false },
    ) = buildNearbyRows(channels, locationNames, counts, selected, permission, enabled, isBookmarked)

    // L1

    @Test
    fun nearbyChannels_dropBuildingAndKeepOrder() {
        assertEquals(
            listOf(block, neighborhood),
            nearbyChannelsOf(listOf(block, building, neighborhood))
        )
    }

    // L3

    @Test
    fun loading_onlyWhenNoNearbyChannelsAuthorizedAndEnabled() {
        assertTrue(nearby(channels = emptyList()).showLoading)
        assertFalse(nearby(channels = emptyList(), permission = PermissionState.DENIED).showLoading)
        assertFalse(nearby(channels = emptyList(), enabled = false).showLoading)
        assertFalse(nearby(channels = listOf(block)).showLoading)
    }

    // L4

    @Test
    fun notesSubtitle_allSixCases() {
        assertEquals(NotesSubtitle.Reveal, notesSubtitle(false, LocationNotesManager.State.READY, 3))
        assertEquals(NotesSubtitle.NoRelays, notesSubtitle(true, LocationNotesManager.State.NO_RELAYS, 3))
        assertEquals(NotesSubtitle.Loading, notesSubtitle(true, LocationNotesManager.State.LOADING, 3))
        assertEquals(NotesSubtitle.One, notesSubtitle(true, LocationNotesManager.State.READY, 1))
        assertEquals(NotesSubtitle.Many(4), notesSubtitle(true, LocationNotesManager.State.READY, 4))
        assertEquals(NotesSubtitle.Empty, notesSubtitle(true, LocationNotesManager.State.READY, 0))
        assertEquals(NotesSubtitle.Empty, notesSubtitle(true, LocationNotesManager.State.IDLE, 0))
    }

    @Test
    fun notesSubtitle_revealThenRelaysThenLoadingTakePrecedence() {
        assertEquals(NotesSubtitle.Reveal, notesSubtitle(false, LocationNotesManager.State.NO_RELAYS, 0))
        assertEquals(NotesSubtitle.NoRelays, notesSubtitle(true, LocationNotesManager.State.NO_RELAYS, 1))
        assertEquals(NotesSubtitle.Loading, notesSubtitle(true, LocationNotesManager.State.LOADING, 1))
    }

    // L5 and Q-L12

    @Test
    fun meshRow_countSelectionWifiAndBold() {
        val row = meshRow(meshPeopleCount = 2, wifiAwareEnabled = true, selectedChannel = ChannelID.Mesh)

        assertEquals(ChannelTitle(level = null, count = 2, countUnknown = false), row.title)
        assertEquals(2, row.peopleCount)
        assertTrue(row.wifiAwareEnabled)
        assertTrue(row.isSelected)
        assertTrue(row.titleBold)

        val empty = meshRow(0, wifiAwareEnabled = false, selectedChannel = ChannelID.Location(city))
        assertEquals(ChannelTitle(level = null, count = 0, countUnknown = false), empty.title)
        assertFalse(empty.wifiAwareEnabled)
        assertFalse(empty.isSelected)
        assertFalse(empty.titleBold)
        assertEquals("0 people", channelCountText(empty.title, peopleCount))
    }

    @Test
    fun meshCount_countsAllConnectedExceptSelf_currentBehaviour_QL12() {
        // No conversation input: peers the peer list hides as conversations still count here.
        val peers = listOf("0123456789abcdef", "fedcba9876543210", "1111111111111111")

        assertEquals(2, meshPeopleCountOf(peers, myPeerID = "0123456789abcdef"))
        assertEquals(3, meshPeopleCountOf(peers, myPeerID = "2222222222222222"))
    }

    // L6

    @Test
    fun teleportedRow_onlyForSelectedLocationOutsideNearby() {
        assertNull(teleportedRow(ChannelID.Mesh, listOf(block), emptyMap(), emptyMap()) { false })
        assertNull(teleportedRow(null, listOf(block), emptyMap(), emptyMap()) { false })
        assertNull(
            teleportedRow(
                ChannelID.Location(GeohashChannel(GeohashChannelLevel.BLOCK, "S000000")),
                listOf(block),
                emptyMap(),
                emptyMap()
            ) { false }
        )
    }

    @Test
    fun teleportedRow_subtitleCountTitleAndAlwaysSelected() {
        val row = teleportedRow(
            selectedChannel = ChannelID.Location(neighborhood),
            nearbyChannels = listOf(block),
            bookmarkNames = mapOf("s00000" to "Synthetic Block"),
            participantCounts = mapOf("s00000" to 3),
            isBookmarked = { false },
        )!!

        assertEquals(neighborhood, row.channel)
        assertEquals("#s00000 • ~1.2 km • ~Synthetic Block", row.subtitle)
        assertEquals(ChannelTitle(level = null, count = 3, countUnknown = false), row.title)
        assertEquals(3, row.participantCount)
        assertTrue(row.isSelected)
        assertTrue(row.titleBold)
    }

    @Test
    fun teleportedRow_titlePrecisionComesFromGeohashLength() {
        // Level CITY on a 7-character geohash: the "?" follows the length (BLOCK, precision 7).
        val oddLevel = GeohashChannel(GeohashChannelLevel.CITY, "s000000")
        val row = teleportedRow(ChannelID.Location(oddLevel), emptyList(), emptyMap(), emptyMap()) { false }!!

        assertEquals(ChannelTitle(level = null, count = 0, countUnknown = true), row.title)
        assertEquals("#s000000 • ~0.2 km", row.subtitle)
        assertEquals(0, row.participantCount)
        assertFalse(row.titleBold)
    }

    // L7

    @Test
    fun bookmarkRows_inOrderWithLevelFromLengthNameCountAndBookmarked() {
        val rows = buildBookmarkRows(
            bookmarks = listOf("s0000000", "s0000"),
            bookmarkNames = mapOf("s0000000" to "Synthetic Building"),
            participantCounts = mapOf("s0000" to 1),
            selectedChannel = ChannelID.Location(city),
        )

        assertEquals(listOf("s0000000", "s0000"), rows.map { it.geohash })
        assertEquals(building, rows[0].channel)
        assertEquals("#s0000000 • ~0.0 km • ~Synthetic Building", rows[0].subtitle)
        assertEquals(ChannelTitle(level = null, count = 0, countUnknown = true), rows[0].title)
        assertFalse(rows[0].isSelected)
        assertEquals(city, rows[1].channel)
        assertEquals("#s0000 • ~4.9 km", rows[1].subtitle)
        assertEquals(ChannelTitle(level = null, count = 1, countUnknown = false), rows[1].title)
        assertTrue(rows[1].isSelected)
        assertTrue(rows.all { it.isBookmarked })
    }

    @Test
    fun bookmarkRow_selectionIsExactLevelAndCase() {
        // A 3-character bookmark is PROVINCE, so a REGION selection of it does not match (Q-L8).
        val region = GeohashChannel(GeohashChannelLevel.REGION, "s00")
        assertFalse(buildBookmarkRows(listOf("s00"), emptyMap(), emptyMap(), ChannelID.Location(region)).single().isSelected)

        val upper = GeohashChannel(GeohashChannelLevel.CITY, "S0000")
        assertFalse(buildBookmarkRows(listOf("s0000"), emptyMap(), emptyMap(), ChannelID.Location(upper)).single().isSelected)
    }

    @Test
    fun bookmarkedTeleportedChannel_appearsInTeleportedAndBookmarkRows_currentBehaviour_QL6() {
        val selected = ChannelID.Location(city)
        val teleported = teleportedRow(selected, listOf(block), emptyMap(), emptyMap()) { it == "s0000" }
        val bookmarks = buildBookmarkRows(listOf("s0000"), emptyMap(), emptyMap(), selected)

        assertEquals("s0000", teleported!!.geohash)
        assertTrue(teleported.isBookmarked)
        assertEquals(listOf("s0000"), bookmarks.map { it.geohash })
    }

    // L8

    @Test
    fun bookmarkTap_teleportedTruthTable() {
        val available = listOf(block, building)

        // App toggle off: always teleported.
        assertTrue(isBookmarkTeleported("s000000", available, appLocationEnabled = false))
        // No available channels: teleported.
        assertTrue(isBookmarkTeleported("s000000", emptyList(), appLocationEnabled = true))
        // Not among the available channels: teleported.
        assertTrue(isBookmarkTeleported("s0000", available, appLocationEnabled = true))
        // Exact match required.
        assertTrue(isBookmarkTeleported("S000000", available, appLocationEnabled = true))
        // Among them: not teleported, BUILDING included.
        assertFalse(isBookmarkTeleported("s000000", available, appLocationEnabled = true))
        assertFalse(isBookmarkTeleported("s0000000", available, appLocationEnabled = true))
    }

    // L9, L10

    @Test
    fun nearbyRows_subtitleNameCountSelectionAndBookmark() {
        val ui = nearby(
            locationNames = mapOf(GeohashChannelLevel.BLOCK to "Synthetic Block"),
            counts = mapOf("s00000" to 2),
            selected = ChannelID.Location(neighborhood),
            isBookmarked = { it == "s000000" },
        )

        assertEquals(listOf(block, neighborhood), ui.rows.map { it.channel })
        assertEquals("#s000000 • ~0.2 km • ~Synthetic Block", ui.rows[0].subtitle)
        assertEquals("#s00000 • ~1.2 km", ui.rows[1].subtitle)
        assertEquals(0, ui.rows[0].participantCount)
        assertEquals(2, ui.rows[1].participantCount)
        assertFalse(ui.rows[0].titleBold)
        assertTrue(ui.rows[1].titleBold)
        assertFalse(ui.rows[0].isSelected)
        assertTrue(ui.rows[1].isSelected)
        assertTrue(ui.rows[0].isBookmarked)
        assertFalse(ui.rows[1].isBookmarked)
    }

    @Test
    fun nearbyBookmarkFlag_comesFromTheStoreLookup_currentBehaviour_QL5() {
        // There is no bookmarks snapshot input: the flag is whatever the store's lookup says when
        // the rows are built.
        var stored = false
        val lookup: (String) -> Boolean = { stored }

        assertFalse(nearby(channels = listOf(city), isBookmarked = lookup).rows.single().isBookmarked)
        stored = true
        assertTrue(nearby(channels = listOf(city), isBookmarked = lookup).rows.single().isBookmarked)
        assertTrue(
            teleportedRow(ChannelID.Location(block), emptyList(), emptyMap(), emptyMap(), lookup)!!
                .isBookmarked
        )
    }

    @Test
    fun nearbyTitle_questionMarkAboveCityPrecisionWhenNobodySeen() {
        val ui = nearby(
            channels = listOf(city, neighborhood, block),
            counts = mapOf("s000000" to 1),
        )

        assertEquals(
            listOf(
                ChannelTitle(GeohashChannelLevel.CITY, count = 0, countUnknown = false),
                ChannelTitle(GeohashChannelLevel.NEIGHBORHOOD, count = 0, countUnknown = true),
                ChannelTitle(GeohashChannelLevel.BLOCK, count = 1, countUnknown = false),
            ),
            ui.rows.map { it.title }
        )
        assertEquals("0 people", channelCountText(ui.rows[0].title, peopleCount))
        assertEquals("? people", channelCountText(ui.rows[1].title, peopleCount))
        assertEquals("1 person", channelCountText(ui.rows[2].title, peopleCount))
    }

    @Test
    fun hashTitle_questionMarkFromSixCharactersWhenNobodySeen() {
        assertEquals(ChannelTitle(null, 0, countUnknown = false), hashChannelTitle("s0000", 0))
        assertEquals(ChannelTitle(null, 0, countUnknown = true), hashChannelTitle("s00000", 0))
        assertEquals(ChannelTitle(null, 0, countUnknown = true), hashChannelTitle("s0000000", 0))
        assertEquals(ChannelTitle(null, 5, countUnknown = false), hashChannelTitle("s0000000", 5))
    }

    // L17

    @Test
    fun sampling_withoutReveal_liveExcludesBuildingAndBookmarksStaySeparate() {
        val sampling = samplingGeohashes(
            availableChannels = listOf(building, block, neighborhood),
            bookmarks = listOf("zzzzz"),
            notesRevealed = false,
        )

        assertEquals(listOf("s000000", "s00000"), sampling.live)
        assertEquals(listOf("zzzzz"), sampling.userSelected)
    }

    @Test
    fun sampling_withReveal_liveIncludesBuilding() {
        val sampling = samplingGeohashes(
            availableChannels = listOf(building, block),
            bookmarks = emptyList(),
            notesRevealed = true,
        )

        assertEquals(listOf("s0000000", "s000000"), sampling.live)
        assertEquals(emptyList<String>(), sampling.userSelected)
    }
}
