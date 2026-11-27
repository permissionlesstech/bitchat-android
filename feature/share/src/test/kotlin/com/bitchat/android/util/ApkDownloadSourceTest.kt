package com.bitchat.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException

class ApkDownloadSourceTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val source = ApkDownloadSource(
        id = "mirror-one",
        displayName = "Mirror One",
        latestApkUrl = "https://mirror.example/bitchat-universal.apk"
    )
    private val now = 1_700_000_000_000L

    @Test
    fun `default source downloads the stable latest universal asset directly`() {
        assertEquals(
            "https://github.com/permissionlesstech/bitchat-android/releases/latest/" +
                "download/bitchat-android-universal.apk",
            DefaultApkDownloadSources.all.single().latestApkUrls.first()
        )
        assertEquals(
            "https://github.com/permissionlesstech/bitchat-android/releases/latest/" +
                "download/app-universal-release.apk",
            DefaultApkDownloadSources.all.single().latestApkUrls[1]
        )
    }

    @Test
    fun `transient HTTP failures are retryable but ordinary client errors are not`() {
        assertTrue(httpError(408).retryable)
        assertTrue(httpError(500).retryable)
        assertTrue(httpError(503).retryable)
        assertFalse(httpError(400).retryable)
        assertFalse(httpError(404).retryable)
    }

    @Test
    fun `compatibility URL is only tried when the preferred asset is absent`() {
        assertTrue(shouldTryNextSourceUrl(httpError(404), hasMoreUrls = true))
        assertFalse(shouldTryNextSourceUrl(httpError(404), hasMoreUrls = false))
        assertFalse(shouldTryNextSourceUrl(httpError(429), hasMoreUrls = true))
        assertFalse(shouldTryNextSourceUrl(httpError(503), hasMoreUrls = true))
    }

    @Test
    fun `rate limit response retains the server deadline without exposing a countdown`() {
        val failure = ApkDownloadHttpErrors.fromResponse(
            source = source,
            code = 429,
            responseMessage = "Too Many Requests",
            retryAfter = "120",
            rateLimitRemaining = null,
            rateLimitResetEpochSeconds = null,
            nowMillis = now
        )

        assertFalse(failure.retryable)
        assertEquals(now + 120_000L, failure.retryAtMillis)
        assertEquals(ApkDownloadFailureReason.RateLimited, failure.reason)
        assertEquals(listOf(source.displayName), failure.messageArgs)
    }

    @Test
    fun `403 is only treated as a limit when response headers say so`() {
        val permissionsFailure = ApkDownloadHttpErrors.fromResponse(
            source = source,
            code = 403,
            responseMessage = "Forbidden",
            retryAfter = "not-a-date",
            rateLimitRemaining = "42",
            rateLimitResetEpochSeconds = null,
            nowMillis = now
        )
        val quotaFailure = ApkDownloadHttpErrors.fromResponse(
            source = source,
            code = 403,
            responseMessage = "Forbidden",
            retryAfter = null,
            rateLimitRemaining = "0",
            rateLimitResetEpochSeconds = (now / 1000L + 300L).toString(),
            nowMillis = now
        )

        assertNull(permissionsFailure.retryAtMillis)
        assertEquals(ApkDownloadFailureReason.HttpFailure, permissionsFailure.reason)
        assertEquals(
            listOf(source.displayName, "403", "Forbidden"),
            permissionsFailure.messageArgs
        )
        assertEquals(now + 300_000L, quotaFailure.retryAtMillis)
        assertEquals(ApkDownloadFailureReason.RateLimited, quotaFailure.reason)
    }

    @Test
    fun `a reset header alone does not make a 403 a rate limit`() {
        // GitHub sends X-RateLimit-Reset on every response, so a permissions failure carries one
        // while the quota is untouched. Reading it as a limit would park the route in a cooldown
        // and serve stale metadata until a window the failure has nothing to do with.
        val failure = ApkDownloadHttpErrors.fromResponse(
            source = source,
            code = 403,
            responseMessage = "Forbidden",
            retryAfter = null,
            rateLimitRemaining = "4999",
            rateLimitResetEpochSeconds = (now / 1000L + 1_800L).toString(),
            nowMillis = now
        )

        assertEquals(ApkDownloadFailureReason.HttpFailure, failure.reason)
        assertNull(failure.retryAtMillis)
        assertEquals(
            listOf(source.displayName, "403", "Forbidden"),
            failure.messageArgs
        )
    }

    @Test
    fun `a secondary limit is still caught by its Retry-After`() {
        // The quota is intact, so only Retry-After marks this one. It has to keep working, or
        // tightening the reset-header case would blind the client to secondary limits.
        val failure = ApkDownloadHttpErrors.fromResponse(
            source = source,
            code = 403,
            responseMessage = "Forbidden",
            retryAfter = "90",
            rateLimitRemaining = "4999",
            rateLimitResetEpochSeconds = (now / 1000L + 1_800L).toString(),
            nowMillis = now
        )

        assertEquals(ApkDownloadFailureReason.RateLimited, failure.reason)
        assertEquals(now + 90_000L, failure.retryAtMillis)
    }

    @Test
    fun `invalid or overflowing retry headers never crash error mapping`() {
        assertNull(
            ApkDownloadHttpErrors.retryAtMillis(
                retryAfter = Long.MAX_VALUE.toString(),
                rateLimitResetEpochSeconds = Long.MAX_VALUE.toString(),
                nowMillis = now
            )
        )
    }

    @Test
    fun `content ranges validate resume offsets and totals`() {
        assertEquals(
            ContentRange(start = 1_024L, endInclusive = 2_047L, total = 4_096L),
            parseContentRange("bytes 1024-2047/4096")
        )
        assertEquals(4_096L, parseUnsatisfiedContentRangeTotal("bytes */4096"))
        assertNull(parseContentRange("bytes nope"))
        assertNull(parseContentRange("bytes 20-10/100"))
        assertNull(parseContentRange("bytes 90-100/100"))
    }

    @Test
    fun `a full response discards bytes from the release that was being resumed`() {
        val tempFile = temporaryFolder.newFile("download-temp.apk")
        tempFile.writeBytes("old-release-prefix".toByteArray())

        prepareApkTempFileForResponse(tempFile, appendResponse = false)
        tempFile.appendBytes("new-release".toByteArray())

        assertEquals("new-release", tempFile.readText())
    }

    @Test
    fun `a valid partial response keeps resumable bytes`() {
        val tempFile = temporaryFolder.newFile("download-temp.apk")
        tempFile.writeBytes("first-".toByteArray())

        prepareApkTempFileForResponse(tempFile, appendResponse = true)
        tempFile.appendBytes("second".toByteArray())

        assertEquals("first-second", tempFile.readText())
    }

    @Test
    fun `version comparison is host independent`() {
        assertTrue(AppVersion.isNewer("1.7.4", "1.7.5"))
        assertFalse(AppVersion.isNewer("1.7.5", "1.7.4"))
        assertFalse(AppVersion.isNewer("v1.7.5", "1.7.5"))
        assertTrue(AppVersion.isNewer("1.7", "1.7.1"))
    }

    @Test
    fun `worker policy allows exactly three total attempts`() {
        val transient = IOException("offline")

        assertTrue(ApkDownloadRetryPolicy.shouldRetry(runAttemptCount = 0, transient))
        assertTrue(ApkDownloadRetryPolicy.shouldRetry(runAttemptCount = 1, transient))
        assertFalse(ApkDownloadRetryPolicy.shouldRetry(runAttemptCount = 2, transient))
        assertFalse(
            ApkDownloadRetryPolicy.shouldRetry(
                runAttemptCount = 0,
                ApkDownloadException(
                    message = "invalid APK",
                    reason = ApkDownloadFailureReason.Generic,
                    retryable = false
                )
            )
        )
    }

    @Test
    fun `a failure reason survives the round trip through its key`() {
        // WorkManager keeps failed records across app updates, so the key written by one build is
        // read by the next. Resource ids are reassigned per build and would resolve to the wrong
        // string; the name does not move.
        ApkDownloadFailureReason.entries.forEach { reason ->
            assertEquals(reason, ApkDownloadFailureReason.fromKey(reason.name))
        }
    }

    @Test
    fun `an absent or retired reason falls back instead of resolving nothing`() {
        assertEquals(
            ApkDownloadFailureReason.Generic,
            ApkDownloadFailureReason.fromKey(null)
        )
        assertEquals(
            ApkDownloadFailureReason.Generic,
            ApkDownloadFailureReason.fromKey("ReasonFromAFutureBuild")
        )
    }

    private fun httpError(code: Int): ApkDownloadException {
        return ApkDownloadHttpErrors.fromResponse(
            source = source,
            code = code,
            responseMessage = "test",
            retryAfter = null,
            rateLimitRemaining = null,
            rateLimitResetEpochSeconds = null,
            nowMillis = now
        )
    }
}
