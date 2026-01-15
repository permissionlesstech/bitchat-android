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
import kotlin.random.Random

/**
 * Applies a spoiler effect to the content.
 *
 * @param isVisible Whether the spoiler (obscuration) is active.
 * @param onReveal Called when the user taps to reveal the content.
 */
fun Modifier.spoiler(
    isVisible: Boolean,
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

    val noiseShader = remember { createNoiseShader() }
    
    // Icon Logic
    val iconPainter = rememberVectorPainter(Icons.Filled.TouchApp)
    val iconColor = MaterialTheme.colorScheme.onSurface

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
            drawContent() // Calls the next modifier (graphicsLayer -> Image)

            // Calculate overlay opacity (fades out as revealed)
            val overlayAlpha = (1f - revealProgress).coerceIn(0f, 1f)
            
            if (overlayAlpha > 0f) {
                // 1. Draw Fallback Overlay (Noise/Scrim)
                // On API < 31, this is the primary hiding mechanism.
                // On API 31+, it adds texture to the blur.
                drawRect(
                    color = Color.Black.copy(alpha = 0.2f * overlayAlpha) // Dimming
                )
                
                // Draw Noise Texture
                drawRect(
                    brush = ShaderBrush(noiseShader),
                    alpha = 1f * overlayAlpha,
                    blendMode = androidx.compose.ui.graphics.BlendMode.Screen // Blend noise lightly
                )
                
                // 2. Stronger Scrim for API < 31 (since no blur)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    drawRect(
                        color = Color.LightGray.copy(alpha = 0.95f * overlayAlpha) // Almost opaque
                    )
                    // Re-apply noise on top of gray for texture
                    drawRect(
                        brush = ShaderBrush(noiseShader),
                        alpha = 0.3f * overlayAlpha
                    )
                }

                // 3. Draw "TouchApp" Icon
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
        // For API 31+, we can use RenderEffect/graphicsLayer to blur the content efficiently
        // This is applied "inside" the drawWithContent above.
        .graphicsLayer {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Blur radius fades out as we reveal (40.dp -> 0.dp)
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

private fun createNoiseShader(): Shader {
    val width = 128
    val height = 128
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    
    // Generate static noise
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        val alpha = Random.nextInt(255)
        // Grayscale noise
        val v = Random.nextInt(200, 256) // High value (whitish)
        pixels[i] = android.graphics.Color.argb(alpha, v, v, v)
    }
    bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    
    return BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
}
