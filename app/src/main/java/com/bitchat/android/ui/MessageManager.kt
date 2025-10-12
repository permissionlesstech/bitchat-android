package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import java.util.*
import java.util.Collections

/**
 * Handles all message-related operations including deduplication and organization
 */
class MessageManager(private val state: ChatState) {
    
    // Message deduplication - FIXED: Prevent duplicate messages from dual connection paths
    private val processedUIMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val recentSystemEvents = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val MESSAGE_DEDUP_TIMEOUT = com.bitchat.android.util.AppConstants.UI.MESSAGE_DEDUP_TIMEOUT_MS // 30 seconds
    private val SYSTEM_EVENT_DEDUP_TIMEOUT = com.bitchat.android.util.AppConstants.UI.SYSTEM_EVENT_DEDUP_TIMEOUT_MS // 5 seconds
    
    // MARK: - Public Message Management
    
    fun addMessage(message: BitchatMessage) {
        val currentMessages = state.getMessagesValue().toMutableList()
        currentMessages.add(message)
        state.setMessages(currentMessages)
    }

    // Log a system message into the main chat (visible to user)
    fun addSystemMessage(text: String) {
        val sys = BitchatMessage(
            sender = "system",
            content = text,
            timestamp = Date(),
            isRelay = false
        )
        addMessage(sys)
    }
    
    fun clearMessages() {
        state.setMessages(emptyList())
        state.setChannelMessages(emptyMap())
    }
    
    // MARK: - Channel Message Management
    
    fun addChannelMessage(channel: String, message: BitchatMessage) {
        val currentChannelMessages = state.getChannelMessagesValue().toMutableMap()
        if (!currentChannelMessages.containsKey(channel)) {
            currentChannelMessages[channel] = mutableListOf()
        }
        
        val channelMessageList = currentChannelMessages[channel]?.toMutableList() ?: mutableListOf()
        channelMessageList.add(message)
        currentChannelMessages[channel] = channelMessageList
        state.setChannelMessages(currentChannelMessages)
        
        // Update unread count if not currently viewing this channel
        // Consider both classic channels (state.currentChannel) and geohash location channel selection
        val viewingClassicChannel = state.getCurrentChannelValue() == channel
        val viewingGeohashChannel = try {
            if (channel.startsWith("geo:")) {
                val geo = channel.removePrefix("geo:")
                val selected = state.selectedLocationChannel.value
                selected is com.bitchat.android.geohash.ChannelID.Location && selected.channel.geohash.equals(geo, ignoreCase = true)
            } else false
        } catch (_: Exception) { false }

        if (!viewingClassicChannel && !viewingGeohashChannel) {
            val currentUnread = state.getUnreadChannelMessagesValue().toMutableMap()
            currentUnread[channel] = (currentUnread[channel] ?: 0) + 1
            state.setUnreadChannelMessages(currentUnread)
        }
    }
    
    fun clearChannelMessages(channel: String) {
        val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
        updatedChannelMessages[channel] = emptyList()
        state.setChannelMessages(updatedChannelMessages)
    }
    
    fun removeChannelMessages(channel: String) {
        val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
        updatedChannelMessages.remove(channel)
        state.setChannelMessages(updatedChannelMessages)
        
        val updatedUnread = state.getUnreadChannelMessagesValue().toMutableMap()
        updatedUnread.remove(channel)
        state.setUnreadChannelMessages(updatedUnread)
    }
    
    fun clearChannelUnreadCount(channel: String) {
        val currentUnread = state.getUnreadChannelMessagesValue().toMutableMap()
        currentUnread.remove(channel)
        state.setUnreadChannelMessages(currentUnread)
    }
    
    // MARK: - Private Message Management

