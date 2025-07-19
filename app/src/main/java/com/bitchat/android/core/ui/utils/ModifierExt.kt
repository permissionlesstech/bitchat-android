package com.bitchat.android.core.ui.utils

import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun Modifier.singleOrTripleClickable(
    onSingleClick: () -> Unit,
    onTripleClick: () -> Unit,
    clickTimeThreshold: Long = 300L
): Modifier = composed {
    var tapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var singleClickJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val coroutineScope = rememberCoroutineScope()

    this.clickable {
        val currentTime = System.currentTimeMillis()

        if (currentTime - lastTapTime < clickTimeThreshold) {
            tapCount++
        } else {
            tapCount = 1
        }

        lastTapTime = currentTime

        // Cancel any pending single click action
        singleClickJob?.cancel()
        singleClickJob = null

        when (tapCount) {
            1 -> {
                // Wait to see if more taps come
                singleClickJob = coroutineScope.launch {
                    delay(clickTimeThreshold)
                    if (tapCount == 1) {
                        onSingleClick()
                    }
                }
            }
            3 -> {
                // Triple click detected - execute immediately
                onTripleClick()
                tapCount = 0
            }
        }

        // Reset after threshold if no triple click
        if (tapCount > 3) {
            tapCount = 0
        }
    }
}