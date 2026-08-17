package com.bitchat.watch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.People
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.lazy.items
import androidx.wear.compose.material3.Icon
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
    val context = LocalContext.current
    val messages by AppStateStore.publicMessages.collectAsState()
    val peers by AppStateStore.peers.collectAsState()
    val unreadDms by WearChatState.unreadDms.collectAsState()
    val mesh = WearMeshService.peek()
    val myPeerID = mesh?.myPeerID ?: ""
    var viewerPath by remember { mutableStateOf<String?>(null) }
    val liveVoiceManager = remember(context) {
        com.bitchat.android.features.voice.LiveVoiceManager.getInstance(context)
    }
    val busyTalker by liveVoiceManager.activePublicTalker.collectAsState()
    val voice = rememberVoiceNoteController(
        recorderFactory = {
            val target = if (
                mesh != null &&
                com.bitchat.android.features.voice.LiveVoicePreferences.isEnabled(context) &&
                mesh.getPeerNicknames().isNotEmpty()
            ) com.bitchat.android.features.voice.LiveVoiceTarget { payload ->
                mesh.sendVoiceFrame(null, payload)
            } else null
            com.bitchat.android.features.voice.VoiceRecorder(context, target)
        }
    ) { path -> mesh?.let { sendVoiceNote(it, null, path) } }

    androidx.compose.runtime.DisposableEffect(liveVoiceManager) {
        liveVoiceManager.showPublicMesh()
        onDispose { liveVoiceManager.clearVisibleConversation() }
    }

    ChatScaffold(
        messages = messages,
        myPeerID = myPeerID,
        emptyText = "No messages yet\nSay hi to the mesh",
        voice = voice,
        onOpenImage = { viewerPath = it },
        header = { expanded ->
            ChatHeader(
                peerCount = peers.size,
                unreadDms = unreadDms.values.sum(),
                expanded = expanded,
                onOpenPeople = onOpenPeople
            )
        },
        actionBar = {
            ChatActionBar(onKeyboard = onOpenTextInput, voice = voice, busyTalker = busyTalker)
        }
    )

    viewerPath?.let { path ->
        FullScreenImageViewer(path = path, onClose = { viewerPath = null })
    }
}

@Composable
private fun ChatHeader(
    peerCount: Int,
    unreadDms: Int,
    expanded: Boolean,
    onOpenPeople: () -> Unit
) {
    // Floating title row: full-size at the newest messages, shrinks to its dense form
    // while scrolling up into history. Rendered as an overlay, so the animation only
    // relayouts this row, never the message list.
    val spec = androidx.compose.animation.core.tween<androidx.compose.ui.unit.Dp>(
        BitchatMotion.STANDARD_MS
    )
    val iconSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 16.dp else 11.dp, animationSpec = spec, label = "hdrIcon"
    )
    val titleSize by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 15.dp else 11.dp, animationSpec = spec, label = "hdrTitle"
    )
    val vPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (expanded) 6.dp else 1.dp, animationSpec = spec, label = "hdrPad"
    )

    // The entire header region opens the People screen. When there are unread DMs the
    // title gives way so the people and mail icons (with counts) fit side by side on the
    // round screen instead of clipping at the edges.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenPeople() }
            .padding(horizontal = 8.dp, vertical = vPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (unreadDms == 0) {
            Text(
                text = "bitchat",
                style = MaterialTheme.typography.titleSmall,
                fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { titleSize.toSp() },
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Icon(
            imageVector = Icons.Filled.People,
            contentDescription = "people",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(iconSize)
        )
        Text(
            text = "$peerCount",
            style = MaterialTheme.typography.bodySmall,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                (iconSize.value * 0.85f).dp.toSp()
            },
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 2.dp)
        )
        if (unreadDms > 0) {
            Icon(
                imageVector = Icons.Filled.MailOutline,
                contentDescription = "$unreadDms unread messages",
                tint = LocalBitchatPalette.current.accentOrange,
                modifier = Modifier
                    .padding(start = 6.dp)
                    .size(iconSize)
            )
            Text(
                text = "$unreadDms",
                style = MaterialTheme.typography.bodySmall,
                fontSize = with(androidx.compose.ui.platform.LocalDensity.current) {
                    (iconSize.value * 0.85f).dp.toSp()
                },
                color = LocalBitchatPalette.current.accentOrange,
                modifier = Modifier.padding(start = 2.dp)
            )
        }
    }
}

@Composable
fun MessageItem(
    message: BitchatMessage,
    myPeerID: String,
    onOpenImage: (String) -> Unit = {},
    modifier: Modifier = Modifier
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
        modifier = modifier
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
            BitchatMessageType.Audio -> VoiceNoteItem(
                path = message.content.trim(),
                messageID = message.id
            )
            BitchatMessageType.File -> {
                val path = message.content.trim()
                val file = remember(path) { File(path) }
                val sizeBytes = remember(path) { file.length() }
                FileMessageChip(name = file.name, sizeBytes = sizeBytes)
            }
            BitchatMessageType.Message -> Text(
                text = message.content,
                style = ChatVisualTokens.MessageBodyStyle,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 1.dp)
            )
        }
    }
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(date: Date): String = timeFormat.format(date)
