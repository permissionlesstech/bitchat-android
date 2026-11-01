package com.bitchat.android.ui

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.screen.BitchatScreenTopBar
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.feature.chat.R
import com.bitchat.android.ui.theme.BitchatTheme
import kotlinx.coroutines.launch

/**
 * The mesh peer list: private conversations, joined channels and nearby people.
 *
 * A full screen. [onBack] returns to chat; [onOpenPrivateChat] pushes the private chat over
 * this screen, so Back from it lands here again. The Root owns the delete confirmation
 * (Q-P13) and the snackbars. Delete and Undo run in the ViewModel, which keeps each deletion
 * until its snackbar is dismissed, so rotating or opening a chat offers Undo again (F2).
 */
@Composable
fun MeshPeerListRoot(
    onBack: () -> Unit,
    onShowVerification: () -> Unit,
    onOpenPrivateChat: (conversationID: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MeshPeerListViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val deleteFailedMessage = stringResource(R.string.conversation_delete_failed)
    val restoreFailedMessage = stringResource(R.string.conversation_restore_failed)
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            is MeshPeerListEvent.OpenPrivateChat -> onOpenPrivateChat(event.conversationID)
            MeshPeerListEvent.ShowVerification -> onShowVerification()
            MeshPeerListEvent.Close -> onBack()
            MeshPeerListEvent.DeleteFailed -> sheetScope.launch {
                snackbarHostState.showSnackbar(message = deleteFailedMessage)
            }
            MeshPeerListEvent.RestoreFailed -> sheetScope.launch {
                snackbarHostState.showSnackbar(restoreFailedMessage)
            }
        }
    }

    // One Undo at a time, oldest first, as the snackbar queue showed them. Shown again if
    // the list returns before it was answered or timed out.
    uiState.pendingUndos.firstOrNull()?.let { undo ->
        val deletedMessage = stringResource(R.string.conversation_deleted, undo.displayName)
        val undoLabel = stringResource(R.string.undo)
        LaunchedEffect(undo.token) {
            val result = snackbarHostState.showSnackbar(
                message = deletedMessage,
                actionLabel = undoLabel,
                withDismissAction = true,
                duration = SnackbarDuration.Long
            )
            viewModel.onAction(
                if (result == SnackbarResult.ActionPerformed) {
                    MeshPeerListAction.UndoDelete(undo.token)
                } else {
                    MeshPeerListAction.UndoDismissed(undo.token)
                }
            )
        }
    }

    var pendingConversationDelete by remember {
        mutableStateOf<ConversationRowUi?>(null)
    }

    MeshPeerListScreen(
        uiState = uiState,
        onAction = viewModel::onAction,
        onDeleteRequested = { pendingConversationDelete = it },
        snackbarHostState = snackbarHostState,
        modifier = modifier,
    )

    pendingConversationDelete?.let { conversation ->
        DeleteConversationDialog(
            displayName = conversation.displayName,
            onDismiss = { pendingConversationDelete = null },
            onConfirm = {
                pendingConversationDelete = null
                viewModel.onAction(
                    MeshPeerListAction.DeleteConfirmed(
                        conversation.conversationID,
                        conversation.displayName
                    )
                )
            },
        )
    }
}

/** The list itself, drawn from [uiState]; every gesture goes to [onAction]. */
@Composable
internal fun MeshPeerListScreen(
    uiState: MeshPeerListUiState,
    onAction: (MeshPeerListAction) -> Unit,
    onDeleteRequested: (ConversationRowUi) -> Unit,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    // Scroll state for animated top bar
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars.union(WindowInsets.displayCutout))
    ) {
        LazyColumn(
            state = listState,
            // The search field sits in the list; consuming the keyboard inset here
            // lets it scroll above the keyboard.
            modifier = Modifier
                .fillMaxSize()
                .imePadding(),
            contentPadding = PaddingValues(top = 72.dp, bottom = 32.dp)
        ) {
            conversationsSection(
                conversations = uiState.conversations,
                query = uiState.query,
                onAction = onAction,
                onDeleteRequested = onDeleteRequested,
            )

            if (uiState.channels.isNotEmpty()) {
                item(key = "channels_section") {
                    ChannelsSection(
                        channels = uiState.channels,
                        hasConversations = uiState.hasConversations,
                        onAction = onAction,
                    )
                }
            }

            // People / geohash participants
            item(key = "people_section") {
                val peopleModifier = Modifier.padding(
                    top = if (uiState.hasEarlierSections) 20.dp else 8.dp
                )
                when (val people = uiState.people) {
                    is PeopleUi.Geohash -> GeohashPeopleSection(
                        people = people.people,
                        onAction = onAction,
                        modifier = peopleModifier,
                    )

                    is PeopleUi.Mesh -> MeshPeopleSection(
                        people = people,
                        onAction = onAction,
                        modifier = peopleModifier,
                    )
                }
            }
        }

        // TopBar (animated)
        BitchatScreenTopBar(
            title = {
                BitchatSheetTitle(text = stringResource(id = R.string.your_network))
            },
            backgroundAlpha = topBarAlpha,
            actions = {
                if (uiState.showVerifyAction) {
                    IconButton(
                        onClick = { onAction(MeshPeerListAction.VerifyClicked) },
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.QrCode,
                            contentDescription = stringResource(R.string.verify_title),
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(HeaderIconSize)
                        )
                    }
                }
            },
            onBack = { onAction(MeshPeerListAction.BackClicked) },
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .imePadding()
                .padding(16.dp)
        )
    }
    }
}

