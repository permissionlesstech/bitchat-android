package com.bitchat.android.util

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.min

/** Reference design width (dp) the UI was designed against — matches a standard phone viewport. */
private const val BASE_WIDTH = 360f

/** Clamp scaling so tiny/huge screens (foldables, tablets) don't over- or under-scale content. */
private const val MIN_SCALE = 0.85f
private const val MAX_SCALE = 1.30f

/**
 * Returns the current width-based scale factor, clamped to [MIN_SCALE, MAX_SCALE].
 * Uses the smaller of width/height dp so rotation to landscape doesn't blow up the scale.
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun rememberUiScale(): Float {
    val configuration = LocalConfiguration.current
    val shortestDp = min(configuration.screenWidthDp, configuration.screenHeightDp)
    return remember(shortestDp) {
        (shortestDp / BASE_WIDTH).coerceIn(MIN_SCALE, MAX_SCALE)
    }
}

/** Scales a dp value relative to [BASE_WIDTH], clamped to avoid extreme scaling on outlier screens. */
@Composable
fun Float.sdp(): Dp = (this * rememberUiScale()).dp

/** Scales a sp value relative to [BASE_WIDTH], clamped to avoid extreme scaling on outlier screens. */
@Composable
fun Float.ssp(): TextUnit = (this * rememberUiScale()).sp

/** Int convenience overload — lets you write `16.sdp()` directly. */
@Composable
fun Int.sdp(): Dp = this.toFloat().sdp()

@Composable
fun Int.ssp(): TextUnit = this.toFloat().ssp()

/** Double convenience overload — covers decimal literals like `8.5.sdp()`, which Kotlin infers as Double by default. */
@Composable
fun Double.sdp(): Dp = this.toFloat().sdp()

@Composable
fun Double.ssp(): TextUnit = this.toFloat().ssp()