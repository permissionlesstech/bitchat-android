package com.bitchat.android.di

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChannelManager
import com.bitchat.android.ui.ChatSessionMesh
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.ChatViewModelUtils
import com.bitchat.android.ui.CommandProcessor
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.GeohashSession
import com.bitchat.android.ui.MediaSendingManager
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.ui.NotificationTargets
import com.bitchat.android.ui.PrivateChatManager
import com.bitchat.android.ui.VerificationHandler
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityRetainedScoped
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The coroutine scope of the chat session: the state and managers behind the
 * chat screens, shared by their ViewModels.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChatSessionScope

/**
 * The chat session, retained across configuration changes and cleared with
 * the Activity.
 *
 * ActivityRetainedComponent rather than SingletonComponent, because this used
 * to live inside ChatViewModel and that is the lifetime it keeps: the retained
 * component is cleared from the Activity's ViewModelStore, where ChatViewModel
 * lived before it was scoped to its navigation entry. The mesh
 * foreground service runs without the UI and does not see any of it.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
object ChatSessionModule {

    /** What viewModelScope was: a supervisor on the main thread, cancelled when cleared. */
    @Provides
    @ActivityRetainedScoped
    @ChatSessionScope
    fun provideChatSessionScope(lifecycle: ActivityRetainedLifecycle): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        lifecycle.addOnClearedListener { scope.cancel() }
        return scope
    }

    @Provides
    @ActivityRetainedScoped
    fun provideChatState(@ChatSessionScope scope: CoroutineScope): ChatState = ChatState(scope)

    // The managers keep plain constructors, which their unit tests call
    // directly, so they are provided here rather than annotated for injection.

    @Provides
    @ActivityRetainedScoped
    fun provideDataManager(@ApplicationContext context: Context): DataManager =
        DataManager(context)

    @Provides
    @ActivityRetainedScoped
    fun provideMessageManager(state: ChatState): MessageManager = MessageManager(state)

    @Provides
    @ActivityRetainedScoped
    fun provideNotificationManager(
        @ApplicationContext context: Context,
        targets: NotificationTargets,
    ): NotificationManager =
        NotificationManager(context, NotificationManagerCompat.from(context), targets)

    @Provides
    @ActivityRetainedScoped
    fun provideSecureIdentityStateManager(
        @ApplicationContext context: Context
    ): SecureIdentityStateManager = SecureIdentityStateManager(context)

    @Provides
    @ActivityRetainedScoped
    fun provideChannelManager(
        state: ChatState,
        messageManager: MessageManager,
        dataManager: DataManager,
        @ChatSessionScope scope: CoroutineScope,
        locationChannelManager: Lazy<LocationChannelManager>,
    ): ChannelManager = ChannelManager(
        state,
        messageManager,
        dataManager,
        scope,
        onSwitchToMeshLocation = { locationChannelManager.get().select(ChannelID.Mesh) }
    )

    @Provides
    @ActivityRetainedScoped
    fun providePrivateChatManager(
        state: ChatState,
        messageManager: MessageManager,
        dataManager: DataManager,
        mesh: ChatSessionMesh,
        seenMessageStore: Lazy<SeenMessageStore>,
    ): PrivateChatManager = PrivateChatManager(
        state,
        messageManager,
        dataManager,
        mesh,
        hasReadReceiptBeenSent = { messageID ->
            seenMessageStore.get().hasReadReceiptBeenSent(messageID)
        },
        markMessageReadLocally = { messageID ->
            seenMessageStore.get().markReadLocally(messageID)
        }
    )

    @Provides
    @ActivityRetainedScoped
    fun provideCommandProcessor(
        @ApplicationContext context: Context,
        state: ChatState,
        messageManager: MessageManager,
        channelManager: ChannelManager,
        privateChatManager: PrivateChatManager,
        @ChatSessionScope scope: CoroutineScope,
        geohashSession: Lazy<GeohashSession>,
    ): CommandProcessor = CommandProcessor(
        state,
        messageManager,
        channelManager,
        privateChatManager,
        scope,
        routePrivateMessage = { mesh, content, peerID, recipientNickname, messageId ->
            MessageRouter.getInstance(context, mesh)
                .sendPrivate(content, peerID, recipientNickname, messageId)
        },
        geohashPeople = { geohashSession.get().geohashPeople.value },
    )

    @Provides
    @ActivityRetainedScoped
    fun provideVerificationHandler(
        @ApplicationContext context: Context,
        @ChatSessionScope scope: CoroutineScope,
        mesh: ChatSessionMesh,
        identityManager: SecureIdentityStateManager,
        state: ChatState,
        notificationManager: NotificationManager,
        messageManager: MessageManager,
    ): VerificationHandler = VerificationHandler(
        context = context,
        scope = scope,
        getMeshService = { mesh.unified },
        identityManager = identityManager,
        state = state,
        notificationManager = notificationManager,
        messageManager = messageManager
    )

    @Provides
    @ActivityRetainedScoped
    fun provideMediaSendingManager(
        state: ChatState,
        messageManager: MessageManager,
        channelManager: ChannelManager,
        @ChatSessionScope scope: CoroutineScope,
        mesh: ChatSessionMesh,
    ): MediaSendingManager = MediaSendingManager(
        state,
        messageManager,
        channelManager,
        scope
    ) { mesh.unified }

    @Provides
    @ActivityRetainedScoped
    fun provideGeohashSession(
        application: Application,
        state: ChatState,
        messageManager: MessageManager,
        dataManager: DataManager,
        notificationManager: NotificationManager,
    ): GeohashSession = GeohashSession(
        application = application,
        state = state,
        messageManager = messageManager,
        dataManager = dataManager,
        notificationManager = notificationManager
    )

    @Provides
    @ActivityRetainedScoped
    fun provideMeshDelegateHandler(
        @ApplicationContext context: Context,
        state: ChatState,
        messageManager: MessageManager,
        channelManager: ChannelManager,
        privateChatManager: PrivateChatManager,
        notificationManager: NotificationManager,
        @ChatSessionScope scope: CoroutineScope,
        mesh: ChatSessionMesh,
        seenMessageStore: Lazy<SeenMessageStore>,
    ): MeshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = scope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(context) },
        getMyPeerID = { mesh.unified.myPeerID },
        getMeshService = { mesh.unified },
        markMessageReadLocally = { messageID ->
            seenMessageStore.get().markReadLocally(messageID)
        }
    )
}
