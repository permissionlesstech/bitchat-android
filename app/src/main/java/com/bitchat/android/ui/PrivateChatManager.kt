package com.bitchat.android.ui

import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver

import java.util.*
import android.util.Log

/**
 * Interface for Noise session operations needed by PrivateChatManager
 * This avoids reflection and makes dependencies explicit
 */
interface NoiseSessionDelegate {
    fun hasEstablishedSession(peerID: String): Boolean
    fun initiateHandshake(peerID: String)
    fun getMyPeerID(): String
}

enum class PrivateMessageOrigin {
    MESH,
    NOSTR
}

/**
 * Handles private chat functionality including peer management and blocking.
 */
class PrivateChatManager(
    private val state: ChatState,
    private val messageManager: MessageManager,
    private val dataManager: DataManager,
    private val noiseSessionDelegate: NoiseSessionDelegate,
    private val trackUnreadMessages: Boolean = true,
    private val hasReadReceiptBeenSent: (messageID: String) -> Boolean = { false },
    private val markMessageReadLocally: (messageID: String) -> Unit = {}
) {

    companion object {
        private const val TAG = "PrivateChatManager"
    }

    private val fingerprintManager = PeerFingerprintManager.getInstance()

    // Track received private messages that need read receipts
    private val unreadReceivedMessages = mutableMapOf<String, MutableList<BitchatMessage>>()

    // MARK: - Private Chat Lifecycle

    fun startPrivateChat(
        peerID: String,
        meshService: MeshService,
        unreadAliases: Set<String> = emptySet()
    ): Boolean {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val route = ContactDirectory.resolve(conversationID)
        val meshPeerID = route.meshPeerID ?: peerID.takeIf { ContactIdentityResolver.isMeshPeerId(it) }

        if (isPeerBlocked(peerID)) {
            val peerNickname = route.displayName ?: getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot start chat with $peerNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }

        if (meshPeerID != null && meshService.getPeerInfo(meshPeerID)?.isConnected == true) {
            establishNoiseSessionIfNeeded(meshPeerID, meshService)
        }

        try {
            consolidateNostrTempConversationIfNeeded(conversationID, meshService)
        } catch (_: Exception) { }

        state.setSelectedPrivateChatPeer(conversationID)

        // Clear unread
        messageManager.clearPrivateUnreadMessages(conversationID, unreadAliases)

        // Initialize chat if needed
        messageManager.initializePrivateChat(conversationID)

        // Send read receipts for all unread messages from this peer
        sendReadReceiptsForPeer(conversationID, meshPeerID, meshService)

        return true
    }

    fun endPrivateChat() {
        state.setSelectedPrivateChatPeer(null)
    }

    fun sendPrivateMessage(
        content: String,
        peerID: String,
        recipientNickname: String?,
        senderNickname: String?,
        myPeerID: String,
        onSendMessage: (String, String, String, String) -> Unit
    ): Boolean {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        if (isPeerBlocked(peerID)) {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot send message to $recipientNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }

        val message = BitchatMessage(
            sender = senderNickname ?: myPeerID,
            content = content,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = myPeerID,
            deliveryStatus = DeliveryStatus.Sending
        )

        messageManager.addPrivateMessage(conversationID, message)
        onSendMessage(content, conversationID, recipientNickname ?: "", message.id)

        return true
    }

    /**
     * Persists the local echo before handing the payload to a transport.
     *
     * A failed database write deliberately aborts the send: otherwise the remote peer could
     * receive a message that disappears from the sender's conversation after process death.
     */
    suspend fun sendPrivateMessageDurably(
        content: String,
        peerID: String,
        recipientNickname: String?,
        senderNickname: String?,
        myPeerID: String,
        onSendMessage: (String, String, String, String) -> Unit
    ): Boolean {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        if (isPeerBlocked(peerID)) {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "cannot send message to $recipientNickname: user is blocked.",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }

        val message = BitchatMessage(
            sender = senderNickname ?: myPeerID,
            content = content,
            timestamp = Date(),
            isRelay = false,
            isPrivate = true,
            recipientNickname = recipientNickname,
            senderPeerID = myPeerID,
            deliveryStatus = DeliveryStatus.Sending
        )

        if (!messageManager.addPrivateMessageDurably(conversationID, message, forceRead = true)) {
            return false
        }
        onSendMessage(content, conversationID, recipientNickname ?: "", message.id)
        return true
    }

    // MARK: - Peer Management

    fun isPeerBlocked(peerID: String): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
            ?: ContactIdentityResolver.fingerprintFromContactConversationId(ContactDirectory.canonicalConversationId(peerID))
            ?: ContactDirectory.resolve(peerID).noisePublicKey?.let { ContactIdentityResolver.fingerprintHex(it) }
        return fingerprint != null && dataManager.isUserBlocked(fingerprint)
    }

    fun toggleFavorite(peerID: String) {
        var fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
            ?: ContactIdentityResolver.fingerprintFromContactConversationId(ContactDirectory.canonicalConversationId(peerID))

        if (fingerprint == null && ContactIdentityResolver.isNoiseKeyHex(peerID)) {
            try {
                val pubBytes = ContactIdentityResolver.bytesFromHex(peerID) ?: return
                fingerprint = ContactIdentityResolver.fingerprintHex(pubBytes)
                Log.d(TAG, "Computed fingerprint from noise key hex for offline toggle: $fingerprint")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to compute fingerprint from noise key hex: ${e.message}")
            }
        }

        if (fingerprint == null) {
            Log.w(TAG, "toggleFavorite: no fingerprint for peerID=$peerID; ignoring toggle")
            return
        }

        Log.d(TAG, "toggleFavorite called for peerID: $peerID, fingerprint: $fingerprint")

        val wasFavorite = dataManager.isFavorite(fingerprint!!)
        Log.d(TAG, "Current favorite status: $wasFavorite")

        val currentFavorites = state.getFavoritePeersValue()
        Log.d(TAG, "Current UI state favorites: $currentFavorites")

        if (wasFavorite) {
            dataManager.removeFavorite(fingerprint!!)
            Log.d(TAG, "Removed from favorites: $fingerprint")
        } else {
            dataManager.addFavorite(fingerprint!!)
            Log.d(TAG, "Added to favorites: $fingerprint")
        }

        // Always update state to trigger UI refresh - create new set to ensure change detection
        val newFavorites = dataManager.favoritePeers.toSet()
        state.setFavoritePeers(newFavorites)

        Log.d(TAG, "Force updated favorite peers state. New favorites: $newFavorites")
        Log.d(TAG, "All peer fingerprints: ${fingerprintManager.getAllPeerFingerprints()}")
    }


    fun isFavorite(peerID: String): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
            ?: ContactIdentityResolver.fingerprintFromContactConversationId(ContactDirectory.canonicalConversationId(peerID))
            ?: if (ContactIdentityResolver.isNoiseKeyHex(peerID)) {
                ContactIdentityResolver.bytesFromHex(peerID)?.let { ContactIdentityResolver.fingerprintHex(it) }
            } else {
                null
            }

        if (fingerprint != null && dataManager.isFavorite(fingerprint)) {
            Log.d(TAG, "isFavorite check: peerID=$peerID, fingerprint=$fingerprint, result=true")
            return true
        }

        val persistedFavorite = try {
            FavoritesPersistenceService.shared.getFavoriteStatus(peerID)?.isFavorite == true
        } catch (_: Exception) {
            false
        }
        Log.d(TAG, "isFavorite check: peerID=$peerID, fingerprint=$fingerprint, result=$persistedFavorite")
        return persistedFavorite
    }

    fun getPeerFingerprint(peerID: String): String? {
        return fingerprintManager.getFingerprintForPeer(peerID)
    }

    fun getPeerFingerprints(): Map<String, String> {
        return fingerprintManager.getAllPeerFingerprints()
    }

    // MARK: - Block/Unblock Operations

    fun blockPeer(peerID: String, meshService: MeshService): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
        if (fingerprint != null) {
            dataManager.addBlockedUser(fingerprint)

            val peerNickname = getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "blocked user $peerNickname",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)

            // End private chat if currently in one with this peer
            if (state.getSelectedPrivateChatPeerValue() == peerID) {
                endPrivateChat()
            }

            return true
        }
        return false
    }

    fun unblockPeer(peerID: String, meshService: MeshService): Boolean {
        val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
        if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
            dataManager.removeBlockedUser(fingerprint)

            val peerNickname = getPeerNickname(peerID, meshService)
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "unblocked user $peerNickname",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return true
        }
        return false
    }

    fun blockPeerByNickname(targetName: String, meshService: MeshService): Boolean {
        val peerID = getPeerIDForNickname(targetName, meshService)

        if (peerID != null) {
            return blockPeer(peerID, meshService)
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "user '$targetName' not found",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
    }

    fun unblockPeerByNickname(targetName: String, meshService: MeshService): Boolean {
        val peerID = getPeerIDForNickname(targetName, meshService)

        if (peerID != null) {
            val fingerprint = fingerprintManager.getFingerprintForPeer(peerID)
            if (fingerprint != null && dataManager.isUserBlocked(fingerprint)) {
                return unblockPeer(peerID, meshService)
            } else {
                val systemMessage = BitchatMessage(
                    sender = "system",
                    content = "user '$targetName' is not blocked",
                    timestamp = Date(),
                    isRelay = false
                )
                messageManager.addMessage(systemMessage)
                return false
            }
        } else {
            val systemMessage = BitchatMessage(
                sender = "system",
                content = "user '$targetName' not found",
                timestamp = Date(),
                isRelay = false
            )
            messageManager.addMessage(systemMessage)
            return false
        }
    }

    fun listBlockedUsers(): String {
        val blockedCount = dataManager.blockedUsers.size
        return if (blockedCount == 0) {
            "no blocked users"
        } else {
            "blocked users: $blockedCount fingerprints"
        }
    }

    // MARK: - Message Handling

    fun handleIncomingPrivateMessage(message: BitchatMessage) {
        handleIncomingPrivateMessage(
            message = message,
            suppressUnread = false,
            origin = PrivateMessageOrigin.MESH
        )
    }

    fun handleIncomingPrivateMessage(
        message: BitchatMessage,
        suppressUnread: Boolean,
        origin: PrivateMessageOrigin = PrivateMessageOrigin.MESH
    ) {
        val senderPeerID = message.senderPeerID
        if (senderPeerID != null) {
            val conversationID = ContactDirectory.canonicalConversationId(senderPeerID)
            // Mesh-origin private message: AppStateStore updates the list; avoid double-add here.
            if (!isPeerBlocked(senderPeerID)) {
                // Ensure chat exists
                messageManager.initializePrivateChat(conversationID)

                // Mesh messages are already reflected through AppStateStore by the mesh service.
                // Nostr messages originate here and must be added explicitly, even after their
                // sender alias has canonicalized to a contact_* conversation ID.
                if (origin == PrivateMessageOrigin.NOSTR) {
                    if (suppressUnread || !trackUnreadMessages) {
                        messageManager.addPrivateMessageNoUnread(conversationID, message)
                    } else {
                        messageManager.addPrivateMessage(conversationID, message)
                    }
                }

                // Track as unread for read receipt purposes if not focused
                if (trackUnreadMessages &&
                    !suppressUnread &&
                    state.getSelectedPrivateChatPeerValue() != conversationID
                ) {
                    val unreadList = unreadReceivedMessages.getOrPut(conversationID) { mutableListOf() }
                    unreadList.add(message)
                    Log.d(TAG, "Queued unread from $conversationID (count=${unreadList.size})")
                    val currentUnread = state.getUnreadPrivateMessagesValue().toMutableSet()
                    currentUnread.add(conversationID)
                    state.setUnreadPrivateMessages(currentUnread)
                }
            }
            return
        }
        // Non-mesh path (e.g., Nostr): add to UI state using existing logic
        val inferredPeer = state.getSelectedPrivateChatPeerValue() ?: return
        if (suppressUnread) {
            messageManager.addPrivateMessageNoUnread(inferredPeer, message)
        } else {
            messageManager.addPrivateMessage(inferredPeer, message)
        }
    }

    /**
     * Durable admission for transports that do not pass through the mesh admission pipeline.
     *
     * Nostr acknowledgements are emitted by the caller only after this returns true, which lets a
     * failed write be retried rather than silently acknowledging a message that was never saved.
     */
    suspend fun handleIncomingPrivateMessageDurably(
        message: BitchatMessage,
        suppressUnread: Boolean,
        origin: PrivateMessageOrigin
    ): Boolean {
        val senderPeerID = message.senderPeerID
        val conversationID = senderPeerID
            ?.let(ContactDirectory::canonicalConversationId)
            ?: state.getSelectedPrivateChatPeerValue()
            ?: return false

        if (senderPeerID != null && isPeerBlocked(senderPeerID)) return false
        messageManager.initializePrivateChat(conversationID)

        val shouldPersistHere = origin == PrivateMessageOrigin.NOSTR || senderPeerID == null
        if (shouldPersistHere) {
            val accepted = messageManager.addPrivateMessageDurably(
                peerID = conversationID,
                message = message,
                forceRead = suppressUnread || !trackUnreadMessages
            )
            if (!accepted) return false
        }

        if (
            senderPeerID != null &&
            trackUnreadMessages &&
            !suppressUnread &&
            state.getSelectedPrivateChatPeerValue() != conversationID
        ) {
            val unreadList = unreadReceivedMessages.getOrPut(conversationID) { mutableListOf() }
            unreadList.add(message)
            val currentUnread = state.getUnreadPrivateMessagesValue().toMutableSet()
            currentUnread.add(conversationID)
            state.setUnreadPrivateMessages(currentUnread)
        }
        return true
    }

    /**
     * Send read receipts for all unread messages from a specific peer
     * Called when the user focuses on a private chat
     */
    fun sendReadReceiptsForPeer(
        conversationID: String,
        meshPeerID: String?,
        meshService: MeshService
    ) {
        val canonicalConversationID = ContactDirectory.canonicalConversationId(conversationID)

        // Collect candidate messages: all incoming messages from this peer in the conversation
        val chats = try { state.getPrivateChatsValue() } catch (_: Exception) { emptyMap<String, List<BitchatMessage>>() }
        val messages = chats[canonicalConversationID].orEmpty()

        if (messages.isEmpty()) {
            Log.d(TAG, "No messages found for conversation $canonicalConversationID to send read receipts")
        }

        val myNickname = state.getNicknameValue() ?: "unknown"
        val hasMesh = meshPeerID != null && try {
            meshService.getPeerInfo(meshPeerID)?.isConnected == true &&
                meshService.hasEstablishedSession(meshPeerID)
        } catch (_: Exception) {
            false
        }
        var sentCount = 0
        messages.forEach { msg ->
            val senderPeerID = msg.senderPeerID
            val isFromTarget = senderPeerID != null && (
                senderPeerID == meshPeerID ||
                    ContactDirectory.canonicalConversationId(senderPeerID) == canonicalConversationID
                )
            if (isFromTarget) {
                try {
                    markMessageReadLocally(msg.id)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to persist local read for message ${msg.id}: ${e.message}")
                }
            }
            if (isFromTarget && meshPeerID != null && !hasReadReceiptBeenSent(msg.id)) {
                try {
                    if (hasMesh) {
                        meshService.sendReadReceipt(msg.id, meshPeerID, myNickname)
                        sentCount += 1
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send read receipt for message ${msg.id}: ${e.message}")
                }
            }
        }

        // Clear any locally tracked unread queue for this peer
        unreadReceivedMessages.remove(canonicalConversationID)
        // Also clear UI unread marker for this peer now that chat is focused/read
        try { messageManager.clearPrivateUnreadMessages(canonicalConversationID) } catch (_: Exception) { }
        Log.d(
            TAG,
            "Sent $sentCount read receipts for conversation $canonicalConversationID via mesh peer $meshPeerID"
        )
    }

    fun cleanupDisconnectedPeer(peerID: String) {
        // End private chat if peer disconnected
        if (state.getSelectedPrivateChatPeerValue() == peerID) {
            endPrivateChat()
        }

        // Clean up unread messages for disconnected peer
        unreadReceivedMessages.remove(peerID)
        Log.d(TAG, "Cleaned up unread messages for disconnected peer $peerID")
    }

    // MARK: - Noise Session Management

    /**
     * Establish Noise session if needed before starting private chat
     * Uses same lexicographical logic as MessageHandler.handleNoiseIdentityAnnouncement
     */
    private fun establishNoiseSessionIfNeeded(peerID: String, meshService: MeshService) {
        if (noiseSessionDelegate.hasEstablishedSession(peerID)) {
            Log.d(TAG, "Noise session already established with $peerID")
            return
        }

        Log.d(TAG, "No Noise session with $peerID, determining who should initiate handshake")

        val myPeerID = noiseSessionDelegate.getMyPeerID()

        // Use lexicographical comparison to decide who initiates (same logic as MessageHandler)
        if (myPeerID < peerID) {
            // We should initiate the handshake
            Log.d(
                TAG,
                "Our peer ID lexicographically < target peer ID, initiating Noise handshake with $peerID"
            )
            noiseSessionDelegate.initiateHandshake(peerID)
        } else {
            // They should initiate, we send identity announcement through standard announce
            Log.d(
                TAG,
                "Our peer ID lexicographically >= target peer ID, sending identity announcement to prompt handshake from $peerID"
            )
            meshService.sendAnnouncementToPeer(peerID)
            Log.d(TAG, "Sent identity announcement to $peerID – starting handshake now from our side")
            noiseSessionDelegate.initiateHandshake(peerID)
        }

    }

    // MARK: - Utility Functions

    private fun getPeerIDForNickname(nickname: String, meshService: MeshService): String? {
        return meshService.getPeerNicknames().entries.find { it.value == nickname }?.key
    }

    private fun getPeerNickname(peerID: String, meshService: MeshService): String {
        return meshService.getPeerNicknames()[peerID] ?: peerID
    }

    // MARK: - Consolidation

    private fun consolidateNostrTempConversationIfNeeded(targetPeerID: String, meshService: MeshService) {
        val targetConversationID = ContactDirectory.canonicalConversationId(targetPeerID)
        if (ContactIdentityResolver.isNostrAlias(targetPeerID)) return

        val tryMergeKeys = mutableListOf<String>()
        val noiseKey = when {
            ContactIdentityResolver.isNoiseKeyHex(targetPeerID) ->
                ContactIdentityResolver.bytesFromHex(targetPeerID)
            ContactIdentityResolver.isMeshPeerId(targetPeerID) ->
                meshService.getPeerInfo(targetPeerID)?.noisePublicKey
            else -> null
        }

        if (noiseKey != null) {
            val noiseHex = ContactIdentityResolver.noiseKeyHex(noiseKey)
            if (!noiseHex.equals(targetPeerID, ignoreCase = true)) {
                tryMergeKeys.add(noiseHex)
            }
            try {
                FavoritesPersistenceService.shared.findNostrPubkey(noiseKey)
                    ?.let { ContactIdentityResolver.nostrAliasForPubkey(it) }
                    ?.let { tryMergeKeys.add(it) }
            } catch (_: Exception) { }
        }

        if (tryMergeKeys.isNotEmpty()) {
            com.bitchat.android.services.ConversationAliasResolver.unifyChatsIntoPeer(
                state = state,
                targetPeerID = targetConversationID,
                keysToMerge = tryMergeKeys
            )
        }
    }

    // MARK: - Emergency Clear

    fun clearAllPrivateChats() {
        state.setSelectedPrivateChatPeer(null)
        state.setUnreadPrivateMessages(emptySet())

        // Clear unread messages tracking
        unreadReceivedMessages.clear()

        // Clear fingerprints via centralized manager (only if needed for emergency clear)
        // Note: This will be handled by the parent PeerManager.clearAllPeers()
    }

    // MARK: - Public Getters

    fun getAllPeerFingerprints(): Map<String, String> {
        return fingerprintManager.getAllPeerFingerprints()
    }
}
