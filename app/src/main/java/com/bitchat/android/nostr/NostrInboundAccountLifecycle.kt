package com.bitchat.android.nostr

import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

internal data class NostrInboundAccountContext(
    val epoch: NostrAccountEpoch,
    val receiveJob: Job
)

/**
 * Process-wide lifetime for all account-bound Nostr receive work.
 *
 * Subscription handlers capture an epoch when they are installed. Beginning a
 * replacement account invalidates those handlers and cancels their launched
 * work. Invalidation is also a mutation barrier: after it returns, an old
 * receive can finish non-sensitive parsing but cannot mutate application state.
 */
internal object NostrInboundAccountLifecycle {
    private val lifecycleLock = Any()
    private val epochs = NostrAccountEpochGuard()
    private var currentContext: NostrInboundAccountContext? = null

    fun begin(
        accountPubkeyHex: String,
        parentJob: Job?
    ): NostrInboundAccountContext = synchronized(lifecycleLock) {
        val epoch = epochs.begin(accountPubkeyHex)
        currentContext?.receiveJob?.cancel()
        NostrInboundAccountContext(
            epoch = epoch,
            receiveJob = SupervisorJob(parentJob)
        ).also { currentContext = it }
    }

    fun contextFor(epoch: NostrAccountEpoch): NostrInboundAccountContext? =
        synchronized(lifecycleLock) {
            currentContext?.takeIf {
                it.epoch == epoch
            }
        }

    fun currentEpoch(): NostrAccountEpoch? =
        synchronized(lifecycleLock) { currentContext?.epoch }

    fun isCurrent(epoch: NostrAccountEpoch): Boolean = epochs.isCurrent(epoch)

    fun runIfCurrent(
        epoch: NostrAccountEpoch,
        mutation: () -> Unit
    ): Boolean = epochs.runIfCurrent(epoch, mutation)

    fun invalidate() {
        synchronized(lifecycleLock) {
            epochs.invalidate()
            currentContext?.receiveJob?.cancel()
            currentContext = null
        }
    }
}
