package com.bitchat.android.nostr

import com.bitchat.android.util.AppConstants
import kotlin.math.min
import kotlin.math.pow

/**
 * Reconnect schedule for a Nostr relay socket.
 *
 * A phone loses its data connection constantly — airplane mode, a tunnel, a
 * Wi-Fi to cellular handover, a dead zone — and every relay fails at once when
 * it does. The schedule therefore has to survive an outage of arbitrary length
 * and heal on its own, because nothing else will: the relay layer registers no
 * connectivity callback, the periodic subscription validator only repairs
 * subscriptions on sockets that are already open, and `connect()` runs once at
 * startup.
 *
 * So the backoff grows exponentially and then *saturates* rather than
 * terminating. Retrying forever at the ceiling costs one connection attempt per
 * relay per [AppConstants.Nostr.MAX_BACKOFF_INTERVAL_MS]; giving up costs the
 * user every internet DM, delivery receipt and geohash channel until they
 * notice and restart the app.
 */
internal object RelayReconnectPolicy {

    /**
     * Attempt count past which the interval stops growing. Beyond this the
     * exponential term is already above the ceiling, so pinning it keeps the
     * schedule at a steady [AppConstants.Nostr.MAX_BACKOFF_INTERVAL_MS] and
     * keeps the exponent from running away over a long outage.
     */
    const val SATURATION_ATTEMPTS: Int = AppConstants.Nostr.MAX_RECONNECT_ATTEMPTS

    /** Attempt number to record after a failure at [previousAttempts]. */
    fun nextAttempt(previousAttempts: Int): Int =
        (previousAttempts.coerceAtLeast(0) + 1).coerceAtMost(SATURATION_ATTEMPTS)

    /** Delay before the reconnect for a given attempt number (1-based). */
    fun backoffMs(attempt: Int): Long {
        val step = attempt.coerceAtLeast(1)
        val exponential = AppConstants.Nostr.INITIAL_BACKOFF_INTERVAL_MS *
            AppConstants.Nostr.BACKOFF_MULTIPLIER.pow(step - 1.0)
        return min(exponential, AppConstants.Nostr.MAX_BACKOFF_INTERVAL_MS.toDouble()).toLong()
    }
}
