package com.bitchat.android.ui


import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.core.ui.component.text.AnnotatedClickableText
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.ui.media.FileMessageItem
import java.text.SimpleDateFormat
import java.util.Locale


// VoiceNotePlayer moved to com.bitchat.android.ui.media.VoiceNotePlayer

/**
 * Message display components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@Composable
fun MessagesList(
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: MeshService,
    modifier: Modifier = Modifier,
    forceScrollToBottom: Boolean = false,
    onScrolledUpChanged: ((Boolean) -> Unit)? = null,
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val listState = rememberLazyListState()
    
    // Track if this is the first time messages are being loaded
    var hasScrolledToInitialPosition by remember { mutableStateOf(false) }
    var followIncomingMessages by remember { mutableStateOf(true) }
    
    // Smart scroll: auto-scroll to bottom for initial load, then follow unless user scrolls away
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            val isFirstLoad = !hasScrolledToInitialPosition
            if (isFirstLoad || followIncomingMessages) {
                listState.scrollToItem(0)
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
        followIncomingMessages = isAtLatest
        onScrolledUpChanged?.invoke(!isAtLatest)
    }
    
    // Force scroll to bottom when requested (e.g., when user sends a message)
    LaunchedEffect(forceScrollToBottom) {
        if (messages.isNotEmpty()) {
            // With reverseLayout=true and reversed data, latest is at index 0
            followIncomingMessages = true
            listState.scrollToItem(0)
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
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick
                )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: MeshService,
    messages: List<BitchatMessage> = emptyList(),
    onNicknameClick: ((String) -> Unit)? = null,
    onMessageLongPress: ((BitchatMessage) -> Unit)? = null,
    onCancelTransfer: ((BitchatMessage) -> Unit)? = null,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null
) {
    val colorScheme = MaterialTheme.colorScheme
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start,
                verticalAlignment = Alignment.Top
            ) {
                // Provide a small end padding for own private messages so overlay doesn't cover text
                val endPad = if (message.isPrivate && message.sender == currentUserNickname) 16.dp else 0.dp
                // Create a custom layout that combines selectable text with clickable nickname areas
                MessageTextWithClickableNicknames(
                    message = message,
                    messages = messages,
                    currentUserNickname = currentUserNickname,
                    meshService = meshService,
                    colorScheme = colorScheme,
                    timeFormatter = timeFormatter,
                    onNicknameClick = onNicknameClick,
                    onMessageLongPress = onMessageLongPress,
                    onCancelTransfer = onCancelTransfer,
                    onImageClick = onImageClick,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = endPad)
                )
            }

            // Delivery status for private messages (overlay, non-displacing)
            if (message.isPrivate && message.sender == currentUserNickname) {
                message.deliveryStatus?.let { status ->
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 2.dp)
                    ) {
                        DeliveryStatusIcon(status = status)
                    }
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
        messages: List<BitchatMessage>,
        currentUserNickname: String,
        meshService: MeshService,
        colorScheme: ColorScheme,
        timeFormatter: SimpleDateFormat,
        onNicknameClick: ((String) -> Unit)?,
        onMessageLongPress: ((BitchatMessage) -> Unit)?,
        onCancelTransfer: ((BitchatMessage) -> Unit)?,
        onImageClick: ((String, List<String>, Int) -> Unit)?,
        modifier: Modifier = Modifier
    ) {
    // Image special rendering
    if (message.type == BitchatMessageType.Image) {
        com.bitchat.android.ui.media.ImageMessageItem(
            message = message,
            messages = messages,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            onImageClick = onImageClick,
            modifier = modifier
        )
        return
    }

    // Voice note special rendering
    if (message.type == BitchatMessageType.Audio) {
        com.bitchat.android.ui.media.AudioMessageItem(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            onCancelTransfer = onCancelTransfer,
            modifier = modifier
        )
        return
    }

    // File special rendering
    if (message.type == BitchatMessageType.File) {
        val path = message.content.trim()
        // Derive sending progress if applicable
        val (overrideProgress, _) = when (val st = message.deliveryStatus) {
            is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered -> {
                if (st.total > 0 && st.reached < st.total) {
                    (st.reached.toFloat() / st.total.toFloat()) to Color(0xFF1E88E5) // blue while sending
                } else null to null
            }
            else -> null to null
        }
        Column(modifier = modifier.fillMaxWidth()) {
            // Header: nickname + timestamp line above the file, identical styling to text messages
            val headerText = formatMessageHeaderAnnotatedString(
                message = message,
                currentUserNickname = currentUserNickname,
                meshService = meshService,
                colorScheme = colorScheme,
                timeFormatter = timeFormatter
            )
            val haptic = LocalHapticFeedback.current
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
                fontFamily = FontFamily.Monospace,
                color = colorScheme.onSurface,
            )

            // Try to load the file packet from the path
            val packet = try {
                val file = java.io.File(path)
                if (file.exists()) {
                    // Create a temporary BitchatFilePacket for display
                    // In a real implementation, this would be stored with the packet metadata
                    com.bitchat.android.model.BitchatFilePacket(
                        fileName = file.name,
                        fileSize = file.length(),
                        mimeType = com.bitchat.android.features.file.FileUtils.getMimeTypeFromExtension(file.name),
                        content = file.readBytes()
                    )
                } else null
            } catch (e: Exception) {
                null
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box {
                    if (packet != null) {
                        if (overrideProgress != null) {
                            // Show sending animation while in-flight
                            com.bitchat.android.ui.media.FileSendingAnimation(
                                fileName = packet.fileName,
                                progress = overrideProgress,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            // Static file display with open/save dialog
                            FileMessageItem(
                                packet = packet,
                                onFileClick = {
                                    // handled inside FileMessageItem via dialog
                                }
                            )
                        }

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
                                Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(14.dp))
                            }
                        }
                    } else {
                        Text(text = stringResource(R.string.file_unavailable), fontFamily = FontFamily.Monospace, color = Color.Gray)
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
    } else if (message.sender == "system") {
        // Keep system messages on the compact legacy line.
        val annotatedText = formatMessageAsAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )

        val haptic = LocalHapticFeedback.current
        Text(
            text = annotatedText,
            modifier = modifier.pointerInput(message) {
                detectTapGestures(
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
            )
        )
    } else {
        TextMessageLayout(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter,
            onNicknameClick = onNicknameClick,
            onMessageLongPress = onMessageLongPress,
            modifier = modifier,
        )
    }
}

@Composable
internal fun TextMessageLayout(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: MeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier,
    bodyContent: String = message.content,
) {
    val myPeerId = meshService.myPeerID
    val displayMessage = remember(message, bodyContent) {
        if (bodyContent == message.content) message else message.copy(content = bodyContent)
    }
    val senderText = remember(message, currentUserNickname, myPeerId, colorScheme) {
        formatTextMessageSender(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
        )
    }
    val metadataText = remember(message.timestamp, message.powDifficulty, timeFormatter) {
        formatTextMessageMetadata(
            message = message,
            timeFormatter = timeFormatter,
        )
    }
    val bodyText = remember(displayMessage, currentUserNickname, myPeerId, colorScheme) {
        formatTextMessageBody(
            message = displayMessage,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
        )
    }
    val isSelf = message.isFromSelf(currentUserNickname, myPeerId)
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val handleLongPress: () -> Unit = {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onMessageLongPress?.invoke(message)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnnotatedClickableText(
                text = senderText,
                annotationTags = listOf("nickname_click"),
                onAnnotationClick = { tag, item ->
                    if (tag == "nickname_click" && !isSelf && onNicknameClick != null) {
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onNicknameClick.invoke(item)
                        true
                    } else {
                        false
                    }
                },
                onLongPress = handleLongPress,
                modifier = Modifier.weight(1f),
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
            )
            AnnotatedClickableText(
                text = metadataText,
                annotationTags = emptyList(),
                onAnnotationClick = { _, _ -> false },
                onLongPress = handleLongPress,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }

        AnnotatedClickableText(
            text = bodyText,
            annotationTags = listOf("geohash_click", "url_click"),
            onAnnotationClick = { tag, item ->
                when (tag) {
                    "geohash_click" -> {
                        navigateToGeohash(context, item)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        true
                    }

                    "url_click" -> {
                        openMessageUrl(context, item)
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        true
                    }

                    else -> false
                }
            },
            onLongPress = handleLongPress,
            fontFamily = FontFamily.Monospace,
            softWrap = true,
            overflow = TextOverflow.Visible,
            style = androidx.compose.ui.text.TextStyle(color = colorScheme.onSurface),
        )
    }
}

@Composable
fun DeliveryStatusIcon(status: DeliveryStatus) {
    val colorScheme = MaterialTheme.colorScheme
    
    when (status) {
        is DeliveryStatus.Sending -> {
            Text(
                text = stringResource(R.string.status_sending),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Sent -> {
            // Use a subtle hollow marker for Sent; single check is reserved for Delivered (iOS parity)
            Text(
                text = stringResource(R.string.status_pending),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
        is DeliveryStatus.Delivered -> {
            // Single check for Delivered (matches iOS expectations)
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.Read -> {
            Text(
                text = stringResource(R.string.status_delivered),
                fontSize = 10.sp,
                color = Color(0xFF007AFF), // Blue
                fontWeight = FontWeight.Bold
            )
        }
        is DeliveryStatus.Failed -> {
            Text(
                text = stringResource(R.string.status_failed),
                fontSize = 10.sp,
                color = Color.Red.copy(alpha = 0.8f)
            )
        }
        is DeliveryStatus.PartiallyDelivered -> {
            // Show a single subdued check without numeric label
            Text(
                text = stringResource(R.string.status_sent),
                fontSize = 10.sp,
                color = colorScheme.primary.copy(alpha = 0.6f)
            )
        }
    }
}
