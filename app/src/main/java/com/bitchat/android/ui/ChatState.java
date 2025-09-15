package com.bitchat.android.ui;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import com.bitchat.android.geohash.ChannelID;
import com.bitchat.android.model.BitchatMessage;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contient tout l'état observable pour le système de chat.
 * Cette classe centralise les données de l'interface utilisateur et expose des LiveData
 * pour permettre aux composants de l'interface de réagir aux changements d'état.
 */
public class ChatState {

    // --- État principal des messages et des pairs ---
    private final MutableLiveData<List<BitchatMessage>> _messages = new MutableLiveData<>(Collections.emptyList());
    public final LiveData<List<BitchatMessage>> messages = _messages;

    private final MutableLiveData<List<String>> _connectedPeers = new MutableLiveData<>(Collections.emptyList());
    public final LiveData<List<String>> connectedPeers = _connectedPeers;

    private final MutableLiveData<String> _nickname = new MutableLiveData<>();
    public final LiveData<String> nickname = _nickname;

    private final MutableLiveData<Boolean> _isConnected = new MutableLiveData<>(false);
    public final LiveData<Boolean> isConnected = _isConnected;

    // --- Discussions privées ---
    private final MutableLiveData<Map<String, List<BitchatMessage>>> _privateChats = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, List<BitchatMessage>>> privateChats = _privateChats;

    private final MutableLiveData<String> _selectedPrivateChatPeer = new MutableLiveData<>(null);
    public final LiveData<String> selectedPrivateChatPeer = _selectedPrivateChatPeer;

    private final MutableLiveData<Set<String>> _unreadPrivateMessages = new MutableLiveData<>(Collections.emptySet());
    public final LiveData<Set<String>> unreadPrivateMessages = _unreadPrivateMessages;

    // --- Canaux ---
    private final MutableLiveData<Set<String>> _joinedChannels = new MutableLiveData<>(Collections.emptySet());
    public final LiveData<Set<String>> joinedChannels = _joinedChannels;

    private final MutableLiveData<String> _currentChannel = new MutableLiveData<>(null);
    public final LiveData<String> currentChannel = _currentChannel;

    private final MutableLiveData<Map<String, List<BitchatMessage>>> _channelMessages = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, List<BitchatMessage>>> channelMessages = _channelMessages;

    private final MutableLiveData<Map<String, Integer>> _unreadChannelMessages = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, Integer>> unreadChannelMessages = _unreadChannelMessages;

    private final MutableLiveData<Set<String>> _passwordProtectedChannels = new MutableLiveData<>(Collections.emptySet());
    public final LiveData<Set<String>> passwordProtectedChannels = _passwordProtectedChannels;

    private final MutableLiveData<Boolean> _showPasswordPrompt = new MutableLiveData<>(false);
    public final LiveData<Boolean> showPasswordPrompt = _showPasswordPrompt;

    private final MutableLiveData<String> _passwordPromptChannel = new MutableLiveData<>(null);
    public final LiveData<String> passwordPromptChannel = _passwordPromptChannel;

    // --- État de la barre latérale ---
    private final MutableLiveData<Boolean> _showSidebar = new MutableLiveData<>(false);
    public final LiveData<Boolean> showSidebar = _showSidebar;

    // --- Auto-complétion des commandes ---
    private final MutableLiveData<Boolean> _showCommandSuggestions = new MutableLiveData<>(false);
    public final LiveData<Boolean> showCommandSuggestions = _showCommandSuggestions;

    private final MutableLiveData<List<CommandSuggestion>> _commandSuggestions = new MutableLiveData<>(Collections.emptyList());
    public final LiveData<List<CommandSuggestion>> commandSuggestions = _commandSuggestions;

    // --- Auto-complétion des mentions ---
    private final MutableLiveData<Boolean> _showMentionSuggestions = new MutableLiveData<>(false);
    public final LiveData<Boolean> showMentionSuggestions = _showMentionSuggestions;

    private final MutableLiveData<List<String>> _mentionSuggestions = new MutableLiveData<>(Collections.emptyList());
    public final LiveData<List<String>> mentionSuggestions = _mentionSuggestions;

    // --- Favoris ---
    private final MutableLiveData<Set<String>> _favoritePeers = new MutableLiveData<>(Collections.emptySet());
    public final LiveData<Set<String>> favoritePeers = _favoritePeers;

