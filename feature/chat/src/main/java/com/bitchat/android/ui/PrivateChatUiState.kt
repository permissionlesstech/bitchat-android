package com.bitchat.android.ui

import androidx.compose.runtime.Immutable
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ContactDirectory

/** One private conversation as its sheet shows it. */
@Immutable
data class PrivateChatUiState(
    /** The conversation shown: the route's id, canonicalized as it resolves to a contact. */
    val conversationID: String,
    val titleText: String,
    val messages: List<BitchatMessage>,
    val nickname: String,
    /** The Noise session state of the conversation's mesh peer, when it has one. */
    val sessionState: String?,
    val isVerified: Boolean,
    val isFavorite: Boolean,
    val theyFavoritedUs: Boolean,
    val isDirect: Boolean,
    val isWifiAware: Boolean,
    /** A geohash DM, reached only over Nostr. */
    val isNostrPeer: Boolean,
    /** A mutual favourite that is off the mesh but reachable over Nostr. */
    val isNostrReachableFavorite: Boolean,
)

sealed interface PrivateChatAction {
    /** Saves the draft. Suggestions are not updated: this composer renders none. */
    data class ComposerTextChanged(val text: String) : PrivateChatAction
    /** Sends [content]; [PrivateChatEvent.MessageSent] follows once it is accepted. */
    data class Send(val content: String) : PrivateChatAction
    data class SendVoiceNote(val peerID: String?, val channel: String?, val path: String) : PrivateChatAction
    data class SendImageNote(val peerID: String?, val channel: String?, val path: String) : PrivateChatAction
    data class SendFileNote(val peerID: String?, val channel: String?, val path: String) : PrivateChatAction
    data class CancelMediaSend(val messageID: String) : PrivateChatAction
    data object ToggleFavorite : PrivateChatAction
}

sealed interface PrivateChatEvent {
    /** A sent message was accepted: clear the composer and scroll to the bottom. */
    data object MessageSent : PrivateChatEvent
}

/** The session's values a private conversation is derived from. */
internal data class PrivateChatInputs(
    val conversationID: String,
    val privateChats: Map<String, List<BitchatMessage>>,
    val peerNicknames: Map<String, String>,
    val nickname: String,
    val connectedPeers: List<String>,
    val peerDirect: Map<String, Boolean>,
    val peerSessionStates: Map<String, String>,
    val favoritePeers: Set<String>,
    val peerFavoritedUs: Set<String>,
    val peerFingerprints: Map<String, String>,
    val verifiedFingerprints: Set<String>,
    val wifiAwarePeerIDs: Set<String>,
)

/**
 * The lookups a private conversation needs beyond the session's flows. Most
 * are process-wide registries; tests pass fakes.
 */
internal interface PrivateChatResolvers {
    fun contact(conversationID: String): ContactDirectory.ContactResolution
    fun favoriteStatus(conversationID: String): FavoriteRelationship?
    /** The last-resort name, from the fingerprint's identity cache. */
    fun fingerprintDisplayName(conversationID: String): String
    fun geohashOf(conversationID: String): String?
    fun nostrPubkeyOf(conversationID: String): String?
    fun geohashDisplayName(nostrPubkeyHex: String, geohash: String): String
    fun fingerprintFromContactConversationID(conversationID: String): String?
    /** The fingerprint that verification is recorded against. */
    fun verificationFingerprint(conversationID: String): String?
    /** Whether we favourited a conversation with no known fingerprint. */
    fun isFavorite(conversationID: String): Boolean
}

internal fun privateChatUiStateFor(
    inputs: PrivateChatInputs,
    resolvers: PrivateChatResolvers,
): PrivateChatUiState = with(inputs) {
    val peerID = conversationID
    val contact = resolvers.contact(peerID)
    val activeMeshPeerID = contact.meshPeerID
    val isNostrPeer = peerID.startsWith("nostr_") || peerID.startsWith("nostr:")
    val favoriteRelationship = resolvers.favoriteStatus(peerID)
    val isDirect = activeMeshPeerID?.let { peerDirect[it] } == true || peerDirect[peerID] == true
    val isConnected = activeMeshPeerID?.let { it in connectedPeers } == true ||
        peerID in connectedPeers ||
        isDirect

    val displayName = peerNicknames[peerID]
        ?: activeMeshPeerID?.let { peerNicknames[it] }
        ?: contact.displayName
        ?: favoriteRelationship?.peerNickname
            ?.takeIf { it.isNotBlank() && !it.equals("Unknown", ignoreCase = true) }
        ?: resolvers.fingerprintDisplayName(peerID)
    val titleText = if (isNostrPeer) {
        val geohash = resolvers.geohashOf(peerID) ?: "geohash"
        val fullPubkey = resolvers.nostrPubkeyOf(peerID).orEmpty()
        val name = if (fullPubkey.isNotEmpty()) {
            resolvers.geohashDisplayName(fullPubkey, geohash)
        } else {
            peerNicknames[peerID] ?: "Unknown"
        }
        "#$geohash/@$name"
    } else {
        displayName
    }

    val fingerprint = activeMeshPeerID?.let { peerFingerprints[it] }
        ?: peerFingerprints[peerID]
        ?: resolvers.fingerprintFromContactConversationID(peerID)
    val verificationFingerprint =
        if (isNostrPeer) null else resolvers.verificationFingerprint(peerID)

    PrivateChatUiState(
        conversationID = peerID,
        titleText = titleText,
        messages = privateChats[contact.conversationID] ?: privateChats[peerID] ?: emptyList(),
        nickname = nickname,
        sessionState = resolveConversationSessionState(
            conversationID = peerID,
            activeMeshPeerID = activeMeshPeerID,
            peerSessionStates = peerSessionStates,
        ),
        isVerified = verificationFingerprint != null &&
            verificationFingerprint in verifiedFingerprints,
        isFavorite = if (fingerprint != null) {
            fingerprint in favoritePeers
        } else {
            resolvers.isFavorite(peerID)
        },
        theyFavoritedUs = (fingerprint != null && fingerprint in peerFavoritedUs) ||
            favoriteRelationship?.theyFavoritedUs == true,
        isDirect = isDirect,
        isWifiAware = activeMeshPeerID in wifiAwarePeerIDs || peerID in wifiAwarePeerIDs,
        isNostrPeer = isNostrPeer,
        isNostrReachableFavorite = !isConnected &&
            favoriteRelationship?.isMutual == true &&
            favoriteRelationship.peerNostrPublicKey != null,
    )
}
