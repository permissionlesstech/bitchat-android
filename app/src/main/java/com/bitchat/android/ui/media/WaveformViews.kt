package com.bitchat.android.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bitchat.android.features.voice.AudioWaveformExtractor
import com.bitchat.android.features.voice.VoiceWaveformCache
import com.bitchat.android.features.voice.resampleWave

@Composable
fun ScrollingWaveformRecorder(
    modifier: Modifier = Modifier,
    currentAmplitude: Float,
    samples: SnapshotStateList<Float>,
    maxSamples: Int = 120
) {
    // Append samples at a fixed cadence while visible
    val latestAmp by rememberUpdatedState(currentAmplitude)
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { _: Long -> }
            val v = latestAmp.coerceIn(0f, 1f)
            samples.add(v)
            val overflow = samples.size - maxSamples
            if (overflow > 0) repeat(overflow) { if (samples.isNotEmpty()) samples.removeAt(0) }
            kotlinx.coroutines.delay(80)
        }
    }
    WaveformCanvas(modifier = modifier, samples = samples, fillProgress = 1f, baseColor = Color(0xFF444444), fillColor = Color(0xFF00FF7F))
}

@Composable
fun WaveformPreview(
    modifier: Modifier = Modifier,
    path: String,
    sendProgress: Float?,
    playbackProgress: Float?,
    onLoaded: ((FloatArray) -> Unit)? = null,
    onSeek: ((Float) -> Unit)? = null
) {
    val cached = remember(path) { VoiceWaveformCache.get(path) }
    val stateSamples = remember { mutableStateListOf<Float>() }
    val progress = (sendProgress ?: playbackProgress)?.coerceIn(0f, 1f) ?: 0f
    LaunchedEffect(cached) {
        if (cached != null) {
            val normalized = if (cached.size != 120) resampleWave(cached, 120) else cached
            stateSamples.clear(); stateSamples.addAll(normalized.toList())
        } else {
            AudioWaveformExtractor.extractAsync(path, sampleCount = 120) { arr ->
                if (arr != null) {
                    VoiceWaveformCache.put(path, arr)
                    stateSamples.clear(); stateSamples.addAll(arr.toList())
                    onLoaded?.invoke(arr)
                }
            }
        }
    }
    WaveformCanvas(
        modifier = modifier,
        samples = stateSamples,
        fillProgress = if (stateSamples.isEmpty()) 0f else progress,
        baseColor = Color(0x2200FF7F),
        fillColor = when {
            sendProgress != null -> Color(0xFF1E88E5) // blue while sending
            else -> Color(0xFF00C851) // green during playback
        },
        onSeek = onSeek
    )
}

@Composable
private fun WaveformCanvas(
    modifier: Modifier,
    samples: List<Float>,
    fillProgress: Float,
    baseColor: Color,
    fillColor: Color,
    onSeek: ((Float) -> Unit)? = null
) {
    val seekModifier = if (onSeek != null) {
        modifier.pointerInput(onSeek) {
            detectTapGestures { offset ->
                // Calculate the seek position as a fraction (0.0 to 1.0)
                val position = offset.x / size.width.toFloat()
                val clampedPosition = position.coerceIn(0f, 1f)
                onSeek(clampedPosition)
            }
        }
    } else {
        modifier
    }

    Canvas(modifier = seekModifier.fillMaxWidth()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val n = samples.size
        if (n <= 0) return@Canvas
        val stepX = w / n
        val midY = h / 2f
        val radius = 2.dp.toPx()
        val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        val filledUntil = (n * fillProgress).toInt()
        for (i in 0 until n) {
            val amp = samples[i].coerceIn(0f, 1f)
            val lineH = (amp * (h * 0.8f)).coerceAtLeast(2f)
            val x = i * stepX + stepX / 2f
            val yTop = midY - lineH / 2f
            val yBot = midY + lineH / 2f
            drawLine(
                color = if (i <= filledUntil) fillColor else baseColor,
                start = Offset(x, yTop),
                end = Offset(x, yBot),
                strokeWidth = stroke.width,
                cap = StrokeCap.Round
            )
        }
    }
}
