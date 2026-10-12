package com.bitchat.android.navigation

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.navigation3.ui.NavDisplay

/**
 * How screens move. Pushes and replaces slide in from the end; pops slide back
 * out, which is the motion predictive back previews. The one fade is the
 * onboarding-to-chat root swap, which is not a step deeper.
 */
object ScreenTransitions {
    /** BitchatMotion.EMPHASIZED_MS; :core:navigation cannot see the app theme yet. */
    const val DURATION_MS = 240

    private fun <T> spec() = tween<T>(DURATION_MS, easing = FastOutSlowInEasing)

    fun push(): ContentTransform =
        slideInHorizontally(spec()) { it } togetherWith slideOutHorizontally(spec()) { -it / 4 }

    fun pop(): ContentTransform =
        slideInHorizontally(spec()) { -it / 4 } togetherWith slideOutHorizontally(spec()) { it }

    fun fade(): ContentTransform = fadeIn(spec()) togetherWith fadeOut(spec())

    /**
     * Entry metadata that fades the entry in when it is entered forward.
     *
     * Only the forward key is set, so popping back to the entry keeps the
     * display's default slide.
     */
    fun rootFade(): Map<String, Any> = NavDisplay.transitionSpec { fade() }
}