    fun addPrivateMessage(peerID: String, message: BitchatMessage) {
        val currentPrivateChats = state.getPrivateChatsValue().toMutableMap()
        if (!currentPrivateChats.containsKey(peerID)) {
            currentPrivateChats[peerID] = mutableListOf()
        }
        
        val chatMessages = currentPrivateChats[peerID]?.toMutableList() ?: mutableListOf()
        chatMessages.add(message)
        currentPrivateChats[peerID] = chatMessages
        state.setPrivateChats(currentPrivateChats)
        
        // Mark as unread if not currently viewing this chat
        if (state.getSelectedPrivateChatPeerValue() != peerID && message.sender != state.getNicknameValue()) {
            val currentUnread = state.getUnreadPrivateMessagesValue().toMutableSet()
            currentUnread.add(peerID)
            state.setUnreadPrivateMessages(currentUnread)
        }
    }

    // Variant that does not mark unread (used when we know the message has been read already, e.g., persisted Nostr read store)
    fun addPrivateMessageNoUnread(peerID: String, message: BitchatMessage) {
        val currentPrivateChats = state.getPrivateChatsValue().toMutableMap()
        if (!currentPrivateChats.containsKey(peerID)) {
            currentPrivateChats[peerID] = mutableListOf()
        }
        val chatMessages = currentPrivateChats[peerID]?.toMutableList() ?: mutableListOf()
        chatMessages.add(message)
        currentPrivateChats[peerID] = chatMessages
        state.setPrivateChats(currentPrivateChats)
    }
    
    fun clearPrivateMessages(peerID: String) {
        val updatedChats = state.getPrivateChatsValue().toMutableMap()
        updatedChats[peerID] = emptyList()
        state.setPrivateChats(updatedChats)
    }
    
    fun initializePrivateChat(peerID: String) {
        if (state.getPrivateChatsValue().containsKey(peerID)) return
        
        val updatedChats = state.getPrivateChatsValue().toMutableMap()
        updatedChats[peerID] = emptyList()
        state.setPrivateChats(updatedChats)
    }
    
    fun clearPrivateUnreadMessages(peerID: String) {
        val updatedUnread = state.getUnreadPrivateMessagesValue().toMutableSet()
        updatedUnread.remove(peerID)
        state.setUnreadPrivateMessages(updatedUnread)
    }
    
    // MARK: - Message Deduplication
    
    /**
     * Generate a unique key for message deduplication
     */
    fun generateMessageKey(message: BitchatMessage): String {
        val senderKey = message.senderPeerID ?: message.sender
        val contentHash = message.content.hashCode()
        return "$senderKey-${message.timestamp.time}-$contentHash"
    }
    
    /**
     * Check if a message has already been processed
     */
    fun isMessageProcessed(messageKey: String): Boolean {
        return processedUIMessages.contains(messageKey)
    }
    
    /**
     * Mark a message as processed
     */
    fun markMessageProcessed(messageKey: String) {
        processedUIMessages.add(messageKey)
    }
    
    /**
     * Check if a system event is a duplicate within the timeout window
     */
    fun isDuplicateSystemEvent(eventType: String, peerID: String): Boolean {
        val now = System.currentTimeMillis()
        val eventKey = "$eventType-$peerID"
        val lastEvent = recentSystemEvents[eventKey]
        
        if (lastEvent != null && (now - lastEvent) < SYSTEM_EVENT_DEDUP_TIMEOUT) {
            return true // Duplicate event
        }
        
        recentSystemEvents[eventKey] = now
        return false
    }
    
    /**
     * Clean up old entries from deduplication caches
     */
    fun cleanupDeduplicationCaches() {
        val now = System.currentTimeMillis()
        
        // Clean up processed UI messages (remove entries older than 30 seconds)
        if (processedUIMessages.size > 1000) {
            processedUIMessages.clear()
        }
        
        // Clean up recent system events (remove entries older than timeout)
        recentSystemEvents.entries.removeAll { (_, timestamp) ->
            (now - timestamp) > SYSTEM_EVENT_DEDUP_TIMEOUT * 2
        }
    }
    
    // MARK: - Delivery Status Updates
    
