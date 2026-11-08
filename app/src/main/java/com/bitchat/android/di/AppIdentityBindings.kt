package com.bitchat.android.di

import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.MeshTransportToggles
import com.bitchat.android.ui.SessionMeshSource
import com.bitchat.android.ui.WifiAwarePeers
import com.bitchat.android.wifiaware.WifiAwareController
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Provider
import kotlinx.coroutines.flow.StateFlow

/** What the phone keeps per identity. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface AppIdentityEntryPoint {
    fun meshServiceHolder(): MeshServiceHolder
    fun wifiAwareController(): WifiAwareController
    fun meshTransportToggles(): MeshTransportToggles
    fun meshService(): MeshService
    fun sessionMeshSource(): SessionMeshSource
}

/**
 * Hands the current identity's objects to the process-wide graph. Unscoped on purpose: each
 * `get()` asks the holder, so a consumer never keeps an identity's object past the identity.
 * [meshService] asks the mesh holder too, so it can create or replace a mesh, as it always has.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppIdentityBindings {
    @Provides
    @CurrentIdentity
    fun meshServiceHolder(holder: IdentityHolder): MeshServiceHolder =
        holder.entryPoint<AppIdentityEntryPoint>().meshServiceHolder()

    @Provides
    @CurrentIdentity
    fun wifiAwareController(holder: IdentityHolder): WifiAwareController =
        holder.entryPoint<AppIdentityEntryPoint>().wifiAwareController()

    @Provides
    @CurrentIdentity
    fun meshTransportToggles(holder: IdentityHolder): MeshTransportToggles =
        holder.entryPoint<AppIdentityEntryPoint>().meshTransportToggles()

    @Provides
    @CurrentIdentity
    fun meshService(holder: IdentityHolder): MeshService =
        holder.entryPoint<AppIdentityEntryPoint>().meshService()

    @Provides
    @CurrentIdentity
    fun sessionMeshSource(holder: IdentityHolder): SessionMeshSource =
        holder.entryPoint<AppIdentityEntryPoint>().sessionMeshSource()

    @Provides
    fun wifiAwarePeers(@CurrentIdentity controller: Provider<WifiAwareController>): WifiAwarePeers =
        object : WifiAwarePeers {
            override val connected: StateFlow<Map<String, *>> get() = controller.get().connectedPeers
            override val enabled: StateFlow<Boolean> get() = controller.get().enabled
        }
}
