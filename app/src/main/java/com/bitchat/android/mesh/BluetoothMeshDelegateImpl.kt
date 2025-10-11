package com.bitchat.android.mesh

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.LiveData
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.GeohashChannel
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.logWarn
import com.bitchat.android.ui.ChannelManager
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.ChatViewModelUtils
import com.bitchat.android.ui.CommandProcessor
import com.bitchat.android.ui.CommandSuggestion
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.GeoPerson
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NoiseSessionDelegate
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.ui.PrivateChatManager
import com.bitchat.android.util.NotificationIntervalManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.collections.forEach
import kotlin.random.Random
import kotlin.text.get


class BluetoothMeshDelegateImpl(
    private val scope: CoroutineScope,
    context: Context,
    private val meshService: BluetoothMeshService,
    private val application: Application
) : BluetoothMeshDelegate {
    companion object {
        private const val TAG = "BluetoothMeshDelegate"

    }

    override val dataManager = DataManager(context)
    override val state = ChatState()
    override val messageManager = MessageManager(state)
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String) =
            meshService.hasEstablishedSession(peerID)

        override fun initiateHandshake(peerID: String) = meshService.initiateNoiseHandshake(peerID)
        override fun getMyPeerID(): String = meshService.myPeerID
    }
    override val channelManager = ChannelManager(state, messageManager, dataManager, scope)
    override val privateChatManager =
        PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    override val notificationManager = NotificationManager(
        context, NotificationManagerCompat.from(context), NotificationIntervalManager()
    )
    private val commandProcessor =
        CommandProcessor(state, messageManager, channelManager, privateChatManager)

    override val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = scope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(context) },
        getMyPeerID = { meshService.myPeerID },
        getMeshService = { meshService }
    )
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()
    private val geohashPeople: LiveData<List<GeoPerson>> = state.geohashPeople
    val selectedLocationChannel: LiveData<ChannelID?> = state.selectedLocationChannel

    override fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        meshService.sendBroadcastAnnounce()
    }

    override fun didReceiveMessage(message: BitchatMessage) =
        meshDelegateHandler.didReceiveMessage(message)

    override fun didUpdatePeerList(peers: List<String>) =
        meshDelegateHandler.didUpdatePeerList(peers)

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) =
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)


    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) =
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)


    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) =
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)


    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String) =
        meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)

    override fun getNickname() = meshDelegateHandler.getNickname()


    override fun isFavorite(peerID: String) = meshDelegateHandler.isFavorite(peerID)

   override fun openLatestUnreadPrivateChat(onEnsureGeohashDMSubscription: (String) -> Unit) {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: meshService.myPeerID
            val chats = state.getPrivateChatsValue()

            // Pick the latest incoming message among unread conversations
            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {
                    // Prefer the latest incoming message (sender != me), fallback to last message
                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {
                // Use the exact conversation key for geohash DMs and ensure DM subscription
                onEnsureGeohashDMSubscription(targetKey)
                targetKey
            } else {
                // Resolve to a canonical mesh peer if needed
                val canonical =
                    com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                        selectedPeerID = targetKey,
                        connectedPeers = state.getConnectedPeersValue(),
                        meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                        meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                        nostrPubHexForAlias = { alias ->
                            com.bitchat.android.nostr.GeohashAliasRegistry.get(
                                alias
                            )
                        },
                        findNoiseKeyForNostr = { key ->
                            com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(
                                key
                            )
                        }
                    )
                canonical ?: targetKey
            }

            startPrivateChat(openPeer) { onEnsureGeohashDMSubscription(openPeer) }

            // If sidebar visible, hide it to focus on the private chat
            if (state.getShowSidebarValue()) {
                state.setShowSidebar(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }

    override fun startPrivateChat(peerID: String, onEnsureGeohashDMSubscription: () -> Unit) {
        // For geohash conversation keys, ensure DM subscription is active
        if (peerID.startsWith("nostr_")) {
            onEnsureGeohashDMSubscription()
        }

        val success = privateChatManager.startPrivateChat(peerID, meshService)
        if (success) {
            // Notify notification manager about current private chat
            setCurrentPrivateChatPeer(peerID)
            // Clear notifications for this sender since user is now viewing the chat
            clearNotificationsForSender(peerID)

            // Persistently mark all messages in this conversation as read so Nostr fetches
            // after app restarts won't re-mark them as unread.
            try {
                val seen = com.bitchat.android.services.SeenMessageStore.getInstance(application)
                val chats = state.getPrivateChatsValue()
                val messages = chats[peerID] ?: emptyList()
                messages.forEach { msg ->
                    try {
                        seen.markRead(msg.id)
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun endPrivateChat() {
        privateChatManager.endPrivateChat()
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        clearMeshMentionNotifications()
    }

    override fun cancelMediaSend(messageId: String) {
        val transferId = synchronized(transferMessageMap) { messageTransferMap[messageId] }
        if (transferId != null) {
            val cancelled = meshService.cancelFileTransfer(transferId)
            if (cancelled) {
                // Remove the message from chat upon explicit cancel
                messageManager.removeMessageById(messageId)
                synchronized(transferMessageMap) {
                    transferMessageMap.remove(transferId)
                    messageTransferMap.remove(messageId)
                }
            }
        }
    }

    override fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()

        // Update session states
        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            meshService.getSessionState(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                com.bitchat.android.services.MessageRouter
                    .getInstance(application, meshService)
                    .onSessionEstablished(peerID)
            }
        }
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)

        val nicknames = meshService.getPeerNicknames()
        state.setPeerNicknames(nicknames)

        val rssiValues = meshService.getPeerRSSI()
        state.setPeerRSSI(rssiValues)

        // Update directness per peer (driven by PeerManager state)
        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                meshService.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) {
        }
    }

    override fun joinChannel(channel: String, password: String?): Boolean {
        return channelManager.joinChannel(channel, password, meshService.myPeerID)
    }

    override fun switchToChannel(channel: String?) = channelManager.switchToChannel(channel)


    override fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        meshService.sendMessage("left $channel")
    }

    /** Forward to notification manager for notification logic */
    override fun setAppBackgroundState(inBackground: Boolean) =
        notificationManager.setAppBackgroundState(inBackground)


    override fun setCurrentPrivateChatPeer(peerID: String?) {
        // Update notification manager with current private chat peer
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }

    override fun setCurrentGeohash(geohash: String?) {
        // Update notification manager with current geohash for notification logic
        notificationManager.setCurrentGeohash(geohash)
    }

    override fun clearNotificationsForSender(peerID: String) {
        // Clear notifications when user opens a chat
        notificationManager.clearNotificationsForSender(peerID)
    }

    override fun clearNotificationsForGeohash(geohash: String) {
        // Clear notifications when user opens a geohash chat
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    /**
     * Clear mesh mention notifications when user opens mesh chat
     */
    override fun clearMeshMentionNotifications() {
        notificationManager.clearMeshMentionNotifications()
    }

    // MARK: - Command Autocomplete (delegated)

    override fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }

    override fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }

    // MARK: - Mention Autocomplete

    override fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(
            input, meshService, Pair(geohashPeople.value, selectedLocationChannel.value)
        )
    }

    override fun selectMentionSuggestion(nickname: String, currentText: String) =
        commandProcessor.selectMentionSuggestion(nickname, currentText)

    override fun changeMeshServiceBGState(b: Boolean) =
        meshService.connectionManager.setAppBackgroundState(b)

    override fun startMeshServices() = meshService.startServices()
    override fun stopMeshServices() = meshService.startServices()
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager

    // MARK: - Emergency Clear

    override fun panicClearAllData(onPanicReset: () -> Unit) {
        Log.w(TAG, "🚨 PANIC MODE ACTIVATED - Clearing all sensitive data")

        // Clear all UI managers
        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()
        dataManager.clearAllData()

        // Clear all mesh service data
        clearAllMeshServiceData()

        // Clear all cryptographic data
        clearAllCryptographicData()

        // Clear all notifications
        notificationManager.clearAllNotifications()

        // Clear Nostr/geohash state, keys, connections, bookmarks, and reinitialize from scratch
        try {
            // Clear geohash bookmarks too (panic should remove everything)
            try {
                val store =
                    com.bitchat.android.geohash.GeohashBookmarksStore.getInstance(application)
                store.clearAll()
            } catch (_: Exception) {
            }

            onPanicReset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset Nostr/geohash: ${e.message}")
        }

        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)

        Log.w(TAG, "🚨 PANIC MODE COMPLETED - All sensitive data cleared")

        // Note: Mesh service restart is now handled by MainActivity
        // This method now only clears data, not mesh service lifecycle
    }

    /**
     * Clear all mesh service related data
     */
    private fun clearAllMeshServiceData() {
        try {
            // Request mesh service to clear all its internal data
            meshService.clearAllInternalData()

            Log.d(TAG, "✅ Cleared all mesh service data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service data: ${e.message}")
        }
    }

    /**
     * Clear all cryptographic data including persistent identity
     */
    private fun clearAllCryptographicData() {
        try {
            // Clear encryption service persistent identity (Ed25519 signing keys)
            meshService.clearAllEncryptionData()

            // Clear secure identity state (if used)
            try {
                val identityManager =
                    com.bitchat.android.identity.SecureIdentityStateManager(application)
                identityManager.clearIdentityData()
                // Also clear secure values used by FavoritesPersistenceService (favorites + peerID index)
                try {
                    identityManager.clearSecureValues(
                        "favorite_relationships",
                        "favorite_peerid_index"
                    )
                } catch (_: Exception) {
                }
                Log.d(TAG, "✅ Cleared secure identity state and secure favorites store")
            } catch (e: Exception) {
                Log.d(
                    TAG,
                    "SecureIdentityStateManager not available or already cleared: ${e.message}"
                )
            }

            // Clear FavoritesPersistenceService persistent relationships
            try {
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.clearAllFavorites()
                Log.d(TAG, "✅ Cleared FavoritesPersistenceService relationships")
            } catch (_: Exception) {
            }

            Log.d(TAG, "✅ Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing cryptographic data: ${e.message}")
        }
    }

    override fun loadAndInitialize(
        logCurrentFavoriteState: () -> Unit, initializeSessionStateMonitoring: () -> Unit,
        initializeGeoHashVM: () -> Unit
    ) {
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
        logCurrentFavoriteState()

        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        scope.launch {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().debugMessages.collect { msgs ->
                if (com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().verboseLoggingEnabled.value) {
                    // Only show debug logs in the Mesh chat timeline to avoid leaking into geohash chats
                    val selectedLocation = state.selectedLocationChannel.value
                    if (selectedLocation is ChannelID.Mesh) {
                        // Append only latest debug message as system message to avoid flooding
                        msgs.lastOrNull()?.let { dm ->
                            messageManager.addSystemMessage(dm.content)
                        }
                    }
                }
            }
        }

        // Initialize new geohash architecture
        initializeGeoHashVM()

        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(application)


        // Ensure NostrTransport knows our mesh peer ID for embedded packets
        try {
            val nostrTransport =
                com.bitchat.android.nostr.NostrTransport.getInstance(application)
            nostrTransport.senderPeerID = meshService.myPeerID
        } catch (_: Exception) {
        }

        // Note: Mesh service is now started by MainActivity

        // Show welcome message if no peers after delay
        scope.launch(Dispatchers.Main) {
            delay(10000)
            if (state.getConnectedPeersValue().isEmpty() && state.getMessagesValue().isEmpty()) {
                try {
                    val welcomeMessage = BitchatMessage(
                        sender = "system",
                        content = "get people around you to download bitchat and chat with them here!",
                        timestamp = Date(),
                        isRelay = false
                    )
                    messageManager.addMessage(welcomeMessage)
                } catch (e: Exception) {
                    logWarn("${e.printStackTrace()}")
                }
            }
        }

        // BLE receives are inserted by MessageHandler path; no VoiceNoteBus for Tor in this branch.
    }

    override fun toggleFavorite(peerID: String, logCurrentFavoriteState: () -> Unit) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)

        // Persist relationship in FavoritesPersistenceService
        try {
            var noiseKey: ByteArray? = null
            var nickname: String = meshService.getPeerNicknames()[peerID] ?: peerID

            // Case 1: Live mesh peer with known info
            val peerInfo = meshService.getPeerInfo(peerID)
            if (peerInfo?.noisePublicKey != null) {
                noiseKey = peerInfo.noisePublicKey
                nickname = peerInfo.nickname
            } else {
                // Case 2: Offline favorite entry using 64-hex noise public key as peerID
                if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    try {
                        noiseKey = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        // Prefer nickname from favorites store if available
                        val rel =
                            com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(
                                noiseKey
                            )
                        if (rel != null) nickname = rel.peerNickname
                    } catch (_: Exception) {
                    }
                }
            }

            if (noiseKey != null) {
                // Determine current favorite state from DataManager using fingerprint
                val identityManager =
                    com.bitchat.android.identity.SecureIdentityStateManager(application)
                val fingerprint = identityManager.generateFingerprint(noiseKey)
                val isNowFavorite = dataManager.favoritePeers.contains(fingerprint)

                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                // Send favorite notification via mesh or Nostr with our npub if available
                try {
                    val myNostr =
                        com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(
                            application
                        )
                    val announcementContent =
                        if (isNowFavorite) "[FAVORITED]:${myNostr?.npub ?: ""}" else "[UNFAVORITED]:${myNostr?.npub ?: ""}"
                    // Prefer mesh if session established, else try Nostr
                    if (meshService.hasEstablishedSession(peerID)) {
                        // Reuse existing private message path for notifications
                        meshService.sendPrivateMessage(
                            announcementContent,
                            peerID,
                            nickname,
                            java.util.UUID.randomUUID().toString()
                        )
                    } else {
                        val nostrTransport =
                            com.bitchat.android.nostr.NostrTransport.getInstance(application)
                        nostrTransport.senderPeerID = meshService.myPeerID
                        nostrTransport.sendFavoriteNotification(peerID, isNowFavorite)
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }

        // Log current state after toggle
        logCurrentFavoriteState()
    }

    override fun sendMessage(
        content: String,
        onSendGeohashMessage: (String, GeohashChannel) -> Unit
    ) {
        if (content.isEmpty()) return

        // Check for commands
        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            commandProcessor.processCommand(
                content, meshService, meshService.myPeerID,
                onSendMessage = { messageContent, mentions, channel ->
                    if (selectedLocationForCommand is ChannelID.Location) {
                        // Route command-generated public messages via Nostr in geohash channels
                        onSendGeohashMessage(messageContent, selectedLocationForCommand.channel)

                    } else {
                        // Default: route via mesh
                        meshService.sendMessage(messageContent, mentions, channel)
                    }
                })
            return
        }

        val mentions = messageManager.parseMentions(
            content,
            meshService.getPeerNicknames().values.toSet(),
            state.getNicknameValue()
        )
        // REMOVED: Auto-join mentioned channels feature that was incorrectly parsing hashtags from @mentions
        // This was causing messages like "test @jack#1234 test" to auto-join channel "#1234"

        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()

        if (selectedPeer != null) {
            // If the selected peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
            selectedPeer =
                com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                    selectedPeerID = selectedPeer,
                    connectedPeers = state.getConnectedPeersValue(),
                    meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                    meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                    nostrPubHexForAlias = { alias ->
                        com.bitchat.android.nostr.GeohashAliasRegistry.get(
                            alias
                        )
                    },
                    findNoiseKeyForNostr = { key ->
                        com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(
                            key
                        )
                    }
                ).also { canonical ->
                    if (canonical != state.getSelectedPrivateChatPeerValue()) {
                        privateChatManager.startPrivateChat(canonical, meshService)
                    }
                }
            // Send private message
            val recipientNickname = meshService.getPeerNicknames()[selectedPeer]
            privateChatManager.sendPrivateMessage(
                content,
                selectedPeer,
                recipientNickname,
                state.getNicknameValue(),
                meshService.myPeerID
            ) { messageContent, peerID, recipientNicknameParam, messageId ->
                // Route via MessageRouter (mesh when connected+established, else Nostr)
                val router = com.bitchat.android.services.MessageRouter.getInstance(
                    application,
                    meshService
                )
                router.sendPrivate(messageContent, peerID, recipientNicknameParam, messageId)
            }
        } else {
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is ChannelID.Location) {
                // Send to geohash channel via Nostr ephemeral event
                onSendGeohashMessage(content, selectedLocationChannel.channel)
            } else {
                // Send public/channel message via mesh
                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: meshService.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = meshService.myPeerID,
                    mentions = mentions.ifEmpty { null },
                    channel = currentChannelValue
                )

                if (currentChannelValue != null) {
                    channelManager.addChannelMessage(
                        currentChannelValue,
                        message,
                        meshService.myPeerID
                    )

                    // Check if encrypted channel
                    if (channelManager.hasChannelKey(currentChannelValue)) {
                        channelManager.sendEncryptedChannelMessage(
                            content,
                            mentions,
                            currentChannelValue,
                            state.getNicknameValue(),
                            meshService.myPeerID,
                            onEncryptedPayload = { encryptedData ->
                                // This would need proper mesh service integration
                                meshService.sendMessage(content, mentions, currentChannelValue)
                            },
                            onFallback = {
                                meshService.sendMessage(content, mentions, currentChannelValue)
                            }
                        )
                    } else {
                        meshService.sendMessage(content, mentions, currentChannelValue)
                    }
                } else {
                    messageManager.addMessage(message)
                    meshService.sendMessage(content, mentions, null)
                }
            }
        }
    }
}