package com.bitchat.android.core.ui.utils

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Region
import android.graphics.Shader
import androidx.compose.animation.core.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.random.Random

/**
 *
 * @param isOn Whether the spoiler is active (content hidden).
 * @param onReveal Called when the user taps the spoiler to reveal the content.
 */
fun Modifier.spoiler(
    isOn: Boolean,
    onReveal: () -> Unit
): Modifier = composed {
    val spoilerState = remember { SpoilerEffectState() }
    
    // Animate the shader offset (Time-based constant movement)
    val infiniteTransition = rememberInfiniteTransition(label = "spoiler_driver")
    val time by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "time"
    )

    // Logic for Reveal Transition
    var revealState by remember { 
        mutableStateOf(if (isOn) RevealState.Hidden else RevealState.Revealed)
    }
    var lastIsOn by remember { mutableStateOf(isOn) }
    var touchPosition by remember { mutableStateOf(Offset.Zero) }

    if (isOn != lastIsOn) {
        if (!isOn) {
            revealState = RevealState.Revealing(System.currentTimeMillis(), touchPosition)
        } else {
            revealState = RevealState.Hidden
        }
        lastIsOn = isOn
    }

    val blurRadius by animateDpAsState(
        targetValue = if (revealState is RevealState.Hidden) 46.dp else 0.dp,
        animationSpec = tween(350),
        label = "blur"
    )

    this
        .pointerInput(isOn) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                touchPosition = down.position
                val up = waitForUpOrCancellation()
                if (up != null && isOn) {
                    onReveal() // This will flip isOn -> false -> trigger reveal anim
                }
            }
        }
        .then(
            Modifier.blur(blurRadius)
        )
        .drawWithContent {
            drawContent()
            
            // Only draw if we have something to hide
            if (revealState != RevealState.Revealed) {
                drawIntoCanvas { canvas ->
                    spoilerState.draw(canvas.nativeCanvas, size, time, revealState)
                }
            }
        }
}

private sealed class RevealState {
    object Hidden : RevealState()
    data class Revealing(val start: Long, val origin: Offset) : RevealState()
    object Revealed : RevealState()
}

/**
 * Efficient state holder using a cached BitmapShader pattern.
 */
private class SpoilerEffectState {
    
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val shader: BitmapShader
    private val matrix = Matrix()
    private val ripplePath = Path()
    
    init {
        // Generate a small noise bitmap once
        // Size 128x128 is usually sufficient for repeating noise
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        
        // Fill with randomized "sand" (white dots with varying alpha)
        for (i in 0 until size) {
            for (j in 0 until size) {
                if (Random.nextFloat() > 0.85f) { // ~15% fill rate
                    val alpha = Random.nextInt(50, 255)
                    // White color with random alpha
                    bitmap.setPixel(i, j, Color.argb(alpha, 255, 255, 255))
                } else {
                    bitmap.setPixel(i, j, Color.TRANSPARENT)
                }
            }
        }
        
        shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        paint.shader = shader
    }
    
    fun draw(
        canvas: android.graphics.Canvas,
        size: Size,
        time: Float,
        revealState: RevealState
    ) {
        // 1. Handle Ripple Reveal Clipping
        if (revealState is RevealState.Revealing) {
            val elapsed = System.currentTimeMillis() - revealState.start
            val progress = (elapsed / 400f).coerceIn(0f, 1f)
            val maxRadius = max(size.width, size.height) * 1.5f
            val radius = maxRadius * progress
            
            ripplePath.reset()
            ripplePath.addCircle(revealState.origin.x, revealState.origin.y, radius, Path.Direction.CW)
            
            try {
                canvas.clipPath(ripplePath, Region.Op.DIFFERENCE)
            } catch (e: Exception) {
                // Ignore clip failures
            }
        }
        
        // 2. Draw Background Overlay (Dim)
        // This ensures white sparks are visible on light content
        canvas.drawColor(Color.argb(80, 0, 0, 0))
        
        // 3. Update Shader Matrix for "Swarming" Animation
        // Move diagonally over time
        val offsetX = time * size.width * 0.5f // Move half screen width over cycle
        val offsetY = time * size.height * 0.2f // Move slightly vertical
        
        matrix.reset()
        matrix.setTranslate(offsetX, offsetY)
        // Optional: Scale slightly to make texture independent of bitmap resolution
        // But 1:1 pixel mapping usually looks crispest for noise
        
        shader.setLocalMatrix(matrix)
        
        // 4. Draw the Noise Shader
        canvas.drawRect(0f, 0f, size.width, size.height, paint)
    }
}
