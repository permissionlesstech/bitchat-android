package com.bitchat.android.identity.scope

import com.bitchat.android.features.voice.VoiceWaveformCache
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** What the mesh module keeps per identity. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface MeshIdentityEntryPoint {
    fun voiceWaveformCache(): VoiceWaveformCache
}

/**
 * Hands the current identity's objects to the process-wide graph. Unscoped on purpose: each
 * `get()` asks the holder, so a consumer never keeps an identity's object past the identity.
 */
@Module
@InstallIn(SingletonComponent::class)
object MeshIdentityBindings {
    @Provides
    @CurrentIdentity
    fun voiceWaveformCache(holder: IdentityHolder): VoiceWaveformCache =
        holder.entryPoint<MeshIdentityEntryPoint>().voiceWaveformCache()
}
