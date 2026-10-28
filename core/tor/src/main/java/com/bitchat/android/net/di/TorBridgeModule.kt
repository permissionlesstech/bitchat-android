package com.bitchat.android.net.di

import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bridges the Tor singletons into the graph. Each binding returns today's static
 * instance, which is already single, so none is scoped.
 */
@Module
@InstallIn(SingletonComponent::class)
object TorBridgeModule {

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun artiTorManager(): ArtiTorManager = ArtiTorManager.getInstance()

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun okHttpProvider(): OkHttpProvider = OkHttpProvider
}
