package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.nostr.NostrGeohashService
import kotlinx.coroutines.launch
import com.bitchat.android.util.NotificationIntervalManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.random.Random

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    val meshService: BluetoothMeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "ChatViewModel"
    }

    // State management
    private val state = ChatState()
    
    // Specialized managers
    private val dataManager = DataManager(application.applicationContext)
    private val messageManager = MessageManager(state)
    private val channelManager = ChannelManager(state, messageManager, dataManager, viewModelScope)
    
    // Create Noise session delegate for clean dependency injection
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = meshService.hasEstablishedSession(peerID)
        override fun initiateHandshake(peerID: String) = meshService.initiateNoiseHandshake(peerID) 
        override fun getMyPeerID(): String = meshService.myPeerID
    }
    
    val privateChatManager = PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    private val commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager)
    private val notificationManager = NotificationManager(
      application.applicationContext,
      NotificationManagerCompat.from(application.applicationContext),
      NotificationIntervalManager()
    )
    
    // Delegate handler for mesh callbacks
    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(application.applicationContext) },
        getMyPeerID = { meshService.myPeerID },
        getMeshService = { meshService }
    )
    
    // Nostr and Geohash service - initialize singleton
    private val nostrGeohashService = NostrGeohashService.initialize(
        application = application,
        state = state,
        messageManager = messageManager,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        coroutineScope = viewModelScope,
        dataManager = dataManager,
        notificationManager = notificationManager
    )

    // Expose state through LiveData (maintaining the same interface)
    val messages: LiveData<List<BitchatMessage>> = state.messages
    val connectedPeers: LiveData<List<String>> = state.connectedPeers
    val nickname: LiveData<String> = state.nickname
    val isConnected: LiveData<Boolean> = state.isConnected
    val privateChats: LiveData<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: LiveData<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: LiveData<Set<String>> = state.unreadPrivateMessages
    val joinedChannels: LiveData<Set<String>> = state.joinedChannels
    val currentChannel: LiveData<String?> = state.currentChannel
    val channelMessages: LiveData<Map<String, List<BitchatMessage>>> = state.channelMessages
    val unreadChannelMessages: LiveData<Map<String, Int>> = state.unreadChannelMessages
    val passwordProtectedChannels: LiveData<Set<String>> = state.passwordProtectedChannels
    val showPasswordPrompt: LiveData<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: LiveData<String?> = state.passwordPromptChannel
    val showSidebar: LiveData<Boolean> = state.showSidebar
    val hasUnreadChannels = state.hasUnreadChannels
    val hasUnreadPrivateMessages = state.hasUnreadPrivateMessages
    val showCommandSuggestions: LiveData<Boolean> = state.showCommandSuggestions
    val commandSuggestions: LiveData<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: LiveData<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: LiveData<List<String>> = state.mentionSuggestions
    val favoritePeers: LiveData<Set<String>> = state.favoritePeers
    val peerSessionStates: LiveData<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: LiveData<Map<String, String>> = state.peerFingerprints
    val peerNicknames: LiveData<Map<String, String>> = state.peerNicknames
    val peerRSSI: LiveData<Map<String, Int>> = state.peerRSSI
    val peerDirect: LiveData<Map<String, Boolean>> = state.peerDirect
    val showAppInfo: LiveData<Boolean> = state.showAppInfo
    val selectedLocationChannel: LiveData<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: LiveData<Boolean> = state.isTeleported
    val geohashPeople: LiveData<List<GeoPerson>> = state.geohashPeople
    val teleportedGeo: LiveData<Set<String>> = state.teleportedGeo
    val geohashParticipantCounts: LiveData<Map<String, Int>> = state.geohashParticipantCounts

    init {
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
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
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        viewModelScope.launch {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().debugMessages.collect { msgs ->
                if (com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().verboseLoggingEnabled.value) {
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
        
        // Initialize location channel state
        nostrGeohashService.initializeLocationChannelState()

        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(getApplication())

        // Initialize Nostr integration
        nostrGeohashService.initializeNostrIntegration()

        // Ensure NostrTransport knows our mesh peer ID for embedded packets
        try {
            val nostrTransport = com.bitchat.android.nostr.NostrTransport.getInstance(getApplication())
            nostrTransport.senderPeerID = meshService.myPeerID
        } catch (_: Exception) { }

        // Note: Mesh service is now started by MainActivity
        
        // Show welcome message if no peers after delay
        viewModelScope.launch {
            delay(10000)
            if (state.getConnectedPeersValue().isEmpty() && state.getMessagesValue().isEmpty()) {
                val welcomeMessage = BitchatMessage(
                    sender = "system",
                    content = "get people around you to download bitchat and chat with them here!",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(welcomeMessage)
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        // Note: Mesh service lifecycle is now managed by MainActivity
    }
    
    // MARK: - Nickname Management
    
    fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        meshService.sendBroadcastAnnounce()
    }
    
    // MARK: - Channel Management (delegated)
    
    fun joinChannel(channel: String, password: String? = null): Boolean {
        return channelManager.joinChannel(channel, password, meshService.myPeerID)
    }
    
    fun switchToChannel(channel: String?) {
        channelManager.switchToChannel(channel)
    }
    
    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        meshService.sendMessage("left $channel")
    }
    
    // MARK: - Private Chat Management (delegated)
    
    fun startPrivateChat(peerID: String) {
        val success = privateChatManager.startPrivateChat(peerID, meshService)
        if (success) {
            // Notify notification manager about current private chat
            setCurrentPrivateChatPeer(peerID)
            // Clear notifications for this sender since user is now viewing the chat
            clearNotificationsForSender(peerID)

            // Persistently mark all messages in this conversation as read so Nostr fetches
            // after app restarts won't re-mark them as unread.
            try {
                val seen = com.bitchat.android.services.SeenMessageStore.getInstance(getApplication())
                val chats = state.getPrivateChatsValue()
                val messages = chats[peerID] ?: emptyList()
                messages.forEach { msg ->
                    try { seen.markRead(msg.id) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }
    
    fun endPrivateChat() {
        privateChatManager.endPrivateChat()
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        clearMeshMentionNotifications()
    }
    
    // MARK: - Message Sending
    
    fun sendMessage(content: String) {
        if (content.isEmpty()) return
        
        // Check for commands
        if (content.startsWith("/")) {
            commandProcessor.processCommand(content, meshService, meshService.myPeerID, { messageContent, mentions, channel ->
                meshService.sendMessage(messageContent, mentions, channel)
            }, this)
            return
        }
        
        val mentions = messageManager.parseMentions(content, meshService.getPeerNicknames().values.toSet(), state.getNicknameValue())
        // REMOVED: Auto-join mentioned channels feature that was incorrectly parsing hashtags from @mentions
        // This was causing messages like "test @jack#1234 test" to auto-join channel "#1234"
        
        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // If the selected peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
            selectedPeer = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = selectedPeer,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> meshService.getPeerInfo(pid)?.noisePublicKey },
                meshHasPeer = { pid -> meshService.getPeerInfo(pid)?.isConnected == true },
                nostrPubHexForAlias = { alias -> nostrGeohashService.getNostrKeyMapping()[alias] },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
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
                val router = com.bitchat.android.services.MessageRouter.getInstance(getApplication(), meshService)
                router.sendPrivate(messageContent, peerID, recipientNicknameParam, messageId)
            }
        } else {
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                // Send to geohash channel via Nostr ephemeral event
                nostrGeohashService.sendGeohashMessage(content, selectedLocationChannel.channel, meshService.myPeerID, state.getNicknameValue())
            } else {
                // Send public/channel message via mesh
                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: meshService.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = meshService.myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = currentChannelValue
                )

                if (currentChannelValue != null) {
                    channelManager.addChannelMessage(currentChannelValue, message, meshService.myPeerID)

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

    // MARK: - Utility Functions
    
    fun getPeerIDForNickname(nickname: String): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)

        // Persist relationship in FavoritesPersistenceService when we have Noise key
        try {
            val peerInfo = meshService.getPeerInfo(peerID)
            val noiseKey = peerInfo?.noisePublicKey
            val nickname = peerInfo?.nickname ?: (meshService.getPeerNicknames()[peerID] ?: peerID)
            if (noiseKey != null) {
                val isNowFavorite = dataManager.favoritePeers.contains(
                    com.bitchat.android.mesh.PeerFingerprintManager.getInstance().getFingerprintForPeer(peerID) ?: ""
                )
                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                // Send favorite notification via mesh or Nostr with our npub if available
                try {
                    val myNostr = com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(getApplication())
                    val announcementContent = if (isNowFavorite) "[FAVORITED]:${myNostr?.npub ?: ""}" else "[UNFAVORITED]:${myNostr?.npub ?: ""}"
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
                        val nostrTransport = com.bitchat.android.nostr.NostrTransport.getInstance(getApplication())
                        nostrTransport.senderPeerID = meshService.myPeerID
                        nostrTransport.sendFavoriteNotification(peerID, isNowFavorite)
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Log current state after toggle
        logCurrentFavoriteState()
    }
    
    private fun logCurrentFavoriteState() {
        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "LiveData favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i("ChatViewModel", "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i("ChatViewModel", "==============================")
    }
    
    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
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
            meshService.getSessionState(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                com.bitchat.android.services.MessageRouter
                    .getInstance(getApplication(), meshService)
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
        } catch (_: Exception) { }
    }

    // MARK: - Debug and Troubleshooting
    
    fun getDebugStatus(): String {
        return meshService.getDebugStatus()
    }
    
    // Note: Mesh service restart is now handled by MainActivity
    // This function is no longer needed
    
    fun setAppBackgroundState(inBackground: Boolean) {
        // Forward to notification manager for notification logic
        notificationManager.setAppBackgroundState(inBackground)
    }
    
    fun setCurrentPrivateChatPeer(peerID: String?) {
        // Update notification manager with current private chat peer
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }
    
    fun setCurrentGeohash(geohash: String?) {
        // Update notification manager with current geohash for notification logic
        notificationManager.setCurrentGeohash(geohash)
    }

    fun clearNotificationsForSender(peerID: String) {
        // Clear notifications when user opens a chat
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    fun clearNotificationsForGeohash(geohash: String) {
        // Clear notifications when user opens a geohash chat
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    /**
     * Clear mesh mention notifications when user opens mesh chat
     */
    fun clearMeshMentionNotifications() {
        notificationManager.clearMeshMentionNotifications()
    }

    // MARK: - Command Autocomplete (delegated)
    
    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, meshService, this)
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(nickname, currentText)
    }
    
    // MARK: - BluetoothMeshDelegate Implementation (delegated)
    
    override fun didReceiveMessage(message: BitchatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return meshDelegateHandler.isFavorite(peerID)
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
    
    // MARK: - Emergency Clear
    
    fun panicClearAllData() {
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
        
        // Clear Nostr/geohash state, keys, connections, and reinitialize from scratch
        try {
            nostrGeohashService.panicResetNostrAndGeohash()
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
                val identityManager = com.bitchat.android.identity.SecureIdentityStateManager(getApplication())
                identityManager.clearIdentityData()
                Log.d(TAG, "✅ Cleared secure identity state")
            } catch (e: Exception) {
                Log.d(TAG, "SecureIdentityStateManager not available or already cleared: ${e.message}")
            }
            
            Log.d(TAG, "✅ Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing cryptographic data: ${e.message}")
        }
    }

    /**
     * Get messages for a specific geohash timeline
     */
    fun getGeohashMessages(geohash: String): List<BitchatMessage> {
        return nostrGeohashService.getGeohashMessages(geohash)
    }

    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        return nostrGeohashService.geohashParticipantCount(geohash)
    }

    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        nostrGeohashService.beginGeohashSampling(geohashes)
    }

    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        nostrGeohashService.endGeohashSampling()
    }

    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return nostrGeohashService.isPersonTeleported(pubkeyHex)
    }

    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String) {
        nostrGeohashService.startGeohashDM(pubkeyHex) { convKey ->
            startPrivateChat(convKey)
        }
    }

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        nostrGeohashService.selectLocationChannel(channel)
    }

    /**
     * Block a user in geohash channels by their nickname
     */
    fun blockUserInGeohash(targetNickname: String) {
        nostrGeohashService.blockUserInGeohash(targetNickname)
    }

    // MARK: - Navigation Management
    
    fun showAppInfo() {
        state.setShowAppInfo(true)
    }
    
    fun hideAppInfo() {
        state.setShowAppInfo(false)
    }
    
    fun showSidebar() {
        state.setShowSidebar(true)
    }
    
    fun hideSidebar() {
        state.setShowSidebar(false)
    }
    
    /**
     * Handle Android back navigation
     * Returns true if the back press was handled, false if it should be passed to the system
     */
    fun handleBackPressed(): Boolean {
        return when {
            // Close app info dialog
            state.getShowAppInfoValue() -> {
                hideAppInfo()
                true
            }
            // Close sidebar
            state.getShowSidebarValue() -> {
                hideSidebar()
                true
            }
            // Close password dialog
            state.getShowPasswordPromptValue() -> {
                state.setShowPasswordPrompt(false)
                state.setPasswordPromptChannel(null)
                true
            }
            // Exit private chat
            state.getSelectedPrivateChatPeerValue() != null -> {
                endPrivateChat()
                true
            }
            // Exit channel view
            state.getCurrentChannelValue() != null -> {
                switchToChannel(null)
                true
            }
            // No special navigation state - let system handle (usually exits app)
            else -> false
        }
    }

    // MARK: - iOS-Compatible Color System

    /**
     * Get consistent color for a mesh peer by ID (iOS-compatible)
     */
    fun colorForMeshPeer(peerID: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        // Try to get stable Noise key, fallback to peer ID
        val seed = "noise:${peerID.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }

    /**
     * Get consistent color for a Nostr pubkey (iOS-compatible)
     */
    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        return nostrGeohashService.colorForNostrPubkey(pubkeyHex, isDark)
    }
}
