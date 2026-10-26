package com.bitchat.android.ui.media

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.text.AnnotatedClickableText
import com.bitchat.android.features.video.LiveVideoManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.theme.BitchatFontFamily
import com.bitchat.android.ui.theme.LocalBitchatPalette
import java.text.SimpleDateFormat

/**
 * Chat row for a video message. While the sender is live it shows the decoded stream with a
 * LIVE badge and opens the full-screen live viewer; afterwards it is an ordinary video with a
 * poster frame, play button and duration that opens the full-screen player.
 */
@Composable
fun VideoMessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
    showSender: Boolean = true,
    bubbles: Boolean = false
) {
    val palette = LocalBitchatPalette.current
    val haptic = LocalHapticFeedback.current
    var showViewer by rememberSaveable(message.id) { mutableStateOf(false) }

    val card: @Composable () -> Unit = {
        VideoMessageCard(
            message = message,
            currentUserNickname = currentUserNickname,
            onOpen = { showViewer = true },
            onCancelTransfer = onCancelTransfer,
            onLongPress = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onMessageLongPress?.invoke(message)
            }
        )
    }

    if (bubbles) {
        MediaBubbleShell(
            message = message,
            currentUserNickname = currentUserNickname,
            myPeerID = meshService.myPeerID,
            showSender = showSender,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onLongPress = { onMessageLongPress?.invoke(message) },
            modifier = modifier,
        ) { card() }
    } else {
        Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            val headerText = com.bitchat.android.ui.formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                myPeerID = meshService.myPeerID,
                palette = palette,
                contentColor = colorScheme.onSurface,
                timeFormatter = timeFormatter,
                includeSender = showSender
            )
            AnnotatedClickableText(
                text = headerText,
                annotationTags = listOf("nickname_click"),
                onAnnotationClick = { tag, item ->
                    if (tag == "nickname_click" && onNicknameClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNicknameClick.invoke(item)
                        true
                    } else {
                        false
                    }
                },
                onLongPress = { onMessageLongPress?.invoke(message) },
                fontFamily = BitchatFontFamily,
                color = colorScheme.onSurface,
            )
            card()
        }
    }

    if (showViewer) {
        VideoViewer(
            messageID = message.id,
            path = message.content.trim(),
            senderNickname = message.sender,
            onClose = { showViewer = false }
        )
    }
}

/**
 * Chooses the live viewer while the stream is live. A stream that ends while watched stays on
 * an "ended" card offering the replay, rather than cutting away mid-sentence.
 */
@Composable
fun VideoViewer(messageID: String, path: String, senderNickname: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val live by remember(context) { LiveVideoManager.getInstance(context) }.live.collectAsState()
    val isLive = live.containsKey(messageID)
    val openedLive = remember { isLive }
    var replayRequested by remember { mutableStateOf(false) }
    if (isLive || (openedLive && !replayRequested)) {
        FullScreenLiveVideoViewer(
            messageID = messageID,
            senderNickname = senderNickname,
            ended = !isLive,
            onReplay = { replayRequested = true },
            onClose = onClose
        )
    } else {
        FullScreenVideoPlayer(path = path, title = senderNickname, onClose = onClose)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun VideoMessageCard(
    message: BitchatMessage,
    currentUserNickname: String,
    onOpen: () -> Unit,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    onLongPress: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) { LiveVideoManager.getInstance(context) }
    val live by manager.live.collectAsState()
    val info = live[message.id]
    val shape = RoundedCornerShape(10.dp)
    val path = message.content.trim()

    if (info != null) {
        val aspect = (info.width.toFloat() / info.height.toFloat()).takeIf { it.isFinite() && it > 0f } ?: 0.75f
        Box(
            modifier = Modifier
                .widthIn(max = 220.dp)
                .aspectRatio(aspect)
                .clip(shape)
                .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
        ) {
            LiveVideoFrameView(messageID = message.id, modifier = Modifier.fillMaxSize())
            LiveBadge(
                startedAtMs = info.startedAtMs,
                compact = true,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )
            VideoOverlayPill(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) { SignalBars(info.loss) }
            if (info.frozen) {
                Text(
                    text = stringResource(R.string.video_weak_signal),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.5f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
        return
    }

    val preview = rememberVideoPreview(path)
    val aspect = preview?.let { (it.width.toFloat() / it.height.toFloat()) }
        ?.takeIf { it.isFinite() && it > 0f } ?: 0.75f
    val sending = message.deliveryStatus as? DeliveryStatus.PartiallyDelivered
    val progress = sending?.let { if (it.total > 0) it.reached.toFloat() / it.total else 0f }
    val isOwnSend = message.sender == currentUserNickname && sending != null
    Box(
        modifier = Modifier
            .widthIn(max = 220.dp)
            .aspectRatio(aspect)
            .clip(shape)
            .background(Color.Black)
            .combinedClickable(onClick = onOpen, onLongClick = onLongPress)
    ) {
        val thumbnail = preview?.thumbnail
        if (thumbnail != null) {
            Image(
                bitmap = remember(thumbnail) { thumbnail.asImageBitmap() },
                contentDescription = stringResource(R.string.cd_video),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.5f),
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(36.dp)
            )
        }
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(46.dp)
                .background(Color.Black.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = stringResource(R.string.cd_play_video),
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
        if (preview != null && preview.durationMs > 0) {
            VideoOverlayPill(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
            ) {
                Text(
                    text = formatVideoDuration(preview.durationMs),
                    color = Color.White,
                    fontSize = 11.sp,
                    fontFamily = BitchatFontFamily
                )
            }
        }
        if (progress != null && progress < 1f) {
            LinearProgressIndicator(
                progress = { progress },
                color = Color(0xFF1E88E5),
                trackColor = Color.White.copy(alpha = 0.25f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
            )
        }
        if (isOwnSend) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(22.dp)
                    .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                    .clickable { onCancelTransfer?.invoke(message) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_cancel),
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
    }
}
