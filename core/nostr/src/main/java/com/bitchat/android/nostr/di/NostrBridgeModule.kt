package com.bitchat.android.nostr.di

import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.nostr.NostrEventDeduplicator
import com.bitchat.android.nostr.NostrHttpClients
import com.bitchat.android.nostr.NostrNetwork
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.nostr.RelayDirectory
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

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun relayDirectory(): RelayDirectory = RelayDirectory

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun liveLocationPrivacyGate(): LiveLocationPrivacyGate = LiveLocationPrivacyGate

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun powPreferenceManager(): PoWPreferenceManager = PoWPreferenceManager

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun nostrEventDeduplicator(): NostrEventDeduplicator = NostrEventDeduplicator.getInstance()
}
