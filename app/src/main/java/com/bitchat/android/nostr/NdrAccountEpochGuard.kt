package com.bitchat.android.nostr

internal data class NdrAccountEpoch(
    val generation: Long,
    val accountPubkeyHex: String
)

/**
 * Serializes NDR receive-side mutations against account invalidation.
 *
 * Panic invalidation waits for a mutation already inside [runIfCurrent], then
 * advances the generation before the wipe starts. Old-account jobs can
 * therefore neither overlap nor repopulate the fresh post-wipe epoch.
 */
internal class NdrAccountEpochGuard {
    private val lock = Any()
    private var generation = 0L
    private var accountPubkeyHex: String? = null

    fun begin(accountPubkeyHex: String): NdrAccountEpoch = synchronized(lock) {
        val normalizedPubkeyHex = accountPubkeyHex.lowercase()
        generation += 1
        this.accountPubkeyHex = normalizedPubkeyHex
        NdrAccountEpoch(generation, normalizedPubkeyHex)
    }

    fun invalidate() = synchronized(lock) {
        generation += 1
        accountPubkeyHex = null
    }

    fun isCurrent(epoch: NdrAccountEpoch): Boolean = synchronized(lock) {
        isCurrentLocked(epoch)
    }

    fun runIfCurrent(epoch: NdrAccountEpoch, mutation: () -> Unit): Boolean =
        synchronized(lock) {
            if (!isCurrentLocked(epoch)) {
                false
            } else {
                mutation()
                true
            }
        }

    private fun isCurrentLocked(epoch: NdrAccountEpoch): Boolean =
        generation == epoch.generation &&
            accountPubkeyHex == epoch.accountPubkeyHex
}
