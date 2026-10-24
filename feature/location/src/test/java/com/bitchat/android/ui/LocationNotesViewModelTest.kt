package com.bitchat.android.ui

import androidx.lifecycle.SavedStateHandle
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
import com.bitchat.android.geohash.GeohashChannelLevel.BUILDING
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.LocationNotesManager.State
import com.bitchat.android.nostr.NoteSendResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins LocationNotesViewModel and the decisions under it (inventory N1-N13, F3, Q-N10,
 * Q-N11) against fake ports. Synthetic geohashes only; nothing touches location services.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationNotesViewModelTest {

    private val calls = mutableListOf<String>()
    private lateinit var location: FakeLocation
    private lateinit var store: FakeStore
    private lateinit var gate: RecordingGate
    private val nickname = MutableStateFlow("alice")

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        location = FakeLocation(calls)
        store = FakeStore(calls)
        gate = RecordingGate(calls)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        LocationNotesViewModel(location, store, gate, NotesClock { NOW }, nickname, savedState)

    private fun withBuilding() {
        location.availableChannels.value = listOf(
            GeohashChannel(BUILDING, "s0000000"),
            GeohashChannel(BLOCK, "s000000"),
        )
    }

    private fun LocationNotesUiState.notes() = mode as NotesMode.Notes

    // N1

    @Test
    fun buildingChannel_showsNotesForTheFirstBuildingGeohash() {
        location.availableChannels.value = listOf(
            GeohashChannel(BLOCK, "s000000"),
            GeohashChannel(BUILDING, "s0000000"),
            GeohashChannel(BUILDING, "s0000001"),
        )

        assertEquals("s0000000", newViewModel().uiState.value.notes().geohash)
    }

    @Test
    fun buildingChannel_showsNotesWhateverThePermission() {
        withBuilding()
        location.permissionState.value = PermissionState.DENIED

        assertTrue(newViewModel().uiState.value.mode is NotesMode.Notes)
    }

    @Test
    fun noBuilding_authorizedAndLoading_showsAcquiring() {
        location.permissionState.value = PermissionState.AUTHORIZED
        location.isLoadingLocation.value = true

        assertEquals(NotesMode.Acquiring, newViewModel().uiState.value.mode)
    }

    @Test
    fun noBuilding_otherwise_showsUnavailable() {
        location.permissionState.value = PermissionState.AUTHORIZED
        location.isLoadingLocation.value = false
        assertEquals(NotesMode.Unavailable, newViewModel().uiState.value.mode)

        location.permissionState.value = PermissionState.DENIED
        location.isLoadingLocation.value = true
        assertEquals(NotesMode.Unavailable, newViewModel().uiState.value.mode)
    }

    @Test
    fun buildingArrives_modeFollows() {
        location.permissionState.value = PermissionState.AUTHORIZED
        location.isLoadingLocation.value = true
        val vm = newViewModel()
        assertEquals(NotesMode.Acquiring, vm.uiState.value.mode)

        withBuilding()

        assertEquals("s0000000", vm.uiState.value.notes().geohash)
    }

    // N2 (Q-N1: the route's unfiltered name is gone)

    @Test
    fun displayName_prefersBuilding_thenNonEmptyBlock_elseNull() {
        assertEquals(
            "Synthetic Building",
            notesDisplayLocationName(mapOf(BUILDING to "Synthetic Building", BLOCK to "Synthetic Block")),
        )
        assertEquals(
            "Synthetic Block",
            notesDisplayLocationName(mapOf(BUILDING to "", BLOCK to "Synthetic Block")),
        )
        assertNull(notesDisplayLocationName(mapOf(BUILDING to "", BLOCK to "")))
        assertNull(notesDisplayLocationName(emptyMap<GeohashChannelLevel, String>()))
    }

    @Test
    fun displayName_followsTheLocationNames() {
        withBuilding()
        val vm = newViewModel()

        location.locationNames.value = mapOf(BUILDING to "", BLOCK to "Synthetic Block")

        assertEquals("Synthetic Block", vm.uiState.value.notes().displayLocationName)
    }

    // N3, Q-N11

    @Test
    fun sendEnabled_needsNonBlankDraft_andAnyStateButNoRelays_QN11() {
        assertFalse(notesSendEnabled("   ", State.READY))
        assertTrue(notesSendEnabled(" hi ", State.IDLE))
        assertTrue(notesSendEnabled("hi", State.LOADING))
        assertTrue(notesSendEnabled("hi", State.READY))
        assertFalse(notesSendEnabled("hi", State.NO_RELAYS))
    }

    @Test
    fun draftChanged_updatesDraftAndSendEnabled() {
        withBuilding()
        val vm = newViewModel()
        assertFalse(vm.uiState.value.sendEnabled)

        vm.onAction(LocationNotesAction.DraftChanged("synthetic note"))

        assertEquals("synthetic note", vm.uiState.value.draft)
        assertTrue(vm.uiState.value.sendEnabled)

        store.state.value = State.NO_RELAYS
        assertFalse(vm.uiState.value.sendEnabled)
    }

    // N4, F3

    @Test
    fun send_trimsAndPassesNickname_thenClearsDraft() {
        withBuilding()
        val vm = newViewModel()
        vm.onAction(LocationNotesAction.DraftChanged("  synthetic note  "))

        vm.onAction(LocationNotesAction.SendClicked)

        assertEquals(listOf("synthetic note" to "alice"), store.sent)
        assertEquals("", vm.uiState.value.draft)
        assertFalse(vm.uiState.value.sendEnabled)
    }

    @Test
    fun send_usesTheCurrentNickname() {
        withBuilding()
        val vm = newViewModel()
        nickname.value = ""
        vm.onAction(LocationNotesAction.DraftChanged("synthetic note"))

        vm.onAction(LocationNotesAction.SendClicked)

        assertEquals(listOf("synthetic note" to ""), store.sent)
    }

    @Test
    fun send_blankDraft_doesNothingAndKeepsDraft() {
        withBuilding()
        val vm = newViewModel()
        vm.onAction(LocationNotesAction.DraftChanged("   "))

        vm.onAction(LocationNotesAction.SendClicked)

        assertEquals(emptyList<Pair<String, String?>>(), store.sent)
        assertEquals("   ", vm.uiState.value.draft)
    }

    @Test
    fun send_noRelays_doesNothingAndKeepsDraft() {
        withBuilding()
        store.state.value = State.NO_RELAYS
        val vm = newViewModel()
        vm.onAction(LocationNotesAction.DraftChanged("synthetic note"))

        vm.onAction(LocationNotesAction.SendClicked)

        assertEquals(emptyList<Pair<String, String?>>(), store.sent)
        assertEquals("synthetic note", vm.uiState.value.draft)
    }

    @Test
    fun sendFails_draftKept() {
        withBuilding()
        store.onSend = {
            store.state.value = State.NO_RELAYS
            store.errorMessage.value = "No relays available"
            NoteSendResult.Failed(NoteSendResult.Reason.NO_RELAYS)
        }
        val vm = newViewModel()
        vm.onAction(LocationNotesAction.DraftChanged("synthetic note"))

        vm.onAction(LocationNotesAction.SendClicked)

        assertEquals(1, store.sent.size)
        assertEquals("synthetic note", vm.uiState.value.draft)
        assertEquals(State.NO_RELAYS, vm.uiState.value.notes().loadState)
    }

    @Test
    fun sendSucceeds_draftCleared() {
        withBuilding()
        val vm = newViewModel()
        vm.onAction(LocationNotesAction.DraftChanged("synthetic note"))

        vm.onAction(LocationNotesAction.SendClicked)

        assertEquals(1, store.sent.size)
        assertEquals("", vm.uiState.value.draft)
    }

    @Test
    fun draftSurvivesNewViewModelInstance_viaSavedStateHandle() {
        withBuilding()
        val handle = SavedStateHandle()
        newViewModel(handle).onAction(LocationNotesAction.DraftChanged("synthetic note"))

        val restored = newViewModel(handle)

        assertEquals("synthetic note", handle.get<String>("notes_draft"))
        assertEquals("synthetic note", restored.uiState.value.draft)
        assertTrue(restored.uiState.value.sendEnabled)
    }

    @Test
    fun sendInFlight_secondSendIgnored_andTextTypedMeanwhileKept() {
        withBuilding()
        val pending = CompletableDeferred<NoteSendResult>()
        store.onSend = { pending.await() }
        val vm = newViewModel()
        vm.onAction(LocationNotesAction.DraftChanged("synthetic note"))

        vm.onAction(LocationNotesAction.SendClicked)
        vm.onAction(LocationNotesAction.SendClicked)
        assertEquals(1, store.sent.size)

        vm.onAction(LocationNotesAction.DraftChanged("synthetic note, more"))
        pending.complete(NoteSendResult.Sent)

        assertEquals("synthetic note, more", vm.uiState.value.draft)
    }

    // N5, N6, N8

    @Test
    fun body_inPriorityOrder() {
        assertEquals(NotesBody.NoRelays, notesBody(State.NO_RELAYS, initialLoadComplete = false, hasNotes = true))
        assertEquals(NotesBody.Loading, notesBody(State.LOADING, initialLoadComplete = false, hasNotes = true))
        assertEquals(NotesBody.Notes, notesBody(State.LOADING, initialLoadComplete = true, hasNotes = true))
        assertEquals(NotesBody.Empty, notesBody(State.LOADING, initialLoadComplete = true, hasNotes = false))
        assertEquals(NotesBody.Empty, notesBody(State.IDLE, initialLoadComplete = false, hasNotes = false))
        assertEquals(NotesBody.Notes, notesBody(State.READY, initialLoadComplete = true, hasNotes = true))
    }

    @Test
    fun errorRow_shownForAnError_exceptInNoRelays() {
        assertTrue(notesErrorShown("Not initialized", State.READY))
        assertFalse(notesErrorShown("No relays available", State.NO_RELAYS))
        assertFalse(notesErrorShown(null, State.READY))
    }

    @Test
    fun relaysUnavailableLine_onlyInNoRelays() {
        assertTrue(notesRelaysUnavailableShown(State.NO_RELAYS))
        State.values().filter { it != State.NO_RELAYS }.forEach {
            assertFalse(notesRelaysUnavailableShown(it))
        }
    }

    @Test
    fun dismissError_clearsTheStoreError() {
        withBuilding()
        store.errorMessage.value = "Not initialized"
        val vm = newViewModel()
        assertEquals("Not initialized", vm.uiState.value.notes().errorMessage)

        vm.onAction(LocationNotesAction.DismissError)

        assertEquals(listOf("clearError"), calls)
        assertNull(vm.uiState.value.notes().errorMessage)
    }

    // N7

    @Test
    fun retry_refreshesTheStore() {
        withBuilding()
        val vm = newViewModel()

        vm.onAction(LocationNotesAction.RetryClicked)

        assertEquals(listOf("refresh"), calls)
    }

    // N9, N13, Q-N10

    @Test
    fun notes_mapToRows_withBaseNameAndCount() {
        withBuilding()
        store.notes.value = listOf(
            note("note-1", nickname = "tester", createdAt = NOW.toInt()),
            note("note-2", nickname = "ali#ce", createdAt = NOW.toInt() - 120),
            note("note-3", nickname = null, createdAt = NOW.toInt() - 7_200),
        )

        val notes = newViewModel().uiState.value.notes().notes

        assertEquals(listOf("note-1", "note-2", "note-3"), notes.map { it.id })
        assertEquals(listOf("tester", "ali", "anon"), notes.map { it.authorBaseName })
        assertEquals(listOf("", "2m ago", "2h ago"), notes.map { it.timestampText })
        assertEquals("synthetic note note-1", notes.first().content)
    }

    @Test
    fun authorBaseName_truncatesAtFirstHash_QN10() {
        assertEquals("tester", noteAuthorBaseName("tester#abcd"))
        assertEquals("ali", noteAuthorBaseName("ali#ce#abcd"))
        assertEquals("", noteAuthorBaseName("#abcd"))
    }

    @Test
    fun inputHeader_showsBaseNameOnlyForNonBlankNickname() {
        assertEquals("alice", notesInputBaseName("alice"))
        assertEquals("ali", notesInputBaseName("ali#ce"))
        assertNull(notesInputBaseName("  "))
        assertNull(notesInputBaseName(null))

        withBuilding()
        val vm = newViewModel()
        assertEquals("alice", vm.uiState.value.authorBaseName)
        nickname.value = " "
        assertNull(vm.uiState.value.authorBaseName)
    }

    // N10, N11

    @Test
    fun notesEntered_refreshesChannelsOnce() {
        withBuilding()
        val vm = newViewModel()

        vm.onAction(LocationNotesAction.NotesEntered)

        assertEquals(listOf("refreshChannels"), calls)
    }

    @Test
    fun screenShownThenHidden_holdsTheGateInOrder() {
        withBuilding()
        val vm = newViewModel()

        vm.onAction(
            LocationNotesAction.ScreenShown("s0000000", locationEnabled = true, locationAuthorized = true)
        )
        vm.onAction(LocationNotesAction.ScreenHidden)

        assertEquals(
            listOf(
                "updateAvailability(true, true, s0000000)",
                "activate",
                "reveal",
                "deactivate",
            ),
            calls,
        )
    }

    @Test
    fun screenShown_passesTheKeyedInputs() {
        withBuilding()
        val vm = newViewModel()

        vm.onAction(
            LocationNotesAction.ScreenShown("s0000001", locationEnabled = false, locationAuthorized = false)
        )

        assertEquals("updateAvailability(false, false, s0000001)", calls.first())
    }

    @Test
    fun uiState_carriesTheGateInputs() {
        withBuilding()
        location.effectiveLocationEnabled.value = true
        location.permissionState.value = PermissionState.AUTHORIZED
        val vm = newViewModel()
        assertTrue(vm.uiState.value.locationEnabled)
        assertTrue(vm.uiState.value.locationAuthorized)

        location.effectiveLocationEnabled.value = false
        location.permissionState.value = PermissionState.DENIED

        assertFalse(vm.uiState.value.locationEnabled)
        assertFalse(vm.uiState.value.locationAuthorized)
    }

    @Test
    fun construction_callsNoPort() {
        withBuilding()
        newViewModel()

        assertEquals(emptyList<String>(), calls)
    }

    // N12, Q-N5, Q-N6

    @Test
    fun enableLocation_enablesServicesThenChannelsThenRefreshes_withoutAPermissionRequest() {
        val vm = newViewModel()

        vm.onAction(LocationNotesAction.EnableLocationClicked)

        assertEquals(listOf("enableLocationServices", "enableLocationChannels", "refreshChannels"), calls)
    }

    @Test
    fun back_sendsClose() = runTest {
        val vm = newViewModel()

        vm.onAction(LocationNotesAction.BackClicked)

        assertEquals(LocationNotesEvent.Close, vm.events.first())
    }

    private fun note(id: String, nickname: String?, createdAt: Int) = LocationNotesManager.Note(
        id = id,
        pubkey = "0".repeat(60) + "abcd",
        content = "synthetic note $id",
        createdAt = createdAt,
        nickname = nickname,
    )

    private class FakeLocation(private val calls: MutableList<String>) : NotesLocationSource {
        override val permissionState = MutableStateFlow(PermissionState.AUTHORIZED)
        override val effectiveLocationEnabled = MutableStateFlow(true)
        override val availableChannels = MutableStateFlow<List<GeohashChannel>>(emptyList())
        override val isLoadingLocation = MutableStateFlow(false)
        override val locationNames = MutableStateFlow(
            mapOf(BUILDING to "Synthetic Building", BLOCK to "Synthetic Block")
        )

        override fun refreshChannels() { calls += "refreshChannels" }
        override fun enableLocationServices() { calls += "enableLocationServices" }
        override fun enableLocationChannels() { calls += "enableLocationChannels" }
    }

    private class FakeStore(private val calls: MutableList<String>) : NotesStore {
        override val notes = MutableStateFlow<List<LocationNotesManager.Note>>(emptyList())
        override val state = MutableStateFlow(State.READY)
        override val errorMessage = MutableStateFlow<String?>(null)
        override val initialLoadComplete = MutableStateFlow(true)
        val sent = mutableListOf<Pair<String, String?>>()
        var onSend: suspend () -> NoteSendResult = { NoteSendResult.Sent }

        override suspend fun send(content: String, nickname: String?): NoteSendResult {
            sent += content to nickname
            return onSend()
        }

        override fun refresh() { calls += "refresh" }

        override fun clearError() {
            calls += "clearError"
            errorMessage.value = null
        }
    }

    private class RecordingGate(private val calls: MutableList<String>) : NearbyNotesGate {
        override fun updateAvailability(enabled: Boolean, authorized: Boolean, geohash: String?) {
            calls += "updateAvailability($enabled, $authorized, $geohash)"
        }
        override fun activate() { calls += "activate" }
        override fun deactivate() { calls += "deactivate" }
        override fun reveal() { calls += "reveal" }
    }

    private companion object {
        const val NOW = 1_700_000_000L
    }
}
