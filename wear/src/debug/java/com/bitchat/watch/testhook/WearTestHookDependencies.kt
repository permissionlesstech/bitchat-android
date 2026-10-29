package com.bitchat.watch.testhook

import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.ui.WearChatState
import com.bitchat.watch.ui.WearPeerIdentityState
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * What the debug test hook reaches in the graph. The hook is driven by a broadcast receiver with
 * no activity, so it looks these up through an entry point rather than being injected.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WearTestHookDependencies {
    fun appStateStore(): AppStateStore
    fun chatState(): WearChatState
    fun peerIdentity(): WearPeerIdentityState
    fun liveVoiceManager(): LiveVoiceManager
    fun voiceWaveformCache(): VoiceWaveformCache
    fun transportBridgeService(): TransportBridgeService
}
