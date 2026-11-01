package com.bitchat.android.ui

import androidx.lifecycle.SavedStateHandle
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.ConversationStoreState
import com.bitchat.android.services.DeletedPrivateConversation
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pins MeshPeerListViewModel's actions (inventory A4) and the state it builds from its sources,
 * against fake ports. Synthetic peers and geohashes only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshPeerListViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val sources = FakeSources()
    private val commands = FakeCommands()
    private val lookups = FakePeerListLookups()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun newViewModel(savedState: SavedStateHandle = SavedStateHandle()) =
        MeshPeerListViewModel(
            sources.ports(), commands, lookups, savedState, dispatcher,
            clock = { dispatcher.scheduler.currentTime },
        )

    private fun TestScope.eventsOf(viewModel: MeshPeerListViewModel): List<MeshPeerListEvent> {
        val events = mutableListOf<MeshPeerListEvent>()
        backgroundScope.launch(dispatcher) { viewModel.events.collect { events += it } }
        return events
    }

    private fun TestScope.collectState(viewModel: MeshPeerListViewModel) {
        backgroundScope.launch(dispatcher) { viewModel.uiState.collect {} }
    }

    // a3

    @Test
    fun queryChanged_storesTextUnderPeerListQuery_andFiltersConversations() = runTest(dispatcher) {
        sources.conversations.value = listOf(conversation(BOB_ID, "bob"), conversation(CAROL_ID, "carol"))
        val savedState = SavedStateHandle()
        val viewModel = newViewModel(savedState)
        collectState(viewModel)

        viewModel.onAction(MeshPeerListAction.QueryChanged("car"))

        assertEquals("car", savedState.get<String>("peer_list_query"))
        val state = viewModel.uiState.value
        assertEquals("car", state.query)
        assertEquals(listOf(CAROL_ID), state.conversations.offline.map { it.conversationID })
    }

    @Test
    fun querySurvivesNewViewModel_viaSavedStateHandle() = runTest(dispatcher) {
        sources.conversations.value = listOf(conversation(BOB_ID, "bob"), conversation(CAROL_ID, "carol"))
        val savedState = SavedStateHandle()
        newViewModel(savedState).onAction(MeshPeerListAction.QueryChanged("bob"))

        val recreated = newViewModel(savedState)

        assertEquals("bob", recreated.uiState.value.query)
        assertEquals(listOf(BOB_ID), recreated.uiState.value.conversations.offline.map { it.conversationID })
    }

    // a4, a13, a14

    @Test
    fun conversationClicked_opensCanonicalPrivateChat() = runTest(dispatcher) {
        lookups.canonical = mapOf(BOB_ID to "contact_bob")
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(MeshPeerListAction.ConversationClicked(BOB_ID))

        assertEquals(listOf(MeshPeerListEvent.OpenPrivateChat("contact_bob")), events)
    }

    @Test
    fun personClicked_opensCanonicalPrivateChat() = runTest(dispatcher) {
        lookups.canonical = mapOf(ALICE_ID to "contact_alice")
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(MeshPeerListAction.PersonClicked(ALICE_ID))

        assertEquals(listOf(MeshPeerListEvent.OpenPrivateChat("contact_alice")), events)
    }

    // a5, a7, a8

    @Test
    fun swipeToggleRead_setsTheReadStateItCarries() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onAction(MeshPeerListAction.SwipeToggleRead(BOB_ID, markRead = true))
        viewModel.onAction(MeshPeerListAction.SwipeToggleRead(CAROL_ID, markRead = false))

        assertEquals(listOf("setRead $BOB_ID true", "setRead $CAROL_ID false"), commands.calls)
    }

    @Test
    fun togglePinnedAndMuted_forwardToTheDirectory() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onAction(MeshPeerListAction.TogglePinned(BOB_ID))
        viewModel.onAction(MeshPeerListAction.ToggleMuted(BOB_ID))

        assertEquals(listOf("togglePinned $BOB_ID", "toggleMuted $BOB_ID"), commands.calls)
    }

    // a11

    @Test
    fun channelClicked_switchesToItAndCloses() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(MeshPeerListAction.ChannelClicked("#synthetic"))

        assertEquals(listOf("switchToChannel #synthetic"), commands.calls)
        assertEquals(listOf(MeshPeerListEvent.Close), events)
    }

    @Test
    fun channelAtName_opensThatPeersChat_andStays() = runTest(dispatcher) {
        sources.peerNicknames.value = mapOf(BOB_ID to "bob", ALICE_ID to "alice")
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(MeshPeerListAction.ChannelClicked("@alice"))

        assertEquals(listOf(MeshPeerListEvent.OpenPrivateChat(ALICE_ID)), events)
        assertTrue(commands.calls.isEmpty())
    }

    @Test
    fun channelAtNameWithNoMatch_doesNothing_currentBehaviour_QP9() = runTest(dispatcher) {
        sources.peerNicknames.value = mapOf(BOB_ID to "bob")
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(MeshPeerListAction.ChannelClicked("@alice"))

        assertTrue(events.isEmpty())
        assertTrue(commands.calls.isEmpty())
    }

    // a12

    @Test
    fun leaveChannel_broadcastsLeft_currentBehaviour_QP10() = runTest(dispatcher) {
        val viewModel = newViewModel()

        viewModel.onAction(MeshPeerListAction.LeaveChannel("#synthetic"))

        assertEquals(
            listOf("leaveChannel #synthetic", "sendMeshMessage left #synthetic"),
            commands.calls
        )
    }

    // a15

    @Test
    fun geohashPersonClicked_startsGeohashDm_andOpensItsChat() = runTest(dispatcher) {
        val pubkey = "ab".repeat(32)
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(MeshPeerListAction.GeohashPersonClicked(pubkey))

        assertEquals(listOf("startGeohashDM $pubkey"), commands.calls)
        assertEquals(listOf(MeshPeerListEvent.OpenPrivateChat("nostr_${pubkey.take(16)}")), events)
    }

    // a1, a2

    @Test
    fun verifyClicked_showsVerification() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(MeshPeerListAction.VerifyClicked)

        assertEquals(listOf(MeshPeerListEvent.ShowVerification), events)
    }

    @Test
    fun backClicked_closes() = runTest(dispatcher) {
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)

        viewModel.onAction(MeshPeerListAction.BackClicked)

        assertEquals(listOf(MeshPeerListEvent.Close), events)
    }

    // a9, F2

    @Test
    fun deleteThenNewCollector_undoStillRestores() = runTest(dispatcher) {
        val deletion = deletion(BOB_ID)
        commands.deleteResult = deletion
        val deleteFinishes = CompletableDeferred<Unit>()
        commands.deleteGate = deleteFinishes
        val viewModel = newViewModel()
        // The Root's collection; rotating or opening a chat cancels it.
        val root = launch(dispatcher) { viewModel.uiState.collect {} }

        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))
        // Leave mid-delete, and stay away past WhileSubscribed's 5 s, so nothing collects.
        root.cancel()
        advanceTimeBy(5_001)
        deleteFinishes.complete(Unit)
        collectState(viewModel)

        assertEquals(listOf("delete $BOB_ID"), commands.calls)
        val undo = viewModel.uiState.value.pendingUndos.single()
        assertEquals("bob", undo.displayName)
        viewModel.onAction(MeshPeerListAction.UndoDelete(undo.token))

        assertEquals(listOf(deletion), commands.restored)
        assertTrue(viewModel.uiState.value.pendingUndos.isEmpty())
    }

    @Test
    fun undoAfterWindowExpires_doesNotRestore_andClearsPending() = runTest(dispatcher) {
        commands.deleteResult = deletion(BOB_ID)
        val viewModel = newViewModel()
        collectState(viewModel)

        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))
        val token = viewModel.uiState.value.pendingUndos.single().token
        advanceTimeBy(MeshPeerListViewModel.UNDO_WINDOW_MS + 1)

        assertTrue(viewModel.uiState.value.pendingUndos.isEmpty())
        viewModel.onAction(MeshPeerListAction.UndoDelete(token))
        assertEquals(listOf("delete $BOB_ID"), commands.calls)
        assertTrue(commands.restored.isEmpty())
    }

    @Test
    fun undoJustBeforeWindowEnds_stillRestores() = runTest(dispatcher) {
        val deletion = deletion(BOB_ID)
        commands.deleteResult = deletion
        val viewModel = newViewModel()
        collectState(viewModel)

        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))
        advanceTimeBy(MeshPeerListViewModel.UNDO_WINDOW_MS - 1)
        val undo = viewModel.uiState.value.pendingUndos.single()
        viewModel.onAction(MeshPeerListAction.UndoDelete(undo.token))

        assertEquals(listOf(deletion), commands.restored)
    }

    @Test
    fun deleteFails_sendsDeleteFailed_andOffersNoUndo() = runTest(dispatcher) {
        commands.deleteResult = null
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)
        collectState(viewModel)

        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))

        assertEquals(listOf("delete $BOB_ID"), commands.calls)
        assertEquals(listOf(MeshPeerListEvent.DeleteFailed), events)
        assertTrue(viewModel.uiState.value.pendingUndos.isEmpty())
    }

    @Test
    fun restoreFails_sendsRestoreFailed() = runTest(dispatcher) {
        commands.deleteResult = deletion(BOB_ID)
        commands.restoreResult = false
        val viewModel = newViewModel()
        val events = eventsOf(viewModel)
        collectState(viewModel)

        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))
        viewModel.onAction(MeshPeerListAction.UndoDelete(viewModel.uiState.value.pendingUndos.single().token))

        assertEquals(listOf("delete $BOB_ID", "restore $BOB_ID"), commands.calls)
        assertEquals(listOf(MeshPeerListEvent.RestoreFailed), events)
    }

    @Test
    fun undoDismissed_forgetsTheDeletion_andUndoNoLongerRestores() = runTest(dispatcher) {
        commands.deleteResult = deletion(BOB_ID)
        val viewModel = newViewModel()
        collectState(viewModel)

        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))
        val token = viewModel.uiState.value.pendingUndos.single().token
        viewModel.onAction(MeshPeerListAction.UndoDismissed(token))
        viewModel.onAction(MeshPeerListAction.UndoDelete(token))

        assertTrue(viewModel.uiState.value.pendingUndos.isEmpty())
        assertEquals(listOf("delete $BOB_ID"), commands.calls)
    }

    @Test
    fun queuedUndo_getsItsFullWindowFromWhenItIsOffered() = runTest(dispatcher) {
        val viewModel = newViewModel()
        collectState(viewModel)
        commands.deleteResult = deletion(BOB_ID)
        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))
        val carolDeletion = deletion(CAROL_ID)
        commands.deleteResult = carolDeletion
        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(CAROL_ID, "carol"))
        val (bob, carol) = viewModel.uiState.value.pendingUndos

        // Bob's snackbar is answered late; carol's is offered only then.
        advanceTimeBy(MeshPeerListViewModel.UNDO_WINDOW_MS - 1_000)
        viewModel.onAction(MeshPeerListAction.UndoDismissed(bob.token))
        advanceTimeBy(MeshPeerListViewModel.UNDO_WINDOW_MS - 1)

        assertEquals(listOf(carol), viewModel.uiState.value.pendingUndos)
        viewModel.onAction(MeshPeerListAction.UndoDelete(carol.token))
        assertEquals(listOf(carolDeletion), commands.restored)
    }

    @Test
    fun queuedUndo_expiresAFullWindowAfterItIsOffered() = runTest(dispatcher) {
        val viewModel = newViewModel()
        collectState(viewModel)
        commands.deleteResult = deletion(BOB_ID)
        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))
        commands.deleteResult = deletion(CAROL_ID)
        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(CAROL_ID, "carol"))
        val (bob, carol) = viewModel.uiState.value.pendingUndos

        advanceTimeBy(30_000)
        viewModel.onAction(MeshPeerListAction.UndoDismissed(bob.token))
        advanceTimeBy(MeshPeerListViewModel.UNDO_WINDOW_MS + 1)

        assertTrue(viewModel.uiState.value.pendingUndos.isEmpty())
        viewModel.onAction(MeshPeerListAction.UndoDelete(carol.token))
        assertTrue(commands.restored.isEmpty())
    }

    @Test
    fun twoDeletes_areOfferedInOrder() = runTest(dispatcher) {
        val viewModel = newViewModel()
        collectState(viewModel)

        commands.deleteResult = deletion(BOB_ID)
        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(BOB_ID, "bob"))
        commands.deleteResult = deletion(CAROL_ID)
        viewModel.onAction(MeshPeerListAction.DeleteConfirmed(CAROL_ID, "carol"))

        assertEquals(listOf("bob", "carol"), viewModel.uiState.value.pendingUndos.map { it.displayName })
    }

    // State

    @Test
    fun meshMode_countsVisiblePeersButSelf_andHidesConversationPeers() = runTest(dispatcher) {
        commands.myPeerID = ME_ID
        sources.connectedPeers.value = listOf(ALICE_ID, BOB_ID, ME_ID)
        sources.peerNicknames.value = mapOf(ALICE_ID to "alice", BOB_ID to "bob")
        sources.conversations.value = listOf(conversation(BOB_ID, "bob"))

        val people = newViewModel().uiState.value.people as PeopleUi.Mesh

        assertEquals(1, people.count)
        assertFalse(people.noneConnected)
        assertEquals(listOf(ALICE_ID, ME_ID), people.rows.map { it.peerID }.sorted())
    }

    @Test
    fun meshMode_noVisiblePeer_saysNoneConnected() = runTest(dispatcher) {
        val state = newViewModel().uiState.value

        val people = state.people as PeopleUi.Mesh
        assertTrue(people.noneConnected)
        assertEquals(0, people.count)
        assertTrue(state.showVerifyAction)
        assertFalse(state.hasEarlierSections)
    }

    @Test
    fun locationChannel_showsGeohashPeople_andHidesVerify_QP19() = runTest(dispatcher) {
        lookups.geohashIdentityHex = "aa".repeat(32)
        sources.nickname.value = "alice"
        sources.selectedLocationChannel.value =
            ChannelID.Location(GeohashChannel(GeohashChannelLevel.BUILDING, "s0000000"))
        sources.geohashPeople.value = listOf(GeoPerson("bb".repeat(32), "bob", Date(PEER_LIST_NOW_MILLIS)))

        val state = newViewModel().uiState.value

        val people = state.people as PeopleUi.Geohash
        assertEquals(2, people.count)
        assertEquals(listOf("alice", "bob"), people.people.onLocation.map { it.displayName })
        assertFalse(state.showVerifyAction)
    }

    @Test
    fun stateFollowsSources_whileCollected() = runTest(dispatcher) {
        sources.peerNicknames.value = mapOf(ALICE_ID to "alice")
        val viewModel = newViewModel()
        collectState(viewModel)

        sources.connectedPeers.value = listOf(ALICE_ID)
        sources.joinedChannels.value = setOf("#synthetic")

        val state = viewModel.uiState.value
        assertEquals(listOf(ALICE_ID), (state.people as PeopleUi.Mesh).rows.map { it.peerID })
        assertEquals(listOf(ChannelRowUi("#synthetic", isSelected = false, unread = 0)), state.channels)
        assertTrue(state.hasEarlierSections)
        assertFalse(state.hasConversations)
    }

    private class FakeSources {
        val connectedPeers = MutableStateFlow<List<String>>(emptyList())
        val peerNicknames = MutableStateFlow<Map<String, String>>(emptyMap())
        val peerDirect = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        val wifiAwareConnected = MutableStateFlow<Map<String, *>>(emptyMap<String, Any>())
        val selectedPrivateChatPeer = MutableStateFlow<String?>(null)
        val nickname = MutableStateFlow("")
        val favoritePeers = MutableStateFlow<Set<String>>(emptySet())
        val peerFavoritedUs = MutableStateFlow<Set<String>>(emptySet())
        val peerFingerprints = MutableStateFlow<Map<String, String>>(emptyMap())
        val verifiedFingerprints = MutableStateFlow<Set<String>>(emptySet())
        val unreadPrivateMessages = MutableStateFlow<Set<String>>(emptySet())
        val privateChats = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
        val conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
        val conversationStoreState = MutableStateFlow<ConversationStoreState>(ConversationStoreState.Ready)
        val joinedChannels = MutableStateFlow<Set<String>>(emptySet())
        val currentChannel = MutableStateFlow<String?>(null)
        val unreadChannelMessages = MutableStateFlow<Map<String, Int>>(emptyMap())
        val selectedLocationChannel = MutableStateFlow<ChannelID?>(null)
        val geohashPeople = MutableStateFlow<List<GeoPerson>>(emptyList())
        val isTeleported = MutableStateFlow(false)
        val teleportedGeo = MutableStateFlow<Set<String>>(emptySet())

        fun ports() = PeerListSources(
            connectedPeers = connectedPeers,
            peerNicknames = peerNicknames,
            peerDirect = peerDirect,
            wifiAwareConnected = wifiAwareConnected,
            selectedPrivateChatPeer = selectedPrivateChatPeer,
            nickname = nickname,
            favoritePeers = favoritePeers,
            peerFavoritedUs = peerFavoritedUs,
            peerFingerprints = peerFingerprints,
            verifiedFingerprints = verifiedFingerprints,
            unreadPrivateMessages = unreadPrivateMessages,
            privateChats = privateChats,
            conversations = conversations,
            conversationStoreState = conversationStoreState,
            joinedChannels = joinedChannels,
            currentChannel = currentChannel,
            unreadChannelMessages = unreadChannelMessages,
            selectedLocationChannel = selectedLocationChannel,
            geohashPeople = geohashPeople,
            isTeleported = isTeleported,
            teleportedGeo = teleportedGeo,
        )
    }

    private class FakeCommands : PeerListCommands {
        val calls = mutableListOf<String>()
        override var myPeerID: String = ME_ID
        var deleteResult: DeletedPrivateConversation? = null
        /** When set, the delete suspends until it completes. */
        var deleteGate: CompletableDeferred<Unit>? = null
        var restoreResult: Boolean = true
        val restored = mutableListOf<DeletedPrivateConversation>()

        override fun startGeohashDM(pubkeyHex: String, onStartPrivateChat: (String) -> Unit) {
            calls += "startGeohashDM $pubkeyHex"
            onStartPrivateChat("nostr_${pubkeyHex.take(16)}")
        }

        override fun switchToChannel(channel: String) {
            calls += "switchToChannel $channel"
        }

        override fun leaveChannel(channel: String) {
            calls += "leaveChannel $channel"
        }

        override fun sendMeshMessage(content: String) {
            calls += "sendMeshMessage $content"
        }

        override suspend fun deleteConversation(conversationID: String): DeletedPrivateConversation? {
            calls += "delete $conversationID"
            deleteGate?.await()
            return deleteResult
        }

        override suspend fun restoreConversation(deletion: DeletedPrivateConversation): Boolean {
            calls += "restore ${deletion.conversationID}"
            if (restoreResult) restored += deletion
            return restoreResult
        }

        override suspend fun setConversationRead(conversationID: String, isRead: Boolean): Boolean {
            calls += "setRead $conversationID $isRead"
            return true
        }

        override fun toggleConversationPinned(conversationID: String) {
            calls += "togglePinned $conversationID"
        }

        override fun toggleConversationMuted(conversationID: String) {
            calls += "toggleMuted $conversationID"
        }
    }

    private companion object {
        const val ALICE_ID = "0123456789abcdef"
        const val ME_ID = "fedcba9876543210"
        const val BOB_ID = "1111111111111111"
        const val CAROL_ID = "2222222222222222"

        fun deletion(id: String) = DeletedPrivateConversation(
            conversationID = id,
            aliases = setOf(id),
            displayName = null,
            messages = emptyList(),
            readMessageIDs = emptySet(),
            unreadMessageCount = 0,
        )

        fun conversation(id: String, displayName: String) = ConversationSummary(
            conversationID = id,
            displayName = displayName,
            unreadCount = 0,
            latestMessageAt = PEER_LIST_NOW_MILLIS,
            latestActivityOrder = PEER_LIST_NOW_MILLIS,
            latestMessageType = BitchatMessageType.Message,
            latestMessagePreview = "synthetic",
            transport = DirectMessageTransport.MESH,
            nostrPubkey = null,
            identityAliases = setOf(id),
        )
    }
}
