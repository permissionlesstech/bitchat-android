package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.net.TorMode
import com.bitchat.android.nostr.LocationNotesManager

/**
 * Everything the location channels screen shows (inventory B11). The typed geohash and its error
 * are not here: they stay in the Root, as plain `remember`, so rotation still clears them (Q-L1).
 */
internal data class LocationChannelsUiState(
    val mesh: MeshRowUi,
    /** L6: the selected location channel when it has no nearby row. */
    val teleported: GeohashRowUi?,
    /** L7; the bookmarks card shows when this is not empty. */
    val bookmarks: List<GeohashRowUi>,
    val nearby: NearbyUi,
    /** Effective location on but the permission denied: the notice and "Open settings". */
    val showPermissionDenied: Boolean,
    /** The app's own toggle: picks the enable or the disable button. */
    val appLocationEnabled: Boolean,
    /** App and system location both on: the nearby rows show. */
    val effectiveLocationEnabled: Boolean,
    val notesSubtitle: NotesSubtitle,
    val tor: TorUi,
    /** Keys of the Root's resume effect, with [appLocationEnabled] (inventory B5). */
    val systemLocationEnabled: Boolean,
    /** Keys of the Root's nearby-notes hold, with [effectiveLocationEnabled] (inventory B5). */
    val permissionState: PermissionState,
    val buildingGeohash: String?,
)

/** L21, R2: the Tor switch, following the stored mode. */
internal data class TorUi(val enabled: Boolean, val available: Boolean)

/** Which row a channel tap came from; each card selects its channel its own way. */
internal sealed interface ChannelRowId {
    /** b3: the teleported card. */
    data class Teleported(val channel: GeohashChannel) : ChannelRowId

    /** b5: a bookmark row. */
    data class Bookmark(val channel: GeohashChannel) : ChannelRowId

    /** b7: a nearby row. */
    data class Nearby(val channel: GeohashChannel) : ChannelRowId
}

/** What the user does on the location channels screen (inventory b1-b17). */
internal sealed interface LocationChannelsAction {
    /** b2 */
    data object MeshClicked : LocationChannelsAction

    /** b3, b5, b7 */
    data class ChannelClicked(val id: ChannelRowId) : LocationChannelsAction

    /** b4, b6, b8 */
    data class BookmarkToggled(val geohash: String) : LocationChannelsAction

    /** A bookmark row was composed: look up its name if it has none yet (B5, Q-L15). */
    data class BookmarkShown(val geohash: String) : LocationChannelsAction

    /** b15: the TELEPORT button, with the field's text. */
    data class TeleportClicked(val text: String) : LocationChannelsAction

    /** b14: a geohash picked on the map. */
    data class CustomGeohashSubmitted(val text: String) : LocationChannelsAction

    /** b16 */
    data object NotesClicked : LocationChannelsAction

    /** b17 */
    data class TorToggled(val on: Boolean) : LocationChannelsAction

    /** b10 */
    data object EnableLocationClicked : LocationChannelsAction

    /** b11 */
    data object DisableLocationClicked : LocationChannelsAction

    /** b9 */
    data object OpenSettingsClicked : LocationChannelsAction

    /** b13, with the field's text (L14). */
    data class MapPickerClicked(val customGeohash: String) : LocationChannelsAction

    /** b1: the back arrow. */
    data object BackClicked : LocationChannelsAction

    /** The Root's resume effect (B5). */
    data object ScreenResumed : LocationChannelsAction

    /** The Root's resume effect ending: paused or disposed (B5). */
    data object ScreenPaused : LocationChannelsAction

    /** The Root's nearby-notes hold starting (B5). */
    data object NotesHoldShown : LocationChannelsAction

    /** The Root's nearby-notes hold ending (B5). */
    data object NotesHoldHidden : LocationChannelsAction
}

/** One-shot work the Root does for the ViewModel. */
internal sealed interface LocationChannelsEvent {
    data object Close : LocationChannelsEvent
    data object ShowNotes : LocationChannelsEvent
    data object OpenAppSettings : LocationChannelsEvent

    /** L14: the picker opens at [initial], or at nothing when it is empty. */
    data class OpenMapPicker(val initial: String) : LocationChannelsEvent

    /** L13, Q-L2: the Root puts [fieldText] in the field and shows `invalid_geohash`. */
    data class CustomGeohashRejected(val fieldText: String) : LocationChannelsEvent
}

/** The sources the screen is built from, at one moment (inventory B1.2). */
internal data class LocationChannelsSnapshot(
    val permissionState: PermissionState,
    val availableChannels: List<GeohashChannel>,
    val selectedChannel: ChannelID,
    val locationNames: Map<GeohashChannelLevel, String>,
    val appLocationEnabled: Boolean,
    val systemLocationEnabled: Boolean,
    val effectiveLocationEnabled: Boolean,
    val notesRevealed: Boolean,
    val noteCount: Int,
    val notesState: LocationNotesManager.State,
    val bookmarks: List<String>,
    val bookmarkNames: Map<String, String>,
    val participantCounts: Map<String, Int>,
    val wifiAwareEnabled: Boolean,
    val meshPeopleCount: Int,
    val torMode: TorMode,
)

/**
 * B11 from [snapshot]. Bookmark flags come from the snapshot's bookmarks list, not the store's
 * separate membership lookup, so the state never reads the store off the main thread (Q-L5).
 */
internal fun locationChannelsUiState(
    snapshot: LocationChannelsSnapshot,
    torAvailable: Boolean,
): LocationChannelsUiState = with(snapshot) {
    val nearbyChannels = nearbyChannelsOf(availableChannels)
    // Both sides normalized as the store's own lookup did (lowercase, `#` and foreign characters
    // dropped).
    val bookmarkSet = bookmarks.mapTo(HashSet()) { GeohashBookmarksStore.normalize(it) }
    val isBookmarked: (String) -> Boolean = { GeohashBookmarksStore.normalize(it) in bookmarkSet }
    LocationChannelsUiState(
        mesh = meshRow(meshPeopleCount, wifiAwareEnabled, selectedChannel),
        teleported = teleportedRow(
            selectedChannel = selectedChannel,
            nearbyChannels = nearbyChannels,
            bookmarkNames = bookmarkNames,
            participantCounts = participantCounts,
            isBookmarked = isBookmarked,
        ),
        bookmarks = buildBookmarkRows(
            bookmarks = bookmarks,
            bookmarkNames = bookmarkNames,
            participantCounts = participantCounts,
            selectedChannel = selectedChannel,
        ),
        nearby = buildNearbyRows(
            nearbyChannels = nearbyChannels,
            locationNames = locationNames,
            participantCounts = participantCounts,
            selectedChannel = selectedChannel,
            permissionState = permissionState,
            effectiveLocationEnabled = effectiveLocationEnabled,
            isBookmarked = isBookmarked,
        ),
        showPermissionDenied = effectiveLocationEnabled && permissionState == PermissionState.DENIED,
        appLocationEnabled = appLocationEnabled,
        effectiveLocationEnabled = effectiveLocationEnabled,
        notesSubtitle = notesSubtitle(notesRevealed, notesState, noteCount),
        tor = TorUi(enabled = torMode == TorMode.ON, available = torAvailable),
        systemLocationEnabled = systemLocationEnabled,
        permissionState = permissionState,
        buildingGeohash = buildingGeohashOf(availableChannels),
    )
}

/** L16: the first BUILDING channel's geohash, which the nearby-notes hold targets. */
internal fun buildingGeohashOf(availableChannels: List<GeohashChannel>): String? =
    availableChannels.firstOrNull { it.level == GeohashChannelLevel.BUILDING }?.geohash
