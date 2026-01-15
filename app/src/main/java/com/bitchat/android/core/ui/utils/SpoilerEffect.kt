package com.bitchat.android.core.ui.utils

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Applies a spoiler effect to the content.
 *
 * @param isVisible Whether the spoiler (obscuration) is active.
 * @param blurBitmap Optional bitmap to be blurred and displayed for API < 31.
 *                   If null, a dimming overlay is used as fallback.
 * @param onReveal Called when the user taps to reveal the content.
 */
fun Modifier.spoiler(
    isVisible: Boolean,
    blurBitmap: Bitmap? = null,
    onReveal: () -> Unit
): Modifier = composed {
    val revealProgress by animateFloatAsState(
        targetValue = if (isVisible) 0f else 1f,
        animationSpec = tween(durationMillis = 400),
        label = "revealProgress"
    )

    // If fully revealed, just draw content to avoid overdraw/performance hit
    if (revealProgress == 1f) {
        return@composed this
    }

    // Icon Logic
    val iconPainter = rememberVectorPainter(Icons.Filled.TouchApp)
    val iconColor = MaterialTheme.colorScheme.onSurface
    
    // API < 31 Fallback: Generate a blurred bitmap shader if provided
    val fallbackShader = remember(blurBitmap) {
        // Keep user's debug flags as requested
        if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.S || true) && blurBitmap != null) {
            try {
                // 1. Scale down to a small size for performance (e.g. 64px)
                // This ensures the O(N) pixel loop is negligible even on low-end devices
                val scale = 64f / blurBitmap.width.coerceAtLeast(1)
                val w = (blurBitmap.width * scale).toInt().coerceAtLeast(1)
                val h = (blurBitmap.height * scale).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(blurBitmap, w, h, true)
                
                // 2. Blur the pixels manually (StackBlur/FastBlur algorithm)
                val blurred = fastBlur(small, 8) // Radius 8 on 64px image is very blurry
                
                // 3. Create a Shader from the result
                BitmapShader(blurred, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    this
        .clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null, // No ripple for the spoiler tap itself to keep it clean
            enabled = isVisible,
            onClick = onReveal
        )
        // Order matters:
        // 1. drawWithContent (Outer wrapper) -> Draws overlays ON TOP of everything else
        // 2. graphicsLayer (Inner wrapper) -> Blurs the content (Image)
        .drawWithContent {
            // API < 31 Fallback Logic
            if (fallbackShader != null && revealProgress < 1f) {
                // Draw the blurred shader covering the whole area
                drawRect(
                    brush = ShaderBrush(fallbackShader),
                    alpha = 1f
                )
            } else {
                drawContent() // Normal draw
            }

            // Calculate overlay opacity (fades out as revealed)
            val overlayAlpha = (1f - revealProgress).coerceIn(0f, 1f)
            
            if (overlayAlpha > 0f) {
                // 1. Draw Dimming Overlay
                // Necessary to ensure icon visibility against light images
                drawRect(
                    color = Color.Black.copy(alpha = 0.4f * overlayAlpha)
                )

                // 2. Draw "TouchApp" Icon
                val iconSize = 48.dp.toPx()
                val iconX = (size.width - iconSize) / 2
                val iconY = (size.height - iconSize) / 2
                
                translate(left = iconX, top = iconY) {
                    with(iconPainter) {
                        draw(
                            size = Size(iconSize, iconSize),
                            alpha = overlayAlpha,
                            colorFilter = ColorFilter.tint(iconColor)
                        )
                    }
                }
            }
        }
        // Blur Implementation (API 31+)
        .graphicsLayer {
            // Keep user's debug flag as requested (&& false)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && false) {
                val blurRadius = (40f * (1f - revealProgress)).coerceAtLeast(0f) * density
                
                if (blurRadius > 0) {
                     renderEffect = android.graphics.RenderEffect
                        .createBlurEffect(
                            blurRadius,
                            blurRadius,
                            android.graphics.Shader.TileMode.CLAMP
                        )
                        .asComposeRenderEffect()
                }
            }
        }
}

/**
 * A fast approximation of Gaussian Blur (Stack Blur).
 * Efficient enough to run on the main thread for small bitmaps.
 */
