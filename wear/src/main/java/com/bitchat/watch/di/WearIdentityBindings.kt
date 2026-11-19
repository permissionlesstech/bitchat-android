package com.bitchat.watch.di

import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.watch.mesh.WearMeshHolder
import com.bitchat.watch.ui.WearChatState
import com.bitchat.watch.ui.WearPeerIdentityState
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What the watch keeps per identity. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface WearIdentityEntryPoint {
    fun meshHolder(): WearMeshHolder
    fun chatState(): WearChatState
    fun peerIdentity(): WearPeerIdentityState
}

/**
 * Hands the current identity's objects to the process-wide graph. Unscoped on purpose: each
 * `get()` asks the holder, so a consumer never keeps an identity's object past the identity.
 * `WearMeshService` has no bridge: only [WearMeshHolder] builds it, inside the scope.
 */
@Module
@InstallIn(SingletonComponent::class)
object WearIdentityBindings {
    @Provides
    @CurrentIdentity
    fun meshHolder(holder: IdentityHolder): WearMeshHolder =
        holder.entryPoint<WearIdentityEntryPoint>().meshHolder()

    @Provides
    @CurrentIdentity
    fun chatState(holder: IdentityHolder): WearChatState =
        holder.entryPoint<WearIdentityEntryPoint>().chatState()

    @Provides
    @CurrentIdentity
    fun peerIdentity(holder: IdentityHolder): WearPeerIdentityState =
        holder.entryPoint<WearIdentityEntryPoint>().peerIdentity()
}
