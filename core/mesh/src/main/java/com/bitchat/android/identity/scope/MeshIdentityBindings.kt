package com.bitchat.android.identity.scope

import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.mesh.TransferProgressManager
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.VerificationService
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.spam.SpamFilter
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
    fun liveVoiceManager(): LiveVoiceManager
    fun peerFingerprintManager(): PeerFingerprintManager
    fun transferProgressManager(): TransferProgressManager
    fun transportBridgeService(): TransportBridgeService
    fun seenMessageStore(): SeenMessageStore
    fun verificationService(): VerificationService
    fun meshGraphService(): MeshGraphService
    fun spamFilter(): SpamFilter
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

    @Provides
    @CurrentIdentity
    fun liveVoiceManager(holder: IdentityHolder): LiveVoiceManager =
        holder.entryPoint<MeshIdentityEntryPoint>().liveVoiceManager()

    @Provides
    @CurrentIdentity
    fun peerFingerprintManager(holder: IdentityHolder): PeerFingerprintManager =
        holder.entryPoint<MeshIdentityEntryPoint>().peerFingerprintManager()

    @Provides
    @CurrentIdentity
    fun transferProgressManager(holder: IdentityHolder): TransferProgressManager =
        holder.entryPoint<MeshIdentityEntryPoint>().transferProgressManager()

    @Provides
    @CurrentIdentity
    fun transportBridgeService(holder: IdentityHolder): TransportBridgeService =
        holder.entryPoint<MeshIdentityEntryPoint>().transportBridgeService()

    @Provides
    @CurrentIdentity
    fun seenMessageStore(holder: IdentityHolder): SeenMessageStore =
        holder.entryPoint<MeshIdentityEntryPoint>().seenMessageStore()

    @Provides
    @CurrentIdentity
    fun verificationService(holder: IdentityHolder): VerificationService =
        holder.entryPoint<MeshIdentityEntryPoint>().verificationService()

    @Provides
    @CurrentIdentity
    fun meshGraphService(holder: IdentityHolder): MeshGraphService =
        holder.entryPoint<MeshIdentityEntryPoint>().meshGraphService()

    @Provides
    @CurrentIdentity
    fun spamFilter(holder: IdentityHolder): SpamFilter =
        holder.entryPoint<MeshIdentityEntryPoint>().spamFilter()
}
