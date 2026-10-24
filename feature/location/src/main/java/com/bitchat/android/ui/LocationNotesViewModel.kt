package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * The location notes destination: which of its three screens to show, the notes, and the
 * draft. Entry-scoped, so it is cleared when the destination is popped.
 *
 * The state is rebuilt from the sources' current values whenever any of them, or the
 * draft, changes; that also refreshes the note timestamps, as recomposing the rows did.
 * The location refresh and the nearby-notes hold are tied to the notes content being
 * composed, so the Root sends [LocationNotesAction.NotesEntered],
 * [LocationNotesAction.ScreenShown] and [LocationNotesAction.ScreenHidden] from its effects.
 */
@HiltViewModel
class LocationNotesViewModel internal constructor(
    private val location: NotesLocationSource,
    private val store: NotesStore,
    private val gate: NearbyNotesGate,
    private val clock: NotesClock,
    private val nickname: StateFlow<String>,
) : ViewModel() {

    @Inject
    constructor(
        location: NotesLocationSource,
        store: NotesStore,
        gate: NearbyNotesGate,
        clock: NotesClock,
        chatState: ChatState,
    ) : this(location, store, gate, clock, chatState.nickname)

    private var draft = ""

    private val _uiState = MutableStateFlow(buildState())
    val uiState: StateFlow<LocationNotesUiState> = _uiState.asStateFlow()

    private val _events = Channel<LocationNotesEvent>(Channel.BUFFERED)
    val events: Flow<LocationNotesEvent> = _events.receiveAsFlow()

    init {
        merge(
            location.availableChannels,
            location.permissionState,
            location.isLoadingLocation,
            location.effectiveLocationEnabled,
            location.locationNames,
            store.notes,
            store.state,
            store.errorMessage,
            store.initialLoadComplete,
            nickname,
        ).onEach { rebuild() }.launchIn(viewModelScope)
    }

    fun onAction(action: LocationNotesAction) {
        when (action) {
            LocationNotesAction.NotesEntered -> location.refreshChannels()
            is LocationNotesAction.ScreenShown -> {
                gate.updateAvailability(
                    enabled = action.locationEnabled,
                    authorized = action.locationAuthorized,
                    geohash = action.geohash,
                )
                gate.activate()
                gate.reveal()
            }
            LocationNotesAction.ScreenHidden -> gate.deactivate()
            is LocationNotesAction.DraftChanged -> {
                draft = action.text
                rebuild()
            }
            LocationNotesAction.SendClicked -> send()
            LocationNotesAction.RetryClicked -> store.refresh()
            LocationNotesAction.DismissError -> store.clearError()
            LocationNotesAction.EnableLocationClicked -> {
                location.enableLocationServices()
                location.enableLocationChannels()
                location.refreshChannels()
            }
            LocationNotesAction.BackClicked -> _events.trySend(LocationNotesEvent.Close)
        }
    }

    /** N4: a non-blank draft is sent trimmed, then cleared whatever the send did (Q-N3). */
    private fun send() {
        if (!_uiState.value.sendEnabled) return
        val content = draft.trim()
        if (content.isNotEmpty()) {
            store.send(content, nickname.value)
            draft = ""
            rebuild()
        }
    }

    private fun rebuild() {
        _uiState.value = buildState()
    }

    private fun buildState(): LocationNotesUiState = locationNotesUiState(
        availableChannels = location.availableChannels.value,
        permissionState = location.permissionState.value,
        isLoadingLocation = location.isLoadingLocation.value,
        locationEnabled = location.effectiveLocationEnabled.value,
        locationNames = location.locationNames.value,
        notes = store.notes.value,
        loadState = store.state.value,
        errorMessage = store.errorMessage.value,
        initialLoadComplete = store.initialLoadComplete.value,
        draft = draft,
        nickname = nickname.value,
        nowEpochSeconds = clock.nowEpochSeconds(),
        locale = Locale.getDefault(),
        timeZone = TimeZone.getDefault(),
    )
}
