package com.bitchat.android.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Fetch the latest release information from GitHub.
     * @return Release object with details, or null if fetch fails
     */
    suspend fun fetchLatestRelease(): Release? = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Fetching latest release from GitHub API")

            val request = Request.Builder()
                .url(GITHUB_API_URL)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Accept", "application/vnd.github.v3+json")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                Log.e(TAG, "GitHub API request failed: ${response.code} ${response.message}")
                return@withContext null
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                Log.e(TAG, "Empty response body from GitHub API")
                return@withContext null
            }

            parseRelease(body)
        } catch (e: IOException) {
            Log.e(TAG, "Network error fetching release", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching release", e)
            null
        }
    }

    /**
     * Parse GitHub API JSON response into Release object.
     */
    private fun parseRelease(jsonString: String): Release? {
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

                    // Try to extract SHA256 from release body or notes
                    val body = json.optString("body", "")
                    val sha256 = extractSha256FromBody(body, name)

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

            // Pattern 3: Look for any SHA256 hash (64 hex characters)
            val hashPattern = Regex("""([a-fA-F0-9]{64})""")
            val matches = hashPattern.findAll(body).toList()

            // If we find exactly one hash, assume it's for the universal APK
            if (matches.size == 1) {
                return matches[0].groupValues[1].lowercase()
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
        return try {
            // Simple version comparison (assumes semantic versioning)
            // Remove any non-numeric prefixes
            val current = currentVersion.removePrefix("v").trim()
            val latest = latestRelease.versionName.removePrefix("v").trim()

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
}
