package com.bitchat.android.nostr.di

import android.content.Context
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesInitializer
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrClient
import com.bitchat.android.nostr.NostrEventDeduplicator
import com.bitchat.android.nostr.NostrHttpClients
import com.bitchat.android.nostr.NostrNetwork
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.nostr.NostrTransport
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.nostr.RelayDirectory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Bridges the Nostr and geohash singletons into the graph. Each binding returns
 * today's static instance, which is already single, so only the three that were
 * scoped before the move stay @Singleton.
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

    // 10c-1 bridge: deleted in Task 3
    @Provides
    fun nostrNetwork(): NostrNetwork = NostrNetwork

    // 10c-1 bridge: deleted in Task 3
    @Provides
    fun nostrBackgroundRuntime(): NostrBackgroundRuntime = NostrBackgroundRuntime

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun locationNotesInitializer(): LocationNotesInitializer = LocationNotesInitializer

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

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun nostrRelayManager(
        @ApplicationContext context: Context
    ): NostrRelayManager = NostrRelayManager.getInstance(context)

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun nostrClient(
        @ApplicationContext context: Context
    ): NostrClient = NostrClient.getInstance(context)

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun locationNotesManager(): LocationNotesManager = LocationNotesManager.getInstance()

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    fun nearbyNotesController(): NearbyNotesController = NearbyNotesController.shared

    /**
     * Inject it as dagger.Lazy. Resolving it eagerly would construct the transport
     * during ViewModel creation, which is earlier than the current call sites do it.
     */
    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    @Singleton
    fun provideNostrTransport(
        @ApplicationContext context: Context
    ): NostrTransport = NostrTransport.getInstance(context)

    /**
     * Both hold location state and are process-wide today. Injecting them as
     * dagger.Lazy at the call site preserves the current behaviour of not touching
     * them until something actually needs a channel or a bookmark.
     */
    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    @Singleton
    fun provideLocationChannelManager(
        @ApplicationContext context: Context
    ): LocationChannelManager = LocationChannelManager.getInstance(context)

    // 10c-1 bridge: deleted in Task 4/5
    @Provides
    @Singleton
    fun provideGeohashBookmarksStore(
        @ApplicationContext context: Context
    ): GeohashBookmarksStore = GeohashBookmarksStore.getInstance(context)
}
