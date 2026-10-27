package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.net.TorMode
import com.bitchat.android.nostr.LocationNotesManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The chat session's side of the screen: counts, Wi-Fi Aware, and sampling. */
internal interface ChannelsSession {
    /** Participants seen per geohash while sampling. */
    val participantCounts: StateFlow<Map<String, Int>>

    /** Whether the user has Wi-Fi Aware switched on. */
    val wifiAwareEnabled: StateFlow<Boolean>
    val connectedPeers: StateFlow<List<String>>
    val myPeerID: String
    fun beginGeohashSampling(live: Collection<String>, userSelected: Collection<String>)
    fun endGeohashSampling()
}

private class SessionChannels(
    private val state: ChatState,
    private val mesh: ChatSessionMesh,
    private val geohashSession: GeohashSession,
    private val wifiAwarePeers: WifiAwarePeers,
) : ChannelsSession {
    override val participantCounts get() = geohashSession.geohashParticipantCounts
    override val wifiAwareEnabled get() = wifiAwarePeers.enabled
    override val connectedPeers get() = state.connectedPeers
    override val myPeerID: String get() = mesh.unified.myPeerID

    override fun beginGeohashSampling(live: Collection<String>, userSelected: Collection<String>) =
        geohashSession.beginGeohashSampling(
            liveLocationGeohashes = live,
            userSelectedGeohashes = userSelected,
        )

    override fun endGeohashSampling() = geohashSession.endGeohashSampling()
}

/**
 * The location channels screen: the mesh, the teleported and bookmarked channels, the nearby
 * ones, location notes and Tor. Entry-scoped.
 *
 * Samples the channels the screen lists for the ViewModel's life (L17): it starts on creation,
 * survives rotation and ends in [onCleared], when the entry leaves the stack. The lifecycle-bound
 * holds (live refresh, nearby notes) stay with the Root, which reports them as actions.
 */
