package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.SeenMessageStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges process-wide service singletons into the graph.
 *
 * These stay `getInstance(context)` singletons rather than becoming
 * `@Inject constructor` classes, because they are compiled into `:wear` through
 * the shared-source sync and `:wear` has no Hilt or KSP. Providing them here
 * still lets injectable code depend on the type instead of reaching for the
 * static accessor, which is what makes that code testable.
 *
 * @Singleton matches what the accessor already guarantees; it does not create a
 * second instance.
 */
@Module
@InstallIn(SingletonComponent::class)
object ServicesModule {

    @Provides
    @Singleton
    fun provideSeenMessageStore(
        @ApplicationContext context: Context
    ): SeenMessageStore = SeenMessageStore.getInstance(context)

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
        mesh: MeshService
    ): MessageRouter = MessageRouter.getInstance(context, mesh)
}
