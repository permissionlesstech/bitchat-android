package com.bitchat.watch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import com.bitchat.watch.ui.theme.LocalBitchatPalette

enum class NoiseSessionUiState { Idle, Handshaking, Established }

/**
 * Noise session lock icon, same visual language as the phone's NoiseSessionIcon: quiet grey
 * open lock when idle, orange open lock with a soft pulse while the handshake is in flight,
 * green closed lock once established. Tint and glyph transitions land together.
 */
@Composable
fun NoiseLockIcon(
    state: NoiseSessionUiState,
    modifier: Modifier = Modifier,
    size: androidx.compose.ui.unit.Dp = 13.dp
) {
    val palette = LocalBitchatPalette.current
    val colorScheme = MaterialTheme.colorScheme

    val targetTint = when (state) {
        NoiseSessionUiState.Handshaking -> palette.accentOrange
        NoiseSessionUiState.Established -> colorScheme.primary
        NoiseSessionUiState.Idle -> colorScheme.onSurfaceVariant
    }
    val tint by animateColorAsState(
        targetValue = targetTint,
        animationSpec = tween(480, easing = FastOutSlowInEasing),
        label = "noiseLockTint"
    )

    val pulseAlpha = if (state == NoiseSessionUiState.Handshaking) {
        val transition = rememberInfiniteTransition(label = "noiseLockPulse")
        transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(600, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "noiseLockPulseAlpha"
        ).value
    } else 1f

    Icon(
        imageVector = if (state == NoiseSessionUiState.Established) Icons.Filled.Lock
        else Icons.Filled.LockOpen,
        contentDescription = when (state) {
            NoiseSessionUiState.Handshaking -> "handshake in progress"
            NoiseSessionUiState.Established -> "encrypted"
            NoiseSessionUiState.Idle -> "not encrypted yet"
        },
        tint = tint,
        modifier = modifier
            .size(size)
            .alpha(pulseAlpha)
    )
}
