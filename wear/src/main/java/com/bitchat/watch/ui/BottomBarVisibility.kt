package com.bitchat.watch.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow

/**
 * Scroll-aware bottom-bar visibility (typical dynamic-hide pattern): the bar hides while the
 * user scrolls into history and reappears when they scroll back toward the newest messages.
 * Always visible at the bottom (newest).
 *
 * Lists are normal (top-down) scrollables: value 0 = oldest, maxValue = newest (visual bottom).
 */
@Composable
fun rememberBottomBarVisibility(scrollState: ScrollState): State<Boolean> {
    val visible = remember { mutableStateOf(true) }
    LaunchedEffect(scrollState) {
        var last = 0
        snapshotFlow { scrollState.value to scrollState.maxValue }.collect { (value, max) ->
            val atNewest = max - value < 40
            when {
                atNewest -> visible.value = true
                value < last - 24 -> visible.value = false // scrolling up into history
                value > last + 24 -> visible.value = true  // back down toward newest
            }
            last = value
        }
    }
    return visible
}
