package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.GeohashMessageHandler
import com.bitchat.android.nostr.GeohashParticipantStore
import com.bitchat.android.nostr.GeohashRepository
import com.bitchat.android.nostr.NostrBackgroundEvents
import com.bitchat.android.nostr.NostrDirectMessageHandler
import com.bitchat.android.nostr.NostrEvent
import com.bitchat.android.nostr.NostrIdentity
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.spam.SpamFilter
import dagger.Lazy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Process-owned Nostr event processing.
 *
 * Relay subscriptions must remain useful when no Activity exists, so their handlers cannot be
 * borrowed from a ViewModel. This processor owns only application-scoped collaborators and writes
 * messages to [AppStateStore], which the next UI instance hydrates from.
 */
class NostrBackgroundEventProcessor(
    application: Application,
    parentScope: CoroutineScope,
    participantStore: GeohashParticipantStore,
    powPreferences: PoWPreferenceManager,
    nostrTransport: () -> NostrTransport,
    private val appStateStore: AppStateStore,
    private val contactDirectory: ContactDirectory,
    private val favorites: Lazy<FavoritesPersistenceService>,
    private val peerFingerprints: PeerFingerprintManager,
    private val geohashAliases: GeohashAliasRegistry,
    private val geohashConversations: GeohashConversationRegistry,
    private val nostrIdentity: NostrIdentityBridge,
    private val spamFilter: SpamFilter,
    private val seenMessageStore: Lazy<SeenMessageStore>,
) : NostrBackgroundEvents {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + Dispatchers.IO.limitedParallelism(1)
    )
    private val state = ChatState(scope, appStateStore)
    private val dataManager = DataManager(application.applicationContext).apply {
        state.setNickname(loadNickname())
        loadBlockedUsers()
        loadGeohashBlockedUsers()
    }
    private val messageManager = MessageManager(state, appStateStore, contactDirectory)
    private val geohashRepository = GeohashRepository(application, dataManager, scope, nostrIdentity, participantStore) {
        state.getNicknameValue()
    }
    private val privateChatManager = PrivateChatManager(
        state = state,
        messageManager = messageManager,
        dataManager = dataManager,
        noiseSessionDelegate = object : NoiseSessionDelegate {
            override fun hasEstablishedSession(peerID: String): Boolean = false
            override fun initiateHandshake(peerID: String) = Unit
            override fun getMyPeerID(): String = ""
        },
        contactDirectory = contactDirectory,
        appStateStore = appStateStore,
        favorites = favorites,
        peerFingerprints = peerFingerprints,
        trackUnreadMessages = false
    )
    private val geohashMessageHandler = GeohashMessageHandler(
        application = application,
        repo = geohashRepository,
        scope = scope,
        dataManager = dataManager,
        addChannelMessage = appStateStore::addChannelMessage,
        powPreferences = powPreferences,
        geohashAliases = geohashAliases,
        nostrIdentity = nostrIdentity,
        spamFilter = spamFilter,
    )
    private val directMessageHandler = NostrDirectMessageHandler(
        application = application,
        inbox = ChatPrivateMessageInbox(state, privateChatManager),
        updateDeliveryStatus = ::updateDeliveryStatus,
        scope = scope,
        repo = geohashRepository,
        dataManager = dataManager,
        nostrTransportProvider = nostrTransport,
        seenStoreProvider = { seenMessageStore.get() },
        geohashAliases = geohashAliases,
        geohashConversations = geohashConversations,
        contactDirectory = contactDirectory,
        favorites = favorites,
    )

    init {
        // Keep the headless state aligned with messages sent or received through other transports.
        // This preserves duplicate detection and focused-conversation behavior without retaining UI.
        scope.launch {
            appStateStore.privateMessages.collect(state::setPrivateChats)
        }
        scope.launch {
            appStateStore.nickname.collect(state::setNickname)
        }
        scope.launch {
            appStateStore.selectedPrivateChatPeer.collect(state::setSelectedPrivateChatPeer)
        }
    }

    override fun onAccountDm(event: NostrEvent, identity: NostrIdentity) {
        refreshBlockLists()
        directMessageHandler.onGiftWrap(event, "", identity)
    }

    override fun onGeohashMessage(event: NostrEvent, geohash: String) {
        refreshBlockLists()
        geohashMessageHandler.onEvent(event, geohash)
    }

    override fun onGeohashDm(event: NostrEvent, geohash: String, identity: NostrIdentity) {
        refreshBlockLists()
        directMessageHandler.onGiftWrap(event, geohash, identity)
    }

    override fun conversationGeohash(conversationKey: String): String? =
        geohashRepository.getConversationGeohash(conversationKey)
            ?: geohashConversations.get(conversationKey)

    override fun displayNameForNostrPubkey(pubkeyHex: String): String =
        geohashRepository.displayNameForNostrPubkeyUI(pubkeyHex)

    override fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String =
        geohashRepository.displayNameForGeohashConversation(pubkeyHex, sourceGeohash)

    private fun updateDeliveryStatus(messageId: String, status: DeliveryStatus) {
        messageManager.updateMessageDeliveryStatus(messageId, status)
        // The headless state may not yet contain a just-sent UI message. Update the process store
        // unconditionally so a delivery/read receipt can never be lost during Activity handoff.
        appStateStore.updatePrivateMessageStatus(messageId, status)
    }

    private fun refreshBlockLists() {
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()
    }
}
