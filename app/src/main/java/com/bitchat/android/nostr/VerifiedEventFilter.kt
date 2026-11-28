package com.bitchat.android.nostr

/**
 * Fail-closed admission for observe-only traffic, which skips the relay manager's checks.
 *
 * Every distinct event is signature-verified once (which also re-hashes its id). A repeat of an
 * admitted id is dropped without reading its fields, so a relay cannot replay a known id with a
 * new pubkey or timestamp. Failed ids are not remembered: an attacker who sends a forged copy
 * first must not be able to block the genuine event.
 */
internal class VerifiedEventFilter(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val verify: (NostrEvent) -> Boolean = NostrEvent::isValidSignature
) {
    private val admitted = LinkedHashSet<String>()

    fun admit(event: NostrEvent): Boolean {
        if (event.id in admitted) return false
        if (!verify(event)) return false
        admitted.add(event.id)
        if (admitted.size > capacity) admitted.remove(admitted.first())
        return true
    }

    private companion object {
        const val DEFAULT_CAPACITY = 20_000
    }
}
