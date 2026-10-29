package com.bitchat.watch.di

import android.content.Context
import com.bitchat.watch.notification.WearNotificationCoordinator
import com.bitchat.watch.ui.WearChatState
import com.bitchat.watch.ui.WearPeerIdentityState
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent

/**
 * Hands today's static watch instances to the graph until each class is flipped. `WearMeshService`
 * is not bridged: it is created only after permissions are confirmed, so it gets its own holder.
 */
@Module
@InstallIn(SingletonComponent::class)
object WearBridgeModule {

    // 10c-2a-1 bridge: deleted when WearChatState is flipped
    @Provides
    fun provideWearChatState(): WearChatState = WearChatState

    // 10c-2a-1 bridge: deleted when WearPeerIdentityState is flipped
    @Provides
    fun provideWearPeerIdentityState(): WearPeerIdentityState = WearPeerIdentityState

    // 10c-2a-1 bridge: deleted when WearNotificationCoordinator is flipped
    @Provides
    fun provideWearNotificationCoordinator(
        @ApplicationContext context: Context
    ): WearNotificationCoordinator = WearNotificationCoordinator.getInstance(context)
}
