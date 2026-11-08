package com.bitchat.android.ui

import android.os.SystemClock
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ConversationStoreState
import com.bitchat.android.services.DeletedPrivateConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The sources the peer list is built from (inventory A1.1, without the dead `peerRSSI`). */
internal class PeerListSources(
    val connectedPeers: StateFlow<List<String>>,
    val peerNicknames: StateFlow<Map<String, String>>,
    val peerDirect: StateFlow<Map<String, Boolean>>,
    val wifiAwareConnected: StateFlow<Map<String, *>>,
    val selectedPrivateChatPeer: StateFlow<String?>,
    val nickname: StateFlow<String>,
    val favoritePeers: StateFlow<Set<String>>,
    val peerFavoritedUs: StateFlow<Set<String>>,
    val peerFingerprints: StateFlow<Map<String, String>>,
    val verifiedFingerprints: StateFlow<Set<String>>,
    val unreadPrivateMessages: StateFlow<Set<String>>,
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>>,
    val conversations: StateFlow<List<ConversationSummary>>,
    val conversationStoreState: StateFlow<ConversationStoreState>,
    val joinedChannels: StateFlow<Set<String>>,
    val currentChannel: StateFlow<String?>,
    val unreadChannelMessages: StateFlow<Map<String, Int>>,
    val selectedLocationChannel: StateFlow<ChannelID?>,
    val geohashPeople: StateFlow<List<GeoPerson>>,
    val isTeleported: StateFlow<Boolean>,
    val teleportedGeo: StateFlow<Set<String>>,
)

/** What the peer list's actions drive (inventory A7). */
internal interface PeerListCommands {
    val myPeerID: String

    /** Maps the key to a `nostr_` conversation, then calls [onStartPrivateChat] with it. */
    fun startGeohashDM(pubkeyHex: String, onStartPrivateChat: (String) -> Unit)
    fun switchToChannel(channel: String)
    fun leaveChannel(channel: String)

    /** Sends [content] as a public mesh message. */
    fun sendMeshMessage(content: String)
    suspend fun deleteConversation(conversationID: String): DeletedPrivateConversation?
    suspend fun restoreConversation(deletion: DeletedPrivateConversation): Boolean
    suspend fun setConversationRead(conversationID: String, isRead: Boolean): Boolean
    fun toggleConversationPinned(conversationID: String)
    fun toggleConversationMuted(conversationID: String)
}

private class SessionPeerListCommands(
    private val sessionMesh: ChatSessionMesh,
    private val channelManager: ChannelManager,
    private val conversationDirectory: ConversationDirectory,
    private val geohashSession: GeohashSession,
) : PeerListCommands {
    override val myPeerID: String get() = sessionMesh.unified.myPeerID

    override fun startGeohashDM(pubkeyHex: String, onStartPrivateChat: (String) -> Unit) =
        geohashSession.startGeohashDM(pubkeyHex, onStartPrivateChat)

    override fun switchToChannel(channel: String) = channelManager.switchToChannel(channel)
    override fun leaveChannel(channel: String) = channelManager.leaveChannel(channel)
    override fun sendMeshMessage(content: String) {
        sessionMesh.unified.sendMessage(content, emptyList(), null)
    }

    override suspend fun deleteConversation(conversationID: String) =
        conversationDirectory.delete(conversationID)

    override suspend fun restoreConversation(deletion: DeletedPrivateConversation) =
        conversationDirectory.restore(deletion)

    override suspend fun setConversationRead(conversationID: String, isRead: Boolean) =
        conversationDirectory.setRead(conversationID, isRead)

    override fun toggleConversationPinned(conversationID: String) =
        conversationDirectory.togglePinned(conversationID)

    override fun toggleConversationMuted(conversationID: String) =
        conversationDirectory.toggleMuted(conversationID)
}

/**
 * The network view: conversations, channels, and the people nearby on the mesh or in a
 * geohash. Entry-scoped.
 *
 * [uiState] is rebuilt off the main thread whenever any source or the search text changes;
 * the rows' non-snapshot lookups (Q-P14) are read then, as recomposing the list read them.
 */