    // --- États des pairs pour l'UI ---
    private final MutableLiveData<Map<String, String>> _peerSessionStates = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, String>> peerSessionStates = _peerSessionStates;

    private final MutableLiveData<Map<String, String>> _peerFingerprints = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, String>> peerFingerprints = _peerFingerprints;

    private final MutableLiveData<Map<String, String>> _peerNicknames = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, String>> peerNicknames = _peerNicknames;

    private final MutableLiveData<Map<String, Integer>> _peerRSSI = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, Integer>> peerRSSI = _peerRSSI;

    private final MutableLiveData<Map<String, Boolean>> _peerDirect = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, Boolean>> peerDirect = _peerDirect;

    // --- État de la navigation ---
    private final MutableLiveData<Boolean> _showAppInfo = new MutableLiveData<>(false);
    public final LiveData<Boolean> showAppInfo = _showAppInfo;

    // --- État des canaux de géolocalisation (Nostr) ---
    private final MutableLiveData<ChannelID> _selectedLocationChannel = new MutableLiveData<>(ChannelID.Mesh.INSTANCE);
    public final LiveData<ChannelID> selectedLocationChannel = _selectedLocationChannel;

    private final MutableLiveData<Boolean> _isTeleported = new MutableLiveData<>(false);
    public final LiveData<Boolean> isTeleported = _isTeleported;

    private final MutableLiveData<List<GeoPerson>> _geohashPeople = new MutableLiveData<>(Collections.emptyList());
    public final LiveData<List<GeoPerson>> geohashPeople = _geohashPeople;
    public MutableLiveData<List<GeoPerson>> getGeohashPeopleMutable() { return _geohashPeople; }

    private final MutableLiveData<Set<String>> _teleportedGeo = new MutableLiveData<>(Collections.emptySet());
    public final LiveData<Set<String>> teleportedGeo = _teleportedGeo;

    private final MutableLiveData<Map<String, Integer>> _geohashParticipantCounts = new MutableLiveData<>(Collections.emptyMap());
    public final LiveData<Map<String, Integer>> geohashParticipantCounts = _geohashParticipantCounts;

    // --- Propriétés calculées pour les messages non lus ---
    public final MediatorLiveData<Boolean> hasUnreadChannels = new MediatorLiveData<>();
    public final MediatorLiveData<Boolean> hasUnreadPrivateMessages = new MediatorLiveData<>();

    public ChatState() {
        // Initialise les médiateurs pour les états "non lus"
        hasUnreadChannels.addSource(_unreadChannelMessages, unreadMap -> {
            if (unreadMap == null) {
                hasUnreadChannels.setValue(false);
                return;
            }
            for (Integer count : unreadMap.values()) {
                if (count > 0) {
                    hasUnreadChannels.setValue(true);
                    return;
                }
            }
            hasUnreadChannels.setValue(false);
        });

        hasUnreadPrivateMessages.addSource(_unreadPrivateMessages, unreadSet ->
            hasUnreadPrivateMessages.setValue(unreadSet != null && !unreadSet.isEmpty())
        );
    }

    // --- Getters pour l'accès interne à l'état ---
    public List<BitchatMessage> getMessagesValue() { return _messages.getValue() != null ? _messages.getValue() : Collections.emptyList(); }
    public List<String> getConnectedPeersValue() { return _connectedPeers.getValue() != null ? _connectedPeers.getValue() : Collections.emptyList(); }
    public String getNicknameValue() { return _nickname.getValue(); }
    public Map<String, List<BitchatMessage>> getPrivateChatsValue() { return _privateChats.getValue() != null ? _privateChats.getValue() : Collections.emptyMap(); }
    public String getSelectedPrivateChatPeerValue() { return _selectedPrivateChatPeer.getValue(); }
    public Set<String> getUnreadPrivateMessagesValue() { return _unreadPrivateMessages.getValue() != null ? _unreadPrivateMessages.getValue() : Collections.emptySet(); }
    public Set<String> getJoinedChannelsValue() { return _joinedChannels.getValue() != null ? _joinedChannels.getValue() : Collections.emptySet(); }
    public String getCurrentChannelValue() { return _currentChannel.getValue(); }
    public Map<String, List<BitchatMessage>> getChannelMessagesValue() { return _channelMessages.getValue() != null ? _channelMessages.getValue() : Collections.emptyMap(); }
    public Map<String, Integer> getUnreadChannelMessagesValue() { return _unreadChannelMessages.getValue() != null ? _unreadChannelMessages.getValue() : Collections.emptyMap(); }
    public Set<String> getPasswordProtectedChannelsValue() { return _passwordProtectedChannels.getValue() != null ? _passwordProtectedChannels.getValue() : Collections.emptySet(); }
    public boolean getShowPasswordPromptValue() { return _showPasswordPrompt.getValue() != null ? _showPasswordPrompt.getValue() : false; }
    public String getPasswordPromptChannelValue() { return _passwordPromptChannel.getValue(); }
    public boolean getShowSidebarValue() { return _showSidebar.getValue() != null ? _showSidebar.getValue() : false; }
    public boolean getShowAppInfoValue() { return _showAppInfo.getValue() != null ? _showAppInfo.getValue() : false; }

