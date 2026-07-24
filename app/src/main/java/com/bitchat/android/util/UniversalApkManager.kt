package com.bitchat.android.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.bitchat.android.net.OkHttpProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest

/**
 * Manages downloading, caching, and verifying the universal APK for offline sharing.
 */
class UniversalApkManager(private val context: Context) {

    companion object {
        private const val TAG = "UniversalApk"
        private const val CACHE_DIR_NAME = "universal_apk"
        private const val METADATA_FILE_NAME = "universal_apk_info.json"
        private const val PROGRESS_FILE_NAME = "download_progress.json"
        private const val APK_FILE_PREFIX = "bitchat-universal-"

        // Download buffer size (128KB)
        private const val BUFFER_SIZE = 128 * 1024
    }

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

    private val metadataFile: File get() = File(cacheDir, METADATA_FILE_NAME)
    private val progressFile: File get() = File(cacheDir, PROGRESS_FILE_NAME)

    // Download client: inherits Tor proxy settings but with no call timeout
    // for large file downloads that can take minutes
    private val downloadClient by lazy {
        OkHttpProvider.httpClient().newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

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
     * Check if a partial (resumable) download exists.
     * Returns the progress percentage (0-100) or null if no partial download.
     */
    fun getPartialDownloadProgress(): Int? {
        val tempFile = File(cacheDir, "download_temp.apk")
        val resumeInfo = loadResumeInfo()
        if (tempFile.exists() && resumeInfo != null) {
            val expectedSize = resumeInfo.optLong("expectedSize", 0L)
            if (expectedSize > 0) {
                return ((tempFile.length() * 100) / expectedSize).toInt().coerceIn(0, 99)
            }
        }
        return null
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
     * Check if there's enough disk space to download the APK.
     * Requires 1.5x the file size for safety margin (temp + final file).
     * @throws IOException if insufficient space
     */
    private fun checkDiskSpace(requiredSize: Long) {
        val availableSpace = cacheDir.usableSpace
        val requiredWithMargin = (requiredSize * 1.5).toLong()

        if (availableSpace < requiredWithMargin) {
            val requiredMB = requiredWithMargin / 1024 / 1024
            val availableMB = availableSpace / 1024 / 1024
            val error = "Insufficient storage: need ${requiredMB}MB, have ${availableMB}MB"
            Log.e(TAG, error)
            throw IOException(error)
        }
    }

    /**
     * Download the universal APK from GitHub with resume support.
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

            // Check available disk space before downloading
            checkDiskSpace(expectedSize)

            val tempFile = File(cacheDir, "download_temp.apk")

            // Check for resumable download
            var existingBytes = 0L
            if (tempFile.exists()) {
                val resumeInfo = loadResumeInfo()
                if (resumeInfo != null &&
                    resumeInfo.optString("url") == url &&
                    resumeInfo.optString("versionName") == release.versionName
                ) {
                    existingBytes = tempFile.length()
                    Log.d(TAG, "Resuming download from $existingBytes bytes")
                } else {
                    Log.d(TAG, "Stale temp file found, starting fresh")
                    tempFile.delete()
                    progressFile.delete()
                }
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "BitChat-Android")

            if (existingBytes > 0) {
                requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                Log.d(TAG, "Added Range header: bytes=$existingBytes-")
            }

            val request = requestBuilder.build()

            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    return@withContext Result.failure(
                        IOException("Download failed: ${response.code} ${response.message}")
                    )
                }

                val body = response.body
                    ?: return@withContext Result.failure(IOException("Empty response body"))

                // Handle resume: 206 = partial content (append), 200 = full content (overwrite)
                val append = response.code == 206
                if (!append && existingBytes > 0) {
                    Log.d(TAG, "Server didn't honor Range request, starting from scratch")
                    existingBytes = 0
                }

                // Save resume metadata
                saveResumeInfo(url, expectedSize, release.versionName)

                // Report initial progress when resuming
                if (existingBytes > 0 && expectedSize > 0) {
                    val initialProgress = ((existingBytes * 100) / expectedSize).toInt()
                    progressCallback?.invoke(initialProgress)
                }

                // Download with progress tracking
                body.byteStream().use { input ->
                    FileOutputStream(tempFile, append).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        var bytesRead: Int
                        var totalBytesRead = existingBytes
                        var lastProgress = if (expectedSize > 0) ((existingBytes * 100) / expectedSize).toInt() else 0

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
            }

            // Verify checksum if available
            if (release.universalApkSha256 != null) {
                Log.d(TAG, "Verifying checksum...")
                val isValid = verifyChecksum(tempFile, release.universalApkSha256)
                if (!isValid) {
                    tempFile.delete()
                    progressFile.delete()
                    return@withContext Result.failure(
                        Exception("Checksum verification failed. Downloaded file may be corrupted.")
                    )
                }
                Log.d(TAG, "Checksum verified successfully")
            } else {
                Log.w(TAG, "No checksum available for verification")
            }

            // Verify the downloaded APK is signed with the same certificate as this app
            Log.d(TAG, "Verifying APK signature...")
            if (!verifyApkSignature(tempFile)) {
                tempFile.delete()
                progressFile.delete()
                return@withContext Result.failure(
                    Exception("APK signature verification failed. The downloaded APK is not signed with the same key as this app.")
                )
            }
            Log.d(TAG, "Signature verified successfully")

            // Move to final location
            val finalFileName = "$APK_FILE_PREFIX${release.versionName}.apk"
            val finalFile = File(cacheDir, finalFileName)

            // Clean up old APK files
            cleanupOldApks()

            // Move temp file to final location
            if (finalFile.exists()) {
                finalFile.delete()
            }

            // Try rename first (fast), fallback to copy if it fails (different partitions/filesystems)
            val moved = tempFile.renameTo(finalFile)
            if (!moved) {
                Log.w(TAG, "Rename failed, falling back to copy")
                tempFile.copyTo(finalFile, overwrite = true)
                tempFile.delete()
            }

            // Clean up resume metadata on success
            progressFile.delete()

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
     * Verify the downloaded APK is signed with the same certificate as the running app.
     * No hardcoded fingerprint needed: if the certs match, receivers of the shared APK
     * end up in the same signature lineage as this installation.
     *
     * Debug-signed installations (Android Debug keystore) skip enforcement so the
     * feature stays testable during development.
     */
    private fun verifyApkSignature(apkFile: File): Boolean {
        return try {
            val ownCerts = signatureDigests(
                context.packageManager.getPackageInfo(context.packageName, signingFlags())
            )
            if (ownCerts.isEmpty()) {
                Log.w(TAG, "Could not determine own signing certificate, skipping verification")
                return true
            }

            if (isDebugSigned()) {
                Log.w(TAG, "App is debug-signed, skipping signature enforcement")
                return true
            }

            val packageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, signingFlags())
                ?: run {
                    Log.e(TAG, "Could not parse APK for signature verification")
                    return false
                }
            val apkCerts = signatureDigests(packageInfo)
            if (apkCerts.isEmpty()) {
                Log.e(TAG, "No signatures found in downloaded APK")
                return false
            }

            val matches = apkCerts.intersect(ownCerts).isNotEmpty()
            if (!matches) {
                Log.e(TAG, "Signature mismatch!")
                Log.e(TAG, "Own cert(s): $ownCerts")
                Log.e(TAG, "APK cert(s): $apkCerts")
            }
            matches
        } catch (e: Exception) {
            Log.e(TAG, "Error verifying APK signature", e)
            false
        }
    }

    private fun signingFlags(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
    }

    private fun signatureDigests(packageInfo: android.content.pm.PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = packageInfo.signingInfo ?: return emptySet()
            if (signingInfo.hasMultipleSigners()) {
                signingInfo.apkContentsSigners
            } else {
                signingInfo.signingCertificateHistory
            }
        } else {
            @Suppress("DEPRECATION")
            packageInfo.signatures
        }
        if (signatures.isNullOrEmpty()) return emptySet()

        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { sig ->
            digest.digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun isDebugSigned(): Boolean {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, signingFlags())
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                packageInfo.signatures
            } ?: return false

            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            signatures.any { sig ->
                val cert = certFactory.generateCertificate(sig.toByteArray().inputStream())
                        as java.security.cert.X509Certificate
                cert.subjectX500Principal.name.contains("Android Debug")
            }
        } catch (e: Exception) {
            false
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
                progressFile.delete()
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

    private fun saveResumeInfo(url: String, expectedSize: Long, versionName: String) {
        try {
            val json = JSONObject().apply {
                put("url", url)
                put("expectedSize", expectedSize)
                put("versionName", versionName)
            }
            progressFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving resume info", e)
        }
    }

    private fun loadResumeInfo(): JSONObject? {
        return try {
            if (progressFile.exists()) {
                JSONObject(progressFile.readText())
            } else null
        } catch (e: Exception) {
            Log.e(TAG, "Error loading resume info", e)
            null
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
