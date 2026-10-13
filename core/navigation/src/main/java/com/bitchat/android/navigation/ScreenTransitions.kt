package com.bitchat.android.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.LayoutDirection
import androidx.navigation3.ui.NavDisplay

/**
 * How screens move. Pushes and replaces slide in from the end; pops slide back
 * out, which is the motion predictive back previews. The one fade is the
 * onboarding-to-chat root swap, which is not a step deeper.
 *
 * The end is the right in a left-to-right layout and the left in a
 * right-to-left one, matching the mirrored back arrow and back gesture. The
 * slide offsets are physical, so the sign comes from the layout direction.
 */
object ScreenTransitions {
    /** BitchatMotion.EMPHASIZED_MS; :core:navigation cannot see the app theme yet. */
    const val DURATION_MS = 240

    private fun <T> spec() = tween<T>(DURATION_MS, easing = FastOutSlowInEasing)

    fun push(layoutDirection: LayoutDirection): ContentTransform =
        slideInHorizontally(spec()) { pushEnterOffset(it, layoutDirection) } togetherWith
            slideOutHorizontally(spec()) { pushExitOffset(it, layoutDirection) }

    fun pop(layoutDirection: LayoutDirection): ContentTransform =
        slideInHorizontally(spec()) { popEnterOffset(it, layoutDirection) } togetherWith
            slideOutHorizontally(spec()) { popExitOffset(it, layoutDirection) }

    /** +1 when the end is the physical right, -1 when it is the left. */
    private fun endSign(layoutDirection: LayoutDirection) =
        if (layoutDirection == LayoutDirection.Ltr) 1 else -1

    /** Where a pushed screen starts: a full width off the end edge. */
    fun pushEnterOffset(fullWidth: Int, layoutDirection: LayoutDirection) =
        endSign(layoutDirection) * fullWidth

    /** Where the covered screen goes: a quarter width toward the start. */
    fun pushExitOffset(fullWidth: Int, layoutDirection: LayoutDirection) =
        -endSign(layoutDirection) * fullWidth / 4

    /** Where the uncovered screen starts on a pop: a quarter width toward the start. */
    fun popEnterOffset(fullWidth: Int, layoutDirection: LayoutDirection) =
        -endSign(layoutDirection) * fullWidth / 4

    /** Where the popped screen goes: a full width off the end edge. */
    fun popExitOffset(fullWidth: Int, layoutDirection: LayoutDirection) =
        endSign(layoutDirection) * fullWidth

    fun fade(): ContentTransform = fadeIn(spec()) togetherWith fadeOut(spec())

    /**
     * Entry metadata that fades the entry in when it is entered forward.
     *
     * Only the forward key is set, so popping back to the entry keeps the
     * display's default slide.
     */
    fun rootFade(): Map<String, Any> = NavDisplay.transitionSpec { fade() }
}
