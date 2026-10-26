package com.bitchat.android.ui.media

import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bitchat.android.R
import com.bitchat.android.features.video.LiveVideoManager
import com.bitchat.android.ui.theme.BitchatFontFamily
import kotlinx.coroutines.delay

private val fullScreenDialog = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
    dismissOnClickOutside = false
)

private val topScrim = Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.65f), Color.Transparent))
private val bottomScrim = Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.65f)))

/**
 * Distraction-free live viewer: just the stream, with controls that fade away. Tap toggles
 * the controls, double-tap switches between fit and fill.
 */
@Composable
fun FullScreenLiveVideoViewer(
    messageID: String,
    senderNickname: String,
    ended: Boolean,
    onReplay: () -> Unit,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val manager = remember(context) { LiveVideoManager.getInstance(context) }
    val live by manager.live.collectAsState()
    val muted by manager.mutedStreams.collectAsState()
    val info = live[messageID]
    var controlsVisible by remember { mutableStateOf(true) }
    var fill by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    LaunchedEffect(controlsVisible, interaction, ended) {
        if (controlsVisible && !ended) {
            delay(3_500)
            controlsVisible = false
        }
    }

    Dialog(onDismissRequest = onClose, properties = fullScreenDialog) {
        ImmersiveDialogEffect()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { fill = !fill; interaction++ }
                    )
                }
        ) {
            LiveVideoFrameView(
                messageID = messageID,
                contentScale = if (fill) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            if ((info?.frozen == true || info?.reconnecting == true) && !ended) {
                Row(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(
                            if (info.reconnecting) R.string.video_reconnecting else R.string.video_weak_signal_reconnecting
                        ),
                        color = Color.White,
                        fontSize = 13.sp
                    )
                }
            }

            if (ended) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .background(Color.Black.copy(alpha = 0.7f), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.video_live_ended),
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.video_live_ended_by, senderNickname),
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(
                        onClick = onReplay,
                        colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color.Black)
                    ) {
                        Icon(Icons.Filled.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.video_watch_replay))
                    }
                }
            }

            AnimatedVisibility(
                visible = controlsVisible || ended,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topScrim)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close), tint = Color.White)
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = senderNickname,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (info != null) {
                            Text(
                                text = stringResource(R.string.video_live_rate, info.receivedKbps),
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontFamily = BitchatFontFamily
                            )
                        }
                    }
                    if (!ended) {
                        LiveBadge(startedAtMs = info?.startedAtMs)
                        Spacer(Modifier.width(10.dp))
                        SignalBars(info?.loss ?: 0.0)
                        Spacer(Modifier.width(8.dp))
                    }
                }
            }

            AnimatedVisibility(
                visible = (controlsVisible && info?.hasAudio == true) && !ended,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bottomScrim)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    val isMuted = messageID in muted
                    IconButton(
                        onClick = {
                            manager.setMuted(messageID, !isMuted)
                            interaction++
                        },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.4f), CircleShape)
                    ) {
                        Icon(
                            imageVector = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = stringResource(if (isMuted) R.string.cd_unmute else R.string.cd_mute),
                            tint = Color.White
                        )
                    }
                }
            }
        }
    }
}

/** Full-screen player for a finished video, with scrubbing and auto-hiding controls. */
@Composable
fun FullScreenVideoPlayer(path: String, title: String, onClose: () -> Unit) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var prepared by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf(false) }
    var completed by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var aspect by remember { mutableFloatStateOf(0f) }
    var scrubbing by remember { mutableStateOf<Float?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var interaction by remember { mutableIntStateOf(0) }

    DisposableEffect(path) {
        val mediaPlayer = MediaPlayer()
        player = mediaPlayer
        mediaPlayer.setOnPreparedListener {
            prepared = true
            durationMs = it.duration.toLong().coerceAtLeast(0L)
            it.start()
            playing = true
        }
        mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
            if (width > 0 && height > 0) aspect = width.toFloat() / height
        }
        mediaPlayer.setOnCompletionListener {
            playing = false
            completed = true
            controlsVisible = true
            positionMs = durationMs
        }
        mediaPlayer.setOnErrorListener { _, _, _ ->
            failed = true
            true
        }
        runCatching {
            mediaPlayer.setDataSource(path)
            mediaPlayer.prepareAsync()
        }.onFailure { failed = true }
        onDispose {
            runCatching { mediaPlayer.stop() }
            mediaPlayer.release()
            player = null
        }
    }

    LaunchedEffect(playing) {
        while (playing) {
            player?.let { if (scrubbing == null) positionMs = it.currentPosition.toLong() }
            delay(200)
        }
    }
    LaunchedEffect(controlsVisible, playing, interaction) {
        if (controlsVisible && playing) {
            delay(2_500)
            controlsVisible = false
        }
    }

    fun togglePlay() {
        val mediaPlayer = player ?: return
        if (!prepared) return
        if (playing) {
            mediaPlayer.pause()
            playing = false
        } else {
            if (completed) {
                mediaPlayer.seekTo(0)
                completed = false
            }
            mediaPlayer.start()
            playing = true
        }
        interaction++
    }

    Dialog(onDismissRequest = onClose, properties = fullScreenDialog) {
        ImmersiveDialogEffect()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) { detectTapGestures(onTap = { controlsVisible = !controlsVisible }) },
            contentAlignment = Alignment.Center
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            private var surface: Surface? = null
                            override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                                surface = Surface(texture).also { player?.setSurface(it) }
                            }
                            override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                            override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                                runCatching { player?.setSurface(null) }
                                surface?.release()
                                surface = null
                                return true
                            }
                            override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                        }
                    }
                },
                // Largest box of the video's aspect ratio that fits the screen (letterboxed).
                modifier = if (aspect > 0f) Modifier.aspectRatio(aspect) else Modifier.size(1.dp)
            )

            if (failed) {
                Text(stringResource(R.string.video_unavailable), color = Color.White.copy(alpha = 0.8f))
            } else if (!prepared) {
                CircularProgressIndicator(color = Color.White)
            }

            AnimatedVisibility(
                visible = controlsVisible && prepared && !failed,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                        .clickable { togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = when {
                            completed -> Icons.Filled.Replay
                            playing -> Icons.Filled.Pause
                            else -> Icons.Filled.PlayArrow
                        },
                        contentDescription = stringResource(if (playing) R.string.cd_pause_video else R.string.cd_play_video),
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topScrim)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_close), tint = Color.White)
                    }
                    Text(
                        text = title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            AnimatedVisibility(
                visible = controlsVisible && prepared && !failed,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bottomScrim)
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val shown = scrubbing?.let { (it * durationMs).toLong() } ?: positionMs
                    Text(formatVideoDuration(shown), color = Color.White, fontSize = 12.sp, fontFamily = BitchatFontFamily)
                    Slider(
                        value = scrubbing ?: if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                        onValueChange = {
                            scrubbing = it
                            interaction++
                        },
                        onValueChangeFinished = {
                            scrubbing?.let { fraction ->
                                val target = (fraction * durationMs).toLong()
                                player?.seekTo(target.toInt())
                                positionMs = target
                                completed = false
                            }
                            scrubbing = null
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    Text(formatVideoDuration(durationMs), color = Color.White, fontSize = 12.sp, fontFamily = BitchatFontFamily)
                }
            }
        }
    }
}
