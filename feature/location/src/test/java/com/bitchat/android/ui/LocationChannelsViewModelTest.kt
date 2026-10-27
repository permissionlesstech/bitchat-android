package com.bitchat.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
import com.bitchat.android.geohash.GeohashChannelLevel.BUILDING
import com.bitchat.android.geohash.GeohashChannelLevel.CITY
import com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
import com.bitchat.android.geohash.LocationChannelManager.PermissionState
import com.bitchat.android.net.TorMode
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NoteSendResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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
 * Pins LocationChannelsViewModel's actions (inventory b2-b17), the sampling it runs (L17) and the
 * calls the Root's lifecycle effects make through it (B5), against recording ports. Synthetic
 * geohashes only; nothing touches location services.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationChannelsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val calls = mutableListOf<String>()
    private val location = FakeLocation(calls)
    private val notes = FakeNotes()
    private val gate = RecordingGate(calls)
    private val bookmarks = FakeBookmarks(calls)
    private val tor = FakeTor(calls)
    private val session = FakeSession(calls)

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel() =
        LocationChannelsViewModel(location, notes, gate, bookmarks, tor, session, dispatcher)

    private fun TestScope.eventsOf(viewModel: LocationChannelsViewModel): List<LocationChannelsEvent> {
        val events = mutableListOf<LocationChannelsEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        return events
    }

    private fun TestScope.collectState(viewModel: LocationChannelsViewModel) {
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
    }

    /** Calls made by actions, without the sampling the ViewModel starts on its own. */
    private fun actionCalls() = calls.filterNot { it.startsWith("beginGeohashSampling") }

    // b2

    @Test
    fun meshClicked_selectsMeshAndCloses() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(LocationChannelsAction.MeshClicked)

        assertEquals(listOf("select(Mesh)"), actionCalls())
        assertEquals(listOf<LocationChannelsEvent>(LocationChannelsEvent.Close), events)
    }

    // b3

    @Test
    fun teleportedRowClicked_selectsItsLocationChannelAndCloses() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)
        val channel = GeohashChannel(NEIGHBORHOOD, "s00000")

        viewModel.onAction(LocationChannelsAction.ChannelClicked(ChannelRowId.Teleported(channel)))

        assertEquals(listOf("select(Location(s00000))"), actionCalls())
        assertEquals(listOf<LocationChannelsEvent>(LocationChannelsEvent.Close), events)
    }

    // b5, L8

    @Test
    fun bookmarkRowClicked_selectsManualWithTheTeleportedFlagAndCloses() = runTest(dispatcher) {
        location.availableChannels.value = listOf(GeohashChannel(BLOCK, "s000000"))
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(
            LocationChannelsAction.ChannelClicked(ChannelRowId.Bookmark(GeohashChannel(BLOCK, "s000000")))
        )
        location.locationServicesEnabled.value = false
        viewModel.onAction(
            LocationChannelsAction.ChannelClicked(ChannelRowId.Bookmark(GeohashChannel(BLOCK, "s000000")))
        )

        assertEquals(
            listOf("selectManual(s000000, teleported=false)", "selectManual(s000000, teleported=true)"),
            actionCalls(),
        )
        assertEquals(List(2) { LocationChannelsEvent.Close }, events)
    }

    // b7

    @Test
    fun nearbyRowClicked_closesOnlyWhenTheManagerSelectsIt() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)
        val channel = GeohashChannel(CITY, "s0000")

        location.selectNearbyResult = false
        viewModel.onAction(LocationChannelsAction.ChannelClicked(ChannelRowId.Nearby(channel)))
        assertTrue(events.isEmpty())

        location.selectNearbyResult = true
        viewModel.onAction(LocationChannelsAction.ChannelClicked(ChannelRowId.Nearby(channel)))

        assertEquals(listOf("selectNearby(s0000)", "selectNearby(s0000)"), actionCalls())
        assertEquals(listOf<LocationChannelsEvent>(LocationChannelsEvent.Close), events)
    }

    // b4, b6, b8

    @Test
    fun bookmarkToggled_togglesTheStore() {
        newViewModel().onAction(LocationChannelsAction.BookmarkToggled("s00000"))

        assertEquals(listOf("toggle(s00000)"), actionCalls())
    }

    // B5: the per-bookmark name lookup the bookmark row starts.

    @Test
    fun bookmarkShown_resolvesItsName() {
        newViewModel().onAction(LocationChannelsAction.BookmarkShown("s00000"))

        assertEquals(listOf("resolveNameIfNeeded(s00000)"), actionCalls())
    }

    // b15, L13

    @Test
    fun teleportClicked_validGeohash_selectsManualTeleportedAndCloses() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(LocationChannelsAction.TeleportClicked("s0000"))

        assertEquals(listOf("selectManual(s0000, teleported=true)"), actionCalls())
        assertEquals(listOf<LocationChannelsEvent>(LocationChannelsEvent.Close), events)
    }

    // b14, L13, Q-L2; Review Focus 4. The Root rewrites the field and shows the error.

    @Test
    fun customGeohash_invalid_rewritesFieldAndShowsError() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(LocationChannelsAction.CustomGeohashSubmitted(" #Not-A-Geohash "))

        assertTrue(actionCalls().isEmpty())
        assertEquals(
            listOf<LocationChannelsEvent>(LocationChannelsEvent.CustomGeohashRejected("not-a-geohash")),
            events,
        )
    }

    @Test
    fun customGeohashSubmitted_valid_selectsManualTeleportedAndCloses() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(LocationChannelsAction.CustomGeohashSubmitted("#S0000000"))

        assertEquals(listOf("selectManual(s0000000, teleported=true)"), actionCalls())
        assertEquals(listOf<LocationChannelsEvent>(LocationChannelsEvent.Close), events)
    }

    // b16

    @Test
    fun notesClicked_refreshesRevealsThenShowsNotes() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(LocationChannelsAction.NotesClicked)

        assertEquals(listOf("refreshChannels", "reveal"), actionCalls())
        assertEquals(listOf<LocationChannelsEvent>(LocationChannelsEvent.ShowNotes), events)
    }

    // b17

    @Test
    fun torToggled_writesTheMode() = runTest(dispatcher) {
        val viewModel = newViewModel()
        collectState(viewModel)

        viewModel.onAction(LocationChannelsAction.TorToggled(false))
        assertFalse(viewModel.uiState.value.tor.enabled)
        viewModel.onAction(LocationChannelsAction.TorToggled(true))

        assertEquals(listOf("setMode(OFF)", "setMode(ON)"), actionCalls())
        assertTrue(viewModel.uiState.value.tor.enabled)
    }

    @Test
    fun torToggled_whenTorIsUnavailable_writesNothing() = runTest(dispatcher) {
        tor.available = false
        val viewModel = newViewModel()

        viewModel.onAction(LocationChannelsAction.TorToggled(false))

        assertTrue(actionCalls().isEmpty())
        assertFalse(viewModel.uiState.value.tor.available)
    }

    /**
     * R2: the row used to read the stored mode once (Q-L11), so a change made elsewhere (About)
     * did not show until the row was rebuilt. It now follows the mode as it changes.
     */
    @Test
    fun torModeChangedExternally_rowFollows() = runTest(dispatcher) {
        val viewModel = newViewModel()
        collectState(viewModel)
        assertTrue(viewModel.uiState.value.tor.enabled)

        tor.mode.value = TorMode.OFF

        assertFalse(viewModel.uiState.value.tor.enabled)
    }

    // b10, b11

    @Test
    fun enableAndDisableLocation_driveTheAppToggle() {
        val viewModel = newViewModel()

        viewModel.onAction(LocationChannelsAction.EnableLocationClicked)
        viewModel.onAction(LocationChannelsAction.DisableLocationClicked)

        assertEquals(listOf("enableLocationServices", "disableLocationServices"), actionCalls())
    }

    // b9

    @Test
    fun openSettingsClicked_opensAppSettings() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(LocationChannelsAction.OpenSettingsClicked)

        assertEquals(listOf<LocationChannelsEvent>(LocationChannelsEvent.OpenAppSettings), events)
        assertTrue(actionCalls().isEmpty())
    }

    // b13, L14

    @Test
    fun mapPickerClicked_opensThePickerAtTheTypedThenTheSelectedGeohash() = runTest(dispatcher) {
        location.selectedChannel.value = ChannelID.Location(GeohashChannel(BLOCK, "s000000"))
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(LocationChannelsAction.MapPickerClicked("s0000"))
        viewModel.onAction(LocationChannelsAction.MapPickerClicked(""))
        location.selectedChannel.value = ChannelID.Mesh
        viewModel.onAction(LocationChannelsAction.MapPickerClicked(""))

        assertEquals(
            listOf(
                LocationChannelsEvent.OpenMapPicker("s0000"),
                LocationChannelsEvent.OpenMapPicker("s000000"),
                LocationChannelsEvent.OpenMapPicker(""),
            ),
            events,
        )
    }

    // b1

    @Test
    fun backClicked_closes() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(LocationChannelsAction.BackClicked)

        assertEquals(listOf<LocationChannelsEvent>(LocationChannelsEvent.Close), events)
    }

    // B5: the Root's LifecycleResumeEffect and nearby-notes DisposableEffect, in order.

    @Test
    fun rootEffects_callTheManagersInTheSameOrder() {
        location.availableChannels.value = listOf(
            GeohashChannel(BLOCK, "s000000"),
            GeohashChannel(BUILDING, "s0000000"),
        )
        val viewModel = newViewModel()
        calls.clear()

        viewModel.onAction(LocationChannelsAction.ScreenResumed)
        viewModel.onAction(LocationChannelsAction.NotesHoldShown)
        viewModel.onAction(LocationChannelsAction.ScreenPaused)
        viewModel.onAction(LocationChannelsAction.NotesHoldHidden)

        assertEquals(
            listOf(
                "syncPermissionState",
                "enableLocationChannels",
                "beginLiveRefresh",
                "updateAvailability(true, true, s0000000)",
                "activate",
                "endLiveRefresh",
                "deactivate",
            ),
            calls,
        )
    }

    @Test
    fun screenResumed_refreshesOnlyWhenAppSystemAndPermissionAllowIt() {
        val viewModel = newViewModel()

        location.locationServicesEnabled.value = false
        viewModel.onAction(LocationChannelsAction.ScreenResumed)
        location.locationServicesEnabled.value = true
        location.systemLocationEnabled.value = false
        viewModel.onAction(LocationChannelsAction.ScreenResumed)
        location.systemLocationEnabled.value = true
        location.syncedPermission = PermissionState.DENIED
        viewModel.onAction(LocationChannelsAction.ScreenResumed)

        assertEquals(List(3) { "syncPermissionState" }, actionCalls())
    }

    @Test
    fun notesHoldShown_passesEffectiveLocationAndPermission() {
        location.effectiveLocationEnabled.value = false
        location.permissionState.value = PermissionState.DENIED
        val viewModel = newViewModel()

        viewModel.onAction(LocationChannelsAction.NotesHoldShown)

        assertEquals(listOf("updateAvailability(false, false, null)", "activate"), actionCalls())
    }

    // L17, Q-L13: sampling runs for the ViewModel's life, not the screen's.

    @Test
    fun sampling_startsOnCreation_followsItsSources_andEndsOnlyWhenCleared() {
        location.availableChannels.value = listOf(
            GeohashChannel(BLOCK, "s000000"),
            GeohashChannel(BUILDING, "s0000000"),
        )
        bookmarks.bookmarks.value = listOf("s00000")
        val store = ViewModelStore()
        val viewModel = ViewModelProvider(
            store,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = newViewModel() as T
            },
        )[LocationChannelsViewModel::class.java]

        gate.revealed.value = true
        // The app toggle is not read by the sets, but still re-samples (Q-L13).
        location.locationServicesEnabled.value = false
        viewModel.onAction(LocationChannelsAction.ScreenPaused)
        store.clear()
        bookmarks.bookmarks.value = emptyList()

        assertEquals(
            listOf(
                "beginGeohashSampling([s000000], [s00000])",
                "beginGeohashSampling([s000000, s0000000], [s00000])",
                "beginGeohashSampling([s000000, s0000000], [s00000])",
                "endLiveRefresh",
                "endGeohashSampling",
            ),
            calls,
        )
    }

    // B11

    @Test
    fun uiState_isBuiltFromTheSources() = runTest(dispatcher) {
        val selected = GeohashChannel(NEIGHBORHOOD, "s00000")
        location.availableChannels.value = listOf(
            GeohashChannel(CITY, "s0000"),
            GeohashChannel(BUILDING, "s0000000"),
        )
        location.selectedChannel.value = ChannelID.Location(selected)
        location.permissionState.value = PermissionState.DENIED
        bookmarks.bookmarks.value = listOf("s00000")
        bookmarks.stored += "s00000"
        bookmarks.bookmarkNames.value = mapOf("s00000" to "Synthetic Block")
        session.participantCounts.value = mapOf("s00000" to 2)
        session.connectedPeers.value = listOf(MY_PEER_ID, PEER_ID)
        session.wifiAwareEnabled.value = true
        gate.revealed.value = true
        notes.notes.value = listOf(note("1"), note("2"))
        val viewModel = newViewModel()
        collectState(viewModel)

        val state = viewModel.uiState.value

        assertEquals(1, state.mesh.peopleCount)
        assertTrue(state.mesh.wifiAwareEnabled)
        assertFalse(state.mesh.isSelected)
        assertEquals("s00000", state.teleported!!.geohash)
        assertTrue(state.teleported!!.isBookmarked)
        assertEquals(2, state.teleported!!.participantCount)
        assertEquals(listOf("s00000"), state.bookmarks.map { it.geohash })
        assertEquals(listOf("s0000"), state.nearby.rows.map { it.channel.geohash })
        assertTrue(state.showPermissionDenied)
        assertTrue(state.appLocationEnabled)
        assertTrue(state.effectiveLocationEnabled)
        assertEquals(NotesSubtitle.Many(2), state.notesSubtitle)
        assertEquals(TorUi(enabled = true, available = true), state.tor)
        assertTrue(state.systemLocationEnabled)
        assertEquals(PermissionState.DENIED, state.permissionState)
        assertEquals("s0000000", state.buildingGeohash)
    }

    @Test
    fun uiState_followsSourceChanges() = runTest(dispatcher) {
        val viewModel = newViewModel()
        collectState(viewModel)
        assertNull(viewModel.uiState.value.buildingGeohash)
        assertTrue(viewModel.uiState.value.mesh.isSelected)

        location.availableChannels.value = listOf(GeohashChannel(BUILDING, "s0000000"))
        location.selectedChannel.value = ChannelID.Location(GeohashChannel(CITY, "s0000"))
        notes.state.value = LocationNotesManager.State.NO_RELAYS

        val state = viewModel.uiState.value
        assertEquals("s0000000", state.buildingGeohash)
        assertFalse(state.mesh.isSelected)
        assertEquals(NotesSubtitle.Reveal, state.notesSubtitle)
        gate.revealed.value = true
        assertEquals(NotesSubtitle.NoRelays, viewModel.uiState.value.notesSubtitle)
    }

    private fun note(id: String) = LocationNotesManager.Note(
        id = id,
        pubkey = "0".repeat(60) + "abcd",
        content = "synthetic note $id",
        createdAt = 1_700_000_000,
        nickname = "alice",
    )

    private class FakeLocation(private val calls: MutableList<String>) : NotesLocationSource {
        override val permissionState = MutableStateFlow(PermissionState.AUTHORIZED)
        override val effectiveLocationEnabled = MutableStateFlow(true)
        override val availableChannels = MutableStateFlow<List<GeohashChannel>>(emptyList())
        override val isLoadingLocation = MutableStateFlow(false)
        override val locationNames = MutableStateFlow(
            mapOf(CITY to "Synthetic City", BLOCK to "Synthetic Block")
        )
        override val selectedChannel = MutableStateFlow<ChannelID>(ChannelID.Mesh)
        override val locationServicesEnabled = MutableStateFlow(true)
        override val systemLocationEnabled = MutableStateFlow(true)
        var syncedPermission = PermissionState.AUTHORIZED
        var selectNearbyResult = true

        override fun refreshChannels() { calls += "refreshChannels" }
        override fun enableLocationServices() { calls += "enableLocationServices" }
        override fun enableLocationChannels() { calls += "enableLocationChannels" }
        override fun disableLocationServices() { calls += "disableLocationServices" }
        override fun syncPermissionState(): PermissionState {
            calls += "syncPermissionState"
            return syncedPermission
        }
        override fun beginLiveRefresh() { calls += "beginLiveRefresh" }
        override fun endLiveRefresh() { calls += "endLiveRefresh" }
        override fun select(channel: ChannelID) {
            calls += when (channel) {
                is ChannelID.Mesh -> "select(Mesh)"
                is ChannelID.Location -> "select(Location(${channel.channel.geohash}))"
            }
        }
        override fun selectNearby(channel: GeohashChannel): Boolean {
            calls += "selectNearby(${channel.geohash})"
            return selectNearbyResult
        }
        override fun selectManual(channel: GeohashChannel, teleported: Boolean) {
            calls += "selectManual(${channel.geohash}, teleported=$teleported)"
        }
    }

    private class FakeNotes : NotesStore {
        override val notes = MutableStateFlow<List<LocationNotesManager.Note>>(emptyList())
        override val state = MutableStateFlow(LocationNotesManager.State.READY)
        override val errorMessage = MutableStateFlow<String?>(null)
        override val initialLoadComplete = MutableStateFlow(true)
        override suspend fun send(content: String, nickname: String?) = NoteSendResult.Sent
        override fun refresh() = Unit
        override fun clearError() = Unit
    }

    private class RecordingGate(private val calls: MutableList<String>) : NearbyNotesGate {
        override val revealed = MutableStateFlow(false)
        override fun updateAvailability(enabled: Boolean, authorized: Boolean, geohash: String?) {
            calls += "updateAvailability($enabled, $authorized, $geohash)"
        }
        override fun activate() { calls += "activate" }
        override fun deactivate() { calls += "deactivate" }
        override fun reveal() { calls += "reveal" }
    }

    private class FakeBookmarks(private val calls: MutableList<String>) : ChannelBookmarks {
        override val bookmarks = MutableStateFlow<List<String>>(emptyList())
        override val bookmarkNames = MutableStateFlow<Map<String, String>>(emptyMap())
        val stored = mutableSetOf<String>()
        override fun isBookmarked(geohash: String) = geohash in stored
        override fun toggle(geohash: String) { calls += "toggle($geohash)" }
        override fun resolveNameIfNeeded(geohash: String) { calls += "resolveNameIfNeeded($geohash)" }
    }

    private class FakeTor(private val calls: MutableList<String>) : TorRouting {
        override val mode = MutableStateFlow(TorMode.ON)
        var available = true
        override fun isTorAvailable() = available
        override fun setMode(mode: TorMode) {
            calls += "setMode($mode)"
            this.mode.value = mode
        }
    }

    private class FakeSession(private val calls: MutableList<String>) : ChannelsSession {
        override val participantCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
        override val wifiAwareEnabled = MutableStateFlow(false)
        override val connectedPeers = MutableStateFlow<List<String>>(emptyList())
        override val myPeerID = MY_PEER_ID
        override fun beginGeohashSampling(live: Collection<String>, userSelected: Collection<String>) {
            calls += "beginGeohashSampling($live, $userSelected)"
        }
        override fun endGeohashSampling() { calls += "endGeohashSampling" }
    }

    private companion object {
        const val MY_PEER_ID = "0123456789abcdef"
        const val PEER_ID = "fedcba9876543210"
    }
}
