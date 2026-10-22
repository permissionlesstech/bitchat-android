package com.bitchat.android.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Sends small idempotent control packets redundantly while serializing the attempts submitted
 * through this sender. Android BLE only reports that a GATT write/notification was accepted;
 * it does not prove that the remote application processed the packet. Reusing the exact encoded
 * packet makes retries safe: the receiver's packet/replay protection drops copies it already saw.
 */
internal class RetryingControlPacketSender(
    private val scope: CoroutineScope,
    private val maxAttempts: Int = 3,
    private val retryDelayMs: Long = 750L,
    private val interSendDelayMs: Long = 75L
) {
    private val sendMutex = Mutex()
    private val jobsLock = Any()
    private val activeJobs = mutableMapOf<String, Job>()

    init {
        require(maxAttempts > 0)
        require(retryDelayMs >= 0)
        require(interSendDelayMs >= 0)
    }

    /**
     * Coalesces concurrent requests for the same logical packet. Once the retry window finishes,
     * a later user action may enqueue the packet again; duplicate receipt processing is idempotent.
     */
    fun enqueue(
        key: String,
        sendAttempt: suspend (attempt: Int) -> Boolean,
        onComplete: (acceptedAtLeastOnce: Boolean) -> Unit = {}
    ) {
        val job = synchronized(jobsLock) {
            if (activeJobs[key]?.isActive == true) return

            scope.launch(start = CoroutineStart.LAZY) {
                var acceptedAtLeastOnce = false
                var completed = false
                try {
                    repeat(maxAttempts) { index ->
                        if (!isActive) return@launch
                        val attempt = index + 1
                        sendMutex.withLock {
                            try {
                                val accepted = try {
                                    sendAttempt(attempt)
                                } catch (_: Exception) {
                                    false
                                }
                                acceptedAtLeastOnce = accepted || acceptedAtLeastOnce
                            } finally {
                                if (interSendDelayMs > 0) delay(interSendDelayMs)
                            }
                        }
                        if (attempt < maxAttempts && retryDelayMs > 0) {
                            delay(retryDelayMs)
                        }
                    }
                    completed = true
                } finally {
                    synchronized(jobsLock) {
                        activeJobs.remove(key)
                    }
                    if (completed) {
                        try { onComplete(acceptedAtLeastOnce) } catch (_: Exception) { }
                    }
                }
            }.also { activeJobs[key] = it }
        }
        job.start()
    }
}