    fun updateMessageDeliveryStatus(messageID: String, status: DeliveryStatus) {
        // Update in private chats
        val updatedPrivateChats = state.getPrivateChatsValue().toMutableMap()
        var updated = false
        
        updatedPrivateChats.forEach { (peerID, messages) ->
            val updatedMessages = messages.toMutableList()
            val messageIndex = updatedMessages.indexOfFirst { it.id == messageID }
            if (messageIndex >= 0) {
                updatedMessages[messageIndex] = updatedMessages[messageIndex].copy(deliveryStatus = status)
                updatedPrivateChats[peerID] = updatedMessages
                updated = true
            }
        }
        
        if (updated) {
            state.setPrivateChats(updatedPrivateChats)
        }
        
        // Update in main messages
        val updatedMessages = state.getMessagesValue().toMutableList()
        val messageIndex = updatedMessages.indexOfFirst { it.id == messageID }
        if (messageIndex >= 0) {
            updatedMessages[messageIndex] = updatedMessages[messageIndex].copy(deliveryStatus = status)
            state.setMessages(updatedMessages)
        }
        
        // Update in channel messages
        val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
        updatedChannelMessages.forEach { (channel, messages) ->
            val channelMessagesList = messages.toMutableList()
            val channelMessageIndex = channelMessagesList.indexOfFirst { it.id == messageID }
            if (channelMessageIndex >= 0) {
                channelMessagesList[channelMessageIndex] = channelMessagesList[channelMessageIndex].copy(deliveryStatus = status)
                updatedChannelMessages[channel] = channelMessagesList
            }
        }
        state.setChannelMessages(updatedChannelMessages)
    }

    // Remove a message from all locations (main timeline, private chats, channels)
    fun removeMessageById(messageID: String) {
        // Main timeline
        run {
            val list = state.getMessagesValue().toMutableList()
            val idx = list.indexOfFirst { it.id == messageID }
            if (idx >= 0) {
                list.removeAt(idx)
                state.setMessages(list)
            }
        }
        // Private chats
        run {
            val chats = state.getPrivateChatsValue().toMutableMap()
            var changed = false
            chats.keys.toList().forEach { key ->
                val msgs = chats[key]?.toMutableList() ?: mutableListOf()
                val idx = msgs.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    msgs.removeAt(idx)
                    chats[key] = msgs
                    changed = true
                }
            }
            if (changed) state.setPrivateChats(chats)
        }
        // Channels
        run {
            val chans = state.getChannelMessagesValue().toMutableMap()
            var changed = false
            chans.keys.toList().forEach { ch ->
                val msgs = chans[ch]?.toMutableList() ?: mutableListOf()
                val idx = msgs.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    msgs.removeAt(idx)
                    chans[ch] = msgs
                    changed = true
                }
            }
            if (changed) state.setChannelMessages(chans)
        }
    }
    
    // MARK: - Utility Functions
    
    fun parseMentions(content: String, peerNicknames: Set<String>, currentNickname: String?): List<String> {
        val mentionRegex = "@([a-zA-Z0-9_]+)".toRegex()
        val allNicknames = peerNicknames + (currentNickname ?: "")
        
        return mentionRegex.findAll(content)
            .map { it.groupValues[1] }
            .filter { allNicknames.contains(it) }
            .distinct()
            .toList()
    }
    
    fun parseChannels(content: String): List<String> {
        val channelRegex = "#([a-zA-Z0-9_]+)".toRegex()
        return channelRegex.findAll(content)
            .map { it.groupValues[0] } // Include the #
            .distinct()
            .toList()
    }
    
    // MARK: - Emergency Clear
    
    fun clearAllMessages() {
        state.setMessages(emptyList())
        state.setPrivateChats(emptyMap())
        state.setChannelMessages(emptyMap())
        state.setUnreadPrivateMessages(emptySet())
        state.setUnreadChannelMessages(emptyMap())
        processedUIMessages.clear()
        recentSystemEvents.clear()
    }
}
