package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.geohashesForSampling

/*
 * The rows of the location channels screen as plain functions of snapshots (inventory L1,
 * L3-L10, L17 and the mesh count). Moved verbatim from LocationChannelsSheet.kt, quirks included;
 * the quirk IDs mark the lines tests pin. The UI still resolves strings and colours.
 */

/**
 * The count part of a row title as data (L10, L11): the UI shows the row's own label ("Mesh",
 * `#geohash`, or the nearby channel's level name), followed by the `people_count` plural of
 * [count]. When [countUnknown] the plural of 0 is shown with "0" replaced by "?".
 */
internal data class ChannelTitle(
    val count: Int,
    val countUnknown: Boolean,
)

/**
 * L10: the count label of [title]. [peopleCount] resolves the `people_count` plural for a
 * number, e.g. `resources.getQuantityString(R.plurals.people_count, n, n)`.
 */
internal fun channelCountText(title: ChannelTitle, peopleCount: (Int) -> String): String =
    if (title.countUnknown) {
        peopleCount(0).replace("0", "?")
    } else {
        peopleCount(title.count)
    }

/** L10: precision above 5 (NEIGHBORHOOD, BLOCK, BUILDING) with nobody seen shows "?". */
private fun geohashChannelTitle(
    precisionLevel: GeohashChannelLevel,
    participantCount: Int,
): ChannelTitle {
    val isHighPrecision = precisionLevel.precision > 5
    return ChannelTitle(
        count = participantCount,
        countUnknown = isHighPrecision && participantCount == 0,
    )
}

/**
 * The title of a row labelled `#geohash` (teleported and bookmark rows); the precision comes from
 * [levelForLength].
 */
internal fun hashChannelTitle(geohash: String, participantCount: Int): ChannelTitle =
    geohashChannelTitle(
        precisionLevel = levelForLength(geohash.length),
        participantCount = participantCount,
    )

/** The title of a nearby row, labelled with its level's name. */
internal fun levelChannelTitle(channel: GeohashChannel, participantCount: Int): ChannelTitle =
    geohashChannelTitle(
        precisionLevel = channel.level,
        participantCount = participantCount,
    )

/** Mesh people count: every connected peer except this device (Q-L12, see Q-P2). */
internal fun meshPeopleCountOf(connectedPeers: List<String>, myPeerID: String): Int =
    connectedPeers.count { it != myPeerID }

/** L5: the mesh row. The subtitle range is [meshRangeString]. */
internal data class MeshRowUi(
    val title: ChannelTitle,
    val peopleCount: Int,
    /** Picks `location_bluetooth_wifi_subtitle` over `location_bluetooth_subtitle`. */
    val wifiAwareEnabled: Boolean,
    val isSelected: Boolean,
    val titleBold: Boolean,
)

internal fun meshRow(
    meshPeopleCount: Int,
    wifiAwareEnabled: Boolean,
    selectedChannel: ChannelID?,
): MeshRowUi = MeshRowUi(
    title = ChannelTitle(count = meshPeopleCount, countUnknown = false),
    peopleCount = meshPeopleCount,
    wifiAwareEnabled = wifiAwareEnabled,
    isSelected = selectedChannel is ChannelID.Mesh,
    titleBold = meshPeopleCount > 0,
)

/** L6, L7: a row labelled `#geohash` (the teleported card and each bookmark). */
internal data class GeohashRowUi(
    val channel: GeohashChannel,
    val title: ChannelTitle,
    val subtitle: String,
    val participantCount: Int,
    val isBookmarked: Boolean,
    val isSelected: Boolean,
) {
    val geohash: String get() = channel.geohash
    val titleBold: Boolean get() = participantCount > 0
}

/** `#gh • ~X km`, plus ` • ~name` when there is a name. */
private fun hashRowSubtitle(geohash: String, level: GeohashChannelLevel, name: String?): String {
    val coverage = coverageString(geohash.length)
    return "#$geohash • $coverage" +
        (name?.let { " • ${formattedNamePrefix(level)}$it" } ?: "")
}

/**
 * L6: the teleported card, shown when the selected location channel has no nearby row (L2).
 * Always selected. [isBookmarked] is the store's non-snapshot lookup (Q-L5). A bookmarked
 * teleported channel also gets a bookmark row (Q-L6).
 */
internal fun teleportedRow(
    selectedChannel: ChannelID?,
    nearbyChannels: List<GeohashChannel>,
    bookmarkNames: Map<String, String>,
    participantCounts: Map<String, Int>,
    isBookmarked: (String) -> Boolean,
): GeohashRowUi? {
    val channel = selectedLocationChannelOutsideNearby(selectedChannel, nearbyChannels)
        ?: return null
    val participantCount = participantCounts[channel.geohash] ?: 0
    return GeohashRowUi(
        channel = channel,
        title = hashChannelTitle(channel.geohash, participantCount),
        subtitle = hashRowSubtitle(channel.geohash, channel.level, bookmarkNames[channel.geohash]),
        participantCount = participantCount,
        isBookmarked = isBookmarked(channel.geohash),
        isSelected = true,
    )
}

/**
 * L7: one row per bookmark, in store order. The level comes from [levelForLength] and selection
 * is [isChannelSelected] (Q-L8). Every row is bookmarked.
 */
