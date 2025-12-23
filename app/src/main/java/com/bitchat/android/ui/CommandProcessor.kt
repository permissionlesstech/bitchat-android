package com.bitchat.android.ui

import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatViewModel
import java.util.Date

/**
 * IRC-style command processor for BitChat.
 * Handles both mesh network and geohash (Nostr) commands with proper context awareness.
 */
class CommandProcessor(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val channelManager: ChannelManager,
    private val privateChatManager: PrivateChatManager
) {
    
    companion object {
        private const val NOSTR_PEER_PREFIX = "nostr_"
        private const val NOSTR_ID_LENGTH = 32
    }
    
    // Base command definitions - available in all contexts
    private val baseCommands = listOf(
        CommandSuggestion("/block", emptyList(), "[nickname]", "block or list blocked peers"),
        CommandSuggestion("/channels", emptyList(), null, "show all discovered channels"),
        CommandSuggestion("/clear", emptyList(), null, "clear chat messages"),
        CommandSuggestion("/hug", emptyList(), "<nickname>", "send someone a warm hug"),
        CommandSuggestion("/j", listOf("/join"), "<channel>", "join or create a channel"),
        CommandSuggestion("/m", listOf("/msg"), "<nickname> [message]", "send private message"),
        CommandSuggestion("/slap", emptyList(), "<nickname>", "slap someone with a trout"),
        CommandSuggestion("/unblock", emptyList(), "<nickname>", "unblock a peer"),
        CommandSuggestion("/w", emptyList(), null, "see who's online")
    )
    
    // Command processing entry point
    
    fun processCommand(
        command: String, 
        meshService: BluetoothMeshService, 
        myPeerID: String, 
        onSendMessage: (String, List<String>, String?) -> Unit, 
        viewModel: ChatViewModel? = null
    ): Boolean {
        if (!command.startsWith("/")) return false
        
        val parts = command.split(" ")
        val cmd = parts.first().lowercase()
        when (cmd) {
            "/j", "/join" -> handleJoinCommand(parts, myPeerID, viewModel)
            "/m", "/msg" -> handleMessageCommand(parts, meshService, viewModel)
            "/w" -> handleWhoCommand(meshService, viewModel)
            "/clear" -> handleClearCommand()
            "/pass" -> handlePassCommand(parts, myPeerID)
            "/block" -> handleBlockCommand(parts, meshService, viewModel)
            "/unblock" -> handleUnblockCommand(parts, meshService)
            "/hug" -> handleActionCommand(parts, "gives", "a warm hug 🫂", meshService, myPeerID, onSendMessage)
            "/slap" -> handleActionCommand(parts, "slaps", "around a bit with a large trout 🐟", meshService, myPeerID, onSendMessage)
            "/channels" -> handleChannelsCommand()
            else -> handleUnknownCommand(cmd)
        }
        
        return true
    }
    
    private fun handleJoinCommand(parts: List<String>, myPeerID: String, viewModel: ChatViewModel? = null) {
        // Geohash channels use Nostr, mesh channels use Bluetooth - they're separate systems
        val currentLocation = state.selectedLocationChannel.value
        if (currentLocation is com.bitchat.android.geohash.ChannelID.Location) {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "can't join mesh channels from here. switch to mesh chat first.",
                timestamp = Date(),
                isRelay = false
            ))
            return
        }
        
        if (parts.size < 2) {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "usage: /join <channel>",
                timestamp = Date(),
                isRelay = false
            ))
            return
        }
        
        val channelName = parts[1]
        val channel = if (channelName.startsWith("#")) channelName else "#$channelName"
        val password = if (parts.size > 2) parts[2] else null
        
        if (channelManager.joinChannel(channel, password, myPeerID)) {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "joined $channel",
                timestamp = Date(),
                isRelay = false
            ))
        }
    }
    
    private fun handleMessageCommand(parts: List<String>, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        if (parts.size < 2) {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "usage: /msg <nickname> [message]",
                timestamp = Date(),
                isRelay = false
            ))
            return
        }
        
        val targetName = parts[1].removePrefix("@")
        
        // Different peer lookup depending on whether we're in mesh or geohash context
        val currentLocation = state.selectedLocationChannel.value
        val peerID = try {
            when {
                currentLocation is com.bitchat.android.geohash.ChannelID.Location && viewModel != null -> {
                    // Geohash: find by display name in current participants
                    findGeohashPeerID(targetName, viewModel)
                }
                else -> {
                    // Mesh: find by nickname in connected peers
                    getPeerIDForNickname(targetName, meshService)
                }
            }
        } catch (e: Exception) {
            null
        }
            
            if (peerID == null) {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "couldn't find '$targetName'. they might be offline or using a different name.",
                timestamp = Date(),
                isRelay = false
            ))
            return
        }
        
        if (!privateChatManager.startPrivateChat(peerID, meshService)) {
            return
        }
        
        // If there's a message to send immediately, send it
        if (parts.size > 2) {
            val messageContent = parts.drop(2).joinToString(" ")
            val recipientNickname = resolveRecipientNickname(peerID, targetName, meshService, viewModel)
            
            privateChatManager.sendPrivateMessage(
                messageContent, 
                peerID, 
                recipientNickname,
                state.getNicknameValue(),
                getMyPeerID(meshService)
            ) { content, peerIdParam, recipientNicknameParam, messageId ->
                sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
            }
        } else {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "started chat with $targetName",
                timestamp = Date(),
                isRelay = false
            ))
        }
    }
    
    private fun handleWhoCommand(meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        val currentLocation = viewModel?.selectedLocationChannel?.value
        
        val (userList, context) = when (currentLocation) {
            is com.bitchat.android.geohash.ChannelID.Location -> {
                // Geohash location: show Nostr participants in this area
                val people = viewModel.geohashPeople.value ?: emptyList()
                val myNick = state.getNicknameValue()
                
                val others = people
                    .map { it.displayName }
                    .filter { !it.startsWith("${myNick}#") } // Don't list ourselves
                    .joinToString(", ")
                
                Pair(others, "in ${currentLocation.channel.geohash}")
            }
            else -> {
                // Mesh network: show Bluetooth-connected peers
                val peers = state.getConnectedPeersValue()
                val names = peers.joinToString(", ") { getPeerNickname(it, meshService) }
                Pair(names, "online")
            }
        }
        
        val message = if (userList.isEmpty()) {
            "nobody else around right now"
        } else {
            "$context: $userList"
        }
        
        messageManager.addMessage(BitchatMessage(
            sender = "system",
            content = message,
            timestamp = Date(),
            isRelay = false
        ))
    }
    
    private fun handleClearCommand() {
        // Clear messages based on current view context
        when {
            state.getSelectedPrivateChatPeerValue() != null -> {
                val peerID = state.getSelectedPrivateChatPeerValue()!!
                messageManager.clearPrivateMessages(peerID)
            }
            state.getCurrentChannelValue() != null -> {
                val channel = state.getCurrentChannelValue()!!
                messageManager.clearChannelMessages(channel)
            }
            state.selectedLocationChannel.value is com.bitchat.android.geohash.ChannelID.Location -> {
                val location = state.selectedLocationChannel.value as com.bitchat.android.geohash.ChannelID.Location
                messageManager.clearChannelMessages("geo:${location.channel.geohash}")
            }
            else -> {
                messageManager.clearMessages()
            }
        }
    }

    private fun handlePassCommand(parts: List<String>, peerID: String) {
        val currentChannel = state.getCurrentChannelValue()

        if (currentChannel == null) {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "need to be in a channel to set a password",
                timestamp = Date(),
                isRelay = false
            ))
            return
        }

        if (parts.size != 2) {
            channelManager.addChannelMessage(currentChannel, BitchatMessage(
                sender = "system",
                content = "usage: /pass <password>",
                timestamp = Date(),
                isRelay = false
            ), null)
            return
        }
        
        if (!channelManager.isChannelCreator(currentChannel, peerID)) {
            channelManager.addChannelMessage(currentChannel, BitchatMessage(
                sender = "system",
                content = "only the channel creator can set a password",
                timestamp = Date(),
                isRelay = false
            ), null)
            return
        }
        
        val newPassword = parts[1]
        channelManager.setChannelPassword(currentChannel, newPassword)
        channelManager.addChannelMessage(currentChannel, BitchatMessage(
            sender = "system",
            content = "password updated for $currentChannel",
            timestamp = Date(),
            isRelay = false
        ), null)
    }
    
    private fun handleBlockCommand(parts: List<String>, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        if (parts.size < 2) {
            // No target specified - list currently blocked users
            val blockedInfo = privateChatManager.listBlockedUsers()
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = blockedInfo,
                timestamp = Date(),
                isRelay = false
            ))
            return
        }
        
        val targetName = parts[1].removePrefix("@")
        val currentLocation = state.selectedLocationChannel.value
        
        // Different blocking mechanisms for mesh vs geohash
        if (currentLocation is com.bitchat.android.geohash.ChannelID.Location && viewModel != null) {
            viewModel.blockUserInGeohash(targetName)
        } else {
            privateChatManager.blockPeerByNickname(targetName, meshService)
        }
    }
    
    private fun handleUnblockCommand(parts: List<String>, meshService: BluetoothMeshService) {
        if (parts.size < 2) {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "usage: /unblock <nickname>",
                timestamp = Date(),
                isRelay = false
            ))
            return
        }
        
        val targetName = parts[1].removePrefix("@")
        privateChatManager.unblockPeerByNickname(targetName, meshService)
    }
    
    private fun handleActionCommand(
        parts: List<String>, 
        verb: String, 
        object_: String, 
        meshService: BluetoothMeshService,
        myPeerID: String,
        onSendMessage: (String, List<String>, String?) -> Unit
    ) {
        if (parts.size < 2) {
            messageManager.addMessage(BitchatMessage(
                sender = "system",
                content = "usage: /${parts[0].removePrefix("/")} <nickname>",
                timestamp = Date(),
                isRelay = false
            ))
            return
        }
        
        val targetName = parts[1].removePrefix("@")
        val myNick = state.getNicknameValue() ?: "someone"
        val actionMessage = "* $myNick $verb $targetName $object_ *"

        val currentLocation = state.selectedLocationChannel.value
        val currentChannel = state.getCurrentChannelValue()
        val privatePeer = state.getSelectedPrivateChatPeerValue()

        when {
            privatePeer != null -> {
                // Send action to private chat
                privateChatManager.sendPrivateMessage(
                    actionMessage,
                    privatePeer,
                    getPeerNickname(privatePeer, meshService),
                    state.getNicknameValue(),
                    myPeerID
                ) { content, peerIdParam, recipientNicknameParam, messageId ->
                    sendPrivateMessageVia(meshService, content, peerIdParam, recipientNicknameParam, messageId)
                }
            }
            currentLocation is com.bitchat.android.geohash.ChannelID.Location -> {
                // Geohash: let transport layer handle echo
                onSendMessage(actionMessage, emptyList(), null)
            }
            currentChannel != null -> {
                // Mesh channel: add local echo and send
                val message = BitchatMessage(
                    sender = myNick,
                    content = actionMessage,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = myPeerID,
                    channel = currentChannel
                )
                channelManager.addChannelMessage(currentChannel, message, myPeerID)
                onSendMessage(actionMessage, emptyList(), currentChannel)
            }
            else -> {
                // Main mesh chat: add local echo and send
                val message = BitchatMessage(
                    sender = myNick,
                    content = actionMessage,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = myPeerID,
                    channel = null
                )
                messageManager.addMessage(message)
                onSendMessage(actionMessage, emptyList(), null)
            }
        }
    }
    
    private fun handleChannelsCommand() {
        val channels = channelManager.getJoinedChannelsList()
        val message = if (channels.isEmpty()) {
            "not in any channels"
        } else {
            "channels: ${channels.joinToString(", ")}"
        }
        
        messageManager.addMessage(BitchatMessage(
            sender = "system",
            content = message,
            timestamp = Date(),
            isRelay = false
        ))
    }
    
    private fun handleUnknownCommand(cmd: String) {
        messageManager.addMessage(BitchatMessage(
            sender = "system",
            content = "unknown command: $cmd. type / to see available commands.",
            timestamp = Date(),
            isRelay = false
        ))
    }
    
    // Command autocomplete
    
    fun updateCommandSuggestions(input: String) {
        if (!input.startsWith("/")) {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
            return
        }
        
        val available = getAllAvailableCommands()
        val matching = filterCommands(available, input.lowercase())
        
        if (matching.isNotEmpty()) {
            state.setCommandSuggestions(matching)
            state.setShowCommandSuggestions(true)
        } else {
            state.setShowCommandSuggestions(false)
            state.setCommandSuggestions(emptyList())
        }
    }
    
    private fun getAllAvailableCommands(): List<CommandSuggestion> {
        val channelSpecific = if (state.getCurrentChannelValue() != null) {
            listOf(
                CommandSuggestion("/pass", emptyList(), "[password]", "change channel password"),
                CommandSuggestion("/save", emptyList(), null, "save channel messages locally"),
                CommandSuggestion("/transfer", emptyList(), "<nickname>", "transfer channel ownership")
            )
        } else {
            emptyList()
        }
        
        return baseCommands + channelSpecific
    }
    
    private fun filterCommands(commands: List<CommandSuggestion>, input: String): List<CommandSuggestion> {
        return commands.filter { cmd ->
            cmd.command.startsWith(input) || cmd.aliases.any { it.startsWith(input) }
        }.sortedBy { it.command }
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        state.setShowCommandSuggestions(false)
        state.setCommandSuggestions(emptyList())
        return "${suggestion.command} "
    }
    
    // Mention autocomplete (@nickname)
    
    fun updateMentionSuggestions(input: String, meshService: BluetoothMeshService, viewModel: ChatViewModel? = null) {
        val atIndex = input.lastIndexOf('@')
        if (atIndex == -1) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        val textAfterAt = input.substring(atIndex + 1)
        
        // Stop suggesting if there's a space after the @
        if (textAfterAt.contains(' ')) {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
            return
        }
        
        // Get list of mentionable users based on current context
        val currentLocation = viewModel?.selectedLocationChannel?.value
        val candidates = when (currentLocation) {
            is com.bitchat.android.geohash.ChannelID.Location -> {
                // Geohash: use display names from participants
                val people = viewModel.geohashPeople.value ?: emptyList()
                val myNick = state.getNicknameValue()
                people.mapNotNull { person ->
                    val name = person.displayName
                    if (name.startsWith("${myNick}#")) null else name
                }
            }
            else -> {
                // Mesh: use peer nicknames
                val myPeerID = meshService.myPeerID
                meshService.getPeerNicknames().values.filter { 
                    it != meshService.getPeerNicknames()[myPeerID] 
                }
            }
        }
        
        val matching = candidates
            .filter { it.startsWith(textAfterAt, ignoreCase = true) }
            .sorted()
        
        if (matching.isNotEmpty()) {
            state.setMentionSuggestions(matching)
            state.setShowMentionSuggestions(true)
        } else {
            state.setShowMentionSuggestions(false)
            state.setMentionSuggestions(emptyList())
        }
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        state.setShowMentionSuggestions(false)
        state.setMentionSuggestions(emptyList())
        
        val atIndex = currentText.lastIndexOf('@')
        if (atIndex == -1) {
            return "$currentText@$nickname "
        }
        
        val textBeforeAt = currentText.substring(0, atIndex)
        return "$textBeforeAt@$nickname "
    }
    
    // Helper functions for peer lookup and messaging
    
    /**
     * Find a geohash peer ID by their display name.
     * Uses full 32-char Nostr ID to avoid collisions.
     */
    private fun findGeohashPeerID(targetName: String, viewModel: ChatViewModel): String? {
        val people = viewModel.geohashPeople.value ?: return null
        
        val match = people.find { person ->
            val (baseName, _) = try {
                com.bitchat.android.ui.splitSuffix(person.displayName)
            } catch (e: Exception) {
                return@find false
            }
            baseName.equals(targetName, ignoreCase = true)
        } ?: return null
        
        val idLength = minOf(match.id.length, NOSTR_ID_LENGTH)
        return "$NOSTR_PEER_PREFIX${match.id.take(idLength)}"
    }
    
    /**
     * Resolve the display nickname for a recipient.
     * Handles both mesh peers and Nostr conversation keys.
     */
    private fun resolveRecipientNickname(
        peerID: String, 
        fallbackName: String, 
        meshService: BluetoothMeshService,
        viewModel: ChatViewModel?
    ): String {
        return when {
            peerID.startsWith(NOSTR_PEER_PREFIX) && viewModel != null -> {
                // Nostr peer: look up display name from geohash people
                val pubkeyHex = peerID.removePrefix(NOSTR_PEER_PREFIX)
                val people = viewModel.geohashPeople.value ?: emptyList()
                people.find { it.id.startsWith(pubkeyHex) }?.displayName ?: fallbackName
            }
            else -> {
                // Mesh peer: use nickname from mesh service
                getPeerNickname(peerID, meshService)
            }
        }
    }
    
    private fun getPeerIDForNickname(nickname: String, meshService: BluetoothMeshService): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    private fun getPeerNickname(peerID: String, meshService: BluetoothMeshService): String {
        return meshService.getPeerNicknames()[peerID] ?: peerID
    }
    
    private fun getMyPeerID(meshService: BluetoothMeshService): String {
        return meshService.myPeerID
    }
    
    private fun sendPrivateMessageVia(meshService: BluetoothMeshService, content: String, peerID: String, recipientNickname: String, messageId: String) {
        meshService.sendPrivateMessage(content, peerID, recipientNickname, messageId)
    }
}
