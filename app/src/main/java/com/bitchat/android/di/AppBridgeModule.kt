package com.bitchat.android.di

import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.MeshServicePreferences
import com.bitchat.android.wifiaware.WifiAwareController
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hands today's static `:app` objects to the graph until each is flipped. The mesh services
 * themselves are not bridged here: [MeshModule] already provides them from [MeshServiceHolder].
 */
@Module
@InstallIn(SingletonComponent::class)
object AppBridgeModule {

    // 10c-2a-1 bridge: deleted when WifiAwareController is flipped
    @Provides
    fun provideWifiAwareController(): WifiAwareController = WifiAwareController

    // 10c-2a-1 bridge: deleted when MeshServiceHolder is flipped
    @Provides
    fun provideMeshServiceHolder(): MeshServiceHolder = MeshServiceHolder

    // 10c-2a-1 bridge: deleted when MeshServicePreferences is flipped
    @Provides
    fun provideMeshServicePreferences(): MeshServicePreferences = MeshServicePreferences
}
