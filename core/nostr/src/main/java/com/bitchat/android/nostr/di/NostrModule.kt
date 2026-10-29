package com.bitchat.android.nostr.di

import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.nostr.NostrIdentityBridge
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The Nostr singletons that cannot be built from an `@Inject` constructor. */
@Module
@InstallIn(SingletonComponent::class)
object NostrModule {

    /** Built here so the controller keeps its lambda constructor for tests. */
    @Provides
    @Singleton
    fun nearbyNotesController(manager: LocationNotesManager): NearbyNotesController =
        NearbyNotesController(
            subscribe = manager::setGeohash,
            unsubscribe = manager::stop,
        )

    // 10c-2a-1 bridge: deleted when NostrIdentityBridge is flipped
    @Provides
    fun provideNostrIdentityBridge(): NostrIdentityBridge = NostrIdentityBridge

    // 10c-2a-1 bridge: deleted when GeohashConversationRegistry is flipped
    @Provides
    fun provideGeohashConversationRegistry(): GeohashConversationRegistry =
        GeohashConversationRegistry
}
