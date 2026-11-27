package com.bitchat.android.nostr.di

import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.LocationNotesInitializer
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrEventDeduplicator
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrTransport
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What the Nostr module keeps per identity. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface NostrIdentityEntryPoint {
    fun nostrIdentityBridge(): NostrIdentityBridge
    fun nostrTransport(): NostrTransport
    fun nostrEventDeduplicator(): NostrEventDeduplicator
    fun nostrBackgroundRuntime(): NostrBackgroundRuntime
    fun locationChannelManager(): LocationChannelManager
    fun locationNotesManager(): LocationNotesManager
    fun locationNotesInitializer(): LocationNotesInitializer
    fun nearbyNotesController(): NearbyNotesController
    fun geohashBookmarksStore(): GeohashBookmarksStore
    fun geohashConversationRegistry(): GeohashConversationRegistry
}

/**
 * Hands the current identity's objects to the process-wide graph. Unscoped on purpose: each
 * `get()` asks the holder, so a consumer never keeps an identity's object past the identity.
 * `NostrHttpClients`, the relay manager and the location privacy gate stay process-wide.
 */
@Module
@InstallIn(SingletonComponent::class)
object NostrIdentityBindings {
    @Provides
    @CurrentIdentity
    fun nostrIdentityBridge(holder: IdentityHolder): NostrIdentityBridge =
        holder.entryPoint<NostrIdentityEntryPoint>().nostrIdentityBridge()

    @Provides
    @CurrentIdentity
    fun nostrTransport(holder: IdentityHolder): NostrTransport =
        holder.entryPoint<NostrIdentityEntryPoint>().nostrTransport()

    @Provides
    @CurrentIdentity
    fun nostrEventDeduplicator(holder: IdentityHolder): NostrEventDeduplicator =
        holder.entryPoint<NostrIdentityEntryPoint>().nostrEventDeduplicator()

    @Provides
    @CurrentIdentity
    fun nostrBackgroundRuntime(holder: IdentityHolder): NostrBackgroundRuntime =
        holder.entryPoint<NostrIdentityEntryPoint>().nostrBackgroundRuntime()

    @Provides
    @CurrentIdentity
    fun locationChannelManager(holder: IdentityHolder): LocationChannelManager =
        holder.entryPoint<NostrIdentityEntryPoint>().locationChannelManager()

    @Provides
    @CurrentIdentity
    fun locationNotesManager(holder: IdentityHolder): LocationNotesManager =
        holder.entryPoint<NostrIdentityEntryPoint>().locationNotesManager()

    @Provides
    @CurrentIdentity
    fun locationNotesInitializer(holder: IdentityHolder): LocationNotesInitializer =
        holder.entryPoint<NostrIdentityEntryPoint>().locationNotesInitializer()

    @Provides
    @CurrentIdentity
    fun nearbyNotesController(holder: IdentityHolder): NearbyNotesController =
        holder.entryPoint<NostrIdentityEntryPoint>().nearbyNotesController()

    @Provides
    @CurrentIdentity
    fun geohashBookmarksStore(holder: IdentityHolder): GeohashBookmarksStore =
        holder.entryPoint<NostrIdentityEntryPoint>().geohashBookmarksStore()

    @Provides
    @CurrentIdentity
    fun geohashConversationRegistry(holder: IdentityHolder): GeohashConversationRegistry =
        holder.entryPoint<NostrIdentityEntryPoint>().geohashConversationRegistry()
}