@HiltViewModel
class LocationChannelsViewModel internal constructor(
    private val location: NotesLocationSource,
    private val notes: NotesStore,
    private val nearbyNotes: NearbyNotesGate,
    private val bookmarks: ChannelBookmarks,
    private val tor: TorRouting,
    private val session: ChannelsSession,
    /** Where the state is built; tests pass their own dispatcher. */
    defaultDispatcher: CoroutineDispatcher,
) : ViewModel() {

    @Inject
    constructor(
        state: ChatState,
        mesh: ChatSessionMesh,
        geohashSession: GeohashSession,
        wifiAwarePeers: WifiAwarePeers,
        location: NotesLocationSource,
        notes: NotesStore,
        nearbyNotes: NearbyNotesGate,
        bookmarks: ChannelBookmarks,
        tor: TorRouting,
    ) : this(
        location = location,
        notes = notes,
        nearbyNotes = nearbyNotes,
        bookmarks = bookmarks,
        tor = tor,
        session = SessionChannels(state, mesh, geohashSession, wifiAwarePeers),
        defaultDispatcher = Dispatchers.Default,
    )

    /** Read once, as the row's `remember` did; always true in this build. */
    private val torAvailable = tor.isTorAvailable()

    private val _events = Channel<LocationChannelsEvent>(Channel.BUFFERED)
    internal val events: Flow<LocationChannelsEvent> = _events.receiveAsFlow()

    internal val uiState: StateFlow<LocationChannelsUiState> = snapshots()
        .map(::buildUiState)
        .flowOn(defaultDispatcher)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildUiState(currentSnapshot()),
        )

    init {
        // L17: re-sample whenever the listed channels change. The app toggle is not read here,
        // but its change still re-samples, so the session re-reads the privacy gate (Q-L13).
        viewModelScope.launch {
            combine(
                location.availableChannels,
                bookmarks.bookmarks,
                location.locationServicesEnabled,
                nearbyNotes.revealed,
            ) { availableChannels, bookmarked, _, revealed ->
                samplingGeohashes(availableChannels, bookmarked, revealed)
            }.collect { sampling ->
                session.beginGeohashSampling(sampling.live, sampling.userSelected)
            }
        }
    }

    internal fun onAction(action: LocationChannelsAction) {
        when (action) {
            LocationChannelsAction.MeshClicked -> {
                location.select(ChannelID.Mesh)
                _events.trySend(LocationChannelsEvent.Close)
            }
            is LocationChannelsAction.ChannelClicked -> selectChannel(action.id)
            is LocationChannelsAction.BookmarkToggled -> bookmarks.toggle(action.geohash)
            is LocationChannelsAction.BookmarkShown -> bookmarks.resolveNameIfNeeded(action.geohash)
            is LocationChannelsAction.TeleportClicked -> teleport(action.text)
            is LocationChannelsAction.CustomGeohashSubmitted -> teleport(action.text)
            LocationChannelsAction.NotesClicked -> {
                location.refreshChannels()
                nearbyNotes.reveal()
                _events.trySend(LocationChannelsEvent.ShowNotes)
            }
            is LocationChannelsAction.TorToggled -> if (torAvailable) {
                tor.setMode(if (action.on) TorMode.ON else TorMode.OFF)
            }
            LocationChannelsAction.EnableLocationClicked -> location.enableLocationServices()
            LocationChannelsAction.DisableLocationClicked -> location.disableLocationServices()
            LocationChannelsAction.OpenSettingsClicked ->
                _events.trySend(LocationChannelsEvent.OpenAppSettings)
            is LocationChannelsAction.MapPickerClicked -> _events.trySend(
                LocationChannelsEvent.OpenMapPicker(
                    mapPickerInitialGeohash(action.customGeohash, location.selectedChannel.value)
                )
            )
            LocationChannelsAction.BackClicked -> _events.trySend(LocationChannelsEvent.Close)
            LocationChannelsAction.ScreenResumed -> beginLiveRefresh()
            LocationChannelsAction.ScreenPaused -> location.endLiveRefresh()
            LocationChannelsAction.NotesHoldShown -> holdNearbyNotes()
            LocationChannelsAction.NotesHoldHidden -> nearbyNotes.deactivate()
        }
    }

    /** b3, b5, b7: each card selects its channel as it always did, then the screen closes. */
    private fun selectChannel(id: ChannelRowId) {
        when (id) {
            is ChannelRowId.Teleported -> location.select(ChannelID.Location(id.channel))
            is ChannelRowId.Bookmark -> location.selectManual(
                channel = id.channel,
                teleported = isBookmarkTeleported(
                    geohash = id.channel.geohash,
                    availableChannels = location.availableChannels.value,
                    appLocationEnabled = location.locationServicesEnabled.value,
                ),
            )
            // Q-L3: when the manager refuses, the screen stays, with no feedback.
            is ChannelRowId.Nearby -> if (!location.selectNearby(id.channel)) return
        }
        _events.trySend(LocationChannelsEvent.Close)
    }

    /** b14, b15 (L13): a valid geohash is selected as teleported; anything else is handed back. */
    private fun teleport(text: String) {
        val channel = channelForManualGeohash(text)
        if (channel != null) {
            location.selectManual(channel, teleported = true)
            _events.trySend(LocationChannelsEvent.Close)
        } else {
            _events.trySend(LocationChannelsEvent.CustomGeohashRejected(rejectedTeleportFieldText(text)))
        }
    }

    /**
     * The Root's resume effect: re-read the permission, then, only with the app toggle, system
     * location and the permission all on, a one-shot request and continuous updates.
     */
    private fun beginLiveRefresh() {
        val permission = location.syncPermissionState()
        if (location.locationServicesEnabled.value &&
            location.systemLocationEnabled.value &&
            permission == PermissionState.AUTHORIZED
        ) {
            // Retain the redesign branch's immediate refresh when the screen resumes while
            // honoring main's independent app/system privacy gates.
            location.enableLocationChannels()
            location.beginLiveRefresh()
        }
    }

    /**
     * The Root's nearby-notes hold. The notes row shows how many notes are nearby, which makes
     * this a nearby-notes surface: as a destination it replaces chat, which releases chat's hold
     * and clears the notes, so the screen holds its own and keeps availability current. A hold
     * is not consent: nothing subscribes until notes have been revealed.
     */
    private fun holdNearbyNotes() {
        nearbyNotes.updateAvailability(
            enabled = location.effectiveLocationEnabled.value,
            authorized = location.permissionState.value == PermissionState.AUTHORIZED,
            geohash = buildingGeohashOf(location.availableChannels.value),
        )
        nearbyNotes.activate()
    }

    override fun onCleared() {
        session.endGeohashSampling()
    }

    private fun buildUiState(snapshot: LocationChannelsSnapshot): LocationChannelsUiState =
        locationChannelsUiState(snapshot, torAvailable)

    private fun meshPeopleCount(peers: List<String>): Int =
        meshPeopleCountOf(peers, session.myPeerID)

    private fun currentSnapshot() = LocationChannelsSnapshot(
        permissionState = location.permissionState.value,
        availableChannels = location.availableChannels.value,
        selectedChannel = location.selectedChannel.value,
        locationNames = location.locationNames.value,
        appLocationEnabled = location.locationServicesEnabled.value,
        systemLocationEnabled = location.systemLocationEnabled.value,
        effectiveLocationEnabled = location.effectiveLocationEnabled.value,
        notesRevealed = nearbyNotes.revealed.value,
        noteCount = notes.notes.value.size,
        notesState = notes.state.value,
        bookmarks = bookmarks.bookmarks.value,
        bookmarkNames = bookmarks.bookmarkNames.value,
        participantCounts = session.participantCounts.value,
        wifiAwareEnabled = session.wifiAwareEnabled.value,
        meshPeopleCount = meshPeopleCount(session.connectedPeers.value),
        torMode = tor.mode.value,
    )

    /** Every source, combined in groups of at most five. */
    private fun snapshots(): Flow<LocationChannelsSnapshot> {
        val place = combine(
            location.permissionState,
            location.availableChannels,
            location.selectedChannel,
            location.locationNames,
        ) { permission, available, selected, names ->
            PlacePart(permission, available, selected, names)
        }
        val toggles = combine(
            location.locationServicesEnabled,
            location.systemLocationEnabled,
            location.effectiveLocationEnabled,
            tor.mode,
        ) { app, system, effective, torMode -> TogglesPart(app, system, effective, torMode) }
        val notesPart = combine(
            nearbyNotes.revealed,
            notes.notes,
            notes.state,
        ) { revealed, list, state -> NotesPart(revealed, list.size, state) }
        val bookmarked = combine(
            bookmarks.bookmarks,
            bookmarks.bookmarkNames,
        ) { list, names -> list to names }
        val people = combine(
            session.participantCounts,
            session.wifiAwareEnabled,
            session.connectedPeers.map(::meshPeopleCount),
        ) { counts, wifiAware, meshCount -> PeoplePart(counts, wifiAware, meshCount) }
        return combine(place, toggles, notesPart, bookmarked, people) { p, t, n, (list, names), pp ->
            LocationChannelsSnapshot(
                permissionState = p.permissionState,
                availableChannels = p.availableChannels,
                selectedChannel = p.selectedChannel,
                locationNames = p.locationNames,
                appLocationEnabled = t.appLocationEnabled,
                systemLocationEnabled = t.systemLocationEnabled,
                effectiveLocationEnabled = t.effectiveLocationEnabled,
                notesRevealed = n.revealed,
                noteCount = n.noteCount,
                notesState = n.state,
                bookmarks = list,
                bookmarkNames = names,
                participantCounts = pp.participantCounts,
                wifiAwareEnabled = pp.wifiAwareEnabled,
                meshPeopleCount = pp.meshPeopleCount,
                torMode = t.torMode,
            )
        }
    }

    private data class PlacePart(
        val permissionState: PermissionState,
        val availableChannels: List<GeohashChannel>,
        val selectedChannel: ChannelID,
        val locationNames: Map<GeohashChannelLevel, String>,
    )

    private data class TogglesPart(
        val appLocationEnabled: Boolean,
        val systemLocationEnabled: Boolean,
        val effectiveLocationEnabled: Boolean,
        val torMode: TorMode,
    )

    private data class NotesPart(
        val revealed: Boolean,
        val noteCount: Int,
        val state: LocationNotesManager.State,
    )

    private data class PeoplePart(
        val participantCounts: Map<String, Int>,
        val wifiAwareEnabled: Boolean,
        val meshPeopleCount: Int,
    )
}
