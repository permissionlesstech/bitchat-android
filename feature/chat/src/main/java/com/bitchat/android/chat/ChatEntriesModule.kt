package com.bitchat.android.chat

import androidx.activity.compose.BackHandler
import com.bitchat.android.navigation.AboutRoute
import com.bitchat.android.navigation.ChatRoute
import com.bitchat.android.navigation.ChatUserRoute
import com.bitchat.android.navigation.EntryProviderInstaller
import com.bitchat.android.navigation.LocationChannelsRoute
import com.bitchat.android.navigation.LocationNotesRoute
import com.bitchat.android.navigation.MeshPeerListRoute
import com.bitchat.android.navigation.Navigator
import com.bitchat.android.navigation.PrivateChatRoute
import com.bitchat.android.navigation.ScreenTransitions
import com.bitchat.android.navigation.SecurityVerificationRoute
import com.bitchat.android.navigation.SheetSceneStrategy
import com.bitchat.android.navigation.VerificationRoute
import com.bitchat.android.ui.ChatScreen
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.ChatUserSheet
import com.bitchat.android.ui.MeshPeerListScreen
import com.bitchat.android.ui.PrivateChatScreen
import com.bitchat.android.ui.SecurityVerificationSheet
import com.bitchat.android.ui.VerificationScreen
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ActivityRetainedComponent
import dagger.multibindings.IntoSet

/**
 * Chat, the peer list, private chats, verification and the chat user sheet,
 * contributed to the host's navigation graph.
 */
@Module
@InstallIn(ActivityRetainedComponent::class)
object ChatEntriesModule {

    @Provides
    @IntoSet
    fun chatEntries(
        navigator: Navigator,
        chatState: ChatState,
    ): EntryProviderInstaller = {
        entry<ChatRoute>(metadata = ScreenTransitions.rootFade()) {
            ChatScreen(
                onShowAbout = { navigator.goTo(AboutRoute) },
                onShowLocationNotes = { navigator.goTo(LocationNotesRoute) },
                onShowChatUser = { nickname, messageId ->
                    navigator.goTo(ChatUserRoute(nickname, messageId))
                },
                onShowLocationChannels = { navigator.goTo(LocationChannelsRoute) },
                onShowPeerList = { navigator.goTo(MeshPeerListRoute) },
                openPrivateChatID = navigator.backStack
                    .lastOrNull { it is PrivateChatRoute }
                    ?.let { (it as PrivateChatRoute).conversationID },
            )
        }
        entry<MeshPeerListRoute> {
            MeshPeerListScreen(
                onBack = { navigator.popTo(MeshPeerListRoute, inclusive = true) },
                // Pushed over the list, so Back from verification
                // returns to it.
                onShowVerification = {
                    navigator.goTo(
                        VerificationRoute(chatState.selectedPrivateChatPeer.value)
                    )
                },
            )
        }
        entry<PrivateChatRoute> { route ->
            PrivateChatScreen(
                routeConversationID = route.conversationID,
                onShowSecurityVerification = { conversationID ->
                    navigator.goTo(SecurityVerificationRoute(conversationID))
                },
                onBack = { navigator.popTo(route, inclusive = true) },
            )
        }
        entry<SecurityVerificationRoute>(metadata = SheetSceneStrategy.sheet()) { route ->
            SecurityVerificationSheet(
                conversationID = route.conversationID,
                onDismiss = { navigator.popTo(route, inclusive = true) },
            )
        }
        entry<VerificationRoute> { route ->
            // One close for the header button and Back. Popping by
            // key makes it idempotent, so a second press during the
            // exit animation does not pop what lies beneath.
            val close = { navigator.popTo(route, inclusive = true); Unit }
            BackHandler(onBack = close)
            VerificationScreen(
                peerID = route.peerID,
                onClose = close,
            )
        }
        entry<ChatUserRoute>(metadata = SheetSceneStrategy.sheet()) { route ->
            ChatUserSheet(
                onDismiss = { navigator.popTo(route, inclusive = true) },
                targetNickname = route.nickname,
                messageId = route.messageId,
            )
        }
    }
}
