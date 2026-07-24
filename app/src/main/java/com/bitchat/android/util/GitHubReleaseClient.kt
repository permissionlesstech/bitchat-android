package com.bitchat.android.util

import android.util.Log
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for fetching BitChat release information from GitHub API.
 */
object GitHubReleaseClient {
    private const val TAG = "GitHubAPI"
    private const val GITHUB_API_URL = "https://api.github.com/repos/permissionlesstech/bitchat-android/releases/latest"
    private const val USER_AGENT = "BitChat-Android"
    private const val CACHE_TTL_MILLIS = 10 * 60 * 1000L
    private const val MAX_FETCH_ATTEMPTS = 3
    private const val ROUTE_READY_TIMEOUT_MILLIS = 60_000L

    private val fetchMutex = Mutex()

    @Volatile
    private var cachedRelease: CachedRelease? = null

    private val client
        get() = OkHttpProvider.httpClient().newBuilder()
            // GitHub requests may travel through Tor, where a 15-second total
            // timeout is too aggressive during circuit establishment.
            .callTimeout(45, TimeUnit.SECONDS)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

    /**
     * Fetch the latest release information from GitHub.
     * Successful metadata is cached briefly so the status screen and download
     * worker use the same release snapshot instead of making duplicate calls.
     */
    suspend fun fetchLatestRelease(forceRefresh: Boolean = false): Result<Release> =
        withContext(Dispatchers.IO) {
            fetchMutex.withLock {
                if (!forceRefresh) {
                    cachedRelease
                        ?.takeIf { System.currentTimeMillis() - it.fetchedAtMillis < CACHE_TTL_MILLIS }
                        ?.let { return@withLock Result.success(it.release) }
                }

                if (!awaitSelectedNetworkRoute()) {
                    return@withLock Result.failure(
                        ReleaseFetchException(
                            message = "Tor is still connecting. Try again when Tor is ready.",
                            retryable = true
                        )
                    )
                }

                var lastFailure: Throwable = ReleaseFetchException(
                    "Failed to fetch the latest release from GitHub"
                )

                repeat(MAX_FETCH_ATTEMPTS) { attempt ->
                    val result = fetchLatestReleaseOnce()
                    result.onSuccess { release ->
                        cachedRelease = CachedRelease(release, System.currentTimeMillis())
                        return@withLock Result.success(release)
                    }
                    lastFailure = result.exceptionOrNull() ?: lastFailure

                    if (!isRetryable(lastFailure) || attempt == MAX_FETCH_ATTEMPTS - 1) {
                        return@withLock Result.failure(lastFailure)
                    }

                    delay(1_000L shl attempt)
                }

                Result.failure(lastFailure)
            }
        }

    /**
     * Wait for Tor when it is the selected route. This deliberately does not
     * fall back to a direct connection because doing so would violate the
     * user's Tor preference.
     */
    suspend fun awaitSelectedNetworkRoute(): Boolean {
        return ArtiTorManager.getInstance()
            .awaitSelectedRoute(ROUTE_READY_TIMEOUT_MILLIS)
    }

