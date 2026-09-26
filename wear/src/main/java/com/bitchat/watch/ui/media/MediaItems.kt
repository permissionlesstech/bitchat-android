package com.bitchat.watch.ui.media

import android.graphics.BitmapFactory
import android.media.MediaPlayer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.bitchat.android.features.voice.AudioWaveformExtractor
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.watch.ui.theme.ChatVisualTokens
import com.bitchat.watch.ui.theme.LocalBitchatPalette
import kotlinx.coroutines.delay
import java.io.File

/**
 * Compact inline image thumbnail; tap opens the full-screen viewer.
 */
@Composable
fun ImageMessageItem(path: String, onOpen: (String) -> Unit) {
    val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
    if (bitmap == null) {
        FileMessageChip(name = File(path).name, sizeBytes = File(path).length())
        return
    }
    Image(
        painter = BitmapPainter(bitmap.asImageBitmap()),
        contentDescription = "image",
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .padding(top = 2.dp)
            .widthIn(max = 120.dp)
            .aspectRatio(
                (bitmap.width.toFloat() / bitmap.height.toFloat()).coerceIn(0.6f, 1.8f)
            )
            .clip(RoundedCornerShape(10.dp))
            .clickable { onOpen(path) }
    )
}

/**
 * Full-screen image viewer (mirrors the phone's FullScreenImageViewer): black surface,
 * fit-to-screen, tap or swipe-back to dismiss.
 */
@Composable
fun FullScreenImageViewer(path: String, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable { onClose() },
            contentAlignment = Alignment.Center
        ) {
            val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
            if (bitmap != null) {
                Image(
                    painter = BitmapPainter(bitmap.asImageBitmap()),
                    contentDescription = "image fullscreen",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "close",
                tint = Color.White.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .size(20.dp)
            )
        }
    }
}

/**
 * Voice-note bubble: play/pause + waveform (120 bins, extracted locally like the phone) +
 * duration/progress. Playback via MediaPlayer.
 */
@Composable
fun VoiceNoteItem(path: String, messageID: String? = null) {
    val palette = LocalBitchatPalette.current
    val context = LocalContext.current
    val liveIDs by com.bitchat.android.features.voice.LiveVoiceManager
        .getInstance(context).liveMessageIDs.collectAsState()
    val isLive = messageID != null && messageID in liveIDs
    var samples by remember { mutableStateOf(VoiceWaveformCache.get(path)) }
    var playing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableIntStateOf(0) }
    val player = remember { MediaPlayer() }

    LaunchedEffect(path) {
        if (samples == null) {
            AudioWaveformExtractor.extractAsync(path) { extracted ->
                if (extracted != null) {
                    VoiceWaveformCache.put(path, extracted)
                    samples = extracted
                }
            }
        }
    }

    DisposableEffect(path) {
        runCatching {
            player.reset()
            player.setDataSource(path)
            player.setOnCompletionListener {
                playing = false
                progress = 0f
            }
            player.prepare()
            durationMs = player.duration
        }
        onDispose {
            runCatching { if (player.isPlaying) player.stop() }
            runCatching { player.release() }
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            progress = if (durationMs > 0) player.currentPosition.toFloat() / durationMs else 0f
            delay(100)
        }
    }

    Row(
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.inputSurface)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isLive) {
            Text(
                text = "LIVE",
                color = Color(0xFFFFB300),
                style = ChatVisualTokens.SystemActionStyle,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Box(
            modifier = Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary)
                .clickable {
                    if (playing) {
                        player.pause()
                        playing = false
                    } else {
                        runCatching { player.start() }
                        playing = true
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (playing) {
                Canvas(modifier = Modifier.size(10.dp)) {
                    val w = size.width
                    val h = size.height
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset(0f, 0f),
                        size = Size(w * 0.35f, h),
                        cornerRadius = CornerRadius(1.dp.toPx())
                    )
                    drawRoundRect(
                        color = Color.Black,
                        topLeft = Offset(w * 0.65f, 0f),
                        size = Size(w * 0.35f, h),
                        cornerRadius = CornerRadius(1.dp.toPx())
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = "play",
                    tint = Color.Black,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        WaveformBars(
            samples = samples,
            progress = progress,
            modifier = Modifier
                .padding(start = 6.dp)
                .weight(1f)
                .height(22.dp),
            activeColor = MaterialTheme.colorScheme.primary,
            inactiveColor = palette.textTertiary.copy(alpha = 0.5f)
        )
        Text(
            text = formatDuration(if (playing) (durationMs * progress).toInt() else durationMs),
            style = ChatVisualTokens.SystemActionStyle,
            color = palette.textTertiary,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

@Composable
fun WaveformBars(
    samples: FloatArray?,
    progress: Float,
    modifier: Modifier = Modifier,
    activeColor: Color,
    inactiveColor: Color
) {
    Canvas(modifier = modifier) {
        val bars = 32
        val values = samples?.let { com.bitchat.android.features.voice.resampleWave(it, bars) }
            ?: FloatArray(bars) { 0.3f }
        val barWidth = size.width / (bars * 2 - 1)
        for (i in 0 until bars) {
            val v = values.getOrElse(i) { 0f }.coerceIn(0.08f, 1f)
            val barHeight = size.height * v
            val x = i * barWidth * 2
            drawRoundRect(
                color = if (i.toFloat() / bars <= progress) activeColor else inactiveColor,
                topLeft = Offset(x, (size.height - barHeight) / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f)
            )
        }
    }
}

/**
 * Compact chip for non-media files.
 */
@Composable
fun FileMessageChip(name: String, sizeBytes: Long) {
    val palette = LocalBitchatPalette.current
    Row(
        modifier = Modifier
            .padding(top = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.inputSurface)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = name,
                style = ChatVisualTokens.SystemActionStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSize(sizeBytes),
                style = ChatVisualTokens.SystemActionStyle,
                color = palette.textTertiary
            )
        }
    }
}

private fun formatDuration(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576f)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024f)
    else -> "$bytes B"
}
