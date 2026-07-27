package com.bitchat.android.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale

/**
 * Press feedback for the app's icon buttons.
 *
 * Terminal-style chrome has no elevation and no fills to lean on, so a Material ripple has almost
 * nothing to show. A brief scale dip is legible on any background and reads as physical. Springs
 * rather than tweens, so releasing overshoots very slightly instead of stopping dead.
 */
@Composable
fun rememberPressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.86f
): Float {
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "pressScale"
    )
    return scale
}

/**
 * Convenience wrapper for the common case: a clickable that scales while held.
 *
 * Returns the modifier chain to apply, having disabled the default indication — the scale *is* the
 * indication, and a ripple underneath it just muddies the edges of these small glyphs.
 */
@Composable
fun Modifier.pressScaleClickable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    pressedScale: Float = 0.86f
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource, pressedScale)
    return this
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick
        )
        .scale(scale)
}
