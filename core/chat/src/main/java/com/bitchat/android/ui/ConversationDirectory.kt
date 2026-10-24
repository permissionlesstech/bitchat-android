package com.bitchat.android.ui

import com.bitchat.android.di.ChatSessionScope
import com.bitchat.android.favorites.FavoritesChangeListener
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.SeenMessageStore
import dagger.Lazy
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class ConversationLiveIdentityState(
    val connectedPeerIDs: List<String>,
    val peerNicknames: Map<String, String>,
    val persistedDisplayNames: Map<String, String>
)

/**
 * The private conversation list: its rows, kept live against presence,
 * nicknames, favourites and list preferences, and deleting, restoring,
 * marking read, pinning and muting a conversation.
 *
 * Moved out of ChatViewModel unchanged. The observers and the favourites
 * listener are started by ChatSessionStartup at the points they always started,
 * because the listener needs FavoritesPersistenceService initialised first.
 */
@ActivityRetainedScoped
class ConversationDirectory @Inject constructor(
    private val state: ChatState,
    private val conversationListPreferences: ConversationListPreferences,
    private val seenMessageStore: Lazy<SeenMessageStore>,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val sessionMesh: ChatSessionMesh,
    @ChatSessionScope private val scope: CoroutineScope,
) {

    private companion object {
        const val CONVERSATION_DISCONNECT_GRACE_MS = 3_000L
    }

    val conversationStoreState =
        com.bitchat.android.services.AppStateStore.conversationStoreState
    private val conversationPresencePeers = MutableStateFlow<List<String>>(emptyList())
    private val conversationPresenceRemovalJobs = mutableMapOf<String, Job>()
    private val conversationDirectoryRevision = MutableStateFlow(0L)
    private var favoriteRelationshipListenerRegistered = false
    private val favoriteRelationshipChangeListener = object : FavoritesChangeListener {
        override fun onFavoriteChanged(noiseKeyHex: String) {
            refreshConversationDirectoryState()
        }

        override fun onAllCleared() {
            refreshConversationDirectoryState()
        }
    }

    private fun refreshConversationDirectoryState() {
        scope.launch {
            refreshPeerFavoritedUs()
            conversationListPreferences.canonicalizeAliases()
            conversationDirectoryRevision.update { it + 1L }
        }
    }

    private val conversationLiveIdentityState = combine(
        conversationPresencePeers,
        state.peerNicknames,
        state.peerFingerprints,
        conversationDirectoryRevision,
        com.bitchat.android.services.AppStateStore.privateConversationDisplayNames
    ) { connectedPeerIDs, peerNicknames, _, _, persistedDisplayNames ->
        ConversationLiveIdentityState(
            connectedPeerIDs = connectedPeerIDs,
            peerNicknames = peerNicknames,
            persistedDisplayNames = persistedDisplayNames
                .mapKeys { (conversationID, _) -> conversationID.lowercase() }
        )
    }
    private val baseConversations = combine(
        state.unreadPrivateMessages,
        state.privateChats,
        state.nickname,
        conversationLiveIdentityState,
        com.bitchat.android.services.AppStateStore.unreadPrivateMessageCounts
    ) { unreadConversationIDs, chats, currentNickname, liveIdentity, unreadCounts ->
        val seenStore = seenMessageStore.get()
        val connectedPeerByIdentity = buildMap {
            liveIdentity.connectedPeerIDs.forEach { peerID ->
                val identities = runCatching {
                    ContactDirectory.aliasesForConversation(peerID) +
                        ContactDirectory.canonicalConversationId(peerID)
                }.getOrDefault(setOf(peerID))
                identities.forEach { identity ->
                    putIfAbsent(identity.lowercase(), peerID)
                }
            }
        }
        buildConversationSummaries(
            unreadConversationIDs = unreadConversationIDs,
            privateChats = chats,
            currentUserIdentifiers = setOf(currentNickname, sessionMesh.unified.myPeerID),
            canonicalize = ContactDirectory::canonicalConversationId,
            isMessageRead = { message ->
                com.bitchat.android.services.AppStateStore.isPrivateMessageRead(message.id) ||
                    seenStore.hasBeenReadLocally(message.id)
            },
            persistedUnreadCounts = unreadCounts
        ).map { summary ->
            val resolution = ContactDirectory.resolve(summary.conversationID)
            val resolvedNostrPubkey = summary.nostrPubkey
                ?: resolution.nostrPubkey?.let(ContactIdentityResolver::nostrPubkeyHex)
            val aliases = buildSet {
                addAll(summary.identityAliases)
                add(summary.conversationID)
                add(resolution.conversationID)
                resolution.meshPeerID?.let(::add)
                resolution.noiseKeyHex?.let(::add)
                resolvedNostrPubkey
                    ?.let(ContactIdentityResolver::nostrAliasForPubkey)
                    ?.let(::add)
            }.mapTo(mutableSetOf()) { it.lowercase() }
            val connectedPeerID = aliases
                .asSequence()
                .mapNotNull(connectedPeerByIdentity::get)
                .firstOrNull()
            val persistedDisplayName = liveIdentity.persistedDisplayNames[
                summary.conversationID.lowercase()
            ] ?: aliases
                .asSequence()
                .mapNotNull(liveIdentity.persistedDisplayNames::get)
                .firstOrNull()

            summary.copy(
                displayName = resolveConversationDisplayName(
                    fallbackName = summary.displayName,
                    connectedPeerID = connectedPeerID,
                    peerNicknames = liveIdentity.peerNicknames,
                    resolvedContactName = resolution.displayName,
                    persistedDisplayName = persistedDisplayName
                ),
                nostrPubkey = resolvedNostrPubkey,
                transport = if (resolvedNostrPubkey != null) {
                    DirectMessageTransport.NOSTR
                } else {
                    summary.transport
                },
                identityAliases = aliases,
                isConnected = connectedPeerID != null,
                connectedPeerID = connectedPeerID,
                sourceGeohash = aliases
                    .asSequence()
                    .mapNotNull(GeohashConversationRegistry::get)
                    .firstOrNull()
            )
        }
    }

    val conversations: StateFlow<List<ConversationSummary>> = combine(
        baseConversations,
        conversationListPreferences.pinned,
        conversationListPreferences.muted,
        conversationListPreferences.drafts
    ) { summaries, pinned, muted, drafts ->
        sortConversationSummaries(
            summaries.map { summary ->
                val key = summary.conversationID.lowercase()
                summary.copy(
                    isPinned = key in pinned,
                    isMuted = key in muted,
                    draft = drafts[key]
                )
            }
        )
    }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = scope,
            started = SharingStarted.Eagerly,
            initialValue = emptyList()
        )

    /**
     * Mesh discovery can briefly drop a peer while transports hand over. Preserve its online
     * treatment for a short grace window to keep conversation rows from jumping between sections.
     * New connections still appear immediately.
     */
    fun observePresenceWithDisconnectGrace() {
        scope.launch {
            state.connectedPeers.collect { connected ->
                val current = connected.toSet()
                current.forEach { peerID ->
                    conversationPresenceRemovalJobs.remove(peerID)?.cancel()
                }

                val displayed = conversationPresencePeers.value.toMutableList()
                connected.forEach { peerID ->
                    if (peerID !in displayed) displayed.add(peerID)
                }
                if (displayed != conversationPresencePeers.value) {
                    conversationPresencePeers.value = displayed
                }

                (displayed.toSet() - current).forEach { peerID ->
                    if (peerID in conversationPresenceRemovalJobs) return@forEach
                    conversationPresenceRemovalJobs[peerID] = launch {
                        delay(CONVERSATION_DISCONNECT_GRACE_MS)
                        if (peerID !in state.connectedPeers.value) {
                            conversationPresencePeers.value =
                                conversationPresencePeers.value - peerID
                        }
                        conversationPresenceRemovalJobs.remove(peerID)
                    }
                }
            }
        }
    }

    fun observeDisplayNames() {
        scope.launch {
            combine(
                state.peerNicknames,
                state.connectedPeers,
                state.peerFingerprints
            ) { peerNicknames, connectedPeers, _ ->
                connectedPeers.mapNotNull { peerID ->
                    peerNicknames[peerID]?.let { peerID to it }
                }.toMap()
            }.collect { connectedNames ->
                conversationListPreferences.canonicalizeAliases()
                com.bitchat.android.services.AppStateStore
                    .updatePrivateConversationDisplayNames(connectedNames)
            }
        }
    }

    private fun refreshPeerFavoritedUs() {
        try {
            val fingerprints = com.bitchat.android.favorites.FavoritesPersistenceService.shared
                .getAllRelationships()
                .filter { it.theyFavoritedUs }
                .mapNotNull { relationship ->
                    runCatching {
                        ContactIdentityResolver.fingerprintHex(relationship.peerNoisePublicKey)
                    }.getOrNull()
                }
                .toSet()
            state.setPeerFavoritedUs(fingerprints)
        } catch (_: Exception) { }
    }

    suspend fun delete(
        peerOrConversationID: String
    ): com.bitchat.android.services.DeletedPrivateConversation? {
        val canonicalID = ContactDirectory.canonicalConversationId(peerOrConversationID)
        val wasPinned = conversationListPreferences.isPinned(canonicalID)
        val wasMuted = conversationListPreferences.isMuted(canonicalID)
        val draft = conversationListPreferences.draftFor(canonicalID)
        val unreadAliases = matchingUnreadAliases(
            unreadConversationIDs = state.getUnreadPrivateMessagesValue(),
            canonicalConversationID = canonicalID,
            canonicalize = ContactDirectory::canonicalConversationId
        )
        val deletion = withContext(Dispatchers.IO) {
            com.bitchat.android.services.AppStateStore
                .deletePrivateConversationAndWait(canonicalID)
        }?.copy(
            wasPinned = wasPinned,
            wasMuted = wasMuted,
            draft = draft
        ) ?: return null
        conversationListPreferences.removeConversation(canonicalID)

        state.setPrivateChats(
            ContactDirectory.canonicalizePrivateChats(
                com.bitchat.android.services.AppStateStore.privateMessages.value
            )
        )
        state.setUnreadPrivateMessages(
            state.getUnreadPrivateMessagesValue() - unreadAliases
        )
        seenMessageStore.get().remove(deletion.messageIDs)

        val selected = state.getSelectedPrivateChatPeerValue()
        if (
            selected != null &&
            ContactDirectory.canonicalConversationId(selected)
                .equals(canonicalID, ignoreCase = true)
        ) {
            privateChatManager.endPrivateChat()
            notificationManager.setCurrentPrivateChatPeer(null)
        }
        notificationManager.clearNotificationsForSender(canonicalID)
        notificationManager.removeConversationShortcut(canonicalID)
        return deletion
    }

    suspend fun restore(
        deletion: com.bitchat.android.services.DeletedPrivateConversation
    ): Boolean {
        val restored = withContext(Dispatchers.IO) {
            com.bitchat.android.services.AppStateStore
                .restoreDeletedConversation(deletion)
        }
        if (!restored) return false
        if (deletion.wasPinned != conversationListPreferences.isPinned(deletion.conversationID)) {
            conversationListPreferences.togglePinned(deletion.conversationID)
        }
        if (deletion.wasMuted != conversationListPreferences.isMuted(deletion.conversationID)) {
            conversationListPreferences.toggleMuted(deletion.conversationID)
        }
        deletion.draft?.let {
            conversationListPreferences.setDraft(deletion.conversationID, it)
        }
        state.setPrivateChats(
            ContactDirectory.canonicalizePrivateChats(
                com.bitchat.android.services.AppStateStore.privateMessages.value
            )
        )
        if (deletion.unreadMessageCount > 0) {
            state.setUnreadPrivateMessages(
                state.getUnreadPrivateMessagesValue() + deletion.conversationID
            )
        }
        return true
    }

    suspend fun setRead(
        conversationID: String,
        isRead: Boolean
    ): Boolean {
        val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
        val updated = withContext(Dispatchers.IO) {
            com.bitchat.android.services.AppStateStore
                .setPrivateConversationRead(canonicalID, isRead)
        }
        if (!updated) return false
        state.setUnreadPrivateMessages(
            if (isRead) {
                state.getUnreadPrivateMessagesValue().filterNotTo(mutableSetOf()) {
                    ContactDirectory.canonicalConversationId(it)
                        .equals(canonicalID, ignoreCase = true)
                }
            } else {
                state.getUnreadPrivateMessagesValue() + canonicalID
            }
        )
        return true
    }

    fun togglePinned(conversationID: String) {
        conversationListPreferences.togglePinned(conversationID)
    }

    fun toggleMuted(conversationID: String) {
        conversationListPreferences.toggleMuted(conversationID)
    }

    /** Starts following "they favourited us"; after FavoritesPersistenceService is initialised. */
    fun startFavoriteTracking() {
        refreshPeerFavoritedUs()
        try {
            com.bitchat.android.favorites.FavoritesPersistenceService.shared.addListener(
                favoriteRelationshipChangeListener
            )
            favoriteRelationshipListenerRegistered = true
        } catch (_: Exception) { }
    }

    fun stopFavoriteTracking() {
        if (favoriteRelationshipListenerRegistered) {
            runCatching {
                FavoritesPersistenceService.shared.removeListener(
                    favoriteRelationshipChangeListener
                )
            }
            favoriteRelationshipListenerRegistered = false
        }
    }
}
