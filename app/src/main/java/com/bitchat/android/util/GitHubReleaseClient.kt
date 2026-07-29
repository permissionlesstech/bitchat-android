package com.bitchat.android.util

import android.util.Log
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import com.bitchat.android.net.TorMode
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
    private const val HTTP_NOT_MODIFIED = 304

    private val fetchMutex = Mutex()

    @Volatile
    private var cachedRelease: CachedRelease? = null

    /**
     * ETag of the cached release, replayed as `If-None-Match`. GitHub does not charge a 304
     * against the rate limit, so revalidating an expired cache this way costs nothing where an
     * unconditional refetch costs one of only 60 hourly requests.
     */
    @Volatile
    private var cachedEtag: String? = null

    /**
     * Epoch millis before which GitHub has already told us it will reject anything we send,
     * held per route.
     *
     * GitHub counts unauthenticated requests per IP, so a Tor exit and a direct connection
     * have separate quotas. They are kept side by side rather than as one deadline that
     * moves with the route: replacing it would mean switching away and back forgets a
     * cooldown that is still running, and the app would hit the limited exit again.
     *
     * Without any of this, an exhausted quota fed itself: nothing cached the failure, so
     * every screen that asked for release info spent three more requests rediscovering the
     * same limit.
     */
    @Volatile
    private var torBlockedUntilMillis = 0L

    @Volatile
    private var directBlockedUntilMillis = 0L

    /**
     * The route requests will take, which is what the quota belongs to.
     *
     * Deliberately the selected mode rather than `isProxyEnabled()`: that reports
     * readiness, and is false while Tor is still bootstrapping or restarting even though
     * requests will still go through Tor once it is up.
     */
    private fun selectedRouteUsesTor(): Boolean? =
        runCatching { ArtiTorManager.getInstance().statusFlow.value.mode != TorMode.OFF }
            .getOrNull()

    /**
     * The deadline for the route about to be used. When the route cannot be determined the
     * stricter of the two applies: failing to identify it must not release a real cooldown.
     */
    private fun blockedUntilFor(routeUsesTor: Boolean?): Long = when (routeUsesTor) {
        true -> torBlockedUntilMillis
        false -> directBlockedUntilMillis
        null -> maxOf(torBlockedUntilMillis, directBlockedUntilMillis)
    }

    private fun recordBlockedUntil(untilMillis: Long, routeUsesTor: Boolean?) {
        when (routeUsesTor) {
            true -> torBlockedUntilMillis = untilMillis
            false -> directBlockedUntilMillis = untilMillis
            null -> {
                torBlockedUntilMillis = untilMillis
                directBlockedUntilMillis = untilMillis
            }
        }
    }

    /** A success proves this route is clear. The other route's cooldown is left alone. */
    private fun clearBlockedFor(routeUsesTor: Boolean?) {
        when (routeUsesTor) {
            true -> torBlockedUntilMillis = 0L
            false -> directBlockedUntilMillis = 0L
            null -> {
                torBlockedUntilMillis = 0L
                directBlockedUntilMillis = 0L
            }
        }
    }

    /**
     * The gate's answer for [routeUsesTor], or null when nothing blocks the request.
     *
     * Sending a request GitHub has already said it will reject helps nobody and pushes
     * the reset further out, so a stale release is a better answer than an error the
     * user cannot act on.
     */
    private fun blockedResultOrNull(
        nowMillis: Long,
        routeUsesTor: Boolean?,
        cached: CachedRelease?,
    ): Result<Release>? {
        val blockedUntil = blockedUntilFor(routeUsesTor)
        if (nowMillis >= blockedUntil) return null

        val waitMinutes = (blockedUntil - nowMillis) / 60_000 + 1
        Log.w(TAG, "Rate limited; not contacting GitHub for another ${waitMinutes}min")
        cached?.let { return Result.success(it.release) }
        return Result.failure(
            ReleaseFetchException(
                message = "GitHub API rate limit reached. Try again in " +
                    "$waitMinutes minute${if (waitMinutes == 1L) "" else "s"}.",
                httpCode = 429,
                retryable = false
            )
        )
    }

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
    /**
     * @param onAwaitingNetworkRoute invoked if this call is about to block on the selected route
     *   (a Tor bootstrap can take the better part of a minute). A cache hit returns before that
     *   point and never invokes it, so callers can report the wait only when there is one.
     */
    suspend fun fetchLatestRelease(
        forceRefresh: Boolean = false,
        onAwaitingNetworkRoute: (() -> Unit)? = null,
        onResolvingRelease: (() -> Unit)? = null,
    ): Result<Release> =
        withContext(Dispatchers.IO) {
            fetchMutex.withLock {
                val now = System.currentTimeMillis()
                val cached = cachedRelease

                if (!forceRefresh &&
                    cached != null &&
                    now - cached.fetchedAtMillis < CACHE_TTL_MILLIS
                ) {
                    return@withLock Result.success(cached.release)
                }

                // Honoured even on an explicit refresh.
                blockedResultOrNull(now, selectedRouteUsesTor(), cached)
                    ?.let { return@withLock it }

                onAwaitingNetworkRoute?.invoke()
                if (!awaitSelectedNetworkRoute()) {
                    return@withLock Result.failure(
                        ReleaseFetchException(
                            message = "Tor is still connecting. Try again when Tor is ready.",
                            retryable = true
                        )
                    )
                }
                // The wait is over, so stop saying we are waiting. In direct mode it
                // returned immediately and never really started, and the fetch below
                // retries -- either way the caller must not keep reporting a Tor wait
                // for the whole metadata request.
                onResolvingRelease?.invoke()

                var lastFailure: Throwable = ReleaseFetchException(
                    "Failed to fetch the latest release from GitHub"
                )

                repeat(MAX_FETCH_ATTEMPTS) { attempt ->
                    // Sampled immediately before the call and reused for its response, so
                    // a route change mid-flight cannot file the cooldown against the route
                    // the request did not use.
                    val routeUsesTor = selectedRouteUsesTor()

                    // Per attempt rather than once before the loop. The route can change
                    // during the wait above, during a request, or during a backoff, and
                    // the one we have just switched to may carry a cooldown of its own.
                    blockedResultOrNull(System.currentTimeMillis(), routeUsesTor, cached)
                        ?.let { return@withLock it }

                    val result = fetchLatestReleaseOnce(routeUsesTor)
                    result.onSuccess { release ->
                        cachedRelease = CachedRelease(release, System.currentTimeMillis())
                        return@withLock Result.success(release)
                    }
                    lastFailure = result.exceptionOrNull() ?: lastFailure

                    // The response that just set the gate is the one the user is waiting
                    // on. Reporting an error here and only serving the cache on the next
                    // call makes the first check fail and an immediate retry succeed from
                    // metadata we already had.
                    if (System.currentTimeMillis() < blockedUntilFor(routeUsesTor)) {
                        cached?.let {
                            Log.w(TAG, "Rate limited; serving the cached release instead of failing")
                            return@withLock Result.success(it.release)
                        }
                        return@withLock Result.failure(lastFailure)
                    }

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

    private fun fetchLatestReleaseOnce(routeUsesTor: Boolean?): Result<Release> {
        val cached = cachedRelease
        val etag = cachedEtag
        return try {
            Log.d(TAG, "Fetching latest release from GitHub API")
            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/vnd.github+json")
                .addHeader("X-GitHub-Api-Version", "2022-11-28")
                .apply {
                    // Revalidate rather than refetch. GitHub does not charge a 304 against the
                    // hourly quota, so an unchanged release costs nothing to confirm.
                    if (cached != null && etag != null) addHeader("If-None-Match", etag)
                }
                .build()

            client.newCall(request).execute().use { response ->
                if (response.code == HTTP_NOT_MODIFIED && cached != null) {
                    Log.d(TAG, "Release unchanged; cache revalidated at no quota cost")
                    cachedRelease = cached.copy(fetchedAtMillis = System.currentTimeMillis())
                    return Result.success(cached.release)
                }

                if (!response.isSuccessful) {
                    val blockedUntil = GitHubRateLimit.blockedUntilMillis(
                        code = response.code,
                        remaining = response.header("X-RateLimit-Remaining"),
                        resetEpochSeconds = response.header("X-RateLimit-Reset"),
                        retryAfterSeconds = response.header("Retry-After"),
                        nowMillis = System.currentTimeMillis(),
                    )
                    if (blockedUntil != null) {
                        // Recorded against the route the request took, not the one
                        // selected now: the setting can change while a call is in flight.
                        recordBlockedUntil(blockedUntil, routeUsesTor)
                    }

                    val message = if (blockedUntil != null) {
                        val waitMinutes =
                            (blockedUntil - System.currentTimeMillis()) / 60_000 + 1
                        "GitHub API rate limit exceeded. Try again in " +
                            "$waitMinutes minute${if (waitMinutes == 1L) "" else "s"}."
                    } else {
                        "GitHub release request failed: HTTP ${response.code} ${response.message}"
                    }
                    Log.e(TAG, message)
                    return Result.failure(
                        ReleaseFetchException(
                            message = message,
                            httpCode = response.code,
                            // A rate limit is never worth an in-loop retry: the gate in
                            // fetchLatestRelease decides when it is worth asking again. A plain
                            // 403 is a permissions failure and will not fix itself either.
                            retryable = blockedUntil == null &&
                                (response.code == 408 || response.code >= 500)
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
                // Kept alongside the release so the pair can never drift: a stale ETag would
                // revalidate to a 304 that confirms a release we no longer hold.
                cachedEtag = response.header("ETag")
                clearBlockedFor(routeUsesTor)
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
