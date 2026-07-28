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
    val messages by AppStateStore.publicMessages.collectAsState()
    val peers by AppStateStore.peers.collectAsState()
    val unreadDms by WearChatState.unreadDms.collectAsState()
    val mesh = WearMeshService.peek()
    val myPeerID = mesh?.myPeerID ?: ""
    val scrollState = androidx.compose.foundation.rememberScrollState()
    val rotaryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { rotaryFocus.requestFocus() }
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
            // Keep the newest message visible (bottom of a normal top-down scrollable)
            if (messages.isNotEmpty()) {
                scrollState.animateScrollTo(scrollState.maxValue)
            }
        }
        previousCount = messages.size
    }

    val buttonsVisible = rememberBottomBarVisibility(scrollState)
    val headerExpanded = scrollState.maxValue - scrollState.value > 60
    // Full bottom clearance only at the newest message, so the last message sits comfortably
    // above the floating buttons; collapses when scrolling up so history flows behind them.
    val atNewest = scrollState.maxValue - scrollState.value < 40
    val listBottomPadding by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (atNewest) 56.dp else 8.dp,
        animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS),
        label = "listBottomPad"
    )

    Column(modifier = Modifier.fillMaxSize()) {
        // Sticky header
        ChatHeader(
            peerCount = peers.size,
            unreadDms = unreadDms.values.sum(),
            expanded = headerExpanded,
            onOpenPeople = onOpenPeople
        )
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            // Normal top-down scrollable (natural rotary direction + ScreenScaffold scrollbar).
            // BottomCenter alignment anchors short content to the bottom above the action bar;
            // empty space collects at the top instead of a gap above the buttons.
            ScreenScaffold(scrollState = scrollState) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = listBottomPadding)
                            .rotaryScrollable(
                                RotaryScrollableDefaults.behavior(scrollState),
                                rotaryFocus
                            )
                            .focusRequester(rotaryFocus)
                            .focusable()
                            .verticalScroll(scrollState)
                    ) {
                        if (messages.isEmpty()) {
                            Text(
                                text = "no messages yet\nsay hi to the mesh",
                                style = ChatVisualTokens.SystemActionStyle,
                                color = palette.textTertiary,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 48.dp)
                            )
                        }
                        messages.forEach { message ->
                            MessageItem(
                                message = message,
                                myPeerID = myPeerID,
                                onOpenImage = { viewerPath = it }
                            )
                        }
                    }
                }
            }

            // Scroll-aware action bar: hides while scrolling into history, returns when
            // scrolling back toward the newest messages.
            androidx.compose.animation.AnimatedVisibility(
                visible = buttonsVisible.value,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = androidx.compose.animation.slideInVertically(
                    initialOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS)
                ) + androidx.compose.animation.fadeIn(
                    animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS)
                ),
                exit = androidx.compose.animation.slideOutVertically(
                    targetOffsetY = { it },
                    animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS)
                ) + androidx.compose.animation.fadeOut(
                    animationSpec = androidx.compose.animation.core.tween(BitchatMotion.STANDARD_MS)
                )
            ) {
                ChatActionBar(
                    onKeyboard = onOpenTextInput,
                    voice = voice,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
            }

            VoiceRecordOverlay(voice)
        }
    }

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
    // Collapsing header: dense (small title, tiny icons) at the newest messages so the chat
    // gets maximum space; scales up smoothly when the user scrolls into history.
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

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = vPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "bitchat",
            style = MaterialTheme.typography.titleSmall,
            fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { titleSize.toSp() },
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(start = 8.dp)
                .clickable { onOpenPeople() }
        ) {
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
                        .padding(start = 5.dp)
                        .size(iconSize)
                )
            }
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
