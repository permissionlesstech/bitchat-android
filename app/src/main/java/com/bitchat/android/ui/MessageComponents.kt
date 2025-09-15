package com.bitchat.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
 

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import android.content.Intent
import android.net.Uri
import com.bitchat.android.model.BitchatMessage
import androidx.compose.ui.graphics.asImageBitmap
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*
import android.media.MediaPlayer
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import com.bitchat.android.ui.media.FullScreenImageViewer
import com.bitchat.android.ui.media.WaveformPreview

@Composable
private fun VoiceNotePlayer(
    path: String,
    progressOverride: Float? = null,
    progressColor: Color? = null
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var durationMs by remember { mutableStateOf(0) }
    val player = remember { MediaPlayer() }

    LaunchedEffect(path) {
        isPrepared = false
        isError = false
        progress = 0f
        durationMs = 0
        isPlaying = false
        try {
            player.reset()
            player.setOnPreparedListener {
                isPrepared = true
                durationMs = try { player.duration } catch (_: Exception) { 0 }
            }
            player.setOnCompletionListener {
                isPlaying = false
                progress = 1f
            }
            player.setOnErrorListener { _, _, _ ->
                isError = true
                isPlaying = false
                true
            }
            player.setDataSource(path)
            player.prepareAsync()
        } catch (_: Exception) {
            isError = true
        }
    }

    LaunchedEffect(isPlaying, isPrepared) {
        try {
            if (isPlaying && isPrepared) player.start() else if (isPrepared && player.isPlaying) player.pause()
        } catch (_: Exception) {}
    }
    LaunchedEffect(isPlaying, isPrepared) {
        while (isPlaying && isPrepared) {
            progress = try { player.currentPosition.toFloat() / (player.duration.toFloat().coerceAtLeast(1f)) } catch (_: Exception) { 0f }
            kotlinx.coroutines.delay(100)
        }
    }
    DisposableEffect(Unit) { onDispose { try { player.release() } catch (_: Exception) {} } }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Disable play/pause while showing send progress override (optional UX choice)
        val controlsEnabled = isPrepared && !isError && progressOverride == null
        FilledTonalIconButton(onClick = { if (controlsEnabled) isPlaying = !isPlaying }, enabled = controlsEnabled) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = if (isPlaying) "Pause" else "Play"
            )
        }
        WaveformPreview(
            modifier = Modifier.weight(1f).height(36.dp),
            path = path,
            sendProgress = progressOverride,
            playbackProgress = if (progressOverride == null) progress else null
        )
        val durText = if (durationMs > 0) String.format("%02d:%02d", (durationMs / 1000) / 60, (durationMs / 1000) % 60) else "--:--"
        Text(text = durText, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
    }
}

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    modifier: Modifier = Modifier,
    forceScrollToBottom: Boolean = false,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    
    // Track if this is the first time messages are being loaded
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    
    // Smart scroll: auto-scroll to bottom for initial load, then only when user is at or near the bottom
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val layoutInfo = listState.layoutInfo
            val firstVisibleIndex = layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            
            // With reverseLayout=true and reversed data, index 0 is the latest message at the bottom
            val isFirstLoad = !hasScrolledToInitialPosition
            val isNearLatest = firstVisibleIndex <= 2
            
            if (isFirstLoad || isNearLatest) {
                listState.animateScrollToItem(0)
                if (isFirstLoad) {
                    hasScrolledToInitialPosition = true
                }
            }
        }
    }
    
    // Track whether user has scrolled away from the latest messages
    val isAtLatest by remember {
        derivedStateOf {
            val firstVisibleIndex = listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index ?: -1
            firstVisibleIndex <= 2
        }
    }
    LaunchedEffect(isAtLatest) {
        onScrolledUpChanged?.invoke(!isAtLatest)
    }
    
    // Force scroll to bottom when requested (e.g., when user sends a message)
    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            // With reverseLayout=true and reversed data, latest is at index 0
            listState.animateScrollToItem(0)
        }
    }
    
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
        reverseLayout = true
    ) {
        items(
            items = messages.asReversed(),
            key = { it.id }
        ) { message ->
                MessageItem(
                    message = message,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            // Create a custom layout that combines selectable text with clickable nickname areas
            MessageTextWithClickableNicknames(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter,
                onNicknameClick = onNicknameClick,
                onMessageLongPress = onMessageLongPress,
                onCancelTransfer = onCancelTransfer,
                modifier = Modifier.weight(1f)
            )
            
            // Delivery status for private messages
            if (message.isPrivate && message.sender == currentUserNickname) {
                message.deliveryStatus?.let { status ->
                    DeliveryStatusIcon(status = status)
                }
            }
        }
        
        // Link previews removed; links are now highlighted inline and clickable within the message text
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
    private fun MessageTextWithClickableNicknames(
        message: BitchatMessage,
        currentUserNickname: String,
        meshService: BluetoothMeshService,
        colorScheme: ColorScheme,
        timeFormatter: SimpleDateFormat,
        onNicknameClick: ((String) -> Unit)?,
        onMessageLongPress: ((BitchatMessage) -> Unit)?,
        onCancelTransfer: ((BitchatMessage) -> Unit)?,
        modifier: Modifier = Modifier
    ) {
    // Image special rendering
    if (message.content.startsWith("[image] ")) {
        val path = message.content.removePrefix("[image] ").trim()
        Column(modifier = modifier.fillMaxWidth()) {
            // Header: nickname + timestamp line above the image, identical styling to text messages
            val headerText = formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter
            )
            val haptic = LocalHapticFeedback.current
            var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = headerText,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface,
                modifier = Modifier.pointerInput(message.id) {
                    detectTapGestures(onTap = { pos ->
                        val layout = headerLayout ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)
                        val ann = headerText.getStringAnnotations("nickname_click", offset, offset)
                        if (ann.isNotEmpty() && onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick.invoke(ann.first().item)
                        }
                    }, onLongPress = { onMessageLongPress?.invoke(message) })
                },
                onTextLayout = { headerLayout = it }
            )

            val context = LocalContext.current
            var showViewer by remember { mutableStateOf(false) }
            val bmp = remember(path) { try { android.graphics.BitmapFactory.decodeFile(path) } catch (_: Exception) { null } }
            if (bmp != null) {
                val img = bmp.asImageBitmap()
                val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).takeIf { it.isFinite() && it > 0 } ?: 1f
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                    Box {
                        androidx.compose.foundation.Image(
                            bitmap = img,
                            contentDescription = "Image",
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .aspectRatio(aspect)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .clickable { showViewer = true },
                            contentScale = androidx.compose.ui.layout.ContentScale.Fit
                        )
                        // Cancel button overlay during sending
                        val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
                        if (showCancel) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .size(22.dp)
                                    .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                                    .clickable { onCancelTransfer?.invoke(message) },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(imageVector = Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            } else {
                Text(text = "[image unavailable]", fontFamily = FontFamily.Monospace, color = Color.Gray)
            }

            if (showViewer) {
                FullScreenImageViewer(path = path, onClose = { showViewer = false })
            }

            // Optional: show transfer progress if partial delivery
            when (val st = message.deliveryStatus) {
                is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered -> {
                    if (st.total > 0) {
                        LinearProgressIndicator(
                            progress = (st.reached.toFloat() / st.total.toFloat()).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                            color = Color(0xFF1E88E5)
                        )
                    }
                }
                else -> {}
            }
        }
        return
    }

    // Voice note special rendering
    if (message.content.startsWith("[voice] ")) {
        val path = message.content.removePrefix("[voice] ").trim()
        // Derive sending progress if applicable
        val (overrideProgress, overrideColor) = when (val st = message.deliveryStatus) {
            is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered -> {
                if (st.total > 0 && st.reached < st.total) {
                    (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5) // blue while sending
                } else null to null
            }
            else -> null to null
        }
        Column(modifier = modifier.fillMaxWidth()) {
            // Header: nickname + timestamp line above the audio note, identical styling to text messages
            val headerText = formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter
            )
            val haptic = LocalHapticFeedback.current
            var headerLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
            Text(
                text = headerText,
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface,
                modifier = Modifier.pointerInput(message.id) {
                    detectTapGestures(onTap = { pos ->
                        val layout = headerLayout ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(pos)
                        val ann = headerText.getStringAnnotations("nickname_click", offset, offset)
                        if (ann.isNotEmpty() && onNicknameClick != null) {
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onNicknameClick.invoke(ann.first().item)
                        }
                    }, onLongPress = { onMessageLongPress?.invoke(message) })
                },
                onTextLayout = { headerLayout = it }
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                VoiceNotePlayer(
                    path = path,
                    progressOverride = overrideProgress,
                    progressColor = overrideColor
                )
                val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is DeliveryStatus.PartiallyDelivered)
                if (showCancel) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .background(Color.Gray.copy(alpha = 0.6f), CircleShape)
                            .clickable { onCancelTransfer?.invoke(message) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Cancel", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
        return
    }
    // Check if this message should be animated during PoW mining
    val shouldAnimate = shouldAnimateMessage(message.id)
    
    // If animation is needed, use the matrix animation component for content only
    if (shouldAnimate) {
        // Display message with matrix animation for content
        MessageWithMatrixAnimation(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            modifier = modifier
        )
    } else {
        // Normal message display
        val annotatedText = formatMessageAsAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
        
        // Check if this message was sent by self to avoid click interactions on own nickname
        val isSelf = message.senderPeerID == meshService.myPeerID || 
                     message.sender == currentUserNickname ||
                     message.sender.startsWith("$currentUserNickname#")
        
        val haptic = LocalHapticFeedback.current
        val context = LocalContext.current
        var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
        Text(
            text = annotatedText,
            modifier = modifier.pointerInput(message) {
                detectTapGestures(
                    onTap = { position ->
                        val layout = textLayoutResult ?: return@detectTapGestures
                        val offset = layout.getOffsetForPosition(position)
                        // Nickname click only when not self
                        if (!isSelf && onNicknameClick != null) {
                            val nicknameAnnotations = annotatedText.getStringAnnotations(
                                tag = "nickname_click",
                                start = offset,
                                end = offset
                            )
                            if (nicknameAnnotations.isNotEmpty()) {
                                val nickname = nicknameAnnotations.first().item
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onNicknameClick.invoke(nickname)
                                return@detectTapGestures
                            }
                        }
                        // Geohash teleport (all messages)
                        val geohashAnnotations = annotatedText.getStringAnnotations(
                            tag = "geohash_click",
                            start = offset,
                            end = offset
                        )
                        if (geohashAnnotations.isNotEmpty()) {
                            val geohash = geohashAnnotations.first().item
                            try {
                                val locationManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(
                                    context
                                )
                                val level = when (geohash.length) {
                                    in 0..2 -> com.bitchat.android.geohash.GeohashChannelLevel.REGION
                                    in 3..4 -> com.bitchat.android.geohash.GeohashChannelLevel.PROVINCE
                                    5 -> com.bitchat.android.geohash.GeohashChannelLevel.CITY
                                    6 -> com.bitchat.android.geohash.GeohashChannelLevel.NEIGHBORHOOD
                                    else -> com.bitchat.android.geohash.GeohashChannelLevel.BLOCK
                                }
                                val channel = com.bitchat.android.geohash.GeohashChannel(level, geohash.lowercase())
                                locationManager.setTeleported(true)
                                locationManager.select(com.bitchat.android.geohash.ChannelID.Location(channel))
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                        // URL open (all messages)
                        val urlAnnotations = annotatedText.getStringAnnotations(
                            tag = "url_click",
                            start = offset,
                            end = offset
                        )
                        if (urlAnnotations.isNotEmpty()) {
                            val raw = urlAnnotations.first().item
                            val resolved = if (raw.startsWith("http://", ignoreCase = true) || raw.startsWith("https://", ignoreCase = true)) raw else "https://$raw"
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(resolved))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (_: Exception) { }
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            return@detectTapGestures
                        }
                    },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onMessageLongPress?.invoke(message)
                    }
                )
            },
            fontFamily = FontFamily.Monospace,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(
                color = colorScheme.onSurface
            ),
            onTextLayout = { result -> textLayoutResult = result }
        )
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = "○",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            // Use a subtle hollow marker for Sent; single check is reserved for Delivered (iOS parity)
            Text(
                text = "○",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            // Single check for Delivered (matches iOS expectations)
            Text(
                text = "✓",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = "✓✓",
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = "⚠",
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            Text(
                text = "✓${status.reached}/${status.total}",
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}
