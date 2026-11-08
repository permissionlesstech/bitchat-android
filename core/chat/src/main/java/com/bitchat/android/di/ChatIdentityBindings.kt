package com.bitchat.android.di

import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.MessageRouterHolder
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider

/** What the chat module keeps per identity. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface ChatIdentityEntryPoint {
    fun routerHolder(): MessageRouterHolder
    fun conversationListPreferences(): ConversationListPreferences
}

/**
 * Hands the current identity's objects to the process-wide graph. Unscoped on purpose: each
 * `get()` asks the holder, so a consumer never keeps an identity's object past the identity.
 */
@Module
@InstallIn(SingletonComponent::class)
object ChatIdentityBindings {
    @Provides
    @CurrentIdentity
    fun messageRouterHolder(holder: IdentityHolder): MessageRouterHolder =
        holder.entryPoint<ChatIdentityEntryPoint>().routerHolder()

    /**
     * Every resolution re-points the router at the current mesh, so take it as a `Provider` and
     * ask each time: a cached router would keep pointing at the mesh a panic clear replaced.
     * [mesh] asks the mesh holder, so this can create a mesh, as it always has. Code that must not
     * create one uses [MessageRouterHolder.peek].
     */
    @Provides
    @CurrentIdentity
    fun messageRouter(holder: IdentityHolder, @CurrentIdentity mesh: Provider<MeshService>): MessageRouter =
        holder.entryPoint<ChatIdentityEntryPoint>().routerHolder().getOrAttach(mesh.get())

    @Provides
    @CurrentIdentity
    fun conversationListPreferences(holder: IdentityHolder): ConversationListPreferences =
        holder.entryPoint<ChatIdentityEntryPoint>().conversationListPreferences()
}
