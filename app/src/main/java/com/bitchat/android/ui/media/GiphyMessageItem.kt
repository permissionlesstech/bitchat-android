package com.bitchat.android.ui.media

import android.os.Build.VERSION.SDK_INT
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.model.BitchatMessage
import java.text.SimpleDateFormat

@Composable
fun GiphyMessageItem(
    message: BitchatMessage,
    currentUserNickname: String,
    meshService: BluetoothMeshService,
    colorScheme: ColorScheme,
    timeFormatter: SimpleDateFormat,
    onNicknameClick: ((String) -> Unit)?,
    onMessageLongPress: ((BitchatMessage) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    
    // Create an ImageLoader that supports GIFs
    val imageLoader = remember {
        ImageLoader.Builder(context)
            .components {
                if (SDK_INT >= 28) {
                    add(ImageDecoderDecoder.Factory())
                } else {
                    add(GifDecoder.Factory())
                }
            }
            .build()
    }

    Column(modifier = modifier.fillMaxWidth()) {
        val headerText = com.bitchat.android.ui.formatMessageHeaderAnnotatedString(
            message = message,
            currentUserNickname = currentUserNickname,
            meshService = meshService,
            colorScheme = colorScheme,
            timeFormatter = timeFormatter
        )
        
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
                        haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
                        onNicknameClick.invoke(ann.first().item)
                    }
                }, onLongPress = { onMessageLongPress?.invoke(message) })
            },
            onTextLayout = { headerLayout = it }
        )

        val gifUrl = message.content.trim()

        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.1f))
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(gifUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = "GIF",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
                    .clickable { 
                        // Optional: Open full screen or do something on click
                    }
            )
            
            // Branding overlay (optional, but good for Giphy attribution if needed)
            /*
            Text(
                text = "GIPHY",
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 10.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                modifier = Modifier
                    .align(androidx.compose.ui.Alignment.BottomStart)
                    .padding(4.dp)
            )
            */
        }
    }
}