internal fun buildBookmarkRows(
    bookmarks: List<String>,
    bookmarkNames: Map<String, String>,
    participantCounts: Map<String, Int>,
    selectedChannel: ChannelID?,
): List<GeohashRowUi> = bookmarks.map { gh ->
    val level = levelForLength(gh.length)
    val channel = GeohashChannel(level = level, geohash = gh)
    val participantCount = participantCounts[gh] ?: 0
    GeohashRowUi(
        channel = channel,
        title = hashChannelTitle(gh, participantCount),
        subtitle = hashRowSubtitle(gh, level, bookmarkNames[gh]),
        participantCount = participantCount,
        isBookmarked = true,
        isSelected = isChannelSelected(channel, selectedChannel),
    )
}

/**
 * L8: whether tapping bookmark [geohash] selects it as teleported. Uses the app toggle, not the
 * effective flag, and BUILDING channels count as regional (Q-L7).
 */
internal fun isBookmarkTeleported(
    geohash: String,
    availableChannels: List<GeohashChannel>,
    appLocationEnabled: Boolean,
): Boolean {
    val inRegional = availableChannels.any { it.geohash == geohash }
    return !appLocationEnabled || availableChannels.isEmpty() || !inRegional
}

/** L1: nearby channels are the available ones minus BUILDING. */
internal fun nearbyChannelsOf(availableChannels: List<GeohashChannel>): List<GeohashChannel> =
    availableChannels.filter { it.level != GeohashChannelLevel.BUILDING }

/** L9: one nearby channel row. */
internal data class NearbyRowUi(
    val channel: GeohashChannel,
    val title: ChannelTitle,
    val subtitle: String,
    val participantCount: Int,
    val isBookmarked: Boolean,
    val isSelected: Boolean,
) {
    val titleBold: Boolean get() = participantCount > 0
}

/** The nearby card: [rows] (L9) and whether it shows the loading row instead (L3). */
internal data class NearbyUi(
    val rows: List<NearbyRowUi>,
    val showLoading: Boolean,
)

/**
 * L3, L9: rows for [nearbyChannels] (L1) and the loading flag. The screen shows either only when
 * effective location is on. [isBookmarked] is the store's non-snapshot lookup (Q-L5).
 */
internal fun buildNearbyRows(
    nearbyChannels: List<GeohashChannel>,
    locationNames: Map<GeohashChannelLevel, String>,
    participantCounts: Map<String, Int>,
    selectedChannel: ChannelID?,
    permissionState: LocationChannelManager.PermissionState,
    effectiveLocationEnabled: Boolean,
    isBookmarked: (String) -> Boolean,
): NearbyUi {
    val showNearbyLoading = nearbyChannels.isEmpty() &&
        permissionState == LocationChannelManager.PermissionState.AUTHORIZED &&
        effectiveLocationEnabled
    val rows = nearbyChannels.map { channel ->
        val coverage = coverageString(channel.geohash.length)
        val nameBase = locationNames[channel.level]
        val namePart = nameBase?.let { formattedNamePrefix(channel.level) + it }
        val subtitlePrefix = "#${channel.geohash} • $coverage"
        val participantCount = participantCounts[channel.geohash] ?: 0
        NearbyRowUi(
            channel = channel,
            title = levelChannelTitle(channel, participantCount),
            subtitle = subtitlePrefix + (namePart?.let { " • $it" } ?: ""),
            participantCount = participantCount,
            isBookmarked = isBookmarked(channel.geohash),
            isSelected = isChannelSelected(channel, selectedChannel),
        )
    }
    return NearbyUi(rows = rows, showLoading = showNearbyLoading)
}

/** L4: the location notes row subtitle, resolved to a string in the UI. */
internal sealed interface NotesSubtitle {
    /** `nearby_notes_reveal` */
    data object Reveal : NotesSubtitle
    /** `location_notes_relays_unavailable` */
    data object NoRelays : NotesSubtitle
    /** `loading_location_notes` */
    data object Loading : NotesSubtitle
    /** `nearby_notes_one` */
    data object One : NotesSubtitle
    /** `nearby_notes_many` with [n] */
    data class Many(val n: Int) : NotesSubtitle
    /** `location_notes_empty_title` */
    data object Empty : NotesSubtitle
}

internal fun notesSubtitle(
    notesRevealed: Boolean,
    notesState: LocationNotesManager.State,
    noteCount: Int,
): NotesSubtitle = when {
    !notesRevealed -> NotesSubtitle.Reveal
    notesState == LocationNotesManager.State.NO_RELAYS -> NotesSubtitle.NoRelays
    notesState == LocationNotesManager.State.LOADING -> NotesSubtitle.Loading
    noteCount == 1 -> NotesSubtitle.One
    noteCount > 1 -> NotesSubtitle.Many(noteCount)
    else -> NotesSubtitle.Empty
}

/** L17: what the screen samples. */
internal data class SamplingGeohashes(
    /** Live-location channels; BUILDING only once notes are revealed. */
    val live: List<String>,
    /** The bookmarks, as given. */
    val userSelected: List<String>,
)

internal fun samplingGeohashes(
    availableChannels: List<GeohashChannel>,
    bookmarks: List<String>,
    notesRevealed: Boolean,
): SamplingGeohashes = SamplingGeohashes(
    live = geohashesForSampling(
        availableChannels = availableChannels,
        bookmarks = emptyList(),
        notesRevealed = notesRevealed,
    ),
    userSelected = bookmarks,
)
