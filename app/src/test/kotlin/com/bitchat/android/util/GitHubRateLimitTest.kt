package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unauthenticated GitHub allows 60 requests an hour per IP, and over Tor that IP is an exit node
 * shared with everyone else using it. Reading the rejection correctly is what keeps the app from
 * hammering a quota it has already exhausted.
 */
class GitHubRateLimitTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a 403 that still has quota is a permissions error, not a rate limit`() {
        assertFalse(GitHubRateLimit.isRateLimited(code = 403, remaining = "42"))
        assertNull(
            GitHubRateLimit.blockedUntilMillis(
                code = 403,
                remaining = "42",
                resetEpochSeconds = null,
                retryAfterSeconds = null,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `a 403 with no quota left blocks until the advertised reset`() {
        val resetSeconds = now / 1000 + 900

        assertTrue(GitHubRateLimit.isRateLimited(code = 403, remaining = "0"))
        assertEquals(
            resetSeconds * 1000,
            GitHubRateLimit.blockedUntilMillis(
                code = 403,
                remaining = "0",
                resetEpochSeconds = resetSeconds.toString(),
                retryAfterSeconds = null,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `a 429 is a rate limit even without a remaining header`() {
        assertTrue(GitHubRateLimit.isRateLimited(code = 429, remaining = null))
    }

    @Test
    fun `Retry-After takes precedence over the reset header`() {
        // Retry-After is a delta and is what GitHub sends for secondary limits, which can expire
        // sooner than the primary window the reset header describes.
        assertEquals(
            now + 30_000,
            GitHubRateLimit.blockedUntilMillis(
                code = 429,
                remaining = "0",
                resetEpochSeconds = (now / 1000 + 3_000).toString(),
                retryAfterSeconds = "30",
                nowMillis = now,
            )
        )
    }

    @Test
    fun `a rejection with no timing headers falls back to a fixed backoff`() {
        assertEquals(
            now + GitHubRateLimit.DEFAULT_BACKOFF_MILLIS,
            GitHubRateLimit.blockedUntilMillis(
                code = 429,
                remaining = null,
                resetEpochSeconds = null,
                retryAfterSeconds = null,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `a reset time already in the past falls back rather than unblocking immediately`() {
        // A skewed device clock must not turn a real rejection into "retry right now".
        assertEquals(
            now + GitHubRateLimit.DEFAULT_BACKOFF_MILLIS,
            GitHubRateLimit.blockedUntilMillis(
                code = 429,
                remaining = null,
                resetEpochSeconds = (now / 1000 - 500).toString(),
                retryAfterSeconds = null,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `an absurd reset time is clamped so the app is never locked out for long`() {
        assertEquals(
            now + GitHubRateLimit.MAX_BACKOFF_MILLIS,
            GitHubRateLimit.blockedUntilMillis(
                code = 429,
                remaining = null,
                resetEpochSeconds = (now / 1000 + 86_400).toString(),
                retryAfterSeconds = null,
                nowMillis = now,
            )
        )
    }

    @Test
    fun `unparseable headers fall back instead of throwing`() {
        assertEquals(
            now + GitHubRateLimit.DEFAULT_BACKOFF_MILLIS,
            GitHubRateLimit.blockedUntilMillis(
                code = 429,
                remaining = null,
                resetEpochSeconds = "not-a-number",
                retryAfterSeconds = "Wed, 21 Oct 2015 07:28:00 GMT",
                nowMillis = now,
            )
        )
    }
}
