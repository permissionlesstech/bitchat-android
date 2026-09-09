package com.bitchat.android.features.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.dp
import kotlin.math.min

@Composable
fun CyberpunkVisualizer(amplitude: Int, color: Color, modifier: Modifier = Modifier) {
    val norm = min(1f, amplitude / 20_000f)
    val heightFrac by animateFloatAsState(
        targetValue = 0.1f + 0.9f * norm,
        animationSpec = tween(120, easing = LinearEasing), label = "amp"
    )
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
    ) {
        val w = size.width
        val h = size.height
        val barCount = 24
        val gap = 6f
        val bw = (w - gap * (barCount - 1)) / barCount
        for (i in 0 until barCount) {
            val phase = (i.toFloat() / barCount)
            val barH = (0.2f + heightFrac * (0.8f * (0.5f + 0.5f * kotlin.math.sin(phase * Math.PI * 2).toFloat()))) * h
            val x = i * (bw + gap)
            val y = (h - barH) / 2f
            drawRect(color.copy(alpha = 0.85f), topLeft = androidx.compose.ui.geometry.Offset(x, y), size = androidx.compose.ui.geometry.Size(bw, barH))
        }
    }
}
