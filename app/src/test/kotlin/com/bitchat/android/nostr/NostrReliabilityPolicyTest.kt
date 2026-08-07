package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NostrReliabilityPolicyTest {
    @Test
    fun commitAwareDedupeRetriesAfterFailureAndConsumesOnlySuccess() {
        val deduplicator = NostrEventDeduplicator(maxCapacity = 8)
        val event = event("01".repeat(32))
        var attempts = 0

        assertFalse(
            deduplicator.processEventAfterSuccess(event) {
                attempts += 1
                false
            }
        )
        assertTrue(
            deduplicator.processEventAfterSuccess(event) {
                attempts += 1
                true
            }
        )
        assertFalse(
            deduplicator.processEventAfterSuccess(event) {
                attempts += 1
                true
            }
        )
        assertEquals(2, attempts)
    }

    @Test
    fun nip20DuplicateSuccessIsExactAndCaseSensitive() {
        assertTrue(isNip20ConfirmedSuccess(accepted = true, message = null))
        assertTrue(
            isNip20ConfirmedSuccess(
                accepted = false,
                message = "duplicate: already have this event"
            )
        )
        assertFalse(isNip20ConfirmedSuccess(false, "Duplicate: already have this event"))
        assertFalse(isNip20ConfirmedSuccess(false, "duplicate"))
        assertFalse(isNip20ConfirmedSuccess(false, " duplicate: already have this event"))
    }

    private fun event(id: String) = NostrEvent(
        id = id,
        pubkey = "02".repeat(32),
        createdAt = 1,
        kind = 1060,
        tags = emptyList(),
        content = "ciphertext",
        sig = "signature"
    )
}
