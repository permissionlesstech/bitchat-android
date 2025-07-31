package com.bitchat.android.ui

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.MutableLiveData
import com.bitchat.android.model.BitchatMessage

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
class ChatState {
    
    // Core messages and peer state
    private val _messages = MutableLiveData<List<BitchatMessage>>(emptyList())
    val messages: LiveData<List<BitchatMessage>> = _messages
    
    private val _connectedPeers = MutableLiveData<List<String>>(emptyList())
    val connectedPeers: LiveData<List<String>> = _connectedPeers
    
    private val _nickname = MutableLiveData<String>()
    val nickname: LiveData<String> = _nickname
    
    private val _isConnected = MutableLiveData<Boolean>(false)
    val isConnected: LiveData<Boolean> = _isConnected
    
    // Private chats
    private val _privateChats = MutableLiveData<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateChats: LiveData<Map<String, List<BitchatMessage>>> = _privateChats
    
    private val _selectedPrivateChatPeer = MutableLiveData<String?>(null)
    val selectedPrivateChatPeer: LiveData<String?> = _selectedPrivateChatPeer
    
    private val _unreadPrivateMessages = MutableLiveData<Set<String>>(emptySet())
    val unreadPrivateMessages: LiveData<Set<String>> = _unreadPrivateMessages
    
    // Channels
    private val _joinedChannels = MutableLiveData<Set<String>>(emptySet())
    val joinedChannels: LiveData<Set<String>> = _joinedChannels
    
    private val _currentChannel = MutableLiveData<String?>(null)
    val currentChannel: LiveData<String?> = _currentChannel
    
    private val _channelMessages = MutableLiveData<Map<String, List<BitchatMessage>>>(emptyMap())
    val channelMessages: LiveData<Map<String, List<BitchatMessage>>> = _channelMessages
    
    private val _unreadChannelMessages = MutableLiveData<Map<String, Int>>(emptyMap())
    val unreadChannelMessages: LiveData<Map<String, Int>> = _unreadChannelMessages
    
    private val _passwordProtectedChannels = MutableLiveData<Set<String>>(emptySet())
    val passwordProtectedChannels: LiveData<Set<String>> = _passwordProtectedChannels
    
    private val _showPasswordPrompt = MutableLiveData<Boolean>(false)
    val showPasswordPrompt: LiveData<Boolean> = _showPasswordPrompt
    
    private val _passwordPromptChannel = MutableLiveData<String?>(null)
    val passwordPromptChannel: LiveData<String?> = _passwordPromptChannel
    
    // Sidebar state
    private val _showSidebar = MutableLiveData(false)
    val showSidebar: LiveData<Boolean> = _showSidebar
    
    // Command autocomplete
    private val _showCommandSuggestions = MutableLiveData(false)
    val showCommandSuggestions: LiveData<Boolean> = _showCommandSuggestions
    
    private val _commandSuggestions = MutableLiveData<List<CommandSuggestion>>(emptyList())
    val commandSuggestions: LiveData<List<CommandSuggestion>> = _commandSuggestions
    
    // Favorites
    private val _favoritePeers = MutableLiveData<Set<String>>(emptySet())
    val favoritePeers: LiveData<Set<String>> = _favoritePeers
    
    // Noise session states for peers (for reactive UI updates)
    private val _peerSessionStates = MutableLiveData<Map<String, String>>(emptyMap())
    val peerSessionStates: LiveData<Map<String, String>> = _peerSessionStates
    
    // Peer fingerprint state for reactive favorites (for reactive UI updates)
    private val _peerFingerprints = MutableLiveData<Map<String, String>>(emptyMap())
    val peerFingerprints: LiveData<Map<String, String>> = _peerFingerprints
    
    // peerIDToPublicKeyFingerprint REMOVED - fingerprints now handled centrally in PeerManager
    
    // Navigation state
    private val _showAppInfo = MutableLiveData<Boolean>(false)
    val showAppInfo: LiveData<Boolean> = _showAppInfo
    
    // Unread state computed properties
    val hasUnreadChannels: MediatorLiveData<Boolean> = MediatorLiveData<Boolean>()
    val hasUnreadPrivateMessages: MediatorLiveData<Boolean> = MediatorLiveData<Boolean>()
    
    init {
        // Initialize unread state mediators
        hasUnreadChannels.addSource(_unreadChannelMessages) { unreadMap ->
            hasUnreadChannels.value = unreadMap.values.any { it > 0 }
        }
        
        hasUnreadPrivateMessages.addSource(_unreadPrivateMessages) { unreadSet ->
            hasUnreadPrivateMessages.value = unreadSet.isNotEmpty()
        }
    }
    
    // Getters for internal state access
    fun getMessagesValue() = _messages.value ?: emptyList()
    fun getConnectedPeersValue() = _connectedPeers.value ?: emptyList()
    fun getNicknameValue() = _nickname.value
    fun getPrivateChatsValue() = _privateChats.value ?: emptyMap()
    fun getSelectedPrivateChatPeerValue() = _selectedPrivateChatPeer.value
    fun getUnreadPrivateMessagesValue() = _unreadPrivateMessages.value ?: emptySet()
    fun getJoinedChannelsValue() = _joinedChannels.value ?: emptySet()
    fun getCurrentChannelValue() = _currentChannel.value
    fun getChannelMessagesValue() = _channelMessages.value ?: emptyMap()
    fun getUnreadChannelMessagesValue() = _unreadChannelMessages.value ?: emptyMap()
    fun getPasswordProtectedChannelsValue() = _passwordProtectedChannels.value ?: emptySet()
    fun getShowPasswordPromptValue() = _showPasswordPrompt.value ?: false
    fun getPasswordPromptChannelValue() = _passwordPromptChannel.value
    fun getShowSidebarValue() = _showSidebar.value ?: false
    fun getShowCommandSuggestionsValue() = _showCommandSuggestions.value ?: false
    fun getCommandSuggestionsValue() = _commandSuggestions.value ?: emptyList()
    fun getFavoritePeersValue() = _favoritePeers.value ?: emptySet()
    fun getPeerSessionStatesValue() = _peerSessionStates.value ?: emptyMap()
    fun getPeerFingerprintsValue() = _peerFingerprints.value ?: emptyMap()
    fun getShowAppInfoValue() = _showAppInfo.value ?: false
    
    // Setters for state updates
    fun setMessages(messages: List<BitchatMessage>) {
        _messages.value = messages
    }
    
    fun setConnectedPeers(peers: List<String>) {
        _connectedPeers.value = peers
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

    fun setFavoritePeers(favorites: Set<String>) {
        val currentValue = _favoritePeers.value ?: emptySet()
        Log.d("ChatState", "setFavoritePeers called with ${favorites.size} favorites: $favorites")
        Log.d("ChatState", "Current value: $currentValue")
        Log.d("ChatState", "Values equal: ${currentValue == favorites}")
        Log.d("ChatState", "Setting on thread: ${Thread.currentThread().name}")
        
        // Always set the value - even if equal, this ensures observers are triggered
        _favoritePeers.value = favorites
        
        Log.d("ChatState", "LiveData value after set: ${_favoritePeers.value}")
        Log.d("ChatState", "LiveData has active observers: ${_favoritePeers.hasActiveObservers()}")
    }
    
    fun setPeerSessionStates(states: Map<String, String>) {
        _peerSessionStates.value = states
    }
    
    fun setPeerFingerprints(fingerprints: Map<String, String>) {
        _peerFingerprints.value = fingerprints
    }
    
    fun setShowAppInfo(show: Boolean) {
        _showAppInfo.value = show
    }

}
