package com.bitchat.android.di

import android.content.Context
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.ui.ChatSessionEnder
import com.bitchat.android.ui.ChatSessionHolder
import com.bitchat.android.ui.entryPoint
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/** How [ChatSessionHolder] reaches the session builder: only from inside an identity. */
@EntryPoint
@InstallIn(IdentityComponent::class)
interface ChatSessionBuilderEntryPoint {
    fun chatSessionBuilder(): ChatSessionComponentBuilder
}

/** What the chat session keeps. */
@EntryPoint
@InstallIn(ChatSessionComponent::class)
interface ChatSessionEntryPoint {
    fun secureIdentityStateManager(): SecureIdentityStateManager
}

@Module
@InstallIn(ChatSessionComponent::class)
object ChatSessionScopeModule {
    @Provides
    @ChatSessionScoped
    fun provideSecureIdentityStateManager(
        @ApplicationContext context: Context,
    ): SecureIdentityStateManager = SecureIdentityStateManager(context)
}

/**
 * Hands the current session's objects to the retained graph. Unscoped on purpose: each `get()` asks
 * the holder, so a consumer never keeps a session's object past the session.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
object ChatSessionBindings {
    @Provides
    @CurrentSession
    fun secureIdentityStateManager(holder: ChatSessionHolder): SecureIdentityStateManager =
        holder.entryPoint<ChatSessionEntryPoint>().secureIdentityStateManager()
}

@Module
@InstallIn(ActivityRetainedComponent::class)
abstract class ChatSessionEnderModule {
    @Binds
    abstract fun bindChatSessionEnder(ender: DefaultChatSessionEnder): ChatSessionEnder
}

/** Nothing in the session owns a job or a listener yet, so ending one has nothing to stop. */
class DefaultChatSessionEnder @Inject constructor() : ChatSessionEnder {
    override fun end(component: ChatSessionComponent) = Unit
}
