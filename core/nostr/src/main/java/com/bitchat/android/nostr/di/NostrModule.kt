package com.bitchat.android.nostr.di

import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityScoped
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn

/** The Nostr classes of an identity that cannot be built from an `@Inject` constructor. */
@Module
@InstallIn(IdentityComponent::class)
object NostrModule {

    /** Built here so the controller keeps its lambda constructor for tests. */
    @Provides
    @IdentityScoped
    fun nearbyNotesController(manager: LocationNotesManager): NearbyNotesController =
        NearbyNotesController(
            subscribe = manager::setGeohash,
            unsubscribe = manager::stop,
        )
}
