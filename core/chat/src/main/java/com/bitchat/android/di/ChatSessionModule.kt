package com.bitchat.android.di

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.identity.scope.IdentityCoroutineScope
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.nostr.NostrBackgroundRuntime
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.nostr.NostrRelayManager
import com.bitchat.android.nostr.PoWPreferenceManager
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.MessageRouterHolder
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.VerificationService
import com.bitchat.android.spam.SpamFilter
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Provider
import javax.inject.Qualifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The coroutine scope of the chat session: the state and managers behind the
 * chat screens, shared by their ViewModels.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ChatSessionScope

/**
 * The session's router, always the one of the identity the session belongs to. Every resolution
 * re-points it at the current mesh, so take it as a `Provider`. A qualifier because Dagger refuses an
 * unqualified request for an `@AssistedInject` type.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SessionRouter

/**
 * The chat session: the state and managers behind the chat screens, one set per
 * [ChatSessionComponent]. Inside the identity, so identity objects are taken directly.
 *
 * It lived inside ChatViewModel once, and it keeps that lifetime: it ends when the retained lifecycle
 * clears, from the Activity's ViewModelStore. A configuration change keeps it, and so does the mesh
 * foreground service, which runs without the UI and does not see any of it.
 */
@Module
@InstallIn(ChatSessionComponent::class)
object ChatSessionModule {

    /** What viewModelScope was: a supervisor on the main thread, cancelled when the session ends. */
    @Provides
    @ChatSessionScoped
    @ChatSessionScope
    fun provideChatSessionScope(): CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Provides
    @SessionRouter
    fun provideSessionRouter(holder: MessageRouterHolder, mesh: Provider<MeshService>): MessageRouter =
        holder.getOrAttach(mesh.get())

    @Provides
    @ChatSessionScoped
    fun provideSecureIdentityStateManager(@ApplicationContext context: Context): SecureIdentityStateManager =
        SecureIdentityStateManager(context)

    @Provides
    @ChatSessionScoped
    fun provideChatState(
        @ChatSessionScope scope: CoroutineScope,
        appStateStore: AppStateStore,
    ): ChatState = ChatState(scope, appStateStore)

    // The managers keep plain constructors, which their unit tests call
    // directly, so they are provided here rather than annotated for injection.

    @Provides
    @ChatSessionScoped
    fun provideDataManager(@ApplicationContext context: Context): DataManager =
        DataManager(context)

    @Provides
    @ChatSessionScoped
    fun provideMessageManager(
        state: ChatState,
        appStateStore: AppStateStore,
        contactDirectory: ContactDirectory,
    ): MessageManager = MessageManager(state, appStateStore, contactDirectory)

    @Provides
    @ChatSessionScoped
    fun provideNotificationManager(
        @ApplicationContext context: Context,
        targets: NotificationTargets,
        contactDirectory: ContactDirectory,
        conversationListPreferences: ConversationListPreferences,
        teardown: ChatSessionTeardown,
    ): NotificationManager = NotificationManager(
        context,
        NotificationManagerCompat.from(context),
        targets,
        contactDirectory,
        conversationListPreferences,
    ).also { manager -> teardown.register("NotificationManager", manager::close) }

    @Provides
    @ChatSessionScoped
    fun provideChannelManager(
        state: ChatState,
        messageManager: MessageManager,
        dataManager: DataManager,
        @ChatSessionScope scope: CoroutineScope,
        locationChannelManager: Provider<LocationChannelManager>,
    ): ChannelManager = ChannelManager(
        state,
        messageManager,
        dataManager,
        scope,
        onSwitchToMeshLocation = { locationChannelManager.get().select(ChannelID.Mesh) }
    )

    @Provides
    @ChatSessionScoped
    fun providePrivateChatManager(
        state: ChatState,
        messageManager: MessageManager,
        dataManager: DataManager,
        mesh: ChatSessionMesh,
        seenMessageStore: Provider<SeenMessageStore>,
        contactDirectory: ContactDirectory,
        appStateStore: AppStateStore,
        favorites: Provider<FavoritesPersistenceService>,
        peerFingerprints: PeerFingerprintManager,
    ): PrivateChatManager = PrivateChatManager(
        state,
        messageManager,
        dataManager,
        mesh,
        contactDirectory,
        appStateStore,
        favorites,
        peerFingerprints,
        hasReadReceiptBeenSent = { messageID ->
            seenMessageStore.get().hasReadReceiptBeenSent(messageID)
        },
        markMessageReadLocally = { messageID ->
            seenMessageStore.get().markReadLocally(messageID)
        }
    )

