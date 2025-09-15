package com.bitchat.android.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun RecordingProgressLine(
    modifier: Modifier = Modifier,
    elapsedMs: Long,
    lineColor: Color = Color(0xFF00FF7F),
    baseColor: Color = Color(0xFF444444)
) {
    Canvas(modifier = modifier.fillMaxWidth()) {
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        val midY = h / 2f
        // Base line across the width
        drawLine(
            color = baseColor,
            start = Offset(0f, midY),
            end = Offset(w, midY),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        // Moving head position based on time
        val speedPxPerMs = 0.25f // 0.25 px per ms -> ~250px per second
        val x = ((elapsedMs * speedPxPerMs) % w).coerceIn(0f, w)
        // Draw the progressed segment from 0 to x
        drawLine(
            color = lineColor,
            start = Offset(0f, midY),
            end = Offset(x, midY),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round
        )
        // Draw a small head indicator
        drawLine(
            color = lineColor,
            start = Offset(x, midY - 6.dp.toPx()),
            end = Offset(x, midY + 6.dp.toPx()),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
    }
}

