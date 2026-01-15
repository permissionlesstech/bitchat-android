package com.bitchat.android.core.ui.utils

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

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
    
    // API < 31 Fallback: Generate a blurred bitmap if provided
    val fallbackBlurredBitmap = remember(blurBitmap) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && blurBitmap != null) {
            // Perform a fast "Downscale Blur" (Box/Bilinear Filter simulation)
            // Scale down to ~5% size then let the canvas upscale it with filtering
            try {
                val w = (blurBitmap.width * 0.05f).toInt().coerceAtLeast(1)
                val h = (blurBitmap.height * 0.05f).toInt().coerceAtLeast(1)
                val small = Bitmap.createScaledBitmap(blurBitmap, w, h, true)
                small.asImageBitmap()
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
            if (fallbackBlurredBitmap != null && revealProgress < 1f) {
                // Instead of drawing the sharp content, draw the blurred bitmap
                // filling the drawing area
                drawImage(
                    image = fallbackBlurredBitmap,
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                    dstOffset = IntOffset.Zero,
                    filterQuality = androidx.compose.ui.graphics.FilterQuality.Low // Ensure filtering happens
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