    private fun fetchLatestReleaseOnce(): Result<Release> {
        return try {
            Log.d(TAG, "Fetching latest release from GitHub API")
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val remaining = response.header("X-RateLimit-Remaining")
                    val resetAt = response.header("X-RateLimit-Reset")
                    val message = when {
                        response.code == 403 && remaining == "0" ->
                            "GitHub API rate limit exceeded. Try again after reset time $resetAt."
                        response.code == 429 ->
                            "GitHub API rate limit exceeded. Please try again later."
                        else ->
                            "GitHub release request failed: HTTP ${response.code} ${response.message}"
                    }
                    Log.e(TAG, message)
                    return Result.failure(
                        ReleaseFetchException(
                            message = message,
                            httpCode = response.code,
                            retryable = response.code == 403 ||
                                response.code == 408 ||
                                response.code == 429 ||
                                response.code >= 500
                        )
                    )
                }

                val body = response.body?.string()
                if (body.isNullOrBlank()) {
                    return Result.failure(
                        ReleaseFetchException(
                            message = "GitHub returned an empty response",
                            retryable = true
                        )
                    )
                }

                val release = parseRelease(body)
                    ?: return Result.failure(
                        ReleaseFetchException(
                            message = "GitHub's latest release has no universal APK asset",
                            retryable = false
                        )
                    )
                Result.success(release)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error fetching release", e)
            Result.failure(
                ReleaseFetchException(
                    "Could not reach GitHub${e.message?.let { ": $it" } ?: ""}",
                    cause = e
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching release", e)
            Result.failure(ReleaseFetchException("Invalid GitHub release response", cause = e))
        }
    }

    private fun isRetryable(error: Throwable): Boolean {
        return error !is ReleaseFetchException || error.retryable
    }

    /**
     * Parse GitHub API JSON response into Release object.
     */
    internal fun parseRelease(jsonString: String): Release? {
        try {
            val json = JSONObject(jsonString)
            val tagName = json.optString("tag_name", "")
            val versionName = tagName.removePrefix("v") // Remove "v" prefix if present

            if (versionName.isBlank()) {
                Log.e(TAG, "No version tag found in release")
                return null
            }

            Log.d(TAG, "Found release: $versionName")

            // Parse assets array to find universal APK
            val assets = json.optJSONArray("assets")
            if (assets == null || assets.length() == 0) {
                Log.e(TAG, "No assets found in release")
                return null
            }

            // Look for universal APK (usually named "app-universal-release.apk")
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                val name = asset.optString("name", "")

                if (name.contains("universal", ignoreCase = true) && name.endsWith(".apk")) {
                    val downloadUrl = asset.optString("browser_download_url", "")
                    val size = asset.optLong("size", 0L)

                    if (downloadUrl.isBlank()) {
                        Log.e(TAG, "Universal APK found but no download URL")
                        continue
                    }

                    // Prefer GitHub's asset digest when available, then fall
                    // back to release notes used by older releases.
                    val body = json.optString("body", "")
                    val assetDigest = asset.optString("digest", "")
                        .takeIf { it.startsWith("sha256:", ignoreCase = true) }
                        ?.substringAfter(":")
                        ?.takeIf { it.matches(Regex("[a-fA-F0-9]{64}")) }
                        ?.lowercase()
                    val sha256 = assetDigest ?: extractSha256FromBody(body, name)

                    Log.d(TAG, "Found universal APK: $name (${size / 1024 / 1024}MB)")

                    return Release(
                        tagName = tagName,
                        versionName = versionName,
                        universalApkUrl = downloadUrl,
                        universalApkSha256 = sha256,
                        universalApkSize = size,
                        universalApkName = name
                    )
                }
            }

            Log.e(TAG, "No universal APK found in release assets")
            return null

        } catch (e: Exception) {
            Log.e(TAG, "Error parsing release JSON", e)
            return null
        }
    }

    /**
     * Extract SHA256 checksum from release body/notes.
     * Looks for patterns like:
     * - sha256:abc123...
     * - SHA256: abc123...
     * - app-universal-release.apk: abc123...
     */
    private fun extractSha256FromBody(body: String, apkName: String): String? {
        if (body.isBlank()) return null

        try {
            // Pattern 1: Look for "sha256:" followed by hash
            val sha256Pattern = Regex("""sha256:\s*([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE)
            sha256Pattern.find(body)?.let { match ->
                return match.groupValues[1].lowercase()
            }

            // Pattern 2: Look for APK name followed by hash
            val apkPattern = Regex("""${Regex.escape(apkName)}.*?([a-fA-F0-9]{64})""", RegexOption.IGNORE_CASE)
            apkPattern.find(body)?.let { match ->
                return match.groupValues[1].lowercase()
            }

            Log.w(TAG, "Could not extract SHA256 from release body")
            return null

        } catch (e: Exception) {
            Log.w(TAG, "Error extracting SHA256", e)
            return null
        }
    }

    /**
     * Check if a newer version is available.
     * @param currentVersion Current installed/cached version
     * @param latestRelease Latest release from GitHub
     * @return true if latestRelease is newer
     */
    fun isNewerVersion(currentVersion: String, latestRelease: Release): Boolean {
        return isNewerVersion(currentVersion, latestRelease.versionName)
    }

    internal fun isNewerVersion(currentVersion: String, candidateVersion: String): Boolean {
        return try {
            // Simple version comparison (assumes semantic versioning)
            // Remove any non-numeric prefixes
            val current = currentVersion.removePrefix("v").trim()
            val latest = candidateVersion.removePrefix("v").trim()

            if (current == latest) {
                return false
            }

            // Split by dots and compare each part
            val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
            val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }

            val maxLength = maxOf(currentParts.size, latestParts.size)

            for (i in 0 until maxLength) {
                val currentPart = currentParts.getOrNull(i) ?: 0
                val latestPart = latestParts.getOrNull(i) ?: 0

                if (latestPart > currentPart) {
                    return true
                } else if (latestPart < currentPart) {
                    return false
                }
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Error comparing versions", e)
            false
        }
    }

    /**
     * Release information from GitHub.
     */
    data class Release(
        val tagName: String,
        val versionName: String,
        val universalApkUrl: String,
        val universalApkSha256: String?,
        val universalApkSize: Long,
        val universalApkName: String
    )

    class ReleaseFetchException(
        message: String,
        val httpCode: Int? = null,
        val retryable: Boolean = true,
        cause: Throwable? = null
    ) : IOException(message, cause)

    private data class CachedRelease(
        val release: Release,
        val fetchedAtMillis: Long
    )
}
