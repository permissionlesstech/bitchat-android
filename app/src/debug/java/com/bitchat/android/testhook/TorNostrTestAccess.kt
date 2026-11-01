package com.bitchat.android.testhook

import android.content.Context
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NostrHttpClients
import com.bitchat.android.nostr.NostrRelayManager
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * The app graph's Nostr singletons, for device tests that check each is built once and shared.
 * Debug builds only.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface TorNostrTestAccess {
    fun nostrRelayManager(): NostrRelayManager
    fun locationChannelManager(): LocationChannelManager
    fun locationNotesManager(): LocationNotesManager
    fun liveLocationPrivacyGate(): LiveLocationPrivacyGate
    fun nostrHttpClients(): NostrHttpClients

    companion object {
        fun of(context: Context): TorNostrTestAccess =
            EntryPointAccessors.fromApplication(context, TorNostrTestAccess::class.java)
    }
}
