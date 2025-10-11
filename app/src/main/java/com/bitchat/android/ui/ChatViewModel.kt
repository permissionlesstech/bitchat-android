package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    val meshService: BluetoothMeshService, private val bmd: BluetoothMeshDelegate
) : AndroidViewModel(application) {
    private val debugManager by lazy {
        try {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance()
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "ChatViewModel"
    }

    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendVoiceNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendFileNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendImageNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    // Media file sending manager
    private val mediaSendingManager =
        MediaSendingManager(bmd.state, bmd.messageManager, bmd.channelManager, meshService)

    // Delegate handler for mesh callbacks

    // New Geohash architecture ViewModel (replaces God object service usage in UI path)
    val geohashViewModel = GeohashViewModel(
        application = application,
        state = bmd.state,
        messageManager = bmd.messageManager,
        privateChatManager = bmd.privateChatManager,
        meshDelegateHandler = bmd.meshDelegateHandler,
        dataManager = bmd.dataManager,
        notificationManager = bmd.notificationManager
    )


    // Expose state through LiveData (maintaining the same interface)
    val messages: LiveData<List<BitchatMessage>> = bmd.state.messages
    val connectedPeers: LiveData<List<String>> = bmd.state.connectedPeers
    val nickname: LiveData<String> = bmd.state.nickname
    val isConnected: LiveData<Boolean> = bmd.state.isConnected
    val privateChats: LiveData<Map<String, List<BitchatMessage>>> = bmd.state.privateChats
    val selectedPrivateChatPeer: LiveData<String?> = bmd.state.selectedPrivateChatPeer
    val unreadPrivateMessages: LiveData<Set<String>> = bmd.state.unreadPrivateMessages
    val joinedChannels: LiveData<Set<String>> = bmd.state.joinedChannels
    val currentChannel: LiveData<String?> = bmd.state.currentChannel
    val channelMessages: LiveData<Map<String, List<BitchatMessage>>> = bmd.state.channelMessages
    val unreadChannelMessages: LiveData<Map<String, Int>> = bmd.state.unreadChannelMessages
    val passwordProtectedChannels: LiveData<Set<String>> = bmd.state.passwordProtectedChannels
    val showPasswordPrompt: LiveData<Boolean> = bmd.state.showPasswordPrompt
    val passwordPromptChannel: LiveData<String?> = bmd.state.passwordPromptChannel
    val showSidebar: LiveData<Boolean> = bmd.state.showSidebar
    val hasUnreadChannels = bmd.state.hasUnreadChannels
    val hasUnreadPrivateMessages = bmd.state.hasUnreadPrivateMessages
    val showCommandSuggestions: LiveData<Boolean> = bmd.state.showCommandSuggestions
    val commandSuggestions: LiveData<List<CommandSuggestion>> = bmd.state.commandSuggestions
    val showMentionSuggestions: LiveData<Boolean> = bmd.state.showMentionSuggestions
    val mentionSuggestions: LiveData<List<String>> = bmd.state.mentionSuggestions
    val favoritePeers: LiveData<Set<String>> = bmd.state.favoritePeers
    val peerSessionStates: LiveData<Map<String, String>> = bmd.state.peerSessionStates
    val peerFingerprints: LiveData<Map<String, String>> = bmd.state.peerFingerprints
    val peerNicknames: LiveData<Map<String, String>> = bmd.state.peerNicknames
    val peerRSSI: LiveData<Map<String, Int>> = bmd.state.peerRSSI
    val peerDirect: LiveData<Map<String, Boolean>> = bmd.state.peerDirect
    val showAppInfo: LiveData<Boolean> = bmd.state.showAppInfo
    val selectedLocationChannel: LiveData<com.bitchat.android.geohash.ChannelID?> =
        bmd.state.selectedLocationChannel
    val isTeleported: LiveData<Boolean> = bmd.state.isTeleported
    val geohashPeople: LiveData<List<GeoPerson>> = bmd.state.geohashPeople
    val teleportedGeo: LiveData<Set<String>> = bmd.state.teleportedGeo
    val geohashParticipantCounts: LiveData<Map<String, Int>> = bmd.state.geohashParticipantCounts

    init {
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
        // Subscribe to BLE transfer progress and reflect in message deliveryStatus
        viewModelScope.launch {
            com.bitchat.android.mesh.TransferProgressManager.events.collect { evt ->
                mediaSendingManager.handleTransferProgressEvent(evt)
            }
        }
    }

    fun cancelMediaSend(messageId: String) = bmd.cancelMediaSend(messageId)

    private fun loadAndInitialize() = bmd.loadAndInitialize(
        logCurrentFavoriteState = { logCurrentFavoriteState() },
        initializeSessionStateMonitoring = { initializeSessionStateMonitoring() },
        initializeGeoHashVM = { geohashViewModel.initialize() }
    )

//    override fun onCleared() {
//        super.onCleared()
//        // Note: Mesh service lifecycle is now managed by MainActivity
//    }

    // MARK: - Nickname Management

    fun setNickname(newNickname: String) = bmd.setNickname(newNickname)

    /**
     * Ensure Nostr DM subscription for a geohash conversation key if known
     * Minimal-change approach: reflectively access GeohashViewModel internals to reuse pipeline
     */
    private fun ensureGeohashDMSubscriptionIfNeeded(convKey: String) {
        try {
            val repoField = GeohashViewModel::class.java.getDeclaredField("repo")
            repoField.isAccessible = true
            val repo =
                repoField.get(geohashViewModel) as com.bitchat.android.nostr.GeohashRepository
            val gh = repo.getConversationGeohash(convKey)
            if (!gh.isNullOrEmpty()) {
                val subMgrField =
                    GeohashViewModel::class.java.getDeclaredField("subscriptionManager")
                subMgrField.isAccessible = true
                val subMgr =
                    subMgrField.get(geohashViewModel) as com.bitchat.android.nostr.NostrSubscriptionManager
                val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                    gh,
                    getApplication()
                )
                val subId = "geo-dm-$gh"
                val currentDmSubField =
                    GeohashViewModel::class.java.getDeclaredField("currentDmSubId")
                currentDmSubField.isAccessible = true
                val currentId = currentDmSubField.get(geohashViewModel) as String?
                if (currentId != subId) {
                    (currentId)?.let { subMgr.unsubscribe(it) }
                    currentDmSubField.set(geohashViewModel, subId)
                    subMgr.subscribeGiftWraps(
                        pubkey = identity.publicKeyHex,
                        sinceMs = System.currentTimeMillis() - 172800000L,
                        id = subId,
                        handler = { event ->
                            val dmHandlerField =
                                GeohashViewModel::class.java.getDeclaredField("dmHandler")
                            dmHandlerField.isAccessible = true
                            val dmHandler =
                                dmHandlerField.get(geohashViewModel) as com.bitchat.android.nostr.NostrDirectMessageHandler
                            dmHandler.onGiftWrap(event, gh, identity)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureGeohashDMSubscriptionIfNeeded failed: ${e.message}")
        }
    }

    // MARK: - Channel Management (delegated)

    fun joinChannel(channel: String, password: String? = null) =
        bmd.joinChannel(channel, password)


    fun switchToChannel(channel: String?) = bmd.switchToChannel(channel)


    fun leaveChannel(channel: String) = bmd.leaveChannel(channel)


    // MARK: - Private Chat Management (delegated)

    fun startPrivateChat(peerID: String) = bmd.startPrivateChat(peerID) {
        ensureGeohashDMSubscriptionIfNeeded(peerID)
    }

    fun endPrivateChat() = bmd.endPrivateChat()

    // MARK: - Open Latest Unread Private Chat

    fun openLatestUnreadPrivateChat() = bmd.openLatestUnreadPrivateChat(
        onEnsureGeohashDMSubscription = { ensureGeohashDMSubscriptionIfNeeded(it) }
    )

    // END - Open Latest Unread Private Chat


    // MARK: - Message Sending

    fun sendMessage(content: String) = bmd.sendMessage(content) { msg, channel ->
        geohashViewModel.sendGeohashMessage(
            msg, channel, meshService.myPeerID, bmd.state.getNicknameValue()
        )
    }

    // MARK: - Utility Functions

    fun getPeerIDForNickname(nickname: String): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }

    fun toggleFavorite(peerID: String) = bmd.toggleFavorite(peerID) { logCurrentFavoriteState() }

    private fun logCurrentFavoriteState() {
        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "LiveData favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${bmd.dataManager.favoritePeers}")
        Log.i(
            "ChatViewModel",
            "Peer fingerprints: ${bmd.privateChatManager.getAllPeerFingerprints()}"
        )
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
    private fun updateReactiveStates() = bmd.updateReactiveStates()

    // MARK: - Debug and Troubleshooting

    fun getDebugStatus(): String {
        return meshService.getDebugStatus()
    }

    // Note: Mesh service restart is now handled by MainActivity
    // This function is no longer needed

    fun setAppBackgroundState(inBackground: Boolean) = bmd.setAppBackgroundState(inBackground)

    fun changeMeshServiceBGState(b: Boolean) = bmd.changeMeshServiceBGState(b)
    fun startMeshServices() = bmd.startMeshServices()
    fun stopMeshServices() = bmd.startMeshServices()

    fun setCurrentPrivateChatPeer(peerID: String?) = bmd.setCurrentPrivateChatPeer(peerID)


    fun setCurrentGeohash(geohash: String?) = bmd.setCurrentGeohash(geohash)

    fun clearNotificationsForSender(peerID: String) = bmd.clearNotificationsForSender(peerID)

    fun clearNotificationsForGeohash(geohash: String) = bmd.clearNotificationsForGeohash(geohash)

    /**
     * Clear mesh mention notifications when user opens mesh chat
     */
    fun clearMeshMentionNotifications() = bmd.clearMeshMentionNotifications()


    // MARK: - Command Autocomplete (delegated)

    fun updateCommandSuggestions(input: String) = bmd.updateCommandSuggestions(input)


    fun selectCommandSuggestion(suggestion: CommandSuggestion) =
        bmd.selectCommandSuggestion(suggestion)

    // MARK: - Mention Autocomplete

    fun updateMentionSuggestions(input: String) = bmd.updateMentionSuggestions(input)

    fun selectMentionSuggestion(nickname: String, currentText: String) =
        bmd.selectMentionSuggestion(nickname, currentText)


    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager

    // MARK: - Emergency Clear

    fun panicClearAllData() = bmd.panicClearAllData { geohashViewModel.panicReset() }

    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        return geohashViewModel.geohashParticipantCount(geohash)
    }

    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        geohashViewModel.beginGeohashSampling(geohashes)
    }

    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        // No-op in refactored architecture; sampling subscriptions are short-lived
    }

    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return geohashViewModel.isPersonTeleported(pubkeyHex)
    }

    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String) {
        geohashViewModel.startGeohashDM(pubkeyHex) { convKey ->
            startPrivateChat(convKey)
        }
    }

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        geohashViewModel.selectLocationChannel(channel)
    }

    /**
     * Block a user in geohash channels by their nickname
     */
    fun blockUserInGeohash(targetNickname: String) {
        geohashViewModel.blockUserInGeohash(targetNickname)
    }

    // MARK: - Navigation Management

    fun showAppInfo() = bmd.state.setShowAppInfo(true)


    fun hideAppInfo() = bmd.state.setShowAppInfo(false)


    fun showSidebar() = bmd.state.setShowSidebar(true)


    fun hideSidebar() = bmd.state.setShowSidebar(false)


    /**
     * Handle Android back navigation
     * Returns true if the back press was handled, false if it should be passed to the system
     */
    fun handleBackPressed(): Boolean {
        return when {
            // Close app info dialog
            bmd.state.getShowAppInfoValue() -> {
                hideAppInfo()
                true
            }
            // Close sidebar
            bmd.state.getShowSidebarValue() -> {
                hideSidebar()
                true
            }
            // Close password dialog
            bmd.state.getShowPasswordPromptValue() -> {
                bmd.state.setShowPasswordPrompt(false)
                bmd.state.setPasswordPromptChannel(null)
                true
            }
            // Exit private chat
            bmd.state.getSelectedPrivateChatPeerValue() != null -> {
                endPrivateChat()
                true
            }
            // Exit channel view
            bmd.state.getCurrentChannelValue() != null -> {
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
    fun colorForNostrPubkey(
        pubkeyHex: String,
        isDark: Boolean
    ): androidx.compose.ui.graphics.Color {
        return geohashViewModel.colorForNostrPubkey(pubkeyHex, isDark)
    }
}
