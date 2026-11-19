package com.bitchat.android.ui

import android.view.Choreographer
import javax.inject.Inject
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits for what the screen shows to reach the display.
 *
 * Panic's wipe stages run on the main thread without suspending, as does the swap after them, so
 * without this no frame would come between blanking the screen and replacing it: someone under
 * duress would watch the messages stay up for the whole wipe.
 *
 * Two frames: composition runs in the first and its draw comes after this callback in that frame, so
 * the second proves the draw happened. Bounded, because a stopped Activity draws nothing and a wipe
 * must never wait for a screen.
 */
class BlankFrame @Inject constructor() {
    suspend fun await() {
        withTimeoutOrNull(MAX_WAIT_MS) {
            repeat(2) { nextFrame() }
        }
    }

    private suspend fun nextFrame() = suspendCancellableCoroutine { continuation ->
        val choreographer = Choreographer.getInstance()
        val callback = Choreographer.FrameCallback { continuation.resume(Unit) }
        choreographer.postFrameCallback(callback)
        continuation.invokeOnCancellation { choreographer.removeFrameCallback(callback) }
    }

    private companion object {
        const val MAX_WAIT_MS = 250L
    }
}
