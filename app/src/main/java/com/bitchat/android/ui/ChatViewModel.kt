package com.bitchat.android.ui

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.ReadReceipt
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.*
import kotlin.random.Random

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    val meshService: BluetoothMeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {

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
        override fun sendIdentityAnnouncement() = meshService.sendKeyExchangeToDevice()
        override fun sendHandshakeRequest(targetPeerID: String, pendingCount: UByte) = meshService.sendHandshakeRequest(targetPeerID, pendingCount)
        override fun getMyPeerID(): String = meshService.myPeerID
    }
    
    val privateChatManager = PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    private val commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager)
    private val notificationManager = NotificationManager(application.applicationContext)
    
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
    val favoritePeers: LiveData<Set<String>> = state.favoritePeers
    val peerSessionStates: LiveData<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: LiveData<Map<String, String>> = state.peerFingerprints
    val showAppInfo: LiveData<Boolean> = state.showAppInfo
    
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
        state.setFavoritePeers(dataManager.favoritePeers)
        dataManager.loadBlockedUsers()
        
        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()
        
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
        }
    }
    
    fun endPrivateChat() {
        privateChatManager.endPrivateChat()
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
    }
    
    // MARK: - Message Sending
    
    fun sendMessage(content: String) {
        if (content.isEmpty()) return
        
        // Check for commands
        if (content.startsWith("/")) {
            commandProcessor.processCommand(content, meshService, meshService.myPeerID) { messageContent, mentions, channel ->
                meshService.sendMessage(messageContent, mentions, channel)
            }
            return
        }
        
        val mentions = messageManager.parseMentions(content, meshService.getPeerNicknames().values.toSet(), state.getNicknameValue())
        val channels = messageManager.parseChannels(content)
        
        // Auto-join mentioned channels
        channels.forEach { channel ->
            if (!state.getJoinedChannelsValue().contains(channel)) {
                joinChannel(channel)
            }
        }
        
        val selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // Send private message
            val recipientNickname = meshService.getPeerNicknames()[selectedPeer]
            privateChatManager.sendPrivateMessage(
                content, 
                selectedPeer, 
                recipientNickname,
                state.getNicknameValue(),
                meshService.myPeerID
            ) { messageContent, peerID, recipientNicknameParam, messageId ->
                meshService.sendPrivateMessage(messageContent, peerID, recipientNicknameParam, messageId)
            }
        } else {
            // Send public/channel message
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
    
    // MARK: - Utility Functions
    
    fun getPeerIDForNickname(nickname: String): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)
        
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
     * Update reactive states for all connected peers (session states and fingerprints)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()
        
        // Update session states
        val sessionStates = currentPeers.associateWith { peerID ->
            meshService.getSessionState(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)
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
    
    fun clearNotificationsForSender(peerID: String) {
        // Clear notifications when user opens a chat
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    // MARK: - Command Autocomplete (delegated)
    
    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }
    
    // MARK: - BluetoothMeshDelegate Implementation (delegated)
    
    override fun didReceiveMessage(message: BitchatMessage) {
        meshDelegateHandler.didReceiveMessage(message)
    }
    
    override fun didConnectToPeer(peerID: String) {
        meshDelegateHandler.didConnectToPeer(peerID)
    }
    
    override fun didDisconnectFromPeer(peerID: String) {
        meshDelegateHandler.didDisconnectFromPeer(peerID)
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }
    
    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(ack: DeliveryAck) {
        meshDelegateHandler.didReceiveDeliveryAck(ack)
    }
    
    override fun didReceiveReadReceipt(receipt: ReadReceipt) {
        meshDelegateHandler.didReceiveReadReceipt(receipt)
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
        // Clear all managers
        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()
        dataManager.clearAllData()
        
        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        
        // Note: Mesh service restart is now handled by MainActivity
        // This method now only clears data, not mesh service lifecycle
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
}
