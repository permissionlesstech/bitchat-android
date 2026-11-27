package com.bitchat.android.ui

import com.bitchat.android.di.ChatSessionScoped
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
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
@ChatSessionScoped
class PrivateChatSession @Inject constructor(
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
    private val notificationManager: NotificationManager,
    private val geohashSession: GeohashSession,
    private val sessionMesh: ChatSessionMesh,
    private val appStateStore: AppStateStore,
    private val contactDirectory: ContactDirectory,
) {
    // The route that started the chat on screen, and that chat. Guarded by [ownerLock], which a
    // start also holds while it selects its chat on IO: a route's claim, its end, and a start's
    // check-then-select are each atomic, so a start that lost its route selects nothing.
    private val ownerLock = Any()
    private var owner: Any? = null
    private var ownedConversation: String? = null

    /**
     * Starts the chat with [peerID] for [owner], the route showing it, which alone may end it with
     * [end], from now on. Returns false when the chat could not start, as for a blocked peer. A start
     * another route has taken over since selects nothing and returns true: its route is leaving,
     * and a close from it must not reach the screen that replaced it.
     */
    suspend fun start(peerID: String, owner: Any): Boolean {
        // Claimed before the chat loads: the route this one replaces can be disposed while it
        // loads, and its end must not undo the selection this start makes.
        synchronized(ownerLock) { this.owner = owner }
        // For geohash conversation keys, ensure DM subscription is active
        if (peerID.startsWith("nostr_")) {
            geohashSession.ensureGeohashDMSubscriptionForConversation(peerID)
        }

        val (conversationID, success) = withContext(Dispatchers.IO) {
            val canonicalID = contactDirectory.canonicalConversationId(peerID)
            appStateStore.loadPrivateConversationHistory(canonicalID)
            state.setPrivateChats(
                contactDirectory.canonicalizePrivateChats(
                    appStateStore.privateMessages.value
                )
            )
            val unreadAliases = matchingUnreadAliases(
                unreadConversationIDs = state.getUnreadPrivateMessagesValue(),
                canonicalConversationID = canonicalID,
                canonicalize = contactDirectory::canonicalConversationId
            )
            synchronized(ownerLock) {
                if (owner !== this@PrivateChatSession.owner) return@withContext null
                // Even when it fails: the route then closes, and its end lets go of what was selected.
                ownedConversation = canonicalID
                canonicalID to privateChatManager.startPrivateChat(
                    peerID = canonicalID,
                    meshService = sessionMesh.unified,
                    unreadAliases = unreadAliases
                )
            }
        } ?: return true
        if (success && synchronized(ownerLock) { owner === this.owner }) {
            // Notify notification manager about current private chat
            notificationManager.setCurrentPrivateChatPeer(conversationID)
            // Clear notifications for this sender since user is now viewing the chat
            notificationManager.clearNotificationsForSender(conversationID)
        }
        return success
    }

    fun end() = synchronized(ownerLock) { endConversation(state.getSelectedPrivateChatPeerValue()) }

    private fun endConversation(conversationID: String?) {
        owner = null
        ownedConversation = null
        privateChatManager.endPrivateChat()
        if (conversationID != null) {
            appStateStore.releasePrivateConversationHistory(conversationID)
            state.setPrivateChats(
                contactDirectory.canonicalizePrivateChats(
                    appStateStore.privateMessages.value
                )
            )
        }
        // Notify notification manager that no private chat is active
        notificationManager.setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        notificationManager.clearMeshMentionNotifications()
    }

    /**
     * Ends the chat [owner] started, unless another route has started a chat since.
     *
     * A private chat route calls this as it leaves. Opening a chat from another, or the same
     * conversation again under its contact ID, starts it for the new route before the old one is
     * disposed, and that late call must not end the chat now on screen. A command such as `/join`
     * can instead have left private chat before the route goes; the route still owns its chat
     * then and ends it, or the departed peer would keep notification focus and its history.
     */
    fun end(owner: Any) = synchronized(ownerLock) {
        if (owner === this.owner) endConversation(ownedConversation)
    }
}
