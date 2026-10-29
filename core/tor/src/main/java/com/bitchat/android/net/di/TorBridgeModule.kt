package com.bitchat.android.net.di

import com.bitchat.android.net.TorPreferenceManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Hands today's static Tor preference store to the graph until its class is flipped. */
@Module
@InstallIn(SingletonComponent::class)
object TorBridgeModule {

    // 10c-2a-1 bridge: deleted when TorPreferenceManager is flipped
    @Provides
    fun provideTorPreferenceManager(): TorPreferenceManager = TorPreferenceManager
}
