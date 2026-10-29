package com.bitchat.android.testhook

import com.bitchat.android.di.FavoritesInitRetry
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.mesh.TransferProgressManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.meshgraph.MeshGraphService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * What the debug test hook reaches in the graph. The hook is driven by a broadcast receiver with
 * no activity, so it looks these up through an entry point rather than being injected.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TestHookDependencies {
    fun appStateStore(): AppStateStore
    fun favoritesInit(): FavoritesInitRetry
    fun favorites(): FavoritesPersistenceService
    fun liveVoiceManager(): LiveVoiceManager
    fun meshGraphService(): MeshGraphService
    fun meshServiceHolder(): MeshServiceHolder
    fun transferProgressManager(): TransferProgressManager
    fun transportBridgeService(): TransportBridgeService
}
