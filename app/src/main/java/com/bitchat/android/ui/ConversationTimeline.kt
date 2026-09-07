package com.bitchat.android.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.bitchat.android.R
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.media.FullScreenImageViewer

/** The visible conversation, independent of the timeline underneath a private sheet. */
data class ConversationUiContext(
    val key: String,
    val privatePeerID: String? = null,
    val isNostr: Boolean = false,
) {
    fun supportsMediaSend(hasMeshRoute: Boolean): Boolean =
        if (privatePeerID != null) hasMeshRoute else !isNostr
}

internal fun appendConversationMention(
    text: TextFieldValue,
    sender: String,
    context: ConversationUiContext,
): TextFieldValue {
    val (name, suffix) = splitSuffix(sender)
    val mention = "@$name${if (context.isNostr) suffix else ""} "
    val separator = if (text.text.isEmpty() || text.text.endsWith(" ")) "" else " "
    val updated = text.text + separator + mention
    return TextFieldValue(updated, TextRange(updated.length))
}

/** All chat entry points get the same interaction wiring, overlays and scroll control. */
@Composable
internal fun ConversationTimeline(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    viewModel: ChatViewModel,
    context: ConversationUiContext,
    onMention: (String) -> Unit,
    modifier: Modifier = Modifier,
    mentionPeerIdentities: Map<String, PeerIdentity>? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    forceScrollToBottom: Boolean = false,
) {
    ConversationTimelineContent(
        messages, currentUserNickname, viewModel.meshServiceFacade, context, onMention,
        onCancelTransfer = { viewModel.cancelMediaSend(it.id) },
        modifier = modifier, mentionPeerIdentities = mentionPeerIdentities,
        contentPadding = contentPadding, forceScrollToBottom = forceScrollToBottom,
        messageActions = { message, dismiss ->
            ChatUserSheet(
                isPresented = true, onDismiss = dismiss,
                targetNickname = splitSuffix(message.sender).first,
                selectedMessage = message, viewModel = viewModel, conversationContext = context,
            )
        },
    )
}

@Composable
internal fun ConversationTimelineContent(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: com.bitchat.android.mesh.MeshService,
    context: ConversationUiContext,
    onMention: (String) -> Unit,
    onCancelTransfer: (BitchatMessage) -> Unit,
    modifier: Modifier = Modifier,
    mentionPeerIdentities: Map<String, PeerIdentity>? = null,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    forceScrollToBottom: Boolean = false,
    messageActions: @Composable (BitchatMessage, () -> Unit) -> Unit,
) {
    var selectedMessage by remember(context.key) { mutableStateOf<BitchatMessage?>(null) }
    var gallery by remember(context.key) { mutableStateOf<Pair<List<String>, Int>?>(null) }
    var scrolledUp by remember(context.key) { mutableStateOf(false) }
    var scrollRequest by remember(context.key) { mutableStateOf(false) }
    LaunchedEffect(context.key, forceScrollToBottom) { scrollRequest = !scrollRequest }
    Box(modifier) {
        MessagesList(
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            mentionPeerIdentities = mentionPeerIdentities,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            conversationKey = context.key,
            forceScrollToBottom = scrollRequest,
            onScrolledUpChanged = { scrolledUp = it },
            onNicknameClick = onMention,
            onMessageLongPress = { selectedMessage = it },
            onCancelTransfer = onCancelTransfer,
            onImageClick = { _, paths, index ->
                if (paths.isNotEmpty()) gallery = paths to index.coerceIn(paths.indices)
            },
        )
        AnimatedVisibility(
            visible = scrolledUp,
            modifier = Modifier.align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp),
        ) {
            Surface(
                shape = CircleShape,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            ) {
                IconButton(onClick = { scrollRequest = !scrollRequest }) {
                    Icon(Icons.Default.ArrowDownward, stringResource(R.string.cd_scroll_to_bottom))
                }
            }
        }
    }
    gallery?.let { (paths, index) ->
        FullScreenImageViewer(paths, index, onClose = { gallery = null })
    }
    selectedMessage?.let { message ->
        messageActions(message) { selectedMessage = null }
    }
}
