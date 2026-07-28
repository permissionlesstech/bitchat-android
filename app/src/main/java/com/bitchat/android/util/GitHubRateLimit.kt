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
     * A 403 alone is not enough: GitHub also uses it for ordinary permission failures. Only a 403
     * that reports zero remaining quota, or an explicit 429, is a rate limit.
     */
    fun isRateLimited(code: Int, remaining: String?): Boolean =
        code == 429 || (code == 403 && remaining?.trim() == "0")

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
        if (!isRateLimited(code, remaining)) return null

        // Retry-After is a delta and is what GitHub sends for secondary limits, which can lift
        // sooner than the primary window X-RateLimit-Reset describes.
        val fromRetryAfter = retryAfterSeconds?.trim()?.toLongOrNull()
            ?.takeIf { it > 0 }
            ?.let { nowMillis + it * 1000 }

        // Dropped when it is not in the future: a skewed device clock must not turn a genuine
        // rejection into "retry immediately".
        val fromReset = resetEpochSeconds?.trim()?.toLongOrNull()
            ?.let { it * 1000 }
            ?.takeIf { it > nowMillis }

        val target = fromRetryAfter ?: fromReset ?: (nowMillis + DEFAULT_BACKOFF_MILLIS)
        return target.coerceIn(nowMillis, nowMillis + MAX_BACKOFF_MILLIS)
    }
}
