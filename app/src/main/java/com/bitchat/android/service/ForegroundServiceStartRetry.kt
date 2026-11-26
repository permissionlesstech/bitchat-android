package com.bitchat.android.service

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Starts the mesh foreground service for an Activity, and keeps the request pending until the
 * system accepts one: a start before the Activity is started, or one the system refused, is
 * retried at the Activity's next start.
 *
 * The retry runs from a lifecycle observer, not an `onStart()` override: the lifecycle still
 * reads CREATED inside `onStart()`, so a retry there deferred itself again and never ran.
 *
 * Built in `onCreate`, on the main thread, where a lifecycle takes an observer.
 */
internal class ForegroundServiceStartRetry(
    private val lifecycle: Lifecycle,
    private val start: () -> Boolean,
) {
    var pending = false
        private set

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                if (pending) request()
            }
        })
    }

    fun request() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            pending = true
            Log.i(TAG, "Deferring foreground mesh service start until activity is started")
            return
        }
        pending = try {
            !start()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to start foreground mesh service; will retry when activity is started", e)
            true
        }
    }

    private companion object {
        const val TAG = "MainActivity"
    }
}
