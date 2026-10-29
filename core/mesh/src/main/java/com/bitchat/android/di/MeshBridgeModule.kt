package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.PrivateMessageArrivalOrder
import dagger.Module
import dagger.Provides
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

    // 10c-2a-1 bridge: deleted when ConversationRepository is flipped
    @Provides
    fun provideConversationRepository(@ApplicationContext context: Context): ConversationRepository =
        ConversationRepository.getInstance(context)

    // 10c-2a-1 bridge: deleted when PrivateMessageArrivalOrder is flipped
    @Provides
    fun providePrivateMessageArrivalOrder(): PrivateMessageArrivalOrder = PrivateMessageArrivalOrder

    // 10c-2a-1 bridge: deleted when GeohashAliasRegistry is flipped
    @Provides
    fun provideGeohashAliasRegistry(): GeohashAliasRegistry = GeohashAliasRegistry
}
