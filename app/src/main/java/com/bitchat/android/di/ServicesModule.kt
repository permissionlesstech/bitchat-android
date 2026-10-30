package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Bridges [MessageRouter], which is deliberately left static until 10c-2a-2, into the graph.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServicesModule {

    /**
     * Deliberately unscoped, and meant to be injected as `Provider<MessageRouter>`.
     *
     * MessageRouter keeps one instance for the process but re-points it at the
     * current mesh on every getInstance call — the accessor both returns the
     * router and refreshes `mesh` and the sender peer ID. Caching the binding
     * would not hand out a stale object; it would skip that refresh, so the
     * router would keep pointing at the mesh service that panic-clear replaced.
     *
     * Unscoped keeps the refresh on every resolution. It works because
     * [MeshService] is itself unscoped and re-reads MeshServiceHolder, which
     * recreateMeshServiceAfterPanic() clears and repopulates.
     *
     * The deeper fix is for the mesh service to keep a stable identity and swap
     * its internals on panic instead of being replaced. Then this and the
     * repeated `delegate` reassignments all become unnecessary. That change
     * reaches into the mesh layer, which is shared with :wear.
     */
    @Provides
    fun provideMessageRouter(
        @ApplicationContext context: Context,
        mesh: MeshService,
        nostrIdentity: NostrIdentityBridge,
        favorites: FavoritesPersistenceService,
        contactDirectory: ContactDirectory,
        geohashAliases: GeohashAliasRegistry,
        geohashConversations: GeohashConversationRegistry,
        nostrTransport: Lazy<NostrTransport>,
    ): MessageRouter = MessageRouter.getInstance(
        context, mesh, nostrIdentity, favorites, contactDirectory, geohashAliases, geohashConversations
    ) { nostrTransport.get() }
}
