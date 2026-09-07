package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Process-owned Nostr event processing.
 *
 * Relay subscriptions must remain useful when no Activity exists, so their handlers cannot be
 * borrowed from a ViewModel. This processor owns only application-scoped collaborators and writes
 * messages to [AppStateStore], which the next UI instance hydrates from.
 */
internal class NostrBackgroundEventProcessor(
    application: Application,
    parentScope: CoroutineScope
) {
    private val scope = CoroutineScope(
        parentScope.coroutineContext + Dispatchers.IO.limitedParallelism(1)
    )
    private val state = ChatState(scope)
    private val dataManager = DataManager(application.applicationContext).apply {
        state.setNickname(loadNickname())
        loadBlockedUsers()
        loadGeohashBlockedUsers()
    }
    private val geohashRepository = GeohashRepository(application, state, dataManager)
    private val geohashMessageHandler = GeohashMessageHandler(
        application = application,
        repo = geohashRepository,
        scope = scope,
        dataManager = dataManager,
        addChannelMessage = AppStateStore::addChannelMessage
    )
    private val directMessageHandler = NostrDirectMessageHandler(application, scope,
        displayName = geohashRepository::displayNameForNostrPubkeyUI)

    suspend fun processAccountDm(event: NostrEvent, identity: NostrIdentity, token: Long?): Boolean =
        directMessageHandler.process(event, "", identity, token)

    fun onAccountDm(event: NostrEvent, identity: NostrIdentity) {
        refreshBlockLists()
        directMessageHandler.onGiftWrap(event, "", identity)
    }

    fun onGeohashMessage(event: NostrEvent, geohash: String) {
        refreshBlockLists()
        geohashMessageHandler.onEvent(event, geohash)
    }

    fun onGeohashDm(event: NostrEvent, geohash: String, identity: NostrIdentity) {
        refreshBlockLists()
        directMessageHandler.onGiftWrap(event, geohash, identity)
    }

    fun conversationGeohash(conversationKey: String): String? =
        geohashRepository.getConversationGeohash(conversationKey)
            ?: GeohashConversationRegistry.get(conversationKey)

    fun displayNameForNostrPubkey(pubkeyHex: String): String =
        geohashRepository.displayNameForNostrPubkeyUI(pubkeyHex)

    fun displayNameForGeohashConversation(pubkeyHex: String, sourceGeohash: String): String =
        geohashRepository.displayNameForGeohashConversation(pubkeyHex, sourceGeohash)

    private fun refreshBlockLists() {
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()
    }
}
