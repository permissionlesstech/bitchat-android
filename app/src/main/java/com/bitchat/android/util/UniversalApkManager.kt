package com.bitchat.android.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Manages downloading, caching, and verifying the universal APK for offline sharing.
 */
class UniversalApkManager(private val context: Context) {

    companion object {
        private const val TAG = "UniversalApk"
        private const val CACHE_DIR_NAME = "universal_apk"
        private const val METADATA_FILE_NAME = "universal_apk_info.json"
        private const val APK_FILE_PREFIX = "bitchat-universal-"

        // Download buffer size (128KB)
        private const val BUFFER_SIZE = 128 * 1024
    }

    private val cacheDir: File = File(context.cacheDir, CACHE_DIR_NAME).apply {
        if (!exists()) {
            mkdirs()
        }
    }

    private val metadataFile: File = File(cacheDir, METADATA_FILE_NAME)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Get information about the cached universal APK, if it exists.
     */
    fun getCachedApkInfo(): ApkInfo? {
        return try {
            if (!metadataFile.exists()) {
                return null
            }

            val json = JSONObject(metadataFile.readText())
            val version = json.optString("version", "")
            val checksum = json.optString("checksum", "")
            val downloadDate = json.optLong("downloadDate", 0L)
            val size = json.optLong("size", 0L)
            val fileName = json.optString("fileName", "")

            if (version.isBlank() || fileName.isBlank()) {
                return null
            }

            val apkFile = File(cacheDir, fileName)
            if (!apkFile.exists()) {
                Log.w(TAG, "Metadata exists but APK file not found: ${apkFile.path}")
                return null
            }

            ApkInfo(
                version = version,
                checksum = checksum,
                downloadDate = downloadDate,
                size = size,
                file = apkFile
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error reading cached APK info", e)
            null
        }
    }

    /**
     * Get the cached APK file, if it exists.
     */
    fun getCachedApk(): File? {
        return getCachedApkInfo()?.file
    }

    /**
     * Check for updates from GitHub.
     * @return UpdateStatus indicating if update is available, current version, etc.
     */
    suspend fun checkForUpdate(): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val cachedInfo = getCachedApkInfo()
            val latestRelease = GitHubReleaseClient.fetchLatestRelease()

            if (latestRelease == null) {
                return@withContext UpdateStatus.Error("Failed to fetch latest release from GitHub")
            }

            if (cachedInfo == null) {
                // No cached APK
                return@withContext UpdateStatus.NotDownloaded(latestRelease)
            }

            // Compare versions
            val isNewer = GitHubReleaseClient.isNewerVersion(cachedInfo.version, latestRelease)

            if (isNewer) {
                UpdateStatus.UpdateAvailable(
                    currentVersion = cachedInfo.version,
                    latestRelease = latestRelease
                )
            } else {
                UpdateStatus.UpToDate(cachedInfo.version)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error checking for update", e)
            UpdateStatus.Error(e.message ?: "Unknown error")
        }
    }

    /**
     * Download the universal APK from GitHub.
     * @param progressCallback Called with progress percentage (0-100)
     * @return Result with File on success, or error message
     */
    suspend fun downloadUniversalApk(
        progressCallback: ((Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Starting universal APK download")

            // Fetch latest release info
            val release = GitHubReleaseClient.fetchLatestRelease()
                ?: return@withContext Result.failure(Exception("Failed to fetch release info"))

            val url = release.universalApkUrl
            val expectedSize = release.universalApkSize

            Log.d(TAG, "Downloading from: $url")
            Log.d(TAG, "Expected size: ${expectedSize / 1024 / 1024}MB")

            // Download to temporary file first
            val tempFile = File(cacheDir, "download_temp.apk")
            if (tempFile.exists()) {
                tempFile.delete()
            }

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "BitChat-Android")
                .build()

            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    IOException("Download failed: ${response.code} ${response.message}")
                )
            }

            val body = response.body
                ?: return@withContext Result.failure(IOException("Empty response body"))

            // Download with progress tracking
            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalBytesRead = 0L
                    var lastProgress = 0

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytesRead += bytesRead

