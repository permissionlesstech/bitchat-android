package com.bitchat.android.nostr

import com.bitchat.android.mesh.NdrMeshRoute
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

internal data class NdrInviteRetryToken(
    val peerID: String,
    val peerPubkeyHex: String,
    val inviteEventId: String,
    val route: NdrMeshRoute
)

internal data class NdrInviteRetryRequest(
    val token: NdrInviteRetryToken,
    val eventJson: String
)

/**
 * Retries admission of one invite on one exact authenticated transport generation.
 *
 * A repeated trigger for the same token cannot reset its finite retry budget. Every delayed
 * attempt is revalidated by the caller so a replaced Noise generation, changed invite,
 * favorite revocation/rebind, or completed pairwise session makes the request stale.
 */
internal class NdrInviteRetryCoordinator(
    private val scope: CoroutineScope,
    private val retryDelaysMs: List<Long> = DEFAULT_RETRY_DELAYS_MS,
    private val isStillValid: (NdrInviteRetryRequest) -> Boolean,
    private val send: (
        request: NdrInviteRetryRequest,
        completion: (admitted: Boolean) -> Unit
    ) -> Unit,
    private val onAdmitted: (NdrInviteRetryRequest) -> Unit
) {
    private data class ActiveRetry(
        val request: NdrInviteRetryRequest,
        val job: Job
    )

    private val lock = Any()
    private val activeRetries = mutableMapOf<String, ActiveRetry>()

    fun start(request: NdrInviteRetryRequest) {
        val peerID = request.token.peerID
        val job = scope.launch(start = CoroutineStart.LAZY) {
            runAttempts(request)
        }
        val shouldStart = synchronized(lock) {
            val current = activeRetries[peerID]
            if (current?.request?.token == request.token) {
                false
            } else {
                current?.job?.cancel()
                activeRetries[peerID] = ActiveRetry(request, job)
                true
            }
        }
        if (shouldStart) {
            job.start()
        } else {
            job.cancel()
        }
    }

    fun cancel(peerID: String) {
        synchronized(lock) {
            activeRetries.remove(peerID)
        }?.job?.cancel()
    }

    fun retainPeers(peerIDs: Set<String>) {
        val retired = synchronized(lock) {
            val stalePeerIDs = activeRetries.keys - peerIDs
            stalePeerIDs.mapNotNull(activeRetries::remove)
        }
        retired.forEach { it.job.cancel() }
    }

    fun cancelAll() {
        val retired = synchronized(lock) {
            activeRetries.values.toList().also { activeRetries.clear() }
        }
        retired.forEach { it.job.cancel() }
    }

    private suspend fun runAttempts(request: NdrInviteRetryRequest) {
        for (attemptIndex in 0..retryDelaysMs.size) {
            if (attemptIndex > 0) {
                delay(retryDelaysMs[attemptIndex - 1])
            }
            if (!isCurrent(request) || !isStillValid(request)) return

            val admitted = awaitAdmission(request)
            if (!isCurrent(request)) return
            if (admitted) {
                onAdmitted(request)
                return
            }
        }
    }

    private suspend fun awaitAdmission(request: NdrInviteRetryRequest): Boolean =
        suspendCancellableCoroutine { continuation ->
            val delivered = AtomicBoolean(false)
            try {
                send(request) { admitted ->
                    if (delivered.compareAndSet(false, true) && continuation.isActive) {
                        continuation.resume(admitted)
                    }
                }
            } catch (_: Exception) {
                if (delivered.compareAndSet(false, true) && continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }

    private fun isCurrent(request: NdrInviteRetryRequest): Boolean =
        synchronized(lock) {
            activeRetries[request.token.peerID]?.request === request
        }

    companion object {
        internal val DEFAULT_RETRY_DELAYS_MS = listOf(250L, 500L, 1_000L, 2_000L)
    }
}