private fun fastBlur(sentBitmap: Bitmap, radius: Int): Bitmap {
    val bitmap = sentBitmap.copy(sentBitmap.config ?: Bitmap.Config.ARGB_8888, true)
    if (radius < 1) return (null ?: sentBitmap)

    val w = bitmap.width
    val h = bitmap.height
    val pix = IntArray(w * h)
    bitmap.getPixels(pix, 0, w, 0, 0, w, h)

    val wm = w - 1
    val hm = h - 1
    val wh = w * h
    val div = radius + radius + 1

    val r = IntArray(wh)
    val g = IntArray(wh)
    val b = IntArray(wh)
    var rsum: Int
    var gsum: Int
    var bsum: Int
    var x: Int
    var y: Int
    var i: Int
    var p: Int
    var yp: Int
    var yi: Int
    val vmin = IntArray(Math.max(w, h))

    var divsum = (div + 1) shr 1
    divsum *= divsum
    val dv = IntArray(256 * divsum)
    for (i in 0 until 256 * divsum) {
        dv[i] = (i / divsum)
    }

    yi = 0
    var yw = 0

    val stack = Array(div) { IntArray(3) }
    var stackpointer: Int
    var stackstart: Int
    var sir: IntArray
    var rbs: Int
    var r1 = radius + 1
    var routsum: Int
    var goutsum: Int
    var boutsum: Int
    var rinsum: Int
    var ginsum: Int
    var binsum: Int

    for (y in 0 until h) {
        rinsum = 0
        ginsum = 0
        binsum = 0
        routsum = 0
        goutsum = 0
        boutsum = 0
        rsum = 0
        gsum = 0
        bsum = 0
        for (i in -radius..radius) {
            p = pix[yi + Math.min(wm, Math.max(i, 0))]
            sir = stack[i + radius]
            sir[0] = (p and 0xff0000) shr 16
            sir[1] = (p and 0x00ff00) shr 8
            sir[2] = (p and 0x0000ff)
            rbs = r1 - Math.abs(i)
            rsum += sir[0] * rbs
            gsum += sir[1] * rbs
            bsum += sir[2] * rbs
            if (i > 0) {
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
            } else {
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
            }
        }
        stackpointer = radius

        for (x in 0 until w) {
            r[yi] = dv[rsum]
            g[yi] = dv[gsum]
            b[yi] = dv[bsum]

            rsum -= routsum
            gsum -= goutsum
            bsum -= boutsum

            stackstart = stackpointer - radius + div
            sir = stack[stackstart % div]

            routsum -= sir[0]
            goutsum -= sir[1]
            boutsum -= sir[2]

            if (y == 0) {
                vmin[x] = Math.min(x + radius + 1, wm)
            }
            p = pix[yw + vmin[x]]

            sir[0] = (p and 0xff0000) shr 16
            sir[1] = (p and 0x00ff00) shr 8
            sir[2] = (p and 0x0000ff)

            rinsum += sir[0]
            ginsum += sir[1]
            binsum += sir[2]

            rsum += rinsum
            gsum += ginsum
            bsum += binsum

            stackpointer = (stackpointer + 1) % div
            sir = stack[(stackpointer) % div]

            routsum += sir[0]
            goutsum += sir[1]
            boutsum += sir[2]

            rinsum -= sir[0]
            ginsum -= sir[1]
            binsum -= sir[2]

            yi++
        }
        yw += w
    }

    for (x in 0 until w) {
        rinsum = 0
        ginsum = 0
        binsum = 0
        routsum = 0
        goutsum = 0
        boutsum = 0
        rsum = 0
        gsum = 0
        bsum = 0
        yp = -radius * w
        for (i in -radius..radius) {
            yi = Math.max(0, yp) + x
            sir = stack[i + radius]
            sir[0] = r[yi]
            sir[1] = g[yi]
            sir[2] = b[yi]
            rbs = r1 - Math.abs(i)
            rsum += r[yi] * rbs
            gsum += g[yi] * rbs
            bsum += b[yi] * rbs
            if (i > 0) {
                rinsum += sir[0]
                ginsum += sir[1]
                binsum += sir[2]
            } else {
                routsum += sir[0]
                goutsum += sir[1]
                boutsum += sir[2]
            }
            if (i < hm) {
                yp += w
            }
        }
        yi = x
        stackpointer = radius
        for (y in 0 until h) {
            pix[yi] = (0xff000000.toInt() or (dv[rsum] shl 16) or (dv[gsum] shl 8) or dv[bsum])

            rsum -= routsum
            gsum -= goutsum
            bsum -= boutsum

            stackstart = stackpointer - radius + div
            sir = stack[stackstart % div]

            routsum -= sir[0]
            goutsum -= sir[1]
            boutsum -= sir[2]

            if (x == 0) {
                vmin[y] = Math.min(y + r1, hm) * w
            }
            p = x + vmin[y]

            sir[0] = r[p]
            sir[1] = g[p]
            sir[2] = b[p]

            rinsum += sir[0]
            ginsum += sir[1]
            binsum += sir[2]

            rsum += rinsum
            gsum += ginsum
            bsum += binsum

            stackpointer = (stackpointer + 1) % div
            sir = stack[stackpointer]

            routsum += sir[0]
            goutsum += sir[1]
            boutsum += sir[2]

            rinsum -= sir[0]
            ginsum -= sir[1]
            binsum -= sir[2]

            yi += w
        }
    }

    bitmap.setPixels(pix, 0, w, 0, 0, w, h)
    return bitmap
}
