package com.bitchat.android.hotspot

import android.os.Handler
import android.os.Looper

internal interface HotspotScheduler {
    interface Task {
        fun cancel()
    }

    fun schedule(delayMillis: Long, action: () -> Unit): Task
}

internal class HandlerHotspotScheduler(
    private val handler: Handler = Handler(Looper.getMainLooper())
) : HotspotScheduler {
    override fun schedule(delayMillis: Long, action: () -> Unit): HotspotScheduler.Task {
        val runnable = Runnable(action)
        handler.postDelayed(runnable, delayMillis)
        return object : HotspotScheduler.Task {
            override fun cancel() {
                handler.removeCallbacks(runnable)
            }
        }
    }
}
