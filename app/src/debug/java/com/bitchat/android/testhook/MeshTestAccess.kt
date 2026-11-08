package com.bitchat.android.testhook

import android.content.Context
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.MessageRouterHolder
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.ui.debug.DebugSettingsManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The app graph's shared mesh stores, for device tests that check each is built once and that the
 * mesh and the panic wipe hold the same ones. Debug builds only.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface MeshTestAccess {
    @CurrentIdentity fun appStateStore(): AppStateStore
    @CurrentIdentity fun contactDirectory(): ContactDirectory
    @CurrentIdentity fun transportBridgeService(): TransportBridgeService
    @CurrentIdentity fun favorites(): FavoritesPersistenceService
    @CurrentIdentity fun spamFilter(): SpamFilter
    @CurrentIdentity fun seenMessageStore(): SeenMessageStore
    @CurrentIdentity fun meshServiceHolder(): MeshServiceHolder
    fun debugSettings(): DebugSettingsManager
    fun identityHolder(): IdentityHolder
    @CurrentIdentity fun voiceWaveformCache(): VoiceWaveformCache
    @CurrentIdentity fun messageRouterHolder(): MessageRouterHolder
    @CurrentIdentity fun messageRouter(): MessageRouter

    companion object {
        fun of(context: Context): MeshTestAccess =
            EntryPointAccessors.fromApplication(context, MeshTestAccess::class.java)
    }
}
