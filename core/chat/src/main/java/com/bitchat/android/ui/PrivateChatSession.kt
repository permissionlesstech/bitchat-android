package com.bitchat.android.ui

import com.bitchat.android.services.ContactDirectory
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Starting and ending the private chat on screen: loading its history,
 * selecting it, and telling notifications which chat is open.
 *
 * Moved out of ChatViewModel unchanged, so the private chat's own ViewModel
 * can start and end it.
 */
@ActivityRetainedScoped
class PrivateChatSession @Inject constructor(
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val geohashSession: GeohashSession,
    private val sessionMesh: ChatSessionMesh,
) {

    suspend fun start(peerID: String) {
        // For geohash conversation keys, ensure DM subscription is active
        if (peerID.startsWith("nostr_")) {
            geohashSession.ensureGeohashDMSubscriptionForConversation(peerID)
        }

        val (conversationID, success) = withContext(Dispatchers.IO) {
            val canonicalID = ContactDirectory.canonicalConversationId(peerID)
            com.bitchat.android.services.AppStateStore
                .loadPrivateConversationHistory(canonicalID)
            state.setPrivateChats(
                ContactDirectory.canonicalizePrivateChats(
                    com.bitchat.android.services.AppStateStore.privateMessages.value
                )
            )
            val unreadAliases = matchingUnreadAliases(
                unreadConversationIDs = state.getUnreadPrivateMessagesValue(),
                canonicalConversationID = canonicalID,
                canonicalize = ContactDirectory::canonicalConversationId
            )
            canonicalID to privateChatManager.startPrivateChat(
                peerID = canonicalID,
                meshService = sessionMesh.unified,
                unreadAliases = unreadAliases
            )
        }
        if (success) {
            // Notify notification manager about current private chat
            notificationManager.setCurrentPrivateChatPeer(conversationID)
            // Clear notifications for this sender since user is now viewing the chat
            notificationManager.clearNotificationsForSender(conversationID)
        }
    }

    fun end() {
        val conversationID = state.getSelectedPrivateChatPeerValue()
        privateChatManager.endPrivateChat()
        if (conversationID != null) {
            com.bitchat.android.services.AppStateStore
                .releasePrivateConversationHistory(conversationID)
            state.setPrivateChats(
                ContactDirectory.canonicalizePrivateChats(
                    com.bitchat.android.services.AppStateStore.privateMessages.value
                )
            )
        }
        // Notify notification manager that no private chat is active
        notificationManager.setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        notificationManager.clearMeshMentionNotifications()
    }

    /**
     * Ends the chat with [conversationID], unless a different one has been
     * selected since.
     *
     * A private chat route calls this as it leaves. Opening one chat from
     * another can select the new one before the old route is disposed, and
     * that late call must not end the chat now on screen.
     */
    fun end(conversationID: String) {
        val selected = state.getSelectedPrivateChatPeerValue() ?: return
        if (
            ContactDirectory.canonicalConversationId(selected).equals(
                ContactDirectory.canonicalConversationId(conversationID),
                ignoreCase = true
            )
        ) {
            end()
        }
    }
}
