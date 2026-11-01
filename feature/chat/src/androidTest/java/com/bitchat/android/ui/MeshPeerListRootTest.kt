package com.bitchat.android.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.favorites.FavoriteRelationship
import com.bitchat.android.feature.chat.R
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.ConversationStoreState
import com.bitchat.android.services.DeletedPrivateConversation
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test

/**
 * The Root over a ViewModel with in-memory ports: the delete confirmation and its Undo
 * snackbar. Synthetic conversations only; no mesh, identity or Nostr is involved.
 */
class MeshPeerListRootTest {
    @get:Rule val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val conversations = MutableStateFlow(
        listOf(conversation(BOB_ID, "bob"), conversation(CAROL_ID, "carol"))
    )

    @Test
    fun deleteFromMenu_offersUndoInASnackbar() {
        val viewModel = viewModel()
        compose.setContent {
            BitchatTheme {
                MeshPeerListRoot(
                    onBack = {},
                    onShowVerification = {},
                    onOpenPrivateChat = {},
                    viewModel = viewModel,
                )
            }
        }

        compose.onAllNodesWithContentDescription(context.getString(R.string.conversation_actions))
            .onFirst()
            .performClick()
        compose.onNode(hasText(context.getString(R.string.delete)) and hasAnyAncestor(isPopup()))
            .performClick()
        compose.onNode(hasText(context.getString(R.string.delete)) and hasAnyAncestor(isDialog()))
            .performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(context.getString(R.string.conversation_deleted, "bob")))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(context.getString(R.string.undo))).assertExists()
    }

    @Test
    fun deleteBySwipe_offersUndoInASnackbar() {
        val viewModel = viewModel()
        compose.setContent {
            BitchatTheme {
                MeshPeerListRoot(
                    onBack = {},
                    onShowVerification = {},
                    onOpenPrivateChat = {},
                    viewModel = viewModel,
                )
            }
        }

        val y = compose.onNode(hasText("bob")).getBoundsInRoot().let { (it.top + it.bottom) / 2 }
        compose.onRoot().performTouchInput {
            swipe(Offset(width * 0.9f, y.toPx()), Offset(width * 0.1f, y.toPx()), durationMillis = 300)
        }
        compose.onNode(hasText(context.getString(R.string.delete)) and hasAnyAncestor(isDialog()))
            .performClick()

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodes(hasText(context.getString(R.string.conversation_deleted, "bob")))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText(context.getString(R.string.undo))).assertExists()
    }

    private fun viewModel(): MeshPeerListViewModel {
        fun <T> flow(value: T) = MutableStateFlow(value)
        val sources = PeerListSources(
            connectedPeers = flow(emptyList()),
            peerNicknames = flow(emptyMap()),
            peerDirect = flow(emptyMap()),
            wifiAwareConnected = flow(emptyMap<String, Any>()),
            selectedPrivateChatPeer = flow(null),
            nickname = flow("alice"),
            favoritePeers = flow(emptySet()),
            peerFavoritedUs = flow(emptySet()),
            peerFingerprints = flow(emptyMap()),
            verifiedFingerprints = flow(emptySet()),
            unreadPrivateMessages = flow(emptySet()),
            privateChats = flow(emptyMap<String, List<BitchatMessage>>()),
            conversations = conversations,
            conversationStoreState = flow<ConversationStoreState>(ConversationStoreState.Ready),
            joinedChannels = flow(emptySet()),
            currentChannel = flow(null),
            unreadChannelMessages = flow(emptyMap()),
            selectedLocationChannel = flow<ChannelID?>(null),
            geohashPeople = flow(emptyList()),
            isTeleported = flow(false),
            teleportedGeo = flow(emptySet()),
        )
        return MeshPeerListViewModel(
            sources = sources,
            commands = DeletingCommands(conversations),
            lookups = NoLookups,
            savedState = SavedStateHandle(),
            defaultDispatcher = Dispatchers.Default,
            clock = android.os.SystemClock::elapsedRealtime,
        )
    }

    /** Deletes from [conversations] as the directory does; everything else is a no-op. */
    private class DeletingCommands(
        private val conversations: MutableStateFlow<List<ConversationSummary>>,
    ) : PeerListCommands {
        override val myPeerID = ME_ID
        override fun startGeohashDM(pubkeyHex: String, onStartPrivateChat: (String) -> Unit) = Unit
        override fun switchToChannel(channel: String) = Unit
        override fun leaveChannel(channel: String) = Unit
        override fun sendMeshMessage(content: String) = Unit
        override suspend fun deleteConversation(conversationID: String): DeletedPrivateConversation {
            conversations.update { list -> list.filterNot { it.conversationID == conversationID } }
            return DeletedPrivateConversation(
                conversationID = conversationID,
                aliases = setOf(conversationID),
                displayName = null,
                messages = emptyList(),
                readMessageIDs = emptySet(),
                unreadMessageCount = 0,
            )
        }
        override suspend fun restoreConversation(deletion: DeletedPrivateConversation) = true
        override suspend fun setConversationRead(conversationID: String, isRead: Boolean) = true
        override fun toggleConversationPinned(conversationID: String) = Unit
        override fun toggleConversationMuted(conversationID: String) = Unit
    }

    /** Knows nothing about anyone. */
    private object NoLookups : PeerListLookups {
        override fun aliasesForConversation(peerOrConversationID: String) = setOf(peerOrConversationID)
        override fun canonicalConversationId(peerOrConversationID: String) = peerOrConversationID
        override fun isFavorite(peerID: String) = false
        override fun favoriteStatus(peerID: String): FavoriteRelationship? = null
        override fun ourFavorites(): List<FavoriteRelationship> = emptyList()
        override fun nostrPubkeyForPeerID(peerID: String): String? = null
        override fun nostrPubkeyForNoiseKey(noisePublicKey: ByteArray): String? = null
        override fun peerInfo(peerID: String): PeerInfo? = null
        override fun cachedNoiseKey(peerID: String): String? = null
        override fun peerFingerprintForDisplay(peerID: String): String? = null
        override fun fingerprintFromNoiseBytes(noisePublicKey: ByteArray) = ""
        override fun geohashIdentityPublicKeyHex(geohash: String): String? = null
    }

    private companion object {
        const val ME_ID = "fedcba9876543210"
        const val BOB_ID = "1111111111111111"
        const val CAROL_ID = "2222222222222222"

        fun conversation(id: String, displayName: String) = ConversationSummary(
            conversationID = id,
            displayName = displayName,
            unreadCount = 0,
            latestMessageAt = 0L,
            latestActivityOrder = 0L,
            latestMessageType = BitchatMessageType.Message,
            latestMessagePreview = "synthetic",
            transport = DirectMessageTransport.MESH,
            nostrPubkey = null,
            identityAliases = setOf(id),
        )
    }
}
