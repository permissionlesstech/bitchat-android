package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import dagger.Lazy

/**
 * The favourites, the app state and the contact directory wired to each other the way the graph
 * wires them (they read each other in a loop), fresh for each test.
 *
 * The favourites stay uninitialised unless a test calls `favorites.initialize()`, so until then
 * every favourite read throws "not initialized" and the directory finds no favourite. The
 * directory is not initialised either: no context and no mesh, as on the watch.
 */
class TestMeshStores(
    context: Context,
    conversationRepository: ConversationRepository? = null,
    identityManagerProvider: (Context) -> SecureIdentityStateManager = { SecureIdentityStateManager(it) },
) {
    val contactDirectory: ContactDirectory = ContactDirectory(Lazy { favorites }, identityManagerProvider)
    val appStateStore: AppStateStore = AppStateStore(
        contactDirectory,
        Lazy { conversationRepository ?: error("this test builds no conversation repository") },
        conversationRepository,
    )
    val favorites: FavoritesPersistenceService = FavoritesPersistenceService(context, appStateStore)

    init {
        // Still one per process: start from an empty receipt order, as AppStateStore.clear() did.
        PrivateMessageArrivalOrder.clear()
    }
}
