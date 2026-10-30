package com.bitchat.android.nostr.di

import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
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
}
