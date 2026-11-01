package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.geohash.LocationChannelManager
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
    fun locationChannelManager(): LocationChannelManager
    fun locationNotesManager(): LocationNotesManager
    fun nearbyNotesController(): NearbyNotesController
    fun torManager(): ArtiTorManager
    fun liveVoiceManager(): LiveVoiceManager
    fun contactDirectory(): ContactDirectory
    fun voiceWaveformCache(): VoiceWaveformCache
    fun spamFilter(): SpamFilter
}

internal fun Context.chatScreenDependencies(): ChatScreenDependencies =
    EntryPointAccessors.fromApplication(applicationContext, ChatScreenDependencies::class.java)
