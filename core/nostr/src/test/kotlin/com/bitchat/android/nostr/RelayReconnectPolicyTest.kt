package com.bitchat.android.nostr

import com.bitchat.android.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The relay layer has no connectivity callback, its periodic validator only
 * repairs subscriptions on sockets that are already open, and `connect()` runs
 * once at startup. The backoff schedule is therefore the only thing that can
 * bring relays back after the phone loses its data connection, so it has to
 * saturate rather than terminate.
 */
class RelayReconnectPolicyTest {

    private val initial = AppConstants.Nostr.INITIAL_BACKOFF_INTERVAL_MS
    private val ceiling = AppConstants.Nostr.MAX_BACKOFF_INTERVAL_MS

    @Test
    fun `the first retry waits the initial interval`() {
        assertEquals(initial, RelayReconnectPolicy.backoffMs(RelayReconnectPolicy.nextAttempt(0)))
    }

    @Test
    fun `the interval doubles per attempt until it reaches the ceiling`() {
        var attempt = 0
        var previous = 0L
        var sawCeiling = false

        repeat(RelayReconnectPolicy.SATURATION_ATTEMPTS) {
            attempt = RelayReconnectPolicy.nextAttempt(attempt)
            val delay = RelayReconnectPolicy.backoffMs(attempt)

            assertTrue("delay must never exceed the ceiling", delay <= ceiling)
            if (delay == ceiling) {
                sawCeiling = true
            } else {
                assertEquals("expected doubling below the ceiling", maxOf(initial, previous * 2), delay)
            }
            previous = delay
        }

        assertTrue("the schedule must actually reach the ceiling", sawCeiling)
    }

    @Test
    fun `an outage longer than the schedule keeps retrying at the ceiling`() {
        var attempt = 0
        // Far past the old give-up point; a real outage can last hours.
        repeat(500) { attempt = RelayReconnectPolicy.nextAttempt(attempt) }

        assertEquals(RelayReconnectPolicy.SATURATION_ATTEMPTS, attempt)
        assertEquals(ceiling, RelayReconnectPolicy.backoffMs(attempt))
    }

    @Test
    fun `a long outage cannot run the attempt counter or the exponent away`() {
        var attempt = 0
        repeat(10_000) { attempt = RelayReconnectPolicy.nextAttempt(attempt) }

        val delay = RelayReconnectPolicy.backoffMs(attempt)
        assertTrue("delay must stay finite and bounded", delay in 1..ceiling)
    }

    @Test
    fun `a successful connection resets the schedule to the initial interval`() {
        var attempt = 0
        repeat(6) { attempt = RelayReconnectPolicy.nextAttempt(attempt) }
        assertTrue(RelayReconnectPolicy.backoffMs(attempt) > initial)

        // updateRelayStatus zeroes reconnectAttempts on a successful open.
        attempt = 0

        assertEquals(initial, RelayReconnectPolicy.backoffMs(RelayReconnectPolicy.nextAttempt(attempt)))
    }

    @Test
    fun `a nonsensical stored attempt count still yields a usable delay`() {
        assertEquals(initial, RelayReconnectPolicy.backoffMs(RelayReconnectPolicy.nextAttempt(-5)))
        assertTrue(RelayReconnectPolicy.backoffMs(0) in 1..ceiling)
        assertTrue(RelayReconnectPolicy.backoffMs(Int.MAX_VALUE) in 1..ceiling)
    }

    @Test
    fun `the whole schedule stays under an hour of total wait before the ceiling`() {
        var attempt = 0
        var total = 0L
        repeat(RelayReconnectPolicy.SATURATION_ATTEMPTS) {
            attempt = RelayReconnectPolicy.nextAttempt(attempt)
            total += RelayReconnectPolicy.backoffMs(attempt)
        }

        assertTrue("reaching the steady state must not take an hour", total < 60 * 60 * 1000L)
    }
}
