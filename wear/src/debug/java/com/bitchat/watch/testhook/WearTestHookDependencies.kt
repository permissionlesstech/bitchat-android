package com.bitchat.watch.testhook

import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.identity.scope.CurrentIdentity
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshHolder
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
    @CurrentIdentity fun appStateStore(): AppStateStore
    @CurrentIdentity fun chatState(): WearChatState
    @CurrentIdentity fun peerIdentity(): WearPeerIdentityState
    @CurrentIdentity fun liveVoiceManager(): LiveVoiceManager
    @CurrentIdentity fun voiceWaveformCache(): VoiceWaveformCache
    @CurrentIdentity fun transportBridgeService(): TransportBridgeService
    @CurrentIdentity fun meshHolder(): WearMeshHolder
}
