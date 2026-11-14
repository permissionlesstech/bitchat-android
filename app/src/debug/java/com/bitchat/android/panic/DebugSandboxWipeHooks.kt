package com.bitchat.android.panic

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.bitchat.android.testhook.TestHookReceiver
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Test hooks around the panic's sandbox wipe, driven through [TestHookReceiver]. Debug builds only:
 * the debug `PanicWipeBindingModule` wraps the wipe with [wrap], the release one binds it directly.
 *
 * - `panic.failWipeOnce`: the next wipe runs, then reports one leftover more, so the panic restarts.
 * - `panic.pauseBeforeWipe`: the next wipe waits before it starts, until `panic.resume` or the timeout.
 * - `panic.resume`: releases that wait.
 *
 * The wait blocks the panic's thread, the main one, so `panic.resume` cannot reach a receiver that
 * runs there. A second receiver for the same action runs on its own thread and releases it.
 */
object DebugSandboxWipeHooks {
    private const val TAG = "PanicTestHook"
    const val CMD_FAIL_WIPE_ONCE = "panic.failWipeOnce"
    const val CMD_PAUSE_BEFORE_WIPE = "panic.pauseBeforeWipe"
    const val CMD_RESUME = "panic.resume"

    /** The leftover a failed wipe reports. Not a real path, so nothing on disk matches it. */
    const val INJECTED_LEFTOVER = "testhook/failWipeOnce"

    /** A pause nobody resumes still ends, so a forgotten hook cannot hang the app. */
    const val DEFAULT_PAUSE_TIMEOUT_MS = 60_000L

    private val failNext = AtomicBoolean(false)

    @Volatile
    private var pause: Pause? = null

    private var resumeReceiverThread: HandlerThread? = null

    private class Pause(val timeoutMs: Long) {
        val released = CountDownLatch(1)
    }

    fun failWipeOnce() {
        failNext.set(true)
        Log.i(TAG, "The next wipe will report a leftover")
    }

    fun pauseBeforeWipe(context: Context, timeoutMs: Long = DEFAULT_PAUSE_TIMEOUT_MS) {
        listenForResume(context.applicationContext)
        pause = Pause(timeoutMs)
        Log.i(TAG, "The next wipe will wait up to $timeoutMs ms")
    }

    /** True when a pause was armed or waiting. */
    fun resume(): Boolean {
        val armed = pause ?: return false
        pause = null
        armed.released.countDown()
        Log.i(TAG, "Wipe resumed")
        return true
    }

    fun wrap(wipe: SandboxWipeRunner): SandboxWipeRunner = SandboxWipeRunner {
        pause?.let { armed ->
            Log.w(TAG, "Paused before the wipe")
            val resumed = armed.released.await(armed.timeoutMs, TimeUnit.MILLISECONDS)
            pause = null
            Log.w(TAG, if (resumed) "Pause released" else "Pause timed out")
        }
        val startedAt = SystemClock.elapsedRealtime()
        val result = wipe.run()
        // Counts and time only, never paths, so a device check can time the wipe from the log.
        Log.i(
            TAG,
            "Wipe took ${SystemClock.elapsedRealtime() - startedAt} ms, " +
                "${result.leftovers.size} leftovers, ${result.aliasesLeft} keys left",
        )
        if (failNext.getAndSet(false)) {
            Log.w(TAG, "Reporting a leftover the wipe did not leave")
            result.copy(leftovers = result.leftovers + INJECTED_LEFTOVER)
        } else {
            result
        }
    }

    @Synchronized
    private fun listenForResume(context: Context) {
        if (resumeReceiverThread != null) return
        val thread = HandlerThread("PanicTestHookResume").apply { start() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.getStringExtra("cmd") == CMD_RESUME) resume()
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(TestHookReceiver.ACTION),
            null,
            Handler(thread.looper),
            ContextCompat.RECEIVER_EXPORTED,
        )
        resumeReceiverThread = thread
    }
}
