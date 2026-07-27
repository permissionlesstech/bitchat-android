package com.bitchat.android.nostr

@JvmInline
internal value class AccountResetLeaseId(val epoch: Long)

/**
 * Serializes the short ownership-sensitive portions of account replacement.
 *
 * Destructive clearing may happen outside this gate after [begin]. Reopening
 * transports and installing the replacement identity must happen through
 * [runIfCurrent], so an older reset cannot mutate state after a newer one starts.
 */
internal class AccountResetGate {
    private val lock = Any()
    private var epoch = 0L
    private var terminal = false

    fun begin(): AccountResetLeaseId =
        checkNotNull(begin(terminal = false))

    fun begin(
        terminal: Boolean,
        mutation: (AccountResetLeaseId) -> Unit = {}
    ): AccountResetLeaseId? {
        check(!Thread.holdsLock(lock)) {
            "An account reset cannot begin from its own owned mutation"
        }
        return synchronized(lock) {
            if (this.terminal) {
                return@synchronized null
            }
            epoch += 1
            this.terminal = terminal
            AccountResetLeaseId(epoch).also(mutation)
        }
    }

    fun runIfCurrent(
        lease: AccountResetLeaseId,
        mutation: () -> Unit
    ): Boolean =
        synchronized(lock) {
            if (lease.epoch != epoch) {
                false
            } else {
                mutation()
                true
            }
        }
}
