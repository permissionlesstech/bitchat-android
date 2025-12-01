package com.bitchat.android.ui

import android.util.Log
import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Centralized state definitions and data classes for the chat system
 */

// Command suggestion data class
data class CommandSuggestion(
    val command: String,
    val aliases: List<String> = emptyList(),
    val syntax: String? = null,
    val description: String
)

/**
 * Contains all the observable state for the chat system
 */
class ChatState(
    scope: CoroutineScope
) {
    
    // Core messages and peer state
    private val _messages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val messages: StateFlow<List<BitchatMessage>> = _messages.asStateFlow()
    
    private val _connectedPeers = MutableStateFlow<List<String>>(emptyList())
    val connectedPeers: StateFlow<List<String>> = _connectedPeers.asStateFlow()
    
    private val _nickname = MutableStateFlow<String>("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()
    
    private val _isConnected = MutableStateFlow<Boolean>(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    
    // Private chats
    private val _privateChats = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = _privateChats.asStateFlow()
    
    private val _selectedPrivateChatPeer = MutableStateFlow<String?>(null)
    val selectedPrivateChatPeer: StateFlow<String?> = _selectedPrivateChatPeer.asStateFlow()
    
    private val _unreadPrivateMessages = MutableStateFlow<Set<String>>(emptySet())
    val unreadPrivateMessages: StateFlow<Set<String>> = _unreadPrivateMessages.asStateFlow()
    
    // Channels
    private val _joinedChannels = MutableStateFlow<Set<String>>(emptySet())
    val joinedChannels: StateFlow<Set<String>> = _joinedChannels.asStateFlow()
    
    private val _currentChannel = MutableStateFlow<String?>(null)
    val currentChannel: StateFlow<String?> = _currentChannel.asStateFlow()
    
    private val _channelMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = _channelMessages.asStateFlow()
    
    private val _unreadChannelMessages = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadChannelMessages: StateFlow<Map<String, Int>> = _unreadChannelMessages.asStateFlow()
    
    private val _passwordProtectedChannels = MutableStateFlow<Set<String>>(emptySet())
    val passwordProtectedChannels: StateFlow<Set<String>> = _passwordProtectedChannels.asStateFlow()
    
    private val _showPasswordPrompt = MutableStateFlow<Boolean>(false)
    val showPasswordPrompt: StateFlow<Boolean> = _showPasswordPrompt.asStateFlow()
    
    private val _passwordPromptChannel = MutableStateFlow<String?>(null)
    val passwordPromptChannel: StateFlow<String?> = _passwordPromptChannel.asStateFlow()
    
    // Sidebar state
    private val _showSidebar = MutableStateFlow(false)
    val showSidebar: StateFlow<Boolean> = _showSidebar.asStateFlow()
    
    // Command autocomplete
    private val _showCommandSuggestions = MutableStateFlow(false)
    val showCommandSuggestions: StateFlow<Boolean> = _showCommandSuggestions.asStateFlow()
    
    private val _commandSuggestions = MutableStateFlow<List<CommandSuggestion>>(emptyList())
    val commandSuggestions: StateFlow<List<CommandSuggestion>> = _commandSuggestions.asStateFlow()
    
    // Mention autocomplete
    private val _showMentionSuggestions = MutableStateFlow(false)
    val showMentionSuggestions: StateFlow<Boolean> = _showMentionSuggestions.asStateFlow()
    
    private val _mentionSuggestions = MutableStateFlow<List<String>>(emptyList())
    val mentionSuggestions: StateFlow<List<String>> = _mentionSuggestions.asStateFlow()
    
    // Favorites
    private val _favoritePeers = MutableStateFlow<Set<String>>(emptySet())
    val favoritePeers: StateFlow<Set<String>> = _favoritePeers.asStateFlow()
    
    // Noise session states for peers (for reactive UI updates)
    private val _peerSessionStates = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerSessionStates: StateFlow<Map<String, String>> = _peerSessionStates.asStateFlow()
    
    // Peer fingerprint state for reactive favorites (for reactive UI updates)
    private val _peerFingerprints = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerFingerprints: StateFlow<Map<String, String>> = _peerFingerprints.asStateFlow()

    private val _peerNicknames = MutableStateFlow<Map<String, String>>(emptyMap())
    val peerNicknames: StateFlow<Map<String, String>> = _peerNicknames.asStateFlow()

    private val _peerRSSI = MutableStateFlow<Map<String, Int>>(emptyMap())
    val peerRSSI: StateFlow<Map<String, Int>> = _peerRSSI.asStateFlow()

    // Direct connection status per peer (for live UI updates)
    private val _peerDirect = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val peerDirect: StateFlow<Map<String, Boolean>> = _peerDirect.asStateFlow()
    
    // peerIDToPublicKeyFingerprint REMOVED - fingerprints now handled centrally in PeerManager
    
    // Navigation state
    private val _showAppInfo = MutableStateFlow<Boolean>(false)
    val showAppInfo: StateFlow<Boolean> = _showAppInfo.asStateFlow()
    
    // Location channels state (for Nostr geohash features)
    private val _selectedLocationChannel = MutableStateFlow<com.bitchat.android.geohash.ChannelID?>(com.bitchat.android.geohash.ChannelID.Mesh)
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = _selectedLocationChannel.asStateFlow()
    
    private val _isTeleported = MutableStateFlow<Boolean>(false)
    val isTeleported: StateFlow<Boolean> = _isTeleported.asStateFlow()
    
    // Geohash people state (iOS-compatible)
    private val _geohashPeople = MutableStateFlow<List<GeoPerson>>(emptyList())
    val geohashPeople: StateFlow<List<GeoPerson>> = _geohashPeople.asStateFlow()
    
    private val _teleportedGeo = MutableStateFlow<Set<String>>(emptySet())
    val teleportedGeo: StateFlow<Set<String>> = _teleportedGeo.asStateFlow()
    
    // Geohash participant counts reactive state (for real-time location channel counts)
    private val _geohashParticipantCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = _geohashParticipantCounts.asStateFlow()
    

    val hasUnreadChannels: StateFlow<Boolean> = _unreadChannelMessages
        .map { unreadMap -> unreadMap.values.any { it > 0 } }
        .stateIn(
            scope = scope,
            started = WhileSubscribed(5_000),
            initialValue = false
        )
    
    val hasUnreadPrivateMessages: StateFlow<Boolean> = _unreadPrivateMessages
        .map { unreadSet -> unreadSet.isNotEmpty() }
        .stateIn(
            scope = scope,
            started = WhileSubscribed(5_000),
            initialValue = false
        )
    
    // Getters for internal state access
    fun getMessagesValue() = _messages.value
    fun getConnectedPeersValue() = _connectedPeers.value
    fun getNicknameValue() = _nickname.value
    fun getPrivateChatsValue() = _privateChats.value
    fun getSelectedPrivateChatPeerValue() = _selectedPrivateChatPeer.value
    fun getUnreadPrivateMessagesValue() = _unreadPrivateMessages.value
    fun getJoinedChannelsValue() = _joinedChannels.value
    fun getCurrentChannelValue() = _currentChannel.value
    fun getChannelMessagesValue() = _channelMessages.value
    fun getUnreadChannelMessagesValue() = _unreadChannelMessages.value
    fun getPasswordProtectedChannelsValue() = _passwordProtectedChannels.value
    fun getShowPasswordPromptValue() = _showPasswordPrompt.value
    fun getPasswordPromptChannelValue() = _passwordPromptChannel.value
    fun getShowSidebarValue() = _showSidebar.value
    fun getShowCommandSuggestionsValue() = _showCommandSuggestions.value
    fun getCommandSuggestionsValue() = _commandSuggestions.value
    fun getShowMentionSuggestionsValue() = _showMentionSuggestions.value
    fun getMentionSuggestionsValue() = _mentionSuggestions.value
    fun getFavoritePeersValue() = _favoritePeers.value
    fun getPeerSessionStatesValue() = _peerSessionStates.value
    fun getPeerFingerprintsValue() = _peerFingerprints.value
    fun getShowAppInfoValue() = _showAppInfo.value
    fun getGeohashPeopleValue() = _geohashPeople.value
    fun getTeleportedGeoValue() = _teleportedGeo.value
    fun getGeohashParticipantCountsValue() = _geohashParticipantCounts.value
    
    // Setters for state updates
    fun setMessages(messages: List<BitchatMessage>) {
        _messages.value = messages
    }
    
    fun setConnectedPeers(peers: List<String>) {
        _connectedPeers.value = peers
    }
    
    fun postTeleportedGeo(teleported: Set<String>) {
        _teleportedGeo.value = teleported
    }

    fun setNickname(nickname: String) {
        _nickname.value = nickname
    }
    
    fun setIsConnected(connected: Boolean) {
        _isConnected.value = connected
    }
    
    fun setPrivateChats(chats: Map<String, List<BitchatMessage>>) {
        _privateChats.value = chats
    }
    
    fun setSelectedPrivateChatPeer(peerID: String?) {
        _selectedPrivateChatPeer.value = peerID
    }
    
    fun setUnreadPrivateMessages(unread: Set<String>) {
        _unreadPrivateMessages.value = unread
    }
    
    fun setJoinedChannels(channels: Set<String>) {
        _joinedChannels.value = channels
    }
    
    fun setCurrentChannel(channel: String?) {
        _currentChannel.value = channel
    }
    
    fun setChannelMessages(messages: Map<String, List<BitchatMessage>>) {
        _channelMessages.value = messages
    }
    
    fun setUnreadChannelMessages(unread: Map<String, Int>) {
        _unreadChannelMessages.value = unread
    }
    
    fun setPasswordProtectedChannels(channels: Set<String>) {
        _passwordProtectedChannels.value = channels
    }
    
    fun setShowPasswordPrompt(show: Boolean) {
        _showPasswordPrompt.value = show
    }
    
    fun setPasswordPromptChannel(channel: String?) {
        _passwordPromptChannel.value = channel
    }
    
    fun setShowSidebar(show: Boolean) {
        _showSidebar.value = show
    }
    
    fun setShowCommandSuggestions(show: Boolean) {
        _showCommandSuggestions.value = show
    }
    
    fun setCommandSuggestions(suggestions: List<CommandSuggestion>) {
        _commandSuggestions.value = suggestions
    }
    
    fun setShowMentionSuggestions(show: Boolean) {
        _showMentionSuggestions.value = show
    }
    
    fun setMentionSuggestions(suggestions: List<String>) {
        _mentionSuggestions.value = suggestions
    }

    fun setFavoritePeers(favorites: Set<String>) {
        val currentValue = _favoritePeers.value
        Log.d("ChatState", "setFavoritePeers called with ${favorites.size} favorites: $favorites")
        Log.d("ChatState", "Current value: $currentValue")
        Log.d("ChatState", "Values equal: ${currentValue == favorites}")
        Log.d("ChatState", "Setting on thread: ${Thread.currentThread().name}")
        
        // Always set the value - even if equal, this ensures observers are triggered
        _favoritePeers.value = favorites
        
        Log.d("ChatState", "StateFlow value after set: ${_favoritePeers.value}")
    }
    
    fun setPeerSessionStates(states: Map<String, String>) {
        _peerSessionStates.value = states
    }
    
    fun setPeerFingerprints(fingerprints: Map<String, String>) {
        _peerFingerprints.value = fingerprints
    }

    fun setPeerNicknames(nicknames: Map<String, String>) {
        _peerNicknames.value = nicknames
    }

    fun setPeerRSSI(rssi: Map<String, Int>) {
        _peerRSSI.value = rssi
    }

    fun setPeerDirect(direct: Map<String, Boolean>) {
        _peerDirect.value = direct
    }
    
    fun setShowAppInfo(show: Boolean) {
        _showAppInfo.value = show
    }
    
    fun setSelectedLocationChannel(channel: com.bitchat.android.geohash.ChannelID?) {
        _selectedLocationChannel.value = channel
    }
    
    fun setIsTeleported(teleported: Boolean) {
        _isTeleported.value = teleported
    }
    
    fun setGeohashPeople(people: List<GeoPerson>) {
        _geohashPeople.value = people
    }
    
    fun setTeleportedGeo(teleported: Set<String>) {
        _teleportedGeo.value = teleported
    }
    
    fun setGeohashParticipantCounts(counts: Map<String, Int>) {
        _geohashParticipantCounts.value = counts
    }

}
