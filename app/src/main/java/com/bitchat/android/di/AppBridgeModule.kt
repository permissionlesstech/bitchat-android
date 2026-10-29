package com.bitchat.android.di

import com.bitchat.android.service.MeshServicePreferences
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hands today's static `:app` objects to the graph until each is flipped.
 */
@Module
@InstallIn(SingletonComponent::class)
object AppBridgeModule {

    // 10c-2a-1 bridge: deleted when MeshServicePreferences is flipped
    @Provides
    fun provideMeshServicePreferences(): MeshServicePreferences = MeshServicePreferences
}