private fun previewPerson(
    peerID: String,
    name: String,
    isMe: Boolean = false,
    isConnected: Boolean = true,
    isFavorite: Boolean = false,
    unreadCount: Int = 0,
) = MeshPersonRowUi(
    key = peerID,
    peerID = peerID,
    displayName = name,
    baseName = name,
    suffix = "",
    showHashSuffix = false,
    isMe = isMe,
    colorKey = PeerIdentity.mesh(peerID).stableKey,
    isDirect = isConnected,
    isWifiAware = false,
    isConnected = isConnected,
    isSelected = false,
    isFavorite = isFavorite,
    theyFavoritedUs = false,
    isVerified = false,
    unreadCount = unreadCount,
    showNostrGlobe = false,
    openPeerID = peerID,
)

private fun previewConversation(
    conversationID: String,
    name: String,
    preview: String,
    isConnected: Boolean,
    unreadCount: Int = 0,
) = ConversationRowUi(
    conversationID = conversationID,
    displayName = name,
    baseName = name,
    suffix = "",
    unreadCount = unreadCount,
    isPinned = false,
    isMuted = false,
    isConnected = isConnected,
    presence = if (isConnected) ConversationPresence.Connected else ConversationPresence.NotInMesh,
    connection = if (isConnected) MeshConnectionKind.Bluetooth else MeshConnectionKind.Routed,
    isWifiAware = false,
    isDirect = isConnected,
    isFavorite = false,
    theyFavoritedUs = false,
    isVerified = false,
    colorKey = PeerIdentity.mesh(conversationID).stableKey,
    previewKind = ConversationPreviewKind.Literal(preview),
    previewIsOutgoing = false,
    hasDraft = false,
    latestMessageAt = 0L,
)

private fun previewGeoPerson(id: String, name: String, isMe: Boolean, isTeleported: Boolean) =
    GeoPersonRowUi(
        id = id,
        displayName = name,
        baseName = name,
        suffix = "",
        isMe = isMe,
        isTeleported = isTeleported,
        hasUnreadDM = false,
        colorKey = PeerIdentity.nostr(id).stableKey,
    )

/**
 * Synthetic mesh state for previews and screen tests: two conversations, one channel and five
 * people, the first of them `alice`.
 */
internal fun previewMeshPeerListUiState(): MeshPeerListUiState = MeshPeerListUiState(
    conversations = ConversationsUi(
        showSearch = false,
        status = null,
        online = listOf(
            previewConversation("1111111111111111", "bob", "synthetic hello", isConnected = true, unreadCount = 2),
        ),
        offline = listOf(
            previewConversation("2222222222222222", "carol", "synthetic bye", isConnected = false),
        ),
    ),
    channels = listOf(ChannelRowUi(name = "#synthetic", isSelected = true, unread = 0)),
    people = PeopleUi.Mesh(
        count = 4,
        noneConnected = false,
        rows = listOf(
            previewPerson("0123456789abcdef", "alice", unreadCount = 1),
            previewPerson("fedcba9876543210", "dave", isFavorite = true),
            previewPerson("3333333333333333", "erin"),
            previewPerson("4444444444444444", "frank"),
            previewPerson("5555555555555555", "grace", isConnected = false, isFavorite = true),
        ),
    ),
    showVerifyAction = true,
    hasConversations = true,
    hasEarlierSections = true,
    query = "",
)

/** Synthetic geohash state: three people, one of them us, one teleported in. */
internal fun previewGeohashPeerListUiState(): MeshPeerListUiState {
    val onLocation = listOf(
        previewGeoPerson("a".repeat(64), "alice", isMe = true, isTeleported = false),
        previewGeoPerson("b".repeat(64), "bob", isMe = false, isTeleported = false),
    )
    val teleportedIn = listOf(
        previewGeoPerson("c".repeat(64), "carol", isMe = false, isTeleported = true),
    )
    return previewEmptyPeerListUiState().copy(
        people = PeopleUi.Geohash(
            count = 3,
            people = GeohashPeopleUi(count = 3, onLocation = onLocation, teleportedIn = teleportedIn),
        ),
        showVerifyAction = false,
    )
}

/** Nothing yet: no conversations, channels or peers. */
internal fun previewEmptyPeerListUiState(): MeshPeerListUiState = MeshPeerListUiState(
    conversations = ConversationsUi(
        showSearch = false,
        status = ConversationStatusKind.Empty,
        online = emptyList(),
        offline = emptyList(),
    ),
    channels = emptyList(),
    people = PeopleUi.Mesh(count = 0, noneConnected = true, rows = emptyList()),
    showVerifyAction = true,
    hasConversations = false,
    hasEarlierSections = false,
    query = "",
)

@Preview(name = "Mesh")
@Preview(name = "Mesh (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun MeshPeerListMeshPreview() {
    BitchatTheme {
        MeshPeerListScreen(
            uiState = previewMeshPeerListUiState(),
            onAction = {},
            onDeleteRequested = {},
        )
    }
}

@Preview(name = "Geohash")
@Preview(name = "Geohash (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun MeshPeerListGeohashPreview() {
    BitchatTheme {
        MeshPeerListScreen(
            uiState = previewGeohashPeerListUiState(),
            onAction = {},
            onDeleteRequested = {},
        )
    }
}

@Preview(name = "Empty")
@Preview(name = "Empty (dark)", uiMode = UI_MODE_NIGHT_YES)
@Composable
private fun MeshPeerListEmptyPreview() {
    BitchatTheme {
        MeshPeerListScreen(
            uiState = previewEmptyPeerListUiState(),
            onAction = {},
            onDeleteRequested = {},
        )
    }
}