                        // Report progress
                        if (expectedSize > 0) {
                            val progress = ((totalBytesRead * 100) / expectedSize).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                progressCallback?.invoke(progress)
                            }
                        }
                    }

                    Log.d(TAG, "Download complete: ${totalBytesRead / 1024 / 1024}MB")
                }
            }

            // Verify checksum if available
            if (release.universalApkSha256 != null) {
                Log.d(TAG, "Verifying checksum...")
                val isValid = verifyChecksum(tempFile, release.universalApkSha256)
                if (!isValid) {
                    tempFile.delete()
                    return@withContext Result.failure(
                        Exception("Checksum verification failed. Downloaded file may be corrupted.")
                    )
                }
                Log.d(TAG, "Checksum verified successfully")
            } else {
                Log.w(TAG, "No checksum available for verification")
            }

            // Move to final location
            val finalFileName = "$APK_FILE_PREFIX${release.versionName}.apk"
            val finalFile = File(cacheDir, finalFileName)

            // Clean up old APK files
            cleanupOldApks()

            // Move temp file to final location
            if (finalFile.exists()) {
                finalFile.delete()
            }
            tempFile.renameTo(finalFile)

            // Save metadata
            saveMetadata(
                version = release.versionName,
                checksum = release.universalApkSha256 ?: "",
                size = finalFile.length(),
                fileName = finalFileName
            )

            Log.d(TAG, "Universal APK downloaded successfully: ${finalFile.path}")
            Result.success(finalFile)

        } catch (e: IOException) {
            Log.e(TAG, "Network error downloading APK", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK", e)
            Result.failure(e)
        }
    }

    /**
     * Verify the SHA256 checksum of a file.
     */
    suspend fun verifyChecksum(file: File, expectedSha256: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            val matches = checksum.equals(expectedSha256, ignoreCase = true)

            if (!matches) {
                Log.e(TAG, "Checksum mismatch!")
                Log.e(TAG, "Expected: $expectedSha256")
                Log.e(TAG, "Actual:   $checksum")
            }

            matches
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying checksum", e)
            false
        }
    }

    /**
     * Delete the cached universal APK.
     */
    fun deleteCachedApk(): Boolean {
        return try {
            val info = getCachedApkInfo()
            if (info != null) {
                info.file.delete()
                metadataFile.delete()
                Log.d(TAG, "Deleted cached APK: ${info.version}")
                true
            } else {
                Log.w(TAG, "No cached APK to delete")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting cached APK", e)
            false
        }
    }

    /**
     * Clean up old APK files (keep only the current one).
     */
    private fun cleanupOldApks() {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.startsWith(APK_FILE_PREFIX) && file.name.endsWith(".apk")) {
                    file.delete()
                    Log.d(TAG, "Cleaned up old APK: ${file.name}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error cleaning up old APKs", e)
        }
    }

    /**
     * Save metadata about the downloaded APK.
     */
    private fun saveMetadata(version: String, checksum: String, size: Long, fileName: String) {
        try {
            val json = JSONObject().apply {
                put("version", version)
                put("checksum", checksum)
                put("downloadDate", System.currentTimeMillis())
                put("size", size)
                put("fileName", fileName)
            }

            metadataFile.writeText(json.toString())
            Log.d(TAG, "Saved metadata: $version")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving metadata", e)
        }
    }

    /**
     * Information about a cached APK.
     */
    data class ApkInfo(
        val version: String,
        val checksum: String,
        val downloadDate: Long,
        val size: Long,
        val file: File
    )

    /**
     * Update check status.
     */
    sealed class UpdateStatus {
        data class NotDownloaded(val latestRelease: GitHubReleaseClient.Release) : UpdateStatus()
        data class UpToDate(val currentVersion: String) : UpdateStatus()
        data class UpdateAvailable(
            val currentVersion: String,
            val latestRelease: GitHubReleaseClient.Release
        ) : UpdateStatus()
        data class Error(val message: String) : UpdateStatus()
    }
}
