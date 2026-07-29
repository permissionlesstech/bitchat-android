package com.bitchat.android.util

/**
 * Reads GitHub's rate-limit rejections so the app can stop asking.
 *
 * Unauthenticated requests are capped at 60 an hour *per IP*, and when the app routes through Tor
 * that IP belongs to an exit node shared with every other user on it, so the ceiling arrives much
 * sooner than the per-user maths suggests. Retrying a rejection is pure waste, and repeating it on
 * every screen open is what turns a brief limit into a permanent one.
 */
internal object GitHubRateLimit {

    /** Used when GitHub rejects a request without saying when to come back. */
    const val DEFAULT_BACKOFF_MILLIS = 10 * 60 * 1000L

    /** Never sit out longer than this, however far ahead the reset header claims to be. */
    const val MAX_BACKOFF_MILLIS = 60 * 60 * 1000L

    /**
     * A 403 alone is not enough: GitHub also uses it for ordinary permission failures.
     *
     * Three things count as a rate limit. An explicit 429. A 403 reporting zero remaining quota,
     * which is the primary hourly limit. And a 403 carrying Retry-After while quota remains, which
     * is how secondary limits arrive — abuse detection rather than the hourly budget, so treating
     * it as a permissions failure leaves the gate unset and keeps the app calling during exactly
     * the cooldown GitHub asked for.
     */
    fun isRateLimited(code: Int, remaining: String?, retryAfterSeconds: String? = null): Boolean =
        code == 429 ||
            (code == 403 && (remaining?.trim() == "0" || retryAfterDelayMillis(retryAfterSeconds) != null))

    private fun retryAfterDelayMillis(retryAfterSeconds: String?): Long? =
        retryAfterSeconds?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 1000 }

    /**
     * Epoch millis before which no further request should be sent, or null when the response was
     * not a rate-limit rejection at all.
     */
    fun blockedUntilMillis(
        code: Int,
        remaining: String?,
        resetEpochSeconds: String?,
        retryAfterSeconds: String?,
        nowMillis: Long,
    ): Long? {
        if (!isRateLimited(code, remaining, retryAfterSeconds)) return null

        // Retry-After is a delta and is what GitHub sends for secondary limits, which can lift
        // sooner than the primary window X-RateLimit-Reset describes.
        val fromRetryAfter = retryAfterDelayMillis(retryAfterSeconds)?.let { nowMillis + it }

        // Dropped when it is not in the future: a skewed device clock must not turn a genuine
        // rejection into "retry immediately".
        val fromReset = resetEpochSeconds?.trim()?.toLongOrNull()
            ?.let { it * 1000 }
            ?.takeIf { it > nowMillis }

        val target = fromRetryAfter ?: fromReset ?: (nowMillis + DEFAULT_BACKOFF_MILLIS)
        return target.coerceIn(nowMillis, nowMillis + MAX_BACKOFF_MILLIS)
    }
}
