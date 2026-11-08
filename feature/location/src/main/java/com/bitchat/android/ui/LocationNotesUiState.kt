package com.bitchat.android.ui

import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.nostr.LocationNotesManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Everything the location notes screen shows. */
data class LocationNotesUiState(
    val mode: NotesMode,
    val draft: String,
    /** N3: something to send and relays to send it to. */
    val sendEnabled: Boolean,
    /** N13: the nickname before its first '#' for the input header; null when it is blank. */
    val authorBaseName: String?,
    /** Inputs of the nearby-notes gate; the Root keys its effect on them (N11). */
    val locationEnabled: Boolean,
    val locationAuthorized: Boolean,
)

/** N1: which of the three full screens the notes destination shows. */
sealed interface NotesMode {
    data class Notes(
        val geohash: String,
        /** N2: the building's name, else the block's, else null. */
        val displayLocationName: String?,
        /** Newest first, as the store keeps them; the title counts all of them (N9). */
        val notes: List<NoteUi>,
        val loadState: LocationNotesManager.State,
        val errorMessage: String?,
        val initialLoadComplete: Boolean,
    ) : NotesMode

    data object Acquiring : NotesMode

    /** Covers both "permission denied" and "location off", with the same copy (N1). */
    data object Unavailable : NotesMode
}

/** One note row (N13, N14). */
data class NoteUi(
    val id: String,
    val authorBaseName: String,
    /** "" when no timestamp is shown. */
    val timestampText: String,
    val content: String,
)

/** What the user, or the screen being shown, does on the notes destination. */
sealed interface LocationNotesAction {
    /** N10: the notes content entered composition; refreshes the location once. */
    data object NotesEntered : LocationNotesAction

    /**
     * N11: the notes content is shown, or one of the gate's inputs changed. Carries the
     * inputs the Root keyed its effect on, which the gate then receives.
     */
    data class ScreenShown(
        val geohash: String?,
        val locationEnabled: Boolean,
        val locationAuthorized: Boolean,
    ) : LocationNotesAction

    /** N11: the notes content left, or is about to be shown with new inputs. */
    data object ScreenHidden : LocationNotesAction

    data class DraftChanged(val text: String) : LocationNotesAction

    /** N4: the send button or the IME send action. */
    data object SendClicked : LocationNotesAction

    /** N7 */
    data object RetryClicked : LocationNotesAction

    /** N6 */
    data object DismissError : LocationNotesAction

    /** N12 */
    data object EnableLocationClicked : LocationNotesAction

    data object BackClicked : LocationNotesAction
}

sealed interface LocationNotesEvent {
    data object Close : LocationNotesEvent
}

// The pure decisions the notes screen makes (inventory N1-N14), kept out of the
// composables so they can be pinned without a device.

/** N1: the first BUILDING channel's geohash, or null. */
internal fun notesBuildingGeohash(channels: List<GeohashChannel>): String? =
    channels.firstOrNull { it.level == GeohashChannelLevel.BUILDING }?.geohash

/** N1: without a building geohash, Acquiring while an authorized fix is loading, else Unavailable. */
internal fun notesFallbackMode(permissionState: PermissionState, isLoadingLocation: Boolean): NotesMode =
    if (permissionState == PermissionState.AUTHORIZED && isLoadingLocation) {
        NotesMode.Acquiring
    } else {
        NotesMode.Unavailable
    }

/** N2: the building's name if non-empty, else the block's if non-empty, else null. */
internal fun notesDisplayLocationName(locationNames: Map<GeohashChannelLevel, String>): String? =
    locationNames[GeohashChannelLevel.BUILDING]?.takeIf { it.isNotEmpty() }
        ?: locationNames[GeohashChannelLevel.BLOCK]?.takeIf { it.isNotEmpty() }

/** N3 and Q-N11: a non-blank draft can be sent in every state but NO_RELAYS. */
internal fun notesSendEnabled(draft: String, loadState: LocationNotesManager.State): Boolean =
    draft.trim().isNotEmpty() && loadState != LocationNotesManager.State.NO_RELAYS

/** N13 and Q-N10: a note's author is shown as the display name before its first '#'. */
internal fun noteAuthorBaseName(displayName: String): String =
    displayName.split("#", limit = 2).firstOrNull() ?: displayName

/** N13: the input header's name, or null (no header) when the nickname is blank. */
internal fun notesInputBaseName(nickname: String?): String? =
    if (nickname.isNullOrBlank()) null else noteAuthorBaseName(nickname)

/** N5: what the list shows under the header. */
internal enum class NotesBody { NoRelays, Loading, Empty, Notes }

