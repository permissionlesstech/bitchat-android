package com.bitchat.android.ui.media

import com.bitchat.android.ui.theme.BitchatFontFamily
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import android.media.MediaPlayer
import androidx.compose.foundation.layout.*
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import com.bitchat.android.R

@Composable
fun VoiceNotePlayer(
    path: String,
    modifier: Modifier = Modifier,
    progressOverride: Float? = null,
    progressColor: Color? = null,
    isLive: Boolean = false
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isPrepared by remember { mutableStateOf(false) }
    var isError by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableIntStateOf(0) }
    val player = remember { MediaPlayer() }

    // Seek function - position is a fraction from 0.0 to 1.0
    val seekTo: (Float) -> Unit = { position ->
        if (isPrepared && !isError && !isLive && progressOverride == null && durationMs > 0) {
            val seekMs = (position * durationMs).toInt().coerceIn(0, durationMs)
            try {
                player.seekTo(seekMs)
                progress = position  // Update progress immediately for UI responsiveness
            } catch (_: Exception) {}
        }
    }

    LaunchedEffect(path, isLive) {
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
            if (isLive) return@LaunchedEffect
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
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Disable play/pause while showing send progress override (optional UX choice)
        val controlsEnabled = isPrepared && !isError && !isLive && progressOverride == null
        FilledTonalIconButton(onClick = { if (controlsEnabled) isPlaying = !isPlaying }, enabled = controlsEnabled, modifier = Modifier.size(28.dp)) {
            Icon(
                imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (isPlaying) R.string.cd_pause_voice else R.string.cd_play_voice)
            )
        }
        val progressBarColor = progressColor ?: MaterialTheme.colorScheme.primary
        com.bitchat.android.ui.media.WaveformPreview(
            modifier = Modifier
                .height(24.dp)
                .weight(1f)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            path = path,
            sendProgress = progressOverride,
            playbackProgress = if (progressOverride == null) progress else null,
            onSeek = if (controlsEnabled) seekTo else null,
            isLive = isLive,
            progressColor = progressBarColor
        )
        val locale = LocalConfiguration.current.locales[0]
        val durText = if (isError && !isLive) stringResource(R.string.voice_unavailable) else if (durationMs > 0) String.format(locale, "%02d:%02d", (durationMs / 1000) / 60, (durationMs / 1000) % 60) else "--:--"
        Text(text = durText, fontFamily = BitchatFontFamily, fontSize = 12.sp)
    }
}

