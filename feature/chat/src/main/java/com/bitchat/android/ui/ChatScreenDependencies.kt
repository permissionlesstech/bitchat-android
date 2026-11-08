package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NearbyNotesController
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.spam.SpamFilter
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * What the chat composables that are not yet MVI read from the process. Transitional: when
 * chat's screens move to a ViewModel each of these becomes a constructor dependency and this
 * entry point goes away. Each accessor resolves on call, so reading one builds nothing early.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ChatScreenDependencies {
    @CurrentIdentity fun locationChannelManager(): LocationChannelManager
    @CurrentIdentity fun locationNotesManager(): LocationNotesManager
    @CurrentIdentity fun nearbyNotesController(): NearbyNotesController
    fun torManager(): ArtiTorManager
    @CurrentIdentity fun liveVoiceManager(): LiveVoiceManager
    @CurrentIdentity fun contactDirectory(): ContactDirectory
    @CurrentIdentity fun voiceWaveformCache(): VoiceWaveformCache
    @CurrentIdentity fun spamFilter(): SpamFilter
}

internal fun Context.chatScreenDependencies(): ChatScreenDependencies =
    EntryPointAccessors.fromApplication(applicationContext, ChatScreenDependencies::class.java)