/** N5, in priority order: no relays, the initial load, nothing yet, then the notes. */
internal fun notesBody(
    loadState: LocationNotesManager.State,
    initialLoadComplete: Boolean,
    hasNotes: Boolean,
): NotesBody = when {
    loadState == LocationNotesManager.State.NO_RELAYS -> NotesBody.NoRelays
    loadState == LocationNotesManager.State.LOADING && !initialLoadComplete -> NotesBody.Loading
    !hasNotes -> NotesBody.Empty
    else -> NotesBody.Notes
}

/** N6: the error row is shown for an error, except while no relays are available. */
internal fun notesErrorShown(errorMessage: String?, loadState: LocationNotesManager.State): Boolean =
    errorMessage != null && loadState != LocationNotesManager.State.NO_RELAYS

/** N8: the header says relays are unavailable only in NO_RELAYS. */
internal fun notesRelaysUnavailableShown(loadState: LocationNotesManager.State): Boolean =
    loadState == LocationNotesManager.State.NO_RELAYS

/**
 * N14: a note's age as the row shows it. Relative ("5m ago", "3h ago", "2d ago") while
 * the calendar-day difference is under 7, nothing under a minute or for a future note
 * (Q-N12), otherwise the date ("MMM d", plus the year when it differs). The day
 * difference is the day-of-year delta within one year, otherwise elapsed days.
 * The suffixes are English literals (Q-N4).
 */
internal fun notesTimestampText(
    createdAt: Int,
    nowEpochSeconds: Long,
    locale: Locale,
    timeZone: TimeZone,
): String {
    val date = Date(createdAt * 1000L)
    val now = Date(nowEpochSeconds * 1000L)

    val calendar = Calendar.getInstance(timeZone, locale)
    calendar.time = date
    val dateDay = calendar.get(Calendar.DAY_OF_YEAR)
    val dateYear = calendar.get(Calendar.YEAR)

    calendar.time = now
    val nowDay = calendar.get(Calendar.DAY_OF_YEAR)
    val nowYear = calendar.get(Calendar.YEAR)

    val daysDiff = if (dateYear == nowYear) {
        nowDay - dateDay
    } else {
        ((now.time - date.time) / (1000 * 60 * 60 * 24)).toInt()
    }

    return if (daysDiff < 7) {
        val diffSeconds = (now.time - date.time) / 1000
        when {
            diffSeconds < 60 -> ""
            diffSeconds < 3600 -> "${(diffSeconds / 60).toInt()}m ago"
            diffSeconds < 86400 -> "${(diffSeconds / 3600).toInt()}h ago"
            else -> "${(diffSeconds / 86400).toInt()}d ago"
        }
    } else {
        val pattern = if (dateYear == nowYear) "MMM d" else "MMM d, y"
        SimpleDateFormat(pattern, locale).apply { this.timeZone = timeZone }.format(date)
    }
}

/** N1-N3, N13, N14: the whole screen state from what the sources currently hold. */
internal fun locationNotesUiState(
    availableChannels: List<GeohashChannel>,
    permissionState: PermissionState,
    isLoadingLocation: Boolean,
    locationEnabled: Boolean,
    locationNames: Map<GeohashChannelLevel, String>,
    notes: List<LocationNotesManager.Note>,
    loadState: LocationNotesManager.State,
    errorMessage: String?,
    initialLoadComplete: Boolean,
    draft: String,
    nickname: String,
    nowEpochSeconds: Long,
    locale: Locale,
    timeZone: TimeZone,
): LocationNotesUiState {
    val geohash = notesBuildingGeohash(availableChannels)
    val mode = if (geohash != null) {
        NotesMode.Notes(
            geohash = geohash,
            displayLocationName = notesDisplayLocationName(locationNames),
            notes = notes.map { note ->
                NoteUi(
                    id = note.id,
                    authorBaseName = noteAuthorBaseName(note.displayName),
                    timestampText = notesTimestampText(note.createdAt, nowEpochSeconds, locale, timeZone),
                    content = note.content,
                )
            },
            loadState = loadState,
            errorMessage = errorMessage,
            initialLoadComplete = initialLoadComplete,
        )
    } else {
        notesFallbackMode(permissionState, isLoadingLocation)
    }
    return LocationNotesUiState(
        mode = mode,
        draft = draft,
        sendEnabled = notesSendEnabled(draft, loadState),
        authorBaseName = notesInputBaseName(nickname),
        locationEnabled = locationEnabled,
        locationAuthorized = permissionState == PermissionState.AUTHORIZED,
    )
}
