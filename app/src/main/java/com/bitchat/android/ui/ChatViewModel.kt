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
import com.bitchat.android.protocol.BitchatPacket

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
    val showMentionSuggestions: LiveData<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: LiveData<List<String>> = state.mentionSuggestions
    val favoritePeers: LiveData<Set<String>> = state.favoritePeers
    val peerSessionStates: LiveData<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: LiveData<Map<String, String>> = state.peerFingerprints
    val peerNicknames: LiveData<Map<String, String>> = state.peerNicknames
    val peerRSSI: LiveData<Map<String, Int>> = state.peerRSSI
    val showAppInfo: LiveData<Boolean> = state.showAppInfo
    val selectedLocationChannel: LiveData<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: LiveData<Boolean> = state.isTeleported
    val geohashPeople: LiveData<List<GeoPerson>> = state.geohashPeople
    val teleportedGeo: LiveData<Set<String>> = state.teleportedGeo
    
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
        
        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()
        
        // Initialize location channel state
        initializeLocationChannelState()
        
        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(getApplication())
        
        // Initialize Nostr integration
        initializeNostrIntegration()
        
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
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                // Send to geohash channel via Nostr ephemeral event
                sendGeohashMessage(content, selectedLocationChannel.channel)
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
    
    /**
     * Send message to geohash channel via Nostr ephemeral event
     */
    private fun sendGeohashMessage(content: String, channel: com.bitchat.android.geohash.GeohashChannel) {
        viewModelScope.launch {
            try {
                val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                    forGeohash = channel.geohash,
                    context = getApplication()
                )
                
                val teleported = state.isTeleported.value ?: false
                
                val event = com.bitchat.android.nostr.NostrProtocol.createEphemeralGeohashEvent(
                    content = content,
                    geohash = channel.geohash,
                    senderIdentity = identity,
                    nickname = state.getNicknameValue()
                )
                
                val nostrRelayManager = com.bitchat.android.nostr.NostrRelayManager.getInstance(getApplication())
                nostrRelayManager.sendEvent(event)
                
                Log.i(TAG, "📤 Sent geohash message to ${channel.geohash}: ${content.take(50)}")
                
                // Add local echo message
                val localMessage = BitchatMessage(
                    sender = state.getNicknameValue() ?: meshService.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = "geohash:${channel.geohash}",
                    channel = "#${channel.geohash}"
                )
                messageManager.addMessage(localMessage)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash message: ${e.message}")
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
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
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

        val nicknames = meshService.getPeerNicknames()
        state.setPeerNicknames(nicknames)

        val rssiValues = meshService.getPeerRSSI()
        state.setPeerRSSI(rssiValues)
    }
    
    // MARK: - Nostr Message Integration
    
    private val processedNostrEvents = mutableSetOf<String>()
    private val processedNostrEventOrder = mutableListOf<String>()
    private val maxProcessedNostrEvents = 2000
    private val processedNostrAcks = mutableSetOf<String>()
    private val nostrKeyMapping = mutableMapOf<String, String>() // senderPeerID -> nostrPubkey
    
    /**
     * Initialize Nostr relay subscriptions for gift wraps and geohash events
     */
    private fun initializeNostrIntegration() {
        viewModelScope.launch {
            val nostrRelayManager = com.bitchat.android.nostr.NostrRelayManager.getInstance(getApplication())
            
            // Connect to relays
            nostrRelayManager.connect()
            
            // Get current Nostr identity
            val currentIdentity = com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(getApplication())
            if (currentIdentity == null) {
                Log.w(TAG, "No Nostr identity available for subscriptions")
                return@launch
            }
            
            // Subscribe to gift wraps (NIP-17 private messages)
            val dmFilter = com.bitchat.android.nostr.NostrFilter.giftWrapsFor(
                pubkey = currentIdentity.publicKeyHex,
                since = System.currentTimeMillis() - 86400000L // Last 24 hours
            )
            
            nostrRelayManager.subscribe(
                filter = dmFilter,
                id = "chat-messages"
            ) { event ->
                handleNostrMessage(event)
            }
            
            Log.i(TAG, "✅ Nostr integration initialized with gift wrap subscription")
        }
    }
    
    /**
     * Handle incoming Nostr message (gift wrap)
     */
    private fun handleNostrMessage(giftWrap: com.bitchat.android.nostr.NostrEvent) {
        // Simple deduplication
        if (processedNostrEvents.contains(giftWrap.id)) return
        processedNostrEvents.add(giftWrap.id)
        
        // Manage deduplication cache size
        processedNostrEventOrder.add(giftWrap.id)
        if (processedNostrEventOrder.size > maxProcessedNostrEvents) {
            val oldestId = processedNostrEventOrder.removeAt(0)
            processedNostrEvents.remove(oldestId)
        }
        
        // Client-side filtering: ignore messages older than 24 hours + 15 minutes buffer
        val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
        if (messageAge > 87300) { // 24 hours + 15 minutes
            return
        }
        
        Log.d(TAG, "Processing Nostr message: ${giftWrap.id.take(16)}...")
        
        val currentIdentity = com.bitchat.android.nostr.NostrIdentityBridge.getCurrentNostrIdentity(getApplication())
        if (currentIdentity == null) {
            Log.w(TAG, "No Nostr identity available for decryption")
            return
        }
        
        try {
            val decryptResult = com.bitchat.android.nostr.NostrProtocol.decryptPrivateMessage(
                giftWrap = giftWrap,
                recipientIdentity = currentIdentity
            )
            
            if (decryptResult == null) {
                Log.w(TAG, "Failed to decrypt Nostr message")
                return
            }
            
            val (content, senderPubkey, rumorTimestamp) = decryptResult
            
            // Expect embedded BitChat packet content
            if (!content.startsWith("bitchat1:")) {
                Log.d(TAG, "Ignoring non-embedded Nostr DM content")
                return
            }
            
            val base64Content = content.removePrefix("bitchat1:")
            val packetData = base64URLDecode(base64Content)
            if (packetData == null) {
                Log.e(TAG, "Failed to decode base64url BitChat packet")
                return
            }
            
            val packet = com.bitchat.android.protocol.BitchatPacket.fromBinaryData(packetData)
            if (packet == null) {
                Log.e(TAG, "Failed to parse embedded BitChat packet from Nostr DM")
                return
            }
            
            // Only process noiseEncrypted envelope for private messages/receipts
            if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) {
                Log.w(TAG, "Unsupported embedded packet type: ${packet.type}")
                return
            }
            
            // Validate recipient if present
            packet.recipientID?.let { rid ->
                val ridHex = rid.joinToString("") { "%02x".format(it) }
                if (ridHex != meshService.myPeerID) {
                    return
                }
            }
            
            // Parse plaintext typed payload (NoisePayload)
            val noisePayload = com.bitchat.android.model.NoisePayload.decode(packet.payload)
            if (noisePayload == null) {
                Log.e(TAG, "Failed to parse embedded NoisePayload")
                return
            }
            
            // Map sender by Nostr pubkey to Noise key when possible
            val senderNoiseKey = findNoiseKeyForNostrPubkey(senderPubkey)
            val messageTimestamp = Date(rumorTimestamp * 1000L)
            val senderNickname = if (senderNoiseKey != null) {
                // Get nickname from favorites
                getFavoriteNickname(senderNoiseKey) ?: "Unknown"
            } else {
                "Unknown"
            }
            
            // Stable target ID if we know Noise key; otherwise temporary Nostr-based peer
            val targetPeerID = senderNoiseKey?.let { 
                it.joinToString("") { byte -> "%02x".format(byte) }
            } ?: "nostr_${senderPubkey.take(16)}"
            
            // Store Nostr key mapping
            nostrKeyMapping[targetPeerID] = senderPubkey
            
            processNoisePayload(noisePayload, targetPeerID, senderNickname, messageTimestamp)
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Nostr message: ${e.message}")
        }
    }
    
    /**
     * Process NoisePayload from Nostr message
     */
    private fun processNoisePayload(
        noisePayload: com.bitchat.android.model.NoisePayload,
        targetPeerID: String,
        senderNickname: String,
        messageTimestamp: Date
    ) {
        when (noisePayload.type) {
            com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = com.bitchat.android.model.PrivateMessagePacket.decode(noisePayload.data)
                if (pm == null) {
                    Log.e(TAG, "Failed to decode PrivateMessagePacket")
                    return
                }
                
                val messageId = pm.messageID
                val messageContent = pm.content
                
                // Handle favorite/unfavorite notifications
                if (messageContent.startsWith("[FAVORITED]") || messageContent.startsWith("[UNFAVORITED]")) {
                    handleFavoriteNotification(messageContent, targetPeerID, senderNickname)
                    return
                }
                
                // Check for duplicate message
                val existingChats = state.getPrivateChatsValue()
                var messageExists = false
                for ((_, messages) in existingChats) {
                    if (messages.any { it.id == messageId }) {
                        messageExists = true
                        break
                    }
                }
                if (messageExists) return
                
                // Check if viewing this chat
                val isViewingThisChat = state.getSelectedPrivateChatPeerValue() == targetPeerID
                
                // Create BitchatMessage
                val message = BitchatMessage(
                    id = messageId,
                    sender = senderNickname,
                    content = messageContent,
                    timestamp = messageTimestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = targetPeerID,
                    deliveryStatus = com.bitchat.android.model.DeliveryStatus.Delivered(
                        to = state.getNicknameValue() ?: "Unknown",
                        at = Date()
                    )
                )
                
                // Add to private chats
                privateChatManager.handleIncomingPrivateMessage(message)
                
                // Send read receipt if viewing
                if (isViewingThisChat) {
                    privateChatManager.sendReadReceiptsForPeer(targetPeerID, meshService)
                }
                
                Log.i(TAG, "📥 Processed Nostr private message from $senderNickname")
            }
            
            com.bitchat.android.model.NoisePayloadType.DELIVERED -> {
                val messageId = String(noisePayload.data, Charsets.UTF_8)
                // Use the existing delegate to handle delivery acknowledgment
                meshDelegateHandler.didReceiveDeliveryAck(messageId, targetPeerID)
                Log.d(TAG, "📥 Processed Nostr delivery ACK for message $messageId")
            }
            
            com.bitchat.android.model.NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(noisePayload.data, Charsets.UTF_8)
                // Use the existing delegate to handle read receipt
                meshDelegateHandler.didReceiveReadReceipt(messageId, targetPeerID)
                Log.d(TAG, "📥 Processed Nostr read receipt for message $messageId")
            }
        }
    }
    
    /**
     * Find Noise key for Nostr pubkey from favorites
     */
    private fun findNoiseKeyForNostrPubkey(nostrPubkey: String): ByteArray? {
        return com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(nostrPubkey)
    }
    
    /**
     * Get favorite nickname for Noise key
     */
    private fun getFavoriteNickname(noiseKey: ByteArray): String? {
        return com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)?.peerNickname
    }
    
    /**
     * Handle favorite/unfavorite notification
     */
    private fun handleFavoriteNotification(content: String, fromPeerID: String, senderNickname: String) {
        val isFavorite = content.startsWith("[FAVORITED]")
        val action = if (isFavorite) "favorited" else "unfavorited"
        
        // Show system message
        val systemMessage = BitchatMessage(
            sender = "system",
            content = "$senderNickname $action you",
            timestamp = Date(),
            isRelay = false
        )
        messageManager.addMessage(systemMessage)
        
        Log.i(TAG, "📥 Processed favorite notification: $senderNickname $action you")
    }
    
    /**
     * Base64URL decode (without padding)
     */
    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
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
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, meshService)
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
    
    // MARK: - Geohash Participant Tracking (for location channels)
    
    private val geohashParticipants = mutableMapOf<String, MutableMap<String, Date>>() // geohash -> participantId -> lastSeen
    private var geohashSamplingJob: kotlinx.coroutines.Job? = null
    private var geoParticipantsTimer: kotlinx.coroutines.Job? = null
    
    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000) // 5 minutes ago
        val participants = geohashParticipants[geohash] ?: return 0
        
        // Remove expired participants
        val iterator = participants.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.before(cutoff)) {
                iterator.remove()
            }
        }
        
        return participants.size
    }
    
    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        // Cancel existing sampling
        geohashSamplingJob?.cancel()
        
        if (geohashes.isEmpty()) return
        
        Log.d(TAG, "🌍 Beginning geohash sampling for ${geohashes.size} geohashes")
        
        geohashSamplingJob = viewModelScope.launch {
            val nostrRelayManager = com.bitchat.android.nostr.NostrRelayManager.getInstance(getApplication())
            
            // Subscribe to each geohash for ephemeral events (kind 20000)
            geohashes.forEach { geohash ->
                val filter = com.bitchat.android.nostr.NostrFilter.geohashEphemeral(
                    geohash = geohash,
                    since = System.currentTimeMillis() - 86400000L, // Last 24 hours
                    limit = 200
                )
                
                nostrRelayManager.subscribe(
                    filter = filter,
                    id = "geohash-$geohash"
                ) { event ->
                    handleGeohashEvent(event, geohash)
                }
                
                Log.d(TAG, "Subscribed to geohash events for: $geohash")
            }
        }
    }
    
    /**
     * Handle geohash ephemeral event (kind 20000)
     */
    private fun handleGeohashEvent(event: com.bitchat.android.nostr.NostrEvent, geohash: String) {
        // Extract participant ID from event (sender pubkey)
        val participantId = event.pubkey.take(16) // Use first 16 chars as participant ID
        val timestamp = Date(event.createdAt * 1000L)
        
        // Update participant activity
        updateGeohashParticipant(geohash, participantId, timestamp)
        
        Log.v(TAG, "Updated geohash $geohash participant activity: $participantId")
    }
    
    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        Log.d(TAG, "🌍 Ending geohash sampling")
        geohashSamplingJob?.cancel()
        geohashSamplingJob = null
    }
    
    /**
     * Update participant activity for a geohash
     */
    private fun updateGeohashParticipant(geohash: String, participantId: String, lastSeen: Date) {
        val participants = geohashParticipants.getOrPut(geohash) { mutableMapOf() }
        participants[participantId] = lastSeen
        
        // Update geohash people list if this is the current geohash
        if (currentGeohash == geohash) {
            refreshGeohashPeople()
        }
    }
    
    /**
     * Record geohash participant by pubkey hex (iOS-compatible)
     */
    private fun recordGeoParticipant(pubkeyHex: String) {
        currentGeohash?.let { geohash ->
            updateGeohashParticipant(geohash, pubkeyHex, Date())
        }
    }
    
    /**
     * Refresh geohash people list from current participants (iOS-compatible)
     */
    private fun refreshGeohashPeople() {
        val geohash = currentGeohash
        if (geohash == null) {
            state.setGeohashPeople(emptyList())
            return
        }
        
        // Use 5-minute activity window (matches iOS exactly)
        val cutoff = Date(System.currentTimeMillis() - 5 * 60 * 1000)
        val participants = geohashParticipants[geohash] ?: mutableMapOf()
        
        // Remove expired participants
        val iterator = participants.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.before(cutoff)) {
                iterator.remove()
            }
        }
        geohashParticipants[geohash] = participants
        
        // Build GeoPerson list
        val people = participants.map { (pubkeyHex, lastSeen) ->
            GeoPerson(
                id = pubkeyHex.lowercase(),
                displayName = displayNameForNostrPubkey(pubkeyHex),
                lastSeen = lastSeen
            )
        }.sortedByDescending { it.lastSeen } // Most recent first
        
        state.setGeohashPeople(people)
        Log.d(TAG, "🌍 Refreshed geohash people: ${people.size} participants in $geohash")
    }
    
    /**
     * Start participant refresh timer for geohash channels (iOS-compatible)
     */
    private fun startGeoParticipantsTimer() {
        // Cancel existing timer
        geoParticipantsTimer?.cancel()
        
        // Start 30-second refresh timer (matches iOS)
        geoParticipantsTimer = viewModelScope.launch {
            while (currentGeohash != null) {
                delay(30000) // 30 seconds
                refreshGeohashPeople()
            }
        }
    }
    
    /**
     * Stop participant refresh timer
     */
    private fun stopGeoParticipantsTimer() {
        geoParticipantsTimer?.cancel()
        geoParticipantsTimer = null
    }
    
    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return state.getTeleportedGeoValue().contains(pubkeyHex.lowercase())
    }
    
    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String) {
        val convKey = "nostr_${pubkeyHex.take(16)}"
        nostrKeyMapping[convKey] = pubkeyHex
        startPrivateChat(convKey)
        Log.d(TAG, "🗨️ Started geohash DM with $pubkeyHex -> $convKey")
    }
    
    // MARK: - Location Channel Management
    
    private var locationChannelManager: com.bitchat.android.geohash.LocationChannelManager? = null
    
    // Geohash channel subscription state
    private var currentGeohashSubscriptionId: String? = null
    private var currentGeohashDmSubscriptionId: String? = null
    private var currentGeohash: String? = null
    private var geoNicknames: MutableMap<String, String> = mutableMapOf() // pubkeyHex(lowercased) -> nickname
    
    private fun initializeLocationChannelState() {
        try {
            // Initialize location channel manager safely
            locationChannelManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(getApplication())
            
            // Observe location channel manager state and trigger channel switching
            locationChannelManager?.selectedChannel?.observeForever { channel ->
                state.setSelectedLocationChannel(channel)
                // CRITICAL FIX: Switch to the channel when selection changes
                switchLocationChannel(channel)
            }
            
            locationChannelManager?.teleported?.observeForever { teleported ->
                state.setIsTeleported(teleported)
            }
            
            Log.d(TAG, "✅ Location channel state initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to initialize location channel state: ${e.message}")
            // Set default values in case of failure
            state.setSelectedLocationChannel(com.bitchat.android.geohash.ChannelID.Mesh)
            state.setIsTeleported(false)
        }
    }
    
    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        locationChannelManager?.select(channel) ?: run {
            Log.w(TAG, "Cannot select location channel - LocationChannelManager not initialized")
        }
    }
    
    /**
     * Switch to location channel and set up proper Nostr subscriptions (iOS-compatible)
     */
    private fun switchLocationChannel(channel: com.bitchat.android.geohash.ChannelID?) {
        viewModelScope.launch {
            try {
                // Clear processed events when switching channels to get fresh timeline
                processedNostrEvents.clear()
                processedNostrEventOrder.clear()
                
                // Unsubscribe from previous geohash channel
                currentGeohashSubscriptionId?.let { subId ->
                    val nostrRelayManager = com.bitchat.android.nostr.NostrRelayManager.getInstance(getApplication())
                    nostrRelayManager.unsubscribe(subId)
                    currentGeohashSubscriptionId = null
                    Log.d(TAG, "🔄 Unsubscribed from previous geohash channel: $subId")
                }
                
                // Unsubscribe from previous geohash DMs
                currentGeohashDmSubscriptionId?.let { dmSubId ->
                    val nostrRelayManager = com.bitchat.android.nostr.NostrRelayManager.getInstance(getApplication())
                    nostrRelayManager.unsubscribe(dmSubId)
                    currentGeohashDmSubscriptionId = null
                    Log.d(TAG, "🔄 Unsubscribed from previous geohash DMs: $dmSubId")
                }
                
                currentGeohash = null
                geoNicknames.clear()
                
                when (channel) {
                    is com.bitchat.android.geohash.ChannelID.Mesh -> {
                        Log.d(TAG, "📡 Switched to mesh channel")
                        // Clear geohash state
                        stopGeoParticipantsTimer()
                        state.setGeohashPeople(emptyList())
                        state.setTeleportedGeo(emptySet())
                    }
                    
                    is com.bitchat.android.geohash.ChannelID.Location -> {
                        Log.d(TAG, "📍 Switching to geohash channel: ${channel.channel.geohash}")
                        currentGeohash = channel.channel.geohash
                        
                        // Ensure self appears immediately in the people list (iOS-compatible)
                        val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                            forGeohash = channel.channel.geohash,
                            context = getApplication()
                        )
                        recordGeoParticipant(identity.publicKeyHex)
                        
                        // Mark teleported state if applicable  
                        val teleported = state.isTeleported.value ?: false
                        if (teleported) {
                            val currentTeleported = state.getTeleportedGeoValue().toMutableSet()
                            currentTeleported.add(identity.publicKeyHex.lowercase())
                            state.setTeleportedGeo(currentTeleported)
                        }
                        
                        // Start participant refresh timer
                        startGeoParticipantsTimer()
                        
                        val nostrRelayManager = com.bitchat.android.nostr.NostrRelayManager.getInstance(getApplication())
                        
                        // Subscribe to geohash ephemeral events (public messages)
                        val subId = "geo-${channel.channel.geohash}"
                        currentGeohashSubscriptionId = subId
                        
                        val filter = com.bitchat.android.nostr.NostrFilter.geohashEphemeral(
                            geohash = channel.channel.geohash,
                            since = System.currentTimeMillis() - 3600000L, // Last hour
                            limit = 200
                        )
                        
                        nostrRelayManager.subscribe(
                            filter = filter,
                            id = subId
                        ) { event ->
                            handleGeohashChannelEvent(event, channel.channel.geohash)
                        }
                        
                        Log.i(TAG, "✅ Subscribed to geohash channel events: ${channel.channel.geohash}")
                        
                        // Subscribe to geohash DMs for this channel's identity
                        val dmIdentity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                            forGeohash = channel.channel.geohash,
                            context = getApplication()
                        )
                        
                        val dmSubId = "geo-dm-${channel.channel.geohash}"
                        currentGeohashDmSubscriptionId = dmSubId
                        
                        val dmFilter = com.bitchat.android.nostr.NostrFilter.giftWrapsFor(
                            pubkey = dmIdentity.publicKeyHex,
                            since = System.currentTimeMillis() - 86400000L // Last 24 hours
                        )
                        
                        nostrRelayManager.subscribe(
                            filter = dmFilter,
                            id = dmSubId
                        ) { giftWrap ->
                            handleGeohashDmEvent(giftWrap, channel.channel.geohash, dmIdentity)
                        }
                        
                        Log.i(TAG, "✅ Subscribed to geohash DMs for identity: ${dmIdentity.publicKeyHex.take(16)}...")
                    }
                    
                    null -> {
                        Log.d(TAG, "📡 No channel selected")
                    }
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to switch location channel: ${e.message}")
            }
        }
    }
    
    /**
     * Handle geohash channel ephemeral event (public messages) - iOS compatible
     */
    private fun handleGeohashChannelEvent(event: com.bitchat.android.nostr.NostrEvent, geohash: String) {
        try {
            // Only handle ephemeral kind 20000 events
            if (event.kind != 20000) return
            
            // Deduplicate events
            if (processedNostrEvents.contains(event.id)) return
            processedNostrEvents.add(event.id)
            
            // Manage deduplication cache size
            processedNostrEventOrder.add(event.id)
            if (processedNostrEventOrder.size > maxProcessedNostrEvents) {
                val oldestId = processedNostrEventOrder.removeAt(0)
                processedNostrEvents.remove(oldestId)
            }
            
            // Skip our own events (we already locally echoed)
            val myGeoIdentity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                forGeohash = geohash,
                context = getApplication()
            )
            if (myGeoIdentity.publicKeyHex.lowercase() == event.pubkey.lowercase()) {
                return
            }
            
            // Track teleport tag for participants (iOS-compatible)
            event.tags.find { it.size >= 2 && it[0] == "t" && it[1] == "teleport" }?.let {
                val key = event.pubkey.lowercase()
                val currentTeleported = state.getTeleportedGeoValue().toMutableSet()
                if (!currentTeleported.contains(key)) {
                    currentTeleported.add(key)
                    state.setTeleportedGeo(currentTeleported)
                    Log.d(TAG, "📍 Marked geohash participant as teleported: ${event.pubkey.take(8)}...")
                }
            }
            
            // Cache nickname from tag if present
            event.tags.find { it.size >= 2 && it[0] == "n" }?.let { nickTag ->
                val nick = nickTag[1]
                geoNicknames[event.pubkey.lowercase()] = nick
            }
            
            // Store mapping for potential geohash DM initiation
            val key16 = "nostr_${event.pubkey.take(16)}"
            val key8 = "nostr:${event.pubkey.take(8)}"
            nostrKeyMapping[key16] = event.pubkey
            nostrKeyMapping[key8] = event.pubkey
            
            // Update participant activity (use full pubkey for proper tracking)
            recordGeoParticipant(event.pubkey)
            
            val senderName = displayNameForNostrPubkey(event.pubkey)
            val content = event.content
            
            // Skip empty teleport presence events
            val isTeleportPresence = event.tags.any { it.size >= 2 && it[0] == "t" && it[1] == "teleport" } && 
                                     content.trim().isEmpty()
            if (isTeleportPresence) return
            
            val timestamp = Date(event.createdAt * 1000L)
            val mentions = messageManager.parseMentions(content, meshService.getPeerNicknames().values.toSet(), state.getNicknameValue())
            
            val message = BitchatMessage(
                id = event.id,
                sender = senderName,
                content = content,
                timestamp = timestamp,
                isRelay = false,
                senderPeerID = "nostr:${event.pubkey.take(8)}",
                mentions = if (mentions.isNotEmpty()) mentions else null,
                channel = "#$geohash"
            )
            
            // Add to message timeline
            messageManager.addMessage(message)
            
            Log.d(TAG, "📥 Received geohash message from $senderName: ${content.take(50)}")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling geohash channel event: ${e.message}")
        }
    }
    
    /**
     * Handle geohash DM event (private messages in geohash context) - iOS compatible
     */
    private fun handleGeohashDmEvent(
        giftWrap: com.bitchat.android.nostr.NostrEvent, 
        geohash: String, 
        identity: com.bitchat.android.nostr.NostrIdentity
    ) {
        try {
            // Deduplicate
            if (processedNostrEvents.contains(giftWrap.id)) return
            processedNostrEvents.add(giftWrap.id)
            
            // Decrypt with per-geohash identity
            val decryptResult = com.bitchat.android.nostr.NostrProtocol.decryptPrivateMessage(
                giftWrap = giftWrap,
                recipientIdentity = identity
            )
            
            if (decryptResult == null) {
                Log.w(TAG, "Failed to decrypt geohash DM")
                return
            }
            
            val (content, senderPubkey, rumorTimestamp) = decryptResult
            
            // Only process BitChat embedded messages
            if (!content.startsWith("bitchat1:")) return
            
            val base64Content = content.removePrefix("bitchat1:")
            val packetData = base64URLDecode(base64Content) ?: return
            val packet = com.bitchat.android.protocol.BitchatPacket.fromBinaryData(packetData) ?: return
            
            if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) return
            
            val noisePayload = com.bitchat.android.model.NoisePayload.decode(packet.payload) ?: return
            val messageTimestamp = Date(rumorTimestamp * 1000L)
            val convKey = "nostr_${senderPubkey.take(16)}"
            nostrKeyMapping[convKey] = senderPubkey
            
            when (noisePayload.type) {
                com.bitchat.android.model.NoisePayloadType.PRIVATE_MESSAGE -> {
                    val pm = com.bitchat.android.model.PrivateMessagePacket.decode(noisePayload.data) ?: return
                    val messageId = pm.messageID
                    
                    Log.d(TAG, "📥 Received geohash DM from ${senderPubkey.take(8)}...")
                    
                    // Check for duplicate message
                    val existingChats = state.getPrivateChatsValue()
                    var messageExists = false
                    for ((_, messages) in existingChats) {
                        if (messages.any { it.id == messageId }) {
                            messageExists = true
                            break
                        }
                    }
                    if (messageExists) return
                    
                    val senderName = displayNameForNostrPubkey(senderPubkey)
                    val isViewingThisChat = state.getSelectedPrivateChatPeerValue() == convKey
                    
                    val message = BitchatMessage(
                        id = messageId,
                        sender = senderName,
                        content = pm.content,
                        timestamp = messageTimestamp,
                        isRelay = false,
                        isPrivate = true,
                        recipientNickname = state.getNicknameValue(),
                        senderPeerID = convKey,
                        deliveryStatus = com.bitchat.android.model.DeliveryStatus.Delivered(
                            to = state.getNicknameValue() ?: "Unknown",
                            at = Date()
                        )
                    )
                    
                    // Add to private chats
                    privateChatManager.handleIncomingPrivateMessage(message)
                    
                    // Send read receipt if viewing
                    if (isViewingThisChat) {
                        privateChatManager.sendReadReceiptsForPeer(convKey, meshService)
                    }
                }
                
                com.bitchat.android.model.NoisePayloadType.DELIVERED -> {
                    val messageId = String(noisePayload.data, Charsets.UTF_8)
                    meshDelegateHandler.didReceiveDeliveryAck(messageId, convKey)
                }
                
                com.bitchat.android.model.NoisePayloadType.READ_RECEIPT -> {
                    val messageId = String(noisePayload.data, Charsets.UTF_8)
                    meshDelegateHandler.didReceiveReadReceipt(messageId, convKey)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling geohash DM event: ${e.message}")
        }
    }
    
    /**
     * Display name for Nostr pubkey (iOS-compatible)
     */
    private fun displayNameForNostrPubkey(pubkeyHex: String): String {
        val suffix = pubkeyHex.takeLast(4)
        
        // If this is our per-geohash identity, use our nickname
        val currentGeohash = this.currentGeohash
        if (currentGeohash != null) {
            try {
                val myGeoIdentity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(
                    forGeohash = currentGeohash,
                    context = getApplication()
                )
                if (myGeoIdentity.publicKeyHex.lowercase() == pubkeyHex.lowercase()) {
                    return "${state.getNicknameValue()}#$suffix"
                }
            } catch (e: Exception) {
                // Continue with other methods
            }
        }
        
        // If we have a cached nickname for this pubkey, use it
        geoNicknames[pubkeyHex.lowercase()]?.let { nick ->
            return "$nick#$suffix"
        }
        
        // Otherwise, anonymous with collision-resistant suffix
        return "anon#$suffix"
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
