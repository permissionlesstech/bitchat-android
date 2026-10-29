package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.PrivateMessageArrivalOrder
import com.bitchat.android.services.VerificationService
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.ui.debug.DebugPreferenceManager
import dagger.Module
import dagger.Provides
import javax.inject.Inject
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Hands today's static `:core:mesh` instances to the graph, so consumers can depend on the type
 * before each class is flipped to an `@Inject` constructor. Every binding is unscoped on purpose:
 * the accessor it calls already guarantees the single instance, and each bridge is deleted in the
 * commit that flips its class.
 */
@Module
@InstallIn(SingletonComponent::class)
object MeshBridgeModule {

    // 10c-2a-1 bridge: deleted when SpamFilter is flipped
    @Provides
    fun provideSpamFilter(): SpamFilter = SpamFilter.shared

    // 10c-2a-1 bridge: deleted when FavoritesPersistenceService is flipped
    @Provides
    fun provideFavoritesPersistenceService(): FavoritesPersistenceService =
        FavoritesPersistenceService.shared

    // 10c-2a-1 bridge: deleted when ConversationRepository is flipped
    @Provides
    fun provideConversationRepository(@ApplicationContext context: Context): ConversationRepository =
        ConversationRepository.getInstance(context)

    // 10c-2a-1 bridge: deleted when AppStateStore is flipped
    @Provides
    fun provideAppStateStore(): AppStateStore = AppStateStore

    // 10c-2a-1 bridge: deleted when ContactDirectory is flipped
    @Provides
    fun provideContactDirectory(): ContactDirectory = ContactDirectory

    // 10c-2a-1 bridge: deleted when PrivateMessageArrivalOrder is flipped
    @Provides
    fun providePrivateMessageArrivalOrder(): PrivateMessageArrivalOrder = PrivateMessageArrivalOrder

    // 10c-2a-1 bridge: deleted when VoiceWaveformCache is flipped
    @Provides
    fun provideVoiceWaveformCache(): VoiceWaveformCache = VoiceWaveformCache

    // 10c-2a-1 bridge: deleted when SpamFilterPreferences is flipped
    @Provides
    fun provideSpamFilterPreferences(): SpamFilterPreferences = SpamFilterPreferences

    // 10c-2a-1 bridge: deleted when DebugPreferenceManager is flipped
    @Provides
    fun provideDebugPreferenceManager(): DebugPreferenceManager = DebugPreferenceManager

    // 10c-2a-1 bridge: deleted when GeohashAliasRegistry is flipped
    @Provides
    fun provideGeohashAliasRegistry(): GeohashAliasRegistry = GeohashAliasRegistry

    // 10c-2a-1 bridge: deleted when VerificationService is flipped
    @Provides
    fun provideVerificationService(): VerificationService = VerificationService
}

/**
 * `FavoritesPersistenceService.initialize`, for the callers that must run it before the instance
 * exists: Application.onCreate's first attempt and the one retry the chat session's start-up makes.
 */
// 10c-2a-1 bridge: deleted when FavoritesPersistenceService is flipped
class FavoritesInitRetry @Inject constructor(@ApplicationContext private val context: Context) {
    fun initialize() = FavoritesPersistenceService.initialize(context)

    fun run() = initialize()
}
