package com.bitchat.android.ui.media

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Draws an image progressively, revealing it block-by-block based on progress [0f..1f].
 * blocksX * blocksY defines the grid density; higher numbers look more "modem-era".
 */
@Composable
fun BlockRevealImage(
    bitmap: ImageBitmap,
    progress: Float,
    blocksX: Int = 24,
    blocksY: Int = 16,
    modifier: Modifier = Modifier
) {
    val frac = progress.coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxWidth()) {
        drawProgressive(bitmap, frac, blocksX, blocksY)
    }
}

private fun DrawScope.drawProgressive(
    bitmap: ImageBitmap,
    progress: Float,
    blocksX: Int,
    blocksY: Int
) {
    val canvasW = size.width
    val canvasH = size.height
    if (canvasW <= 0f || canvasH <= 0f) return

    val totalBlocks = (blocksX * blocksY).coerceAtLeast(1)
    val toShow = (totalBlocks * progress).toInt().coerceIn(0, totalBlocks)
    if (toShow <= 0) return

    val imgW = bitmap.width
    val imgH = bitmap.height
    if (imgW <= 0 || imgH <= 0) return

    // Compute scaled destination rect maintaining aspect fit
    val canvasRatio = canvasW / canvasH
    val imageRatio = imgW.toFloat() / imgH.toFloat()
    val dstW: Float
    val dstH: Float
    if (imageRatio >= canvasRatio) {
        dstW = canvasW
        dstH = canvasW / imageRatio
    } else {
        dstH = canvasH
        dstW = canvasH * imageRatio
    }
    val left = 0f
    val top = (canvasH - dstH) / 2f

    // Precompute integer edges to avoid 1px gaps due to rounding
    val xDstEdges = IntArray(blocksX + 1) { i -> (left + (dstW * i / blocksX)).toInt().coerceAtLeast(0) }
    val yDstEdges = IntArray(blocksY + 1) { i -> (top + (dstH * i / blocksY)).toInt().coerceAtLeast(0) }
    val xSrcEdges = IntArray(blocksX + 1) { i -> (imgW * i / blocksX) }
    val ySrcEdges = IntArray(blocksY + 1) { i -> (imgH * i / blocksY) }

    var shown = 0
    outer@ for (by in 0 until blocksY) {
        for (bx in 0 until blocksX) {
            if (shown >= toShow) break@outer
            val sx = xSrcEdges[bx]
            val sy = ySrcEdges[by]
            val sw = xSrcEdges[bx + 1] - xSrcEdges[bx]
            val sh = ySrcEdges[by + 1] - ySrcEdges[by]
            val dx = xDstEdges[bx]
            val dy = yDstEdges[by]
            val dw = xDstEdges[bx + 1] - xDstEdges[bx]
            val dh = yDstEdges[by + 1] - yDstEdges[by]

            drawImage(
                image = bitmap,
                srcOffset = IntOffset(sx, sy),
                srcSize = IntSize(sw, sh),
                dstOffset = IntOffset(dx, dy),
                dstSize = IntSize(dw.coerceAtLeast(1), dh.coerceAtLeast(1)),
                alpha = 1f
            )
            shown++
        }
    }
}
