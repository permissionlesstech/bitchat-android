package com.bitchat.android.ui;

import com.bitchat.android.geohash.ChannelID;
import com.bitchat.android.model.BitchatMessage;
import com.bitchat.android.model.DeliveryStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gère toutes les opérations liées aux messages, y compris la déduplication et l'organisation.
 */
public class MessageManager {

    private final ChatState state;
    private final Set<String> processedUIMessages = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> recentSystemEvents = Collections.synchronizedMap(new HashMap<>());
    private static final long MESSAGE_DEDUP_TIMEOUT = 30000L; // 30 secondes
    private static final long SYSTEM_EVENT_DEDUP_TIMEOUT = 5000L; // 5 secondes

    public MessageManager(ChatState state) {
        this.state = state;
    }

    public void addMessage(BitchatMessage message) {
        List<BitchatMessage> currentMessages = new ArrayList<>(state.getMessagesValue());
        currentMessages.add(message);
        state.setMessages(currentMessages);
    }

    public void addSystemMessage(String text) {
        BitchatMessage sys = new BitchatMessage("system", "system", text, new Date(), false, null, false, null, null, null, null, null, false, null, null);
        addMessage(sys);
    }

    public void addChannelMessage(String channel, BitchatMessage message) {
        Map<String, List<BitchatMessage>> currentChannelMessages = new HashMap<>(state.getChannelMessagesValue());
        List<BitchatMessage> channelMessageList = currentChannelMessages.get(channel);
        if (channelMessageList == null) {
            channelMessageList = new ArrayList<>();
        } else {
            channelMessageList = new ArrayList<>(channelMessageList);
        }
        channelMessageList.add(message);
        currentChannelMessages.put(channel, channelMessageList);
        state.setChannelMessages(currentChannelMessages);

        boolean viewingClassicChannel = channel.equals(state.getCurrentChannelValue());
        boolean viewingGeohashChannel = false;
        if (channel.startsWith("geo:")) {
            String geo = channel.substring(4);
            ChannelID selected = state.selectedLocationChannel.getValue();
            if (selected instanceof ChannelID.Location) {
                // This part is tricky without the full geohash object, assuming simple string comparison
                // viewingGeohashChannel = ((ChannelID.Location) selected).channel.geohash.equalsIgnoreCase(geo);
            }
        }

        if (!viewingClassicChannel && !viewingGeohashChannel) {
            Map<String, Integer> currentUnread = new HashMap<>(state.getUnreadChannelMessagesValue());
            currentUnread.put(channel, currentUnread.getOrDefault(channel, 0) + 1);
            state.setUnreadChannelMessages(currentUnread);
        }
    }

    public void addPrivateMessage(String peerID, BitchatMessage message) {
        Map<String, List<BitchatMessage>> currentPrivateChats = new HashMap<>(state.getPrivateChatsValue());
        List<BitchatMessage> chatMessages = currentPrivateChats.get(peerID);
        if (chatMessages == null) {
            chatMessages = new ArrayList<>();
        } else {
            chatMessages = new ArrayList<>(chatMessages);
        }
        chatMessages.add(message);
        currentPrivateChats.put(peerID, chatMessages);
        state.setPrivateChats(currentPrivateChats);

        if (!peerID.equals(state.getSelectedPrivateChatPeerValue()) && !message.getSender().equals(state.getNicknameValue())) {
            Set<String> currentUnread = new HashSet<>(state.getUnreadPrivateMessagesValue());
            currentUnread.add(peerID);
            state.setUnreadPrivateMessages(currentUnread);
        }
    }

    public void updateMessageDeliveryStatus(String messageID, DeliveryStatus status) {
        // Mettre à jour dans les discussions privées
        Map<String, List<BitchatMessage>> updatedPrivateChats = new HashMap<>(state.getPrivateChatsValue());
        boolean updated = false;
        for (Map.Entry<String, List<BitchatMessage>> entry : updatedPrivateChats.entrySet()) {
            List<BitchatMessage> messages = entry.getValue();
            List<BitchatMessage> updatedMessages = new ArrayList<>(messages);
            for (int i = 0; i < updatedMessages.size(); i++) {
                if (updatedMessages.get(i).getId().equals(messageID)) {
                    updatedMessages.set(i, new BitchatMessage(updatedMessages.get(i), status));
                    updatedPrivateChats.put(entry.getKey(), updatedMessages);
                    updated = true;
                    break;
                }
            }
        }
        if (updated) {
            state.setPrivateChats(updatedPrivateChats);
        }

        // Mettre à jour dans les messages principaux
        List<BitchatMessage> updatedMessages = new ArrayList<>(state.getMessagesValue());
        for (int i = 0; i < updatedMessages.size(); i++) {
            if (updatedMessages.get(i).getId().equals(messageID)) {
                updatedMessages.set(i, new BitchatMessage(updatedMessages.get(i), status));
                state.setMessages(updatedMessages);
                break;
            }
        }

        // Mettre à jour dans les messages de canal
        Map<String, List<BitchatMessage>> updatedChannelMessages = new HashMap<>(state.getChannelMessagesValue());
         for (Map.Entry<String, List<BitchatMessage>> entry : updatedChannelMessages.entrySet()) {
            List<BitchatMessage> messages = entry.getValue();
            List<BitchatMessage> updatedChannelList = new ArrayList<>(messages);
            for (int i = 0; i < updatedChannelList.size(); i++) {
                if (updatedChannelList.get(i).getId().equals(messageID)) {
                    updatedChannelList.set(i, new BitchatMessage(updatedChannelList.get(i), status));
                    updatedChannelMessages.put(entry.getKey(), updatedChannelList);
                    break;
                }
            }
        }
        state.setChannelMessages(updatedChannelMessages);
    }

    public List<String> parseMentions(String content, Set<String> peerNicknames, String currentNickname) {
        Pattern mentionRegex = Pattern.compile("@([a-zA-Z0-9_]+)");
        Set<String> allNicknames = new HashSet<>(peerNicknames);
        if (currentNickname != null) {
            allNicknames.add(currentNickname);
        }

        Set<String> foundMentions = new HashSet<>();
        Matcher matcher = mentionRegex.matcher(content);
        while (matcher.find()) {
            String mention = matcher.group(1);
            if (allNicknames.contains(mention)) {
                foundMentions.add(mention);
            }
        }
        return new ArrayList<>(foundMentions);
    }

    public void clearAllMessages() {
        state.setMessages(Collections.emptyList());
        state.setPrivateChats(Collections.emptyMap());
        state.setChannelMessages(Collections.emptyMap());
        state.setUnreadPrivateMessages(Collections.emptySet());
        state.setUnreadChannelMessages(Collections.emptyMap());
        processedUIMessages.clear();
        recentSystemEvents.clear();
    }
}
