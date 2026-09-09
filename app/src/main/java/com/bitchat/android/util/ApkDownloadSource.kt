package com.bitchat.android.util

import androidx.annotation.StringRes
import com.bitchat.android.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * A trusted location that serves the latest signed universal BitChat APK.
 *
 * Sources are tried in order. A source may list compatibility filenames, which
 * are only used when the preferred asset is absent. Adding a mirror should only
 * require another entry; resume, retry, and verification do not depend on the host.
 */
data class ApkDownloadSource(
    val id: String,
    val displayName: String,
    val latestApkUrls: List<String>
) {
    constructor(id: String, displayName: String, latestApkUrl: String) : this(
        id = id,
        displayName = displayName,
        latestApkUrls = listOf(latestApkUrl)
    )

    init {
        require(id.isNotBlank()) { "Download source id must not be blank" }
        require(displayName.isNotBlank()) { "Download source name must not be blank" }
        require(latestApkUrls.isNotEmpty()) { "Download source must have at least one URL" }
        require(latestApkUrls.distinct().size == latestApkUrls.size) {
            "Download source URLs must be unique"
        }
        require(latestApkUrls.all { it.startsWith("https://") }) {
            "APK download sources must use HTTPS"
        }
    }
}

internal object DefaultApkDownloadSources {
    const val GITHUB_ID = "github-releases"

    val all = listOf(
        ApkDownloadSource(
            id = GITHUB_ID,
            displayName = "GitHub Releases",
            latestApkUrls = listOf(
                "https://github.com/permissionlesstech/bitchat-android/releases/latest/" +
                    "download/bitchat-android-universal.apk",
                // Releases published before the stable asset-name rollout use
                // this filename. Remove when supported releases all use the primary URL.
                "https://github.com/permissionlesstech/bitchat-android/releases/latest/" +
                    "download/app-universal-release.apk"
            )
        )
    )
}

/**
 * Why a download failed, and which string says so.
 *
 * Crosses a WorkManager `Data` boundary by [name], never by resource id: WorkManager keeps failed
 * records in its own database across app updates, and AAPT2 reassigns `R.string` ids on every
 * build, so a persisted id would resolve against the wrong resource table after an update. Same
 * reasoning as [ApkDownloader.DownloadPhase.fromKey].
 */
enum class ApkDownloadFailureReason(@StringRes val messageRes: Int) {
    Generic(R.string.prepare_apk_error_generic),
    Cancelled(R.string.prepare_apk_download_cancelled),
    RateLimited(R.string.prepare_apk_error_rate_limited),
    NoUniversalApk(R.string.prepare_apk_error_no_universal),
    HttpFailure(R.string.prepare_apk_error_http),
    InsufficientStorage(R.string.prepare_apk_error_storage_needed),
    NoSources(R.string.prepare_apk_error_no_sources),
    TorConnecting(R.string.prepare_apk_error_tor_connecting),
    NoUsableUrl(R.string.prepare_apk_error_no_url),
    Unreachable(R.string.prepare_apk_error_unreachable),
    InsecureRedirect(R.string.prepare_apk_error_insecure_redirect),
    ResumeRejected(R.string.prepare_apk_error_resume_rejected),
    Incomplete(R.string.prepare_apk_error_incomplete),
    InvalidResume(R.string.prepare_apk_error_invalid_resume),
    UntrustedKey(R.string.prepare_apk_error_untrusted_key),
    NotUniversal(R.string.prepare_apk_error_not_universal),
    ApkUnreadable(R.string.prepare_apk_error_apk_unreadable),
    NotBitchat(R.string.prepare_apk_error_not_bitchat),
    NoVersion(R.string.prepare_apk_error_no_version),
    SourceFailed(R.string.prepare_apk_error_source_failed),
    AllSourcesFailed(R.string.prepare_apk_error_all_sources);

    companion object {
        /** Work enqueued by an older build may name a reason this build no longer has. */
        fun fromKey(key: String?): ApkDownloadFailureReason =
            entries.firstOrNull { it.name == key } ?: Generic
    }
}

/**
 * A host-neutral download failure that tells the worker whether backoff can help.
 *
 * [reason] and [messageArgs] name what the user should be told without saying it in any
 * particular language. This layer has no Context by design — that is what keeps its tests plain
 * JUnit — so the ViewModel resolves them. The inherited [message] stays English for logs and
 * stack traces, and is never shown.
 */
class ApkDownloadException(
    message: String,
    val reason: ApkDownloadFailureReason,
    val messageArgs: List<String> = emptyList(),
    val retryable: Boolean,
    val sourceId: String? = null,
    val httpCode: Int? = null,
    val retryAtMillis: Long? = null,
    cause: Throwable? = null
) : IOException(message, cause)

internal object ApkDownloadRetryPolicy {
    const val MAX_ATTEMPTS = 3

    fun shouldRetry(runAttemptCount: Int, error: Throwable?): Boolean {
        val retryable = when (error) {
            is ApkDownloadException -> error.retryable
            is IOException -> true
            else -> false
        }
        val attemptNumber = runAttemptCount + 1
        return retryable && attemptNumber < MAX_ATTEMPTS
    }
}

