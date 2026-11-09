package com.bitchat.android.ui

import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Where a panic runs: a scope that belongs to the process, on the main thread. A panic ends the
 * session and the identity, so it cannot run on either, and leaving the app must not stop it. A
 * process kill still does; surviving one belongs to a later change.
 *
 * Process-wide so that a panic started in one retained lifetime also refuses one from the next,
 * which is how reopening the app during a wipe cannot start a second wipe.
 */
@Singleton
class PanicRunner @Inject constructor() {
    private val scope = CoroutineScope(SupervisorJob())
    private val running = AtomicBoolean(false)

    /** Whether a panic is running now. */
    val isRunning: Boolean get() = running.get()

    /**
     * Runs [block] unless one is already running. Returns whether it started. A failure ends the
     * run with a log line and goes no further: on this scope it would otherwise end the process,
     * and the app must not restart because of a panic.
     */
    fun runOnce(block: suspend () -> Unit): Boolean {
        if (!running.compareAndSet(false, true)) return false
        scope.launch(Dispatchers.Main.immediate) {
            try {
                block()
            } catch (e: Exception) {
                Log.e(TAG, "The panic stopped early: ${e.javaClass.simpleName}")
            } finally {
                running.set(false)
            }
        }
        return true
    }

    private companion object {
        const val TAG = "PanicRunner"
    }
}