@HiltViewModel
class MeshPeerListViewModel internal constructor(
    private val sources: PeerListSources,
    private val commands: PeerListCommands,
    private val lookups: PeerListLookups,
    /** Keeps the search text, as the field's saveable state did. */
    private val savedState: SavedStateHandle,
    /** Where the list is built; tests pass their own dispatcher. */
    defaultDispatcher: CoroutineDispatcher,
    /** Monotonic milliseconds that bound the Undo window; tests pass virtual time. */
    private val clock: () -> Long,
) : ViewModel() {

    @Inject
    constructor(
        state: ChatState,
        sessionMesh: ChatSessionMesh,
        channelManager: ChannelManager,
        conversationDirectory: ConversationDirectory,
        verificationHandler: VerificationHandler,
        geohashSession: GeohashSession,
        wifiAwarePeers: WifiAwarePeers,
        lookups: PeerListLookups,
        savedState: SavedStateHandle,
    ) : this(
        sources = PeerListSources(
            connectedPeers = state.connectedPeers,
            peerNicknames = state.peerNicknames,
            peerDirect = state.peerDirect,
            wifiAwareConnected = wifiAwarePeers.connected,
            selectedPrivateChatPeer = state.selectedPrivateChatPeer,
            nickname = state.nickname,
            favoritePeers = state.favoritePeers,
            peerFavoritedUs = state.peerFavoritedUs,
            peerFingerprints = state.peerFingerprints,
            verifiedFingerprints = verificationHandler.verifiedFingerprints,
            unreadPrivateMessages = state.unreadPrivateMessages,
            privateChats = state.privateChats,
            conversations = conversationDirectory.conversations,
            conversationStoreState = conversationDirectory.conversationStoreState,
            joinedChannels = state.joinedChannels,
            currentChannel = state.currentChannel,
            unreadChannelMessages = state.unreadChannelMessages,
            selectedLocationChannel = state.selectedLocationChannel,
            geohashPeople = geohashSession.geohashPeople,
            isTeleported = state.isTeleported,
            teleportedGeo = geohashSession.teleportedGeo,
        ),
        commands = SessionPeerListCommands(
            sessionMesh,
            channelManager,
            conversationDirectory,
            geohashSession,
        ),
        lookups = lookups,
        savedState = savedState,
        defaultDispatcher = Dispatchers.Default,
        clock = SystemClock::elapsedRealtime,
    )

    private val query: StateFlow<String> = savedState.getStateFlow(QUERY_KEY, "")

    private val _events = Channel<MeshPeerListEvent>(Channel.BUFFERED)
    internal val events: Flow<MeshPeerListEvent> = _events.receiveAsFlow()

    /**
     * F2: deletions Undo can still bring back, by token. Held here rather than in the Root's
     * scope, so rotating or opening a chat within the Undo window no longer loses them; the Root
     * offers them again. Each is dropped when its window ends.
     */
    private val pendingDeletions = mutableMapOf<Long, PendingDeletion>()
    private val pendingUndos = MutableStateFlow<List<PendingUndoUi>>(emptyList())
    private var nextUndoToken = 0L

    internal val uiState: StateFlow<MeshPeerListUiState> = combine(
        snapshots().map { meshPeerListUiState(it, { commands.myPeerID }, lookups) }
            .flowOn(defaultDispatcher),
        query,
        pendingUndos,
    ) { state, query, pendingUndos ->
        // The field shows the latest text at once; the rows follow when they are rebuilt.
        state.copy(query = query, pendingUndos = pendingUndos)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = meshPeerListUiState(currentSnapshot(), { commands.myPeerID }, lookups),
    )

    internal fun onAction(action: MeshPeerListAction) {
        when (action) {
            is MeshPeerListAction.QueryChanged -> savedState[QUERY_KEY] = action.text
            is MeshPeerListAction.ConversationClicked -> openPrivateChat(action.conversationID)
            is MeshPeerListAction.SwipeToggleRead -> viewModelScope.launch {
                // The result is ignored, as it always was.
                commands.setConversationRead(action.conversationID, action.markRead)
            }
            is MeshPeerListAction.TogglePinned ->
                commands.toggleConversationPinned(action.conversationID)
            is MeshPeerListAction.ToggleMuted ->
                commands.toggleConversationMuted(action.conversationID)
            is MeshPeerListAction.ChannelClicked -> openChannel(action.name)
            is MeshPeerListAction.LeaveChannel -> {
                commands.leaveChannel(action.name)
                // Q-P10: leaving announces itself on the public mesh.
                commands.sendMeshMessage("left ${action.name}")
            }
            is MeshPeerListAction.DeleteConfirmed ->
                deleteConversation(action.conversationID, action.displayName)
            is MeshPeerListAction.UndoDelete -> undoDelete(action.token)
            is MeshPeerListAction.UndoDismissed -> forgetDeletion(action.token)
            is MeshPeerListAction.PersonClicked -> openPrivateChat(action.peerID)
            is MeshPeerListAction.GeohashPersonClicked ->
                // Pushes the chat over the list; closing the list here would pop it again.
                commands.startGeohashDM(action.pubkeyHex, ::openPrivateChat)
            MeshPeerListAction.VerifyClicked -> _events.trySend(MeshPeerListEvent.ShowVerification)
            MeshPeerListAction.BackClicked -> _events.trySend(MeshPeerListEvent.Close)
        }
    }

    /**
     * a11: an `@name` channel opens the private chat with the first peer of that nickname, and
     * does nothing when there is none (Q-P9); either way the list stays. Any other channel
     * becomes the current one and the list closes.
     */
    private fun openChannel(channel: String) {
        if (channel.startsWith("@")) {
            val peerName = channel.removePrefix("@")
            val peerID = sources.peerNicknames.value.entries
                .firstOrNull { it.value == peerName }
                ?.key
            if (peerID != null) openPrivateChat(peerID)
        } else {
            commands.switchToChannel(channel)
            _events.trySend(MeshPeerListEvent.Close)
        }
    }

    /** Shows the private chat with [peerID] over the list; Back returns to it. */
    private fun openPrivateChat(peerID: String) {
        _events.trySend(MeshPeerListEvent.OpenPrivateChat(lookups.canonicalConversationId(peerID)))
    }

    /**
     * a9: deletes the conversation, then offers Undo for it, or says the delete failed. Runs in
     * [viewModelScope], so leaving the list mid-delete no longer abandons it (F2, Q-P12).
     */
    private fun deleteConversation(conversationID: String, displayName: String) {
        viewModelScope.launch {
            val deletion = commands.deleteConversation(conversationID)
            if (deletion == null) {
                _events.send(MeshPeerListEvent.DeleteFailed)
                return@launch
            }
            val token = nextUndoToken++
            pendingDeletions[token] = PendingDeletion(deletion)
            pendingUndos.update { it + PendingUndoUi(token, displayName) }
            offerNextUndo()
        }
    }

    /**
     * Starts the Undo window of the deletion at the head of the queue, the one the Root shows,
     * if it has not started yet. Snackbars show one at a time, so a queued Undo's window starts
     * when its snackbar first appears, not when it was deleted. Undo is not offered again once
     * its window has passed.
     */
    private fun offerNextUndo() {
        val token = pendingUndos.value.firstOrNull()?.token ?: return
        val pending = pendingDeletions[token] ?: return
        if (pending.offeredAt != null) return
        pending.offeredAt = clock()
        viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            forgetDeletion(token)
        }
    }

    /**
     * a9 Undo: restores the deletion [token] names, once and only within its window; says so
     * when that fails.
     */
    private fun undoDelete(token: Long) {
        val pending = forgetDeletion(token) ?: return
        val offeredAt = pending.offeredAt
        if (offeredAt != null && clock() - offeredAt >= UNDO_WINDOW_MS) return
        val deletion = pending.deletion
        viewModelScope.launch {
            if (!commands.restoreConversation(deletion)) {
                _events.send(MeshPeerListEvent.RestoreFailed)
            }
        }
    }

    private fun forgetDeletion(token: Long): PendingDeletion? {
        pendingUndos.update { undos -> undos.filterNot { it.token == token } }
        return pendingDeletions.remove(token).also { offerNextUndo() }
    }

    private fun currentSnapshot(): PeerListSnapshot = with(sources) {
        PeerListSnapshot(
            connectedPeers = connectedPeers.value,
            peerNicknames = peerNicknames.value,
            peerDirect = peerDirect.value,
            wifiAwareConnected = wifiAwareConnected.value,
            selectedPrivateChatPeer = selectedPrivateChatPeer.value,
            nickname = nickname.value,
            favoritePeers = favoritePeers.value,
            peerFavoritedUs = peerFavoritedUs.value,
            peerFingerprints = peerFingerprints.value,
            verifiedFingerprints = verifiedFingerprints.value,
            unreadPrivateMessages = unreadPrivateMessages.value,
            privateChats = privateChats.value,
            conversations = conversations.value,
            conversationStoreState = conversationStoreState.value,
            joinedChannels = joinedChannels.value,
            currentChannel = currentChannel.value,
            unreadChannelMessages = unreadChannelMessages.value,
            selectedLocationChannel = selectedLocationChannel.value,
            myGeohashHex = myGeohashHex(selectedLocationChannel.value, lookups),
            geohashPeople = geohashPeople.value,
            isTeleported = isTeleported.value,
            teleportedGeo = teleportedGeo.value,
            query = query.value,
        )
    }

    /** Every source, combined in groups of at most five. */
    private fun snapshots(): Flow<PeerListSnapshot> = with(sources) {
        val mesh = combine(
            connectedPeers, peerNicknames, peerDirect, wifiAwareConnected, selectedPrivateChatPeer,
        ) { connected, nicknames, direct, wifiAware, selected ->
            MeshPart(connected, nicknames, direct, wifiAware, selected)
        }
        val identity = combine(
            nickname, favoritePeers, peerFavoritedUs, peerFingerprints, verifiedFingerprints,
        ) { nickname, favorites, favoritedUs, fingerprints, verified ->
            IdentityPart(nickname, favorites, favoritedUs, fingerprints, verified)
        }
        val messages = combine(
            unreadPrivateMessages, privateChats, conversations, conversationStoreState, query,
        ) { unread, chats, conversations, storeState, query ->
            MessagesPart(unread, chats, conversations, storeState, query)
        }
        val channels = combine(
            joinedChannels, currentChannel, unreadChannelMessages,
        ) { joined, current, unread -> ChannelsPart(joined, current, unread) }
        // P26 is derived only when the selected channel changes, as its `remember` did.
        val selection = selectedLocationChannel.map { channel ->
            channel to myGeohashHex(channel, lookups)
        }
        val geohash = combine(
            selection, geohashPeople, isTeleported, teleportedGeo,
        ) { (channel, myHex), people, teleported, teleportedGeo ->
            GeohashPart(channel, myHex, people, teleported, teleportedGeo)
        }
        combine(mesh, identity, messages, channels, geohash) { m, i, msg, c, g ->
            PeerListSnapshot(
                connectedPeers = m.connectedPeers,
                peerNicknames = m.peerNicknames,
                peerDirect = m.peerDirect,
                wifiAwareConnected = m.wifiAwareConnected,
                selectedPrivateChatPeer = m.selectedPrivateChatPeer,
                nickname = i.nickname,
                favoritePeers = i.favoritePeers,
                peerFavoritedUs = i.peerFavoritedUs,
                peerFingerprints = i.peerFingerprints,
                verifiedFingerprints = i.verifiedFingerprints,
                unreadPrivateMessages = msg.unreadPrivateMessages,
                privateChats = msg.privateChats,
                conversations = msg.conversations,
                conversationStoreState = msg.conversationStoreState,
                joinedChannels = c.joinedChannels,
                currentChannel = c.currentChannel,
                unreadChannelMessages = c.unreadChannelMessages,
                selectedLocationChannel = g.selectedLocationChannel,
                myGeohashHex = g.myGeohashHex,
                geohashPeople = g.geohashPeople,
                isTeleported = g.isTeleported,
                teleportedGeo = g.teleportedGeo,
                query = msg.query,
            )
        }
    }

    /** A deletion Undo can still bring back; [offeredAt] is when its Undo was first shown. */
    private class PendingDeletion(val deletion: DeletedPrivateConversation) {
        var offeredAt: Long? = null
    }

    private data class MeshPart(
        val connectedPeers: List<String>,
        val peerNicknames: Map<String, String>,
        val peerDirect: Map<String, Boolean>,
        val wifiAwareConnected: Map<String, *>,
        val selectedPrivateChatPeer: String?,
    )

    private data class IdentityPart(
        val nickname: String,
        val favoritePeers: Set<String>,
        val peerFavoritedUs: Set<String>,
        val peerFingerprints: Map<String, String>,
        val verifiedFingerprints: Set<String>,
    )

    private data class MessagesPart(
        val unreadPrivateMessages: Set<String>,
        val privateChats: Map<String, List<BitchatMessage>>,
        val conversations: List<ConversationSummary>,
        val conversationStoreState: ConversationStoreState,
        val query: String,
    )

    private data class ChannelsPart(
        val joinedChannels: Set<String>,
        val currentChannel: String?,
        val unreadChannelMessages: Map<String, Int>,
    )

    private data class GeohashPart(
        val selectedLocationChannel: ChannelID?,
        val myGeohashHex: String?,
        val geohashPeople: List<GeoPerson>,
        val isTeleported: Boolean,
        val teleportedGeo: Set<String>,
    )

    internal companion object {
        /** The search text's key in [SavedStateHandle]. */
        const val QUERY_KEY = "peer_list_query"

        /**
         * How long a deletion can be undone. The Root's snackbar is `SnackbarDuration.Long`
         * (10 s), but Android's "Time to take action" accessibility setting can keep it up for
         * as long as 2 minutes; this covers the longest setting, so an Undo that is showing
         * always works, while Undo is still not offered again long after the delete.
         */
        const val UNDO_WINDOW_MS = 120_000L
    }
}
