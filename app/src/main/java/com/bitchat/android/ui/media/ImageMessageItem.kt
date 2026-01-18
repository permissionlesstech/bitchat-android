package com.bitchat.android.ui.media

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.text.font.FontFamily
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import androidx.compose.material3.ColorScheme
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ImageMessageItem(
    message: BitchatMessage,
    messages: List<BitchatMessage>,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    onCancelTransfer: ((BitchatMessage) -> Unit)?,
    onImageClick: ((String, List<String>, Int) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val path = message.content.trim()
    Column(modifier = modifier.fillMaxWidth()) {
        val headerText = com.bitchat.android.ui.formatMessageHeaderAnnotatedString(
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
        val bmp = remember(path) { try { android.graphics.BitmapFactory.decodeFile(path) } catch (_: Exception) { null } }

        // Collect all image paths from messages for swipe navigation
        val imagePaths = remember(messages) {
            messages.filter { it.type == BitchatMessageType.Image }
                .map { it.content.trim() }
        }

        if (bmp != null) {
            val img = bmp.asImageBitmap()
            val aspect = (bmp.width.toFloat() / bmp.height.toFloat()).takeIf { it.isFinite() && it > 0 } ?: 1f
            val progressFraction: Float? = when (val st = message.deliveryStatus) {
                is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered -> if (st.total > 0) st.reached.toFloat() / st.total.toFloat() else 0f
                else -> null
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                Box {
                    if (progressFraction != null && progressFraction < 1f && message.sender == currentUserNickname) {
                        // Cyberpunk block-reveal while sending
                        BlockRevealImage(
                            bitmap = img,
                            progress = progressFraction,
                            blocksX = 24,
                            blocksY = 16,
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .aspectRatio(aspect)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .clickable {
                                    val currentIndex = imagePaths.indexOf(path)
                                    onImageClick?.invoke(path, imagePaths, currentIndex)
                                }
                        )
                    } else {
                        // Fully revealed image
                        Image(
                            bitmap = img,
                            contentDescription = stringResource(com.bitchat.android.R.string.cd_image),
                            modifier = Modifier
                                .widthIn(max = 300.dp)
                                .aspectRatio(aspect)
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                                .clickable {
                                    val currentIndex = imagePaths.indexOf(path)
                                    onImageClick?.invoke(path, imagePaths, currentIndex)
                                },
                            contentScale = ContentScale.Fit
                        )
                    }
                    // Cancel button overlay during sending
                    val showCancel = message.sender == currentUserNickname && (message.deliveryStatus is com.bitchat.android.model.DeliveryStatus.PartiallyDelivered)
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
                            Icon(imageVector = Icons.Filled.Close, contentDescription = stringResource(com.bitchat.android.R.string.cd_cancel), tint = Color.White, modifier = Modifier.size(14.dp))
                        }
                    }
                }
            }
        } else {
            Text(text = stringResource(com.bitchat.android.R.string.image_unavailable), fontFamily = FontFamily.Monospace, color = Color.Gray)
        }
    }
}

/**
 * Displays an image loaded from a URL using Coil.
 * Used for inline image previews in text messages containing image URLs.
 */
@Composable
fun UrlImageItem(
    url: String,
    onImageClick: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    var isLoading by remember { mutableStateOf(true) }
    var isError by remember { mutableStateOf(false) }
    
    Box(
        modifier = modifier
            .widthIn(max = 300.dp)
            .heightIn(max = 300.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
    ) {
        coil.compose.AsyncImage(
            model = coil.request.ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(true)
                .build(),
            contentDescription = "Image",
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onImageClick?.invoke(url) },
            contentScale = ContentScale.Fit,
            onLoading = { isLoading = true; isError = false },
            onSuccess = { isLoading = false; isError = false },
            onError = { isLoading = false; isError = true }
        )
        
        // Loading indicator
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Gray.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            }
        }
        
        // Error state - show placeholder
        if (isError) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp)
                    .background(Color.Gray.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Failed to load image",
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

/**
 * Displays multiple URL images in a vertical column.
 * Used when a message contains multiple image URLs.
 */
@Composable
fun UrlImagesColumn(
    urls: List<String>,
    onImageClick: ((String, List<String>, Int) -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        urls.forEachIndexed { index, url ->
            UrlImageItem(
                url = url,
                onImageClick = { clickedUrl ->
                    onImageClick?.invoke(clickedUrl, urls, index)
                }
            )
        }
    }
}
