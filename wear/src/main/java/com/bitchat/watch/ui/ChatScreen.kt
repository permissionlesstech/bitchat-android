package com.bitchat.watch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.items
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import com.bitchat.watch.ui.media.FileMessageChip
import com.bitchat.watch.ui.media.FullScreenImageViewer
import com.bitchat.watch.ui.media.ImageMessageItem
import com.bitchat.watch.ui.media.VoiceNoteItem
import com.bitchat.watch.ui.theme.BitchatMotion
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import com.bitchat.watch.ui.theme.colorForPeer
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatScreen(onOpenPeople: () -> Unit, onOpenTextInput: () -> Unit) {
    val messages by AppStateStore.publicMessages.collectAsState()
    val peers by AppStateStore.peers.collectAsState()
    val unreadDms by WearChatState.unreadDms.collectAsState()
    val mesh = WearMeshService.peek()
    val myPeerID = mesh?.myPeerID ?: ""
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val palette = LocalBitchatPalette.current
    val haptics = LocalHapticFeedback.current
    var viewerPath by remember { mutableStateOf<String?>(null) }
    val voice = rememberVoiceNoteController { path ->
        mesh?.let { sendVoiceNote(it, null, path) }
    }

    var previousCount by remember { mutableStateOf(messages.size) }
    LaunchedEffect(messages.size) {
        if (messages.size > previousCount) {
            val last = messages.lastOrNull()
            if (last != null && last.senderPeerID != myPeerID) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            // Keep the newest message visible (index 0 in reverse layout)
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(0)
            }
        }
        previousCount = messages.size
    }

    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        ScreenScaffold(scrollState = listState) {
            // Bottom padding keeps the last message just above the action bar, so messages
            // scroll all the way down to the buttons.
            // LazyColumn + reverseLayout: newest message anchors at the bottom above the action
            // bar; empty space collects at the top (ScalingLazyColumn center-anchors short
            // content, which left an awkward gap above the buttons).
            androidx.compose.foundation.lazy.LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 40.dp,
                    bottom = 56.dp
                )
            ) {
                items(messages.asReversed(), key = { it.id }) { message ->
                    MessageItem(
                        message = message,
                        myPeerID = myPeerID,
                        onOpenImage = { viewerPath = it }
                    )
                }
                if (messages.isEmpty()) {
                    item {
                        Text(
                            text = "no messages yet\nsay hi to the mesh",
                            style = ChatVisualTokens.SystemActionStyle,
                            color = palette.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp)
                        )
                    }
                }
                item {
                    ChatHeader(
                        peerCount = peers.size,
                        unreadDms = unreadDms.values.sum(),
                        onOpenPeople = onOpenPeople
                    )
                }
            }
        }

        // Always-visible action bar (the framework's edgeButton slot auto-hides on scroll,
        // which would make push-to-talk unreachable mid-conversation).
        ChatActionBar(
            onKeyboard = onOpenTextInput,
            voice = voice,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 10.dp)
        )

        VoiceRecordOverlay(voice)
    }

    viewerPath?.let { path ->
        FullScreenImageViewer(path = path, onClose = { viewerPath = null })
    }
}

@Composable
private fun ChatHeader(peerCount: Int, unreadDms: Int, onOpenPeople: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "bitchat",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = "  ·  $peerCount online >",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { onOpenPeople() }
        )
        if (unreadDms > 0) {
            Text(
                text = "  ·  $unreadDms new",
                style = MaterialTheme.typography.bodySmall,
                color = LocalBitchatPalette.current.accentOrange,
                modifier = Modifier.clickable { onOpenPeople() }
            )
        }
    }
}

@Composable
fun MessageItem(
    message: BitchatMessage,
    myPeerID: String,
    onOpenImage: (String) -> Unit = {}
) {
    val palette = LocalBitchatPalette.current
    val isSelf = message.senderPeerID == myPeerID
    val senderColor = when {
        isSelf -> palette.accentOrange
        else -> colorForPeer(message.sender + (message.senderPeerID ?: ""), palette)
    }

    // Snappy appear animation for incoming messages (BitchatMotion.EMPHASIZED_MS)
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(message.id) { appeared = true }
    val alpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.EMPHASIZED_MS),
        label = "msgAlpha"
    )
    val offset by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (appeared) 0.dp else 6.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.EMPHASIZED_MS),
        label = "msgOffset"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 3.dp)
            .offset(y = offset)
            .alpha(alpha)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isSelf) "you" else message.sender,
                style = ChatVisualTokens.SenderStyle,
                color = senderColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Text(
                text = "  ${formatTime(message.timestamp)}",
                style = ChatVisualTokens.SystemActionStyle,
                fontSize = 9.sp,
                color = palette.textTertiary
            )
        }
        when (message.type) {
            BitchatMessageType.Image -> ImageMessageItem(
                path = message.content.trim(),
                onOpen = onOpenImage
            )
            BitchatMessageType.Audio -> VoiceNoteItem(path = message.content.trim())
            BitchatMessageType.File -> FileMessageChip(
                name = File(message.content.trim()).name,
                sizeBytes = File(message.content.trim()).length()
            )
            BitchatMessageType.Message -> Text(
                text = message.content,
                style = ChatVisualTokens.MessageBodyStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

private fun formatTime(date: Date): String =
    SimpleDateFormat("HH:mm", Locale.getDefault()).format(date)