    // --- Setters pour les mises à jour d'état ---
    public void setMessages(List<BitchatMessage> messages) { _messages.setValue(messages); }
    public void setConnectedPeers(List<String> peers) { _connectedPeers.setValue(peers); }
    public void setNickname(String nickname) { _nickname.setValue(nickname); }
    public void setIsConnected(boolean connected) { _isConnected.setValue(connected); }
    public void setPrivateChats(Map<String, List<BitchatMessage>> chats) { _privateChats.setValue(chats); }
    public void setSelectedPrivateChatPeer(String peerID) { _selectedPrivateChatPeer.setValue(peerID); }
    public void setUnreadPrivateMessages(Set<String> unread) { _unreadPrivateMessages.setValue(unread); }
    public void setJoinedChannels(Set<String> channels) { _joinedChannels.setValue(channels); }
    public void setCurrentChannel(String channel) { _currentChannel.setValue(channel); }
    public void setChannelMessages(Map<String, List<BitchatMessage>> messages) { _channelMessages.setValue(messages); }
    public void setUnreadChannelMessages(Map<String, Integer> unread) { _unreadChannelMessages.setValue(unread); }
    public void setPasswordProtectedChannels(Set<String> channels) { _passwordProtectedChannels.setValue(channels); }
    public void setShowPasswordPrompt(boolean show) { _showPasswordPrompt.setValue(show); }
    public void setPasswordPromptChannel(String channel) { _passwordPromptChannel.setValue(channel); }
    public void setShowSidebar(boolean show) { _showSidebar.setValue(show); }
    public void setCommandSuggestions(List<CommandSuggestion> suggestions) { _commandSuggestions.setValue(suggestions); }
    public void setMentionSuggestions(List<String> suggestions) { _mentionSuggestions.setValue(suggestions); }
    public void setFavoritePeers(Set<String> favorites) { _favoritePeers.setValue(favorites); }
    public void setPeerSessionStates(Map<String, String> states) { _peerSessionStates.setValue(states); }
    public void setPeerFingerprints(Map<String, String> fingerprints) { _peerFingerprints.setValue(fingerprints); }
    public void setPeerNicknames(Map<String, String> nicknames) { _peerNicknames.setValue(nicknames); }
    public void setPeerRSSI(Map<String, Integer> rssi) { _peerRSSI.setValue(rssi); }
    public void setPeerDirect(Map<String, Boolean> direct) { _peerDirect.setValue(direct); }
    public void setShowAppInfo(boolean show) { _showAppInfo.setValue(show); }
    public void setSelectedLocationChannel(ChannelID channel) { _selectedLocationChannel.setValue(channel); }
    public void setIsTeleported(boolean teleported) { _isTeleported.setValue(teleported); }
    public void setGeohashPeople(List<GeoPerson> people) { _geohashPeople.setValue(people); }
    public void postGeohashPeople(List<GeoPerson> people) { _geohashPeople.postValue(people); }
    public void setTeleportedGeo(Set<String> teleported) { _teleportedGeo.setValue(teleported); }
    public void postTeleportedGeo(Set<String> teleported) { _teleportedGeo.postValue(teleported); }
    public void setGeohashParticipantCounts(Map<String, Integer> counts) { _geohashParticipantCounts.setValue(counts); }
    public void postGeohashParticipantCounts(Map<String, Integer> counts) { _geohashParticipantCounts.postValue(counts); }
}
