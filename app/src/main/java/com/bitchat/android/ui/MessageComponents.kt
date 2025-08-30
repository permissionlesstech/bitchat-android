package com.bitchat.android.ui

import androidx.compose.foundation.ExperimentalFoundationApi
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
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.mesh.BluetoothMeshService
import java.text.SimpleDateFormat
import java.util.*

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
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null
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
                    items(messages.asReversed()) { message ->
                MessageItem(
                    message = message,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress
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
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null
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
    modifier: Modifier = Modifier
) {
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
