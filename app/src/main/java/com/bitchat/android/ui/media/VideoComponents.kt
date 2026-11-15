package com.bitchat.android.ui.media

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.LruCache
import android.view.WindowManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bitchat.android.R
import com.bitchat.android.features.video.LiveVideoManager
import com.bitchat.android.features.video.LiveVideoTarget
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

/** Conversation-scoped video actions the composer needs; built by the chat view model. */
class VideoComposerActions(
    /** Null when the conversation cannot carry a live stream right now. */
    val createLiveTarget: (peer: String?, channel: String?) -> LiveVideoTarget?,
    val onLiveFinished: (peer: String?, channel: String?, path: String) -> Unit,
    val onVideoReady: (peer: String?, channel: String?, path: String) -> Unit
)

internal val LiveRed = Color(0xFFE53935)

fun formatVideoDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** Red "● LIVE 0:42" pill with a breathing dot. */
@Composable
fun LiveBadge(startedAtMs: Long?, modifier: Modifier = Modifier, compact: Boolean = false) {
    val transition = rememberInfiniteTransition(label = "liveDot")
    val dotAlpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "liveDotAlpha"
    )
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAtMs) {
        while (startedAtMs != null) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(LiveRed)
            .padding(horizontal = if (compact) 5.dp else 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            Modifier
                .size(if (compact) 5.dp else 6.dp)
                .alpha(dotAlpha)
                .background(Color.White, CircleShape)
        )
        Text(
            text = stringResource(R.string.video_live_badge),
            color = Color.White,
            fontSize = if (compact) 10.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
        if (startedAtMs != null) {
            Text(
                text = formatVideoDuration(now - startedAtMs),
                color = Color.White.copy(alpha = 0.9f),
                fontSize = if (compact) 10.sp else 12.sp,
                fontFamily = BitchatFontFamily
            )
        }
    }
}

/** Four-bar signal meter from recent pre-FEC packet loss. */
@Composable
fun SignalBars(loss: Double, modifier: Modifier = Modifier) {
    val level = when {
        loss < 0.05 -> 4
        loss < 0.15 -> 3
        loss < 0.30 -> 2
        else -> 1
    }
    val color = when (level) {
        4, 3 -> Color.White
        2 -> Color(0xFFFFC107)
        else -> LiveRed
    }
    Canvas(modifier = modifier.size(width = 16.dp, height = 12.dp)) {
        val barWidth = size.width / 5.5f
        for (bar in 0 until 4) {
            val barHeight = size.height * (bar + 1) / 4f
            drawRoundRect(
                color = if (bar < level) color else Color.White.copy(alpha = 0.3f),
                topLeft = Offset(bar * barWidth * 1.5f, size.height - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(1.5f, 1.5f)
            )
        }
    }
}

/** Draws the latest decoded picture of a live stream, or a joining placeholder. */
@Composable
fun LiveVideoFrameView(
    messageID: String,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    val context = LocalContext.current
    val manager = remember(context) { LiveVideoManager.getInstance(context) }
    val live by manager.live.collectAsState()
    val frames = remember(messageID, live.containsKey(messageID)) { manager.frames(messageID) }
    val frame = frames?.collectAsState()?.value
    Box(modifier = modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        if (frame != null) {
            // Reading the index keeps a reused Bitmap redrawing on every new frame.
            val index = frame.index
            Image(
                bitmap = remember(index) { frame.bitmap.asImageBitmap() },
                contentDescription = stringResource(R.string.cd_live_video),
                contentScale = contentScale,
                filterQuality = FilterQuality.Low,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.video_joining_live),
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/** First-frame thumbnail and duration of a local MP4, cached in memory. */
data class VideoPreview(val thumbnail: Bitmap?, val durationMs: Long, val width: Int, val height: Int)

object VideoPreviews {
    private val cache = object : LruCache<String, VideoPreview>(24) {}

    suspend fun load(path: String): VideoPreview? = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.isFile || file.length() == 0L) return@withContext null
        val key = "$path:${file.lastModified()}:${file.length()}"
        cache.get(key)?.let { return@withContext it }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(path)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            var width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) width = height.also { height = width }
            // A frame a little in avoids black fade-ins; fall back to the first sync frame.
            val frame = retriever.getFrameAtTime(minOf(500_000L, duration * 500L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.frameAtTime
            VideoPreview(frame, duration, width, height).also { cache.put(key, it) }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }
}

@Composable
fun rememberVideoPreview(path: String): VideoPreview? {
    val preview by produceState<VideoPreview?>(initialValue = null, path) {
        value = VideoPreviews.load(path)
    }
    return preview
}

/** Hides system bars and keeps the screen awake while a full-screen video dialog is shown. */
@Composable
fun ImmersiveDialogEffect() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        window?.let {
            WindowCompat.setDecorFitsSystemWindows(it, false)
            it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            WindowCompat.getInsetsController(it, view).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}

/** Small translucent capsule used for overlay controls on top of video. */
@Composable
fun VideoOverlayPill(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.45f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) { content() }
}
