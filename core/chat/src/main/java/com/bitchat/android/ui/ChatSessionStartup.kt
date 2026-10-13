package com.bitchat.android.ui

import android.content.Context
import android.util.Log
import com.bitchat.android.di.ChatSessionScope
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.util.hexEncodedString
import dagger.Lazy
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Starts the chat session and tears it down: loads the saved state, hydrates
 * [ChatState] from the process-wide stores, and runs the once-a-second
 * peer-state poll that flushes the router outbox when a Noise session
 * establishes.
 *
 * Moved out of ChatViewModel unchanged and in the same order. It lives in the
 * retained scope so the chat screen's ViewModel can be scoped to its
 * navigation entry without restarting the session each time it is created.
 */
@ActivityRetainedScoped
class ChatSessionStartup @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lifecycle: ActivityRetainedLifecycle,
    @ChatSessionScope private val scope: CoroutineScope,
    private val sessionMesh: ChatSessionMesh,
    // dagger.Lazy and Provider for the reasons ChatViewModel gave: these were
    // reached through getInstance(...) at the point of use, and MessageRouter
    // has to be re-resolved so it follows the mesh panic-clear replaces.
    private val seenMessageStoreLazy: Lazy<SeenMessageStore>,
    private val nostrTransportLazy: Lazy<NostrTransport>,
    private val messageRouterProvider: Provider<MessageRouter>,
    private val debugSettingsManager: DebugSettingsManager,
    private val state: ChatState,
    private val dataManager: DataManager,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager,
    private val identityManager: SecureIdentityStateManager,
    private val verificationHandler: VerificationHandler,
    private val mediaSendingManager: MediaSendingManager,
    private val geohashSession: GeohashSession,
    private val contactFavorites: ContactFavorites,
    private val conversationDirectory: ConversationDirectory,
) {

    private companion object {
        const val TAG = "ChatSessionStartup"
    }

    private val mesh: MeshService
        get() = sessionMesh.unified

    private val seenMessageStore: SeenMessageStore get() = seenMessageStoreLazy.get()
    private val nostrTransport: NostrTransport get() = nostrTransportLazy.get()

    private var started = false

    /** Starts the session once; later calls, from a recreated ViewModel, do nothing. */
    fun start() {
        if (started) return
        started = true
        Log.d(TAG, "Starting chat session")
        lifecycle.addOnClearedListener { stop() }

        conversationDirectory.observePresenceWithDisconnectGrace()
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
        ContactDirectory.initialize(context) { mesh }
        com.bitchat.android.services.AppStateStore.canonicalizePrivateChats()
        conversationDirectory.observeDisplayNames()
        // Application startup performs the initial restore. Repeat it for every new UI owner
        // because a quick reopen can reuse a process whose in-memory state was cleared during
        // controlled shutdown.
        com.bitchat.android.services.AppStateStore.reloadConversationPersistence(context)
        // Mark queued private messages as failed when the router gives up on them
        try {
            messageRouterProvider.get().onMessageExpired = { messageID ->
                messageManager.updateMessageDeliveryStatus(
                    messageID,
                    com.bitchat.android.model.DeliveryStatus.Failed("Message expired before delivery")
                )
            }
        } catch (_: Exception) { }
        // Hydrate UI state from process-wide AppStateStore to survive Activity recreation
        scope.launch {
            try { com.bitchat.android.services.AppStateStore.peers.collect { peers ->
                state.setConnectedPeers(peers)
                state.setIsConnected(peers.isNotEmpty())
            } } catch (_: Exception) { }
        }
        scope.launch {
            try { com.bitchat.android.services.AppStateStore.publicMessages.collect { msgs ->
                // Source of truth is AppStateStore; replace to avoid duplicate keys in LazyColumn
                state.setMessages(msgs)
            } } catch (_: Exception) { }
        }
        scope.launch {
            try {
                combine(
                    com.bitchat.android.services.AppStateStore.privateMessages,
                    com.bitchat.android.services.AppStateStore.unreadPrivateMessageCounts
                ) { byPeer, unreadCounts -> byPeer to unreadCounts }
                    .collect { (byPeer, unreadCounts) ->
                val (canonicalChats, unreadConversationIDs) = withContext(Dispatchers.IO) {
                    val canonical = ContactDirectory.canonicalizePrivateChats(byPeer)
                    val unread = try {
                        val myNick = state.getNicknameValue().ifBlank { mesh.myPeerID }
                        canonical
                            .filterValues { messages ->
                                messages.any { message ->
                                    message.sender != myNick &&
                                        message.sender != "system" &&
                                        !com.bitchat.android.services.AppStateStore
                                            .isPrivateMessageRead(message.id) &&
                                        !seenMessageStore.hasBeenReadLocally(message.id)
                                }
                            }
                            .keys + unreadCounts
                                .filterValues { it > 0 }
                                .keys
                    } catch (_: Exception) {
                        state.getUnreadPrivateMessagesValue()
                    }
                    canonical to unread
                }
                state.setPrivateChats(canonicalChats)
                // Recompute unread set using SeenMessageStore for robustness across Activity recreation
                state.setUnreadPrivateMessages(unreadConversationIDs)
            } } catch (_: Exception) { }
        }
        scope.launch {
            try { com.bitchat.android.services.AppStateStore.channelMessages.collect { byChannel ->
                // Replace with store snapshot
                state.setChannelMessages(byChannel)
            } } catch (_: Exception) { }
        }
        // Subscribe to BLE transfer progress and reflect in message deliveryStatus
        scope.launch {
            com.bitchat.android.mesh.TransferProgressManager.events.collect { evt ->
                mediaSendingManager.handleTransferProgressEvent(evt)
            }
        }
    }

    private fun stop() {
        conversationDirectory.stopFavoriteTracking()
        geohashSession.shutdownUiSubscriptions()
        com.bitchat.android.services.AppStateStore.setSelectedPrivateChatPeer(null)
    }

    private fun loadAndInitialize() {
        // Load nickname
        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)

        // Load data
        val (joinedChannels, protectedChannels) = channelManager.loadChannelData()
        state.setJoinedChannels(joinedChannels)
        state.setPasswordProtectedChannels(protectedChannels)

        // Initialize channel messages
        joinedChannels.forEach { channel ->
            if (!state.getChannelMessagesValue().containsKey(channel)) {
                val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                state.setChannelMessages(updatedChannelMessages)
            }
        }

        // Load other data
        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()

        // Log all favorites at startup
        dataManager.logAllFavorites()
        contactFavorites.logCurrentFavoriteState()

        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        scope.launch {
            debugSettingsManager.debugMessages.collect { msgs ->
                if (debugSettingsManager.verboseLoggingEnabled.value) {
                    // Only show debug logs in the Mesh chat timeline to avoid leaking into geohash chats
                    val selectedLocation = state.selectedLocationChannel.value
                    if (selectedLocation is com.bitchat.android.geohash.ChannelID.Mesh) {
                        // Append only latest debug message as system message to avoid flooding
                        msgs.lastOrNull()?.let { dm ->
                            messageManager.addSystemMessage(dm.content)
                        }
                    }
                }
            }
        }

        // Initialize new geohash architecture
        geohashSession.initialize()

        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(context)

        // Reflect "they favorited us" changes into reactive UI state (drives star celebrations)
        conversationDirectory.startFavoriteTracking()

        // Load verified fingerprints from secure storage
        verificationHandler.loadVerifiedFingerprints()

        // Ensure NostrTransport knows our mesh peer ID for embedded packets
        try {
            nostrTransport.senderPeerID = mesh.myPeerID
        } catch (_: Exception) { }
    }

    private fun sessionStateForPeer(peerID: String): NoiseSession.NoiseSessionState {
        return try { mesh.getSessionState(peerID) } catch (_: Exception) { NoiseSession.NoiseSessionState.Uninitialized }
    }

    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        scope.launch {
            while (true) {
                delay(1000) // Check session states every second
                updateReactiveStates()
            }
        }
    }

    /**
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()

        // Update session states
        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            sessionStateForPeer(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                messageRouterProvider.get().onSessionEstablished(peerID)
            }
        }
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)
        fingerprints.forEach { (peerID, fingerprint) ->
            identityManager.cachePeerFingerprint(peerID, fingerprint)
            val info = try { mesh.getPeerInfo(peerID) } catch (_: Exception) { null }
            val noiseKeyHex = info?.noisePublicKey?.hexEncodedString()
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fingerprint)
            }
            info?.nickname?.takeIf { it.isNotBlank() }?.let { nickname ->
                identityManager.cacheFingerprintNickname(fingerprint, nickname)
            }
        }

        state.setPeerNicknames(mesh.getPeerNicknames())

        state.setPeerRSSI(mesh.getPeerRSSI())

        // Update directness per peer (driven by PeerManager state)
        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                mesh.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) { }

        // Flush any pending QR verification once a Noise session is established
        currentPeers.forEach { peerID ->
            if (sessionStateForPeer(peerID) is NoiseSession.NoiseSessionState.Established) {
                verificationHandler.sendPendingVerificationIfNeeded(peerID)
            }
        }
    }
}