internal fun shouldTryNextSourceUrl(
    error: ApkDownloadException,
    hasMoreUrls: Boolean
): Boolean = hasMoreUrls && error.httpCode == 404

internal object ApkDownloadHttpErrors {
    fun fromResponse(
        source: ApkDownloadSource,
        code: Int,
        responseMessage: String,
        retryAfter: String?,
        rateLimitRemaining: String?,
        rateLimitResetEpochSeconds: String?,
        nowMillis: Long = System.currentTimeMillis()
    ): ApkDownloadException {
        val retryAt = retryAtMillis(
            retryAfter = retryAfter,
            rateLimitResetEpochSeconds = rateLimitResetEpochSeconds,
            nowMillis = nowMillis
        )
        // X-RateLimit-Reset rides on every GitHub response, an ordinary 403 included, so it
        // cannot tell an exhausted quota from a permissions failure. Only a spent quota or an
        // explicit Retry-After says this request was the one that got limited. The reset header
        // still supplies the deadline below, once being limited is established some other way.
        val retryAfterMillis = retryAtMillis(
            retryAfter = retryAfter,
            rateLimitResetEpochSeconds = null,
            nowMillis = nowMillis
        )
        val rateLimited = code == 429 ||
            (code == 403 && (rateLimitRemaining?.trim() == "0" || retryAfterMillis != null))

        if (rateLimited) {
            return ApkDownloadException(
                message = "${source.id} rate limited: HTTP $code, retryAt=$retryAt",
                reason = ApkDownloadFailureReason.RateLimited,
                messageArgs = listOf(source.displayName),
                retryable = false,
                sourceId = source.id,
                httpCode = code,
                retryAtMillis = retryAt
            )
        }

        val retryable = code == 408 || code == 425 || code >= 500
        return ApkDownloadException(
            message = "${source.id} failed: HTTP $code $responseMessage",
            reason = if (code == 404) {
                ApkDownloadFailureReason.NoUniversalApk
            } else {
                ApkDownloadFailureReason.HttpFailure
            },
            messageArgs = if (code == 404) {
                listOf(source.displayName)
            } else {
                listOf(source.displayName, code.toString(), responseMessage)
            },
            retryable = retryable,
            sourceId = source.id,
            httpCode = code
        )
    }

    internal fun retryAtMillis(
        retryAfter: String?,
        rateLimitResetEpochSeconds: String?,
        nowMillis: Long
    ): Long? {
        retryAfter?.trim()?.toLongOrNull()
            ?.takeIf { it > 0L }
            ?.let { seconds ->
                runCatching {
                    Math.addExact(nowMillis, Math.multiplyExact(seconds, 1000L))
                }.getOrNull()?.let { return it }
            }

        retryAfter?.trim()?.takeIf { it.isNotEmpty() }?.let { value ->
            val parsed = runCatching {
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                    .toInstant()
                    .toEpochMilli()
            }.getOrNull()
            if (parsed != null && parsed > nowMillis) return parsed
        }

        return rateLimitResetEpochSeconds?.trim()?.toLongOrNull()
            ?.let { runCatching { Instant.ofEpochSecond(it).toEpochMilli() }.getOrNull() }
            ?.takeIf { it > nowMillis }
    }
}

internal object AppVersion {
    fun isNewer(currentVersion: String, candidateVersion: String): Boolean {
        val current = currentVersion.removePrefix("v").trim()
        val candidate = candidateVersion.removePrefix("v").trim()
        if (current == candidate) return false

        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val candidateParts = candidate.split(".").mapNotNull { it.toIntOrNull() }
        val maxLength = maxOf(currentParts.size, candidateParts.size)

        for (index in 0 until maxLength) {
            val currentPart = currentParts.getOrNull(index) ?: 0
            val candidatePart = candidateParts.getOrNull(index) ?: 0
            if (candidatePart != currentPart) return candidatePart > currentPart
        }
        return false
    }
}

internal data class ContentRange(
    val start: Long,
    val endInclusive: Long,
    val total: Long?
)

/**
 * Makes a response body safe to append after resume metadata is updated.
 * A full 200 replacement must discard bytes from the release that supplied the Range request.
 */
internal fun prepareApkTempFileForResponse(tempFile: File, appendResponse: Boolean) {
    if (!appendResponse) FileOutputStream(tempFile, false).use { }
}

internal fun parseContentRange(value: String?): ContentRange? {
    if (value == null) return null
    val match = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
        .matchEntire(value.trim())
        ?: return null
    val start = match.groupValues[1].toLongOrNull() ?: return null
    val end = match.groupValues[2].toLongOrNull() ?: return null
    if (end < start) return null
    val total = match.groupValues[3].takeUnless { it == "*" }?.toLongOrNull()
    if (total != null && end >= total) return null
    return ContentRange(
        start = start,
        endInclusive = end,
        total = total
    )
}

internal fun parseUnsatisfiedContentRangeTotal(value: String?): Long? {
    if (value == null) return null
    return Regex("""bytes\s+\*/(\d+)""", RegexOption.IGNORE_CASE)
        .matchEntire(value.trim())
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
}
