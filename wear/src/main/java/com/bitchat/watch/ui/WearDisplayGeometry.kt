package com.bitchat.watch.ui

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt

/** Width of the narrowest chord across a centered horizontal band in a round display. */
internal fun roundBandWidth(width: Float, height: Float, top: Float, bottom: Float): Float {
    val radius = min(width, height) / 2f
    val distance = maxOf(abs(top - height / 2f), abs(bottom - height / 2f))
    return 2f * sqrt((radius * radius - distance * distance).coerceAtLeast(0f))
}

/** A centered square whose four corners fit inside the physical circle, with a small inset. */
internal fun roundContentSide(width: Float, height: Float): Float =
    (min(width, height) / sqrt(2f) - 4f).coerceAtLeast(0f)
