package com.bitchat.android.di

import android.util.Log
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.ui.ChannelManager
import com.bitchat.android.ui.ChatBackUnwinder
import com.bitchat.android.ui.ChatMeshDelegate
import com.bitchat.android.ui.ChatSessionEnder
import com.bitchat.android.ui.ChatSessionHolder
import com.bitchat.android.ui.ChatSessionMesh
import com.bitchat.android.ui.ChatSessionStartup
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.CommandProcessor
import com.bitchat.android.ui.ComposerMedia
import com.bitchat.android.ui.ContactFavorites
import com.bitchat.android.ui.ConversationDirectory
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.GeohashSession
import com.bitchat.android.ui.MediaSendingManager
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.MessageSender
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.ui.PrivateChatManager
import com.bitchat.android.ui.PrivateChatSession
import com.bitchat.android.ui.VerificationHandler
import com.bitchat.android.ui.entryPoint
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.EntryPoint
import dagger.hilt.EntryPoints
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

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
    fun chatState(): ChatState
    fun dataManager(): DataManager
    fun messageManager(): MessageManager
    fun notificationManager(): NotificationManager
    fun secureIdentityStateManager(): SecureIdentityStateManager
    fun channelManager(): ChannelManager
    fun privateChatManager(): PrivateChatManager
    fun commandProcessor(): CommandProcessor
    fun verificationHandler(): VerificationHandler
    fun mediaSendingManager(): MediaSendingManager
    fun geohashSession(): GeohashSession
    fun chatMeshDelegate(): ChatMeshDelegate
    fun messageSender(): MessageSender
    fun conversationDirectory(): ConversationDirectory
    fun contactFavorites(): ContactFavorites
    fun chatSessionStartup(): ChatSessionStartup
    fun chatBackUnwinder(): ChatBackUnwinder
    fun privateChatSession(): PrivateChatSession
    fun composerMedia(): ComposerMedia
    fun chatSessionMesh(): ChatSessionMesh
}

/** What ending a session needs from inside its component. */
@EntryPoint
@InstallIn(ChatSessionComponent::class)
interface ChatSessionLifetimeEntryPoint {
    fun teardown(): ChatSessionTeardown

    @ChatSessionScope
    fun scope(): CoroutineScope
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
    fun chatState(holder: ChatSessionHolder): ChatState =
        holder.entryPoint<ChatSessionEntryPoint>().chatState()

    @Provides
    @CurrentSession
    fun dataManager(holder: ChatSessionHolder): DataManager =
        holder.entryPoint<ChatSessionEntryPoint>().dataManager()

    @Provides
    @CurrentSession
    fun messageManager(holder: ChatSessionHolder): MessageManager =
        holder.entryPoint<ChatSessionEntryPoint>().messageManager()

    @Provides
    @CurrentSession
    fun notificationManager(holder: ChatSessionHolder): NotificationManager =
        holder.entryPoint<ChatSessionEntryPoint>().notificationManager()

    @Provides
    @CurrentSession
    fun secureIdentityStateManager(holder: ChatSessionHolder): SecureIdentityStateManager =
        holder.entryPoint<ChatSessionEntryPoint>().secureIdentityStateManager()

    @Provides
    @CurrentSession
    fun channelManager(holder: ChatSessionHolder): ChannelManager =
        holder.entryPoint<ChatSessionEntryPoint>().channelManager()

    @Provides
    @CurrentSession
    fun privateChatManager(holder: ChatSessionHolder): PrivateChatManager =
        holder.entryPoint<ChatSessionEntryPoint>().privateChatManager()

    @Provides
    @CurrentSession
    fun commandProcessor(holder: ChatSessionHolder): CommandProcessor =
        holder.entryPoint<ChatSessionEntryPoint>().commandProcessor()

    @Provides
    @CurrentSession
    fun verificationHandler(holder: ChatSessionHolder): VerificationHandler =
        holder.entryPoint<ChatSessionEntryPoint>().verificationHandler()

    @Provides
    @CurrentSession
    fun mediaSendingManager(holder: ChatSessionHolder): MediaSendingManager =
        holder.entryPoint<ChatSessionEntryPoint>().mediaSendingManager()

    @Provides
    @CurrentSession
    fun geohashSession(holder: ChatSessionHolder): GeohashSession =
        holder.entryPoint<ChatSessionEntryPoint>().geohashSession()

    @Provides
    @CurrentSession
    fun chatMeshDelegate(holder: ChatSessionHolder): ChatMeshDelegate =
        holder.entryPoint<ChatSessionEntryPoint>().chatMeshDelegate()

    @Provides
    @CurrentSession
    fun messageSender(holder: ChatSessionHolder): MessageSender =
        holder.entryPoint<ChatSessionEntryPoint>().messageSender()

    @Provides
    @CurrentSession
    fun conversationDirectory(holder: ChatSessionHolder): ConversationDirectory =
        holder.entryPoint<ChatSessionEntryPoint>().conversationDirectory()

    @Provides
    @CurrentSession
    fun contactFavorites(holder: ChatSessionHolder): ContactFavorites =
        holder.entryPoint<ChatSessionEntryPoint>().contactFavorites()

    @Provides
    @CurrentSession
    fun chatSessionStartup(holder: ChatSessionHolder): ChatSessionStartup =
        holder.entryPoint<ChatSessionEntryPoint>().chatSessionStartup()

    @Provides
    @CurrentSession
    fun chatBackUnwinder(holder: ChatSessionHolder): ChatBackUnwinder =
        holder.entryPoint<ChatSessionEntryPoint>().chatBackUnwinder()

    @Provides
    @CurrentSession
    fun privateChatSession(holder: ChatSessionHolder): PrivateChatSession =
        holder.entryPoint<ChatSessionEntryPoint>().privateChatSession()

    @Provides
    @CurrentSession
    fun composerMedia(holder: ChatSessionHolder): ComposerMedia =
        holder.entryPoint<ChatSessionEntryPoint>().composerMedia()

    @Provides
    @CurrentSession
    fun chatSessionMesh(holder: ChatSessionHolder): ChatSessionMesh =
        holder.entryPoint<ChatSessionEntryPoint>().chatSessionMesh()
}

@Module
@InstallIn(ActivityRetainedComponent::class)
abstract class ChatSessionEnderModule {
    @Binds
    abstract fun bindChatSessionEnder(ender: DefaultChatSessionEnder): ChatSessionEnder
}

/**
 * Runs the session's cleanups, then cancels its coroutine scope. It never throws: the holder calls
 * it while retiring a session, and one failing step must not stop the rest.
 */
class DefaultChatSessionEnder @Inject constructor() : ChatSessionEnder {
    override fun end(component: ChatSessionComponent) {
        val lifetime = try {
            EntryPoints.get(component, ChatSessionLifetimeEntryPoint::class.java)
        } catch (e: Throwable) {
            Log.e(TAG, "Could not reach the session's lifetime: ${e.javaClass.simpleName}")
            return
        }
        try {
            val failed = lifetime.teardown().runAll()
            if (failed.isNotEmpty()) Log.e(TAG, "Session cleanups failed: $failed")
        } catch (e: Throwable) {
            Log.e(TAG, "Could not run the session's cleanups: ${e.javaClass.simpleName}")
        }
        try {
            lifetime.scope().cancel()
        } catch (e: Throwable) {
            Log.e(TAG, "Could not cancel the session's scope: ${e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ChatSessionEnder"
    }
}
