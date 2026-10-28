package com.bitchat.android.nostr.di

import com.bitchat.android.nostr.NostrHttpClients
import com.bitchat.android.nostr.NostrNetwork
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Bridges the Nostr singletons still built statically into the graph. Each binding
 * returns today's static instance, which is already single.
 *
 * [NostrHttpClients] stays fail-closed: resolving it before the app installs one
 * throws, exactly as asking [NostrNetwork] does.
 */
@Module
@InstallIn(SingletonComponent::class)
object NostrBridgeModule {

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun nostrHttpClients(): NostrHttpClients = NostrNetwork.clients()

    // 10c-1 bridge: deleted in Task 5
    @Provides
    fun nostrNetwork(): NostrNetwork = NostrNetwork
}