    @Provides
    @ChatSessionScoped
    fun provideCommandProcessor(
        state: ChatState,
        messageManager: MessageManager,
        channelManager: ChannelManager,
        privateChatManager: PrivateChatManager,
        @ChatSessionScope scope: CoroutineScope,
        geohashSession: Lazy<GeohashSession>,
        messageRouterHolder: Provider<MessageRouterHolder>,
    ): CommandProcessor = CommandProcessor(
        state,
        messageManager,
        channelManager,
        privateChatManager,
        scope,
        routePrivateMessage = { mesh, content, peerID, recipientNickname, messageId ->
            messageRouterHolder.get().getOrAttach(mesh)
                .sendPrivate(content, peerID, recipientNickname, messageId)
        },
        geohashPeople = { geohashSession.get().geohashPeople.value },
    )

    @Provides
    @ChatSessionScoped
    fun provideVerificationHandler(
        @ApplicationContext context: Context,
        @ChatSessionScope scope: CoroutineScope,
        mesh: ChatSessionMesh,
        identityManager: Provider<SecureIdentityStateManager>,
        state: ChatState,
        notificationManager: NotificationManager,
        messageManager: MessageManager,
        favorites: Provider<FavoritesPersistenceService>,
        geohashAliases: GeohashAliasRegistry,
        verificationService: VerificationService,
    ): VerificationHandler = VerificationHandler(
        context = context,
        scope = scope,
        getMeshService = { mesh.unified },
        identityManagerProvider = identityManager,
        state = state,
        notificationManager = notificationManager,
        messageManager = messageManager,
        favorites = favorites,
        geohashAliases = geohashAliases,
        verificationService = verificationService,
    )

    @Provides
    @ChatSessionScoped
    fun provideMediaSendingManager(
        state: ChatState,
        messageManager: MessageManager,
        channelManager: ChannelManager,
        @ChatSessionScope scope: CoroutineScope,
        mesh: ChatSessionMesh,
        contactDirectory: ContactDirectory,
    ): MediaSendingManager = MediaSendingManager(
        state,
        messageManager,
        channelManager,
        contactDirectory,
        scope
    ) { mesh.unified }

    @Provides
    @ChatSessionScoped
    fun provideGeohashSession(
        application: Application,
        state: ChatState,
        messageManager: MessageManager,
        dataManager: DataManager,
        notificationManager: NotificationManager,
        nostrBackgroundRuntime: NostrBackgroundRuntime,
        liveLocationPrivacyGate: LiveLocationPrivacyGate,
        powPreferences: PoWPreferenceManager,
        locationChannelManager: Provider<LocationChannelManager>,
        relayManager: Lazy<NostrRelayManager>,
        locationNotesManager: Provider<LocationNotesManager>,
        geohashAliases: GeohashAliasRegistry,
        geohashConversations: GeohashConversationRegistry,
        nostrIdentity: NostrIdentityBridge,
        spamFilter: SpamFilter,
        teardown: ChatSessionTeardown,
        @IdentityCoroutineScope identityScope: CoroutineScope,
    ): GeohashSession = GeohashSession(
        application = application,
        state = state,
        messageManager = messageManager,
        dataManager = dataManager,
        notificationManager = notificationManager,
        nostrBackgroundRuntime = nostrBackgroundRuntime,
        liveLocationPrivacyGate = liveLocationPrivacyGate,
        powPreferences = powPreferences,
        geohashAliases = geohashAliases,
        spamFilter = spamFilter,
        geohashConversations = geohashConversations,
        nostrIdentity = nostrIdentity,
        locationChannelManagerSource = locationChannelManager,
        relayManagerSource = relayManager,
        locationNotesManagerSource = locationNotesManager,
        identityScope = identityScope,
    ).also { session -> teardown.register("GeohashSession", session::shutdownUiSubscriptions) }

    @Provides
    @ChatSessionScoped
    fun provideMeshDelegateHandler(
        @ApplicationContext context: Context,
        state: ChatState,
        messageManager: MessageManager,
        channelManager: ChannelManager,
        privateChatManager: PrivateChatManager,
        notificationManager: NotificationManager,
        @ChatSessionScope scope: CoroutineScope,
        mesh: ChatSessionMesh,
        seenMessageStore: Provider<SeenMessageStore>,
        appStateStore: AppStateStore,
        contactDirectory: ContactDirectory,
        messageRouterHolder: Provider<MessageRouterHolder>,
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
        peekMessageRouter = { messageRouterHolder.get().peek() },
        appStateStore = appStateStore,
        contactDirectory = contactDirectory,
        markMessageReadLocally = { messageID ->
            seenMessageStore.get().markReadLocally(messageID)
        }
    )
}
