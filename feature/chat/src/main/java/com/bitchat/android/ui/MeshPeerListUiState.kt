package com.bitchat.android.ui

import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.ConversationStoreState

/** Everything the peer list shows (inventory A11). */
internal data class MeshPeerListUiState(
    val conversations: ConversationsUi,
    val channels: List<ChannelRowUi>,
    val people: PeopleUi,
    /** Q-P19: the QR verify action is hidden while a location channel is selected. */
    val showVerifyAction: Boolean,
    /** The unfiltered conversation list is not empty; pads the channels header (P11). */
    val hasConversations: Boolean,
    /** Conversations or channels sit above the people card; pads its header (P11). */
    val hasEarlierSections: Boolean,
    /** The conversation search text, kept across rotation and process death. */
    val query: String,
)

/** The people card: mesh peers, or the people in the selected geohash. */
internal sealed interface PeopleUi {
    data class Mesh(
        /** P8: connected peers not shown as conversations, minus self (Q-P2). */
        val count: Int,
        /** No connected peer is left to show; offline favourites may still have rows. */
        val noneConnected: Boolean,
        val rows: List<MeshPersonRowUi>,
    ) : PeopleUi

    data class Geohash(
        /** P30: the people actually listed (Q-P1). */
        val count: Int,
        val people: GeohashPeopleUi,
    ) : PeopleUi
}

/** What the user does on the peer list (inventory A4). */
internal sealed interface MeshPeerListAction {
    /** a3: the search field's text, or "" from its clear button. */
    data class QueryChanged(val text: String) : MeshPeerListAction

    /** a4 */
    data class ConversationClicked(val conversationID: String) : MeshPeerListAction

    /** a5, a7, a8: mark read when it has unread messages, else unread. */
    data class SwipeToggleRead(val conversationID: String, val markRead: Boolean) :
        MeshPeerListAction

    /** a7, a8 */
    data class TogglePinned(val conversationID: String) : MeshPeerListAction

    /** a7, a8 */
    data class ToggleMuted(val conversationID: String) : MeshPeerListAction

    /** a11 */
    data class ChannelClicked(val name: String) : MeshPeerListAction

    /** a12 */
    data class LeaveChannel(val name: String) : MeshPeerListAction

    /** a13, a14: a mesh peer or an offline favourite. */
    data class PersonClicked(val peerID: String) : MeshPeerListAction

    /** a15: someone else in the geohash, by Nostr public key hex. */
    data class GeohashPersonClicked(val pubkeyHex: String) : MeshPeerListAction

    /** a2 */
    data object VerifyClicked : MeshPeerListAction

    /** a1 */
    data object BackClicked : MeshPeerListAction
}

internal sealed interface MeshPeerListEvent {
    /** Push the private chat with this canonical conversation ID over the list. */
    data class OpenPrivateChat(val conversationID: String) : MeshPeerListEvent

    data object ShowVerification : MeshPeerListEvent

    data object Close : MeshPeerListEvent
}

/** The values the list is built from, one per source the screen used to collect. */
internal data class PeerListSnapshot(
    val connectedPeers: List<String>,
    val peerNicknames: Map<String, String>,
    val peerDirect: Map<String, Boolean>,
    val wifiAwareConnected: Map<String, *>,
    val selectedPrivateChatPeer: String?,
    val nickname: String,
    val favoritePeers: Set<String>,
    val peerFavoritedUs: Set<String>,
    val peerFingerprints: Map<String, String>,
    val verifiedFingerprints: Set<String>,
    val unreadPrivateMessages: Set<String>,
    val privateChats: Map<String, List<BitchatMessage>>,
    val conversations: List<ConversationSummary>,
    val conversationStoreState: ConversationStoreState,
    val joinedChannels: Set<String>,
    val currentChannel: String?,
    val unreadChannelMessages: Map<String, Int>,
    val selectedLocationChannel: ChannelID?,
    /** P26 for [selectedLocationChannel]. */
    val myGeohashHex: String?,
    val geohashPeople: List<GeoPerson>,
    val isTeleported: Boolean,
    val teleportedGeo: Set<String>,
    val query: String,
)

/** The whole list from one snapshot (P1-P33). */
internal fun meshPeerListUiState(
    snapshot: PeerListSnapshot,
    myPeerID: () -> String,
    lookups: PeerListLookups,
): MeshPeerListUiState = with(snapshot) {
    val wifiAwarePeerIDs = wifiAwarePeerIDs(wifiAwareConnected)
    val conversationIdentityAliases = conversationIdentityAliases(conversations)
    val people = when (selectedLocationChannel) {
        is ChannelID.Location -> {
            val geohashPeople = buildGeohashPeopleRows(
                geohashPeople = geohashPeople,
                myHex = myGeohashHex,
                nickname = nickname,
                isTeleported = isTeleported,
                teleportedGeo = teleportedGeo,
                unreadPrivateMessages = unreadPrivateMessages,
                excludedIdentityAliases = conversationIdentityAliases,
            )
            PeopleUi.Geohash(count = geohashPeople.count, people = geohashPeople)
        }

        else -> {
            val visibleConnectedPeers =
                visibleConnectedPeers(connectedPeers, conversationIdentityAliases, lookups)
            PeopleUi.Mesh(
                count = meshPeopleCount(visibleConnectedPeers, myPeerID()),
                noneConnected = visibleConnectedPeers.isEmpty(),
                rows = buildMeshPeopleRows(
                    connectedPeers = visibleConnectedPeers,
                    peerNicknames = peerNicknames,
                    peerDirect = peerDirect,
                    wifiAwarePeerIDs = wifiAwarePeerIDs,
                    favoritePeers = favoritePeers,
                    peerFavoritedUs = peerFavoritedUs,
                    peerFingerprints = peerFingerprints,
                    verifiedFingerprints = verifiedFingerprints,
                    unreadPrivateMessages = unreadPrivateMessages,
                    privateChats = privateChats,
                    selectedPrivatePeer = selectedPrivateChatPeer,
                    nickname = nickname,
                    excludedIdentityAliases = conversationIdentityAliases,
                    lookups = lookups,
                ),
            )
        }
    }
    MeshPeerListUiState(
        conversations = buildConversationRows(
            conversations = conversations,
            query = query,
            conversationStoreState = conversationStoreState,
            favoritePeers = favoritePeers,
            peerFavoritedUs = peerFavoritedUs,
            peerFingerprints = peerFingerprints,
            verifiedFingerprints = verifiedFingerprints,
            peerDirect = peerDirect,
            wifiAwarePeerIDs = wifiAwarePeerIDs,
            lookups = lookups,
        ),
        channels = buildChannelRows(joinedChannels, currentChannel, unreadChannelMessages),
        people = people,
        showVerifyAction = selectedLocationChannel !is ChannelID.Location,
        hasConversations = conversations.isNotEmpty(),
        hasEarlierSections = joinedChannels.isNotEmpty() || conversations.isNotEmpty(),
        query = query,
    )
}
