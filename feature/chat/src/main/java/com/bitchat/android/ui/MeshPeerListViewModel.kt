package com.bitchat.android.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.navigation.Navigator
import com.bitchat.android.navigation.MeshPeerListRoute
import com.bitchat.android.navigation.openPrivateChat
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationStoreState
import com.bitchat.android.services.DeletedPrivateConversation
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * The network view: conversations, channels, and the people nearby on the
 * mesh or in a geohash.
 *
 * Its members keep the names the sheet's composables called on ChatViewModel,
 * so moving the sheet onto it changed only the type they take.
 */
@HiltViewModel
class MeshPeerListViewModel @Inject constructor(
    state: ChatState,
    private val sessionMesh: ChatSessionMesh,
    private val channelManager: ChannelManager,
    private val conversationDirectory: ConversationDirectory,
    private val contactFavorites: ContactFavorites,
    private val verificationHandler: VerificationHandler,
    private val geohashSession: GeohashSession,
    private val navigator: Navigator,
    wifiAwarePeers: WifiAwarePeers,
) : ViewModel() {

    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val joinedChannels: StateFlow<Set<String>> = state.joinedChannels
    val currentChannel: StateFlow<String?> = state.currentChannel
    val selectedPrivateChatPeer: StateFlow<String?> = state.selectedPrivateChatPeer
    val nickname: StateFlow<String> = state.nickname
    val unreadChannelMessages: StateFlow<Map<String, Int>> = state.unreadChannelMessages
    val unreadPrivateMessages: StateFlow<Set<String>> = state.unreadPrivateMessages
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val peerRSSI: StateFlow<Map<String, Int>> = state.peerRSSI
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerFavoritedUs: StateFlow<Set<String>> = state.peerFavoritedUs
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedLocationChannel: StateFlow<ChannelID?> = state.selectedLocationChannel
    val geohashPeople: StateFlow<List<GeoPerson>> = geohashSession.geohashPeople
    val isTeleported: StateFlow<Boolean> = state.isTeleported
    val teleportedGeo: StateFlow<Set<String>> = geohashSession.teleportedGeo
    val verifiedFingerprints: StateFlow<Set<String>> = verificationHandler.verifiedFingerprints
    val wifiAwareConnected: StateFlow<Map<String, *>> = wifiAwarePeers.connected
    internal val conversations: StateFlow<List<ConversationSummary>> =
        conversationDirectory.conversations
    val conversationStoreState: StateFlow<ConversationStoreState> =
        conversationDirectory.conversationStoreState

    val myPeerID: String
        get() = sessionMesh.unified.myPeerID

    fun getMeshPeerInfo(peerID: String): PeerInfo? = sessionMesh.unified.getPeerInfo(peerID)

    fun peerIdentityForMeshPeer(peerID: String): PeerIdentity = PeerIdentity.mesh(peerID)

    fun peerIdentityForNostrPubkey(pubkeyHex: String): PeerIdentity =
        geohashSession.peerIdentityForNostrPubkey(pubkeyHex)

    fun isFavorite(peerID: String): Boolean = contactFavorites.isFavorite(peerID)

    fun isPeerVerified(peerID: String, verifiedFingerprints: Set<String>): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = verificationHandler.getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && verifiedFingerprints.contains(fingerprint)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray, verifiedFingerprints: Set<String>): Boolean =
        verifiedFingerprints.contains(verificationHandler.fingerprintFromNoiseBytes(noisePublicKey))

    /** The list's non-snapshot reads, over this ViewModel's collaborators. */
    internal fun peerListLookups(appContext: Context): PeerListLookups =
        RealPeerListLookups(appContext, contactFavorites, verificationHandler, sessionMesh)

    /** Shows the private chat with [peerID] over the list; Back returns to it. */
    fun openPrivateChat(peerID: String) {
        navigator.openPrivateChat(
            ContactDirectory.canonicalConversationId(peerID),
            over = MeshPeerListRoute,
        )
    }

    fun startGeohashDM(pubkeyHex: String) {
        geohashSession.startGeohashDM(pubkeyHex, ::openPrivateChat)
    }

    fun switchToChannel(channel: String?) = channelManager.switchToChannel(channel)

    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        sessionMesh.unified.sendMessage("left $channel", emptyList(), null)
    }

    internal suspend fun deletePrivateConversation(conversationID: String): DeletedPrivateConversation? =
        conversationDirectory.delete(conversationID)

    internal suspend fun restoreDeletedConversation(deletion: DeletedPrivateConversation): Boolean =
        conversationDirectory.restore(deletion)

    internal suspend fun setConversationRead(conversationID: String, isRead: Boolean): Boolean =
        conversationDirectory.setRead(conversationID, isRead)

    internal fun toggleConversationPinned(conversationID: String) =
        conversationDirectory.togglePinned(conversationID)

    internal fun toggleConversationMuted(conversationID: String) =
        conversationDirectory.toggleMuted(conversationID)
}
