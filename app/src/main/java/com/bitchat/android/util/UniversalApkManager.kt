package com.bitchat.android.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.bitchat.android.BuildConfig
import com.bitchat.android.net.OkHttpProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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
    private val downloadClient
        get() = OkHttpProvider.httpClient().newBuilder()
            .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
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
            val source = runCatching {
                ApkSource.valueOf(json.optString("source", ApkSource.GITHUB.name))
            }.getOrDefault(ApkSource.GITHUB)

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
                file = apkFile,
                source = source
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
            // A genuinely universal standalone APK is already an installable
            // sharing artifact. Architecture-specific standalone APKs and split
            // installs still need the universal GitHub artifact.
            val installedApkInfo = cacheInstalledApkIfPreferred()
            if (installedApkInfo != null) {
                return@withContext UpdateStatus.UpToDate(installedApkInfo.version)
            }

            val cachedInfo = getCachedApkInfo()
            val latestRelease = GitHubReleaseClient.fetchLatestRelease().getOrElse { error ->
                return@withContext UpdateStatus.Error(
                    error.message ?: "Failed to fetch latest release from GitHub"
                )
            }
            // The GitHub release may briefly lag behind the installed version
            // (upstream bumps versionName in main before tagging the release).
            // An older release is still a genuine, signed, universal artifact —
            // recipients with a newer install can't be downgraded by Android
            // anyway — so share it rather than disabling the feature.
            if (isOlderThanInstalledVersion(latestRelease.versionName)) {
                Log.i(
                    TAG,
                    "GitHub universal APK ${latestRelease.versionName} is older than installed " +
                        "app ${installedVersionName()}; sharing it until the matching release ships"
                )
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
            // Reuses the short-lived release metadata cache populated by the
            // status check. If this worker is running after process death, the
            // client performs a retried network fetch instead.
            val release = GitHubReleaseClient.fetchLatestRelease().getOrElse { error ->
                return@withContext Result.failure(error)
            }

            if (!GitHubReleaseClient.awaitSelectedNetworkRoute()) {
                return@withContext Result.failure(
                    IOException("Tor is still connecting. Try the download again when Tor is ready.")
                )
            }

            val url = release.universalApkUrl
            val expectedSize = release.universalApkSize

            Log.d(TAG, "Downloading from: $url")
            Log.d(TAG, "Expected size: ${expectedSize / 1024 / 1024}MB")

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

            // Bytes already in the temp file have already consumed storage, so
            // a resume only needs room for the remaining tail. Promotion is a
            // rename and needs no extra space.
            checkDiskSpace((expectedSize - existingBytes).coerceAtLeast(0))

            // A temp file that already holds the full asset means the process
            // died between download and verification. Requesting
            // "Range: bytes=<size>-" for it would get HTTP 416 forever, so skip
            // the network and let checksum/signature verification decide its fate.
            if (expectedSize > 0 && existingBytes >= expectedSize) {
                Log.d(TAG, "Temp file already complete ($existingBytes bytes), skipping to verification")
            } else {
                val requestBuilder = Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "BitChat-Android")

                if (existingBytes > 0) {
                    requestBuilder.addHeader("Range", "bytes=$existingBytes-")
                    Log.d(TAG, "Added Range header: bytes=$existingBytes-")
                }

                val request = requestBuilder.build()
                downloadToTempFile(
                    call = downloadClient.newCall(request),
                    tempFile = tempFile,
                    url = url,
                    expectedSize = expectedSize,
                    versionName = release.versionName,
                    existingBytes = existingBytes,
                    progressCallback = progressCallback
                )
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

            // Verify the downloaded APK against trusted signing certificates.
            Log.d(TAG, "Verifying APK signature...")
            if (!verifyApkSignature(tempFile)) {
                tempFile.delete()
                progressFile.delete()
                return@withContext Result.failure(
                    Exception("APK signature verification failed. The downloaded APK is not signed by a trusted BitChat release key.")
                )
            }
            Log.d(TAG, "Signature verified successfully")

            if (!DistributionInfoProvider.isUniversalApk(tempFile)) {
                tempFile.delete()
                progressFile.delete()
                return@withContext Result.failure(
                    Exception(
                        "GitHub asset is architecture-specific, not universal. " +
                            "Release packaging must be corrected."
                    )
                )
            }

            // Move to final location without deleting the currently usable APK
            // first. Old versions are removed only after the replacement and
            // metadata have both been committed.
            val finalFileName = "$APK_FILE_PREFIX${release.versionName}.apk"
            val finalFile = File(cacheDir, finalFileName)
            replaceFileSafely(tempFile, finalFile)

            // Clean up resume metadata on success
            progressFile.delete()

            // Save metadata
            saveMetadata(
                version = release.versionName,
                checksum = release.universalApkSha256 ?: "",
                size = finalFile.length(),
                fileName = finalFileName,
                source = ApkSource.GITHUB
            )
            cleanupOldApks(except = finalFile)

            Log.d(TAG, "Universal APK downloaded successfully: ${finalFile.path}")
            Result.success(finalFile)

        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.e(TAG, "Network error downloading APK", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK", e)
            Result.failure(e)
        }
    }

    /**
     * Streams an HTTP response into [tempFile] while keeping the coroutine
     * suspended for the lifetime of the response body. Cancelling the worker
     * therefore cancels the OkHttp call and promptly unblocks a pending read.
     */
    private suspend fun downloadToTempFile(
        call: Call,
        tempFile: File,
        url: String,
        expectedSize: Long,
        versionName: String,
        existingBytes: Long,
        progressCallback: ((Int) -> Unit)?
    ) = suspendCancellableCoroutine { continuation ->
        fun completeSuccessfully() {
            continuation.resumeWith(Result.success(Unit))
        }

        fun completeWithError(error: Throwable) {
            continuation.resumeWith(Result.failure(error))
        }

        continuation.invokeOnCancellation {
            call.cancel()
        }

        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    completeWithError(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (response.code == 416) {
                                // Our offset is no longer valid for this asset; discard
                                // the partial state so the retry starts from scratch.
                                Log.w(TAG, "Server rejected resume range, restarting download")
                                tempFile.delete()
                                progressFile.delete()
                                throw IOException(
                                    "Resume rejected by server. Download will restart."
                                )
                            }
                            if (!response.isSuccessful && response.code != 206) {
                                throw IOException(
                                    "Download failed: ${response.code} ${response.message}"
                                )
                            }

                            val body = response.body
                                ?: throw IOException("Empty response body")

                            // Handle resume: 206 = partial content (append), 200 = full
                            // content (overwrite).
                            val append = response.code == 206
                            val resumedBytes = if (!append && existingBytes > 0) {
                                Log.d(
                                    TAG,
                                    "Server didn't honor Range request, starting from scratch"
                                )
                                0L
                            } else {
                                existingBytes
                            }

                            saveResumeInfo(url, expectedSize, versionName)

                            if (resumedBytes > 0 && expectedSize > 0) {
                                val initialProgress =
                                    ((resumedBytes * 100) / expectedSize).toInt()
                                progressCallback?.invoke(initialProgress)
                            }

                            body.byteStream().use { input ->
                                FileOutputStream(tempFile, append).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var bytesRead: Int
                                    var totalBytesRead = resumedBytes
                                    var lastProgress = if (expectedSize > 0) {
                                        ((resumedBytes * 100) / expectedSize).toInt()
                                    } else {
                                        0
                                    }

                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        totalBytesRead += bytesRead

                                        if (expectedSize > 0) {
                                            val progress =
                                                ((totalBytesRead * 100) / expectedSize).toInt()
                                            if (progress != lastProgress) {
                                                lastProgress = progress
                                                progressCallback?.invoke(progress)
                                            }
                                        }
                                    }

                                    Log.d(
                                        TAG,
                                        "Download complete: ${totalBytesRead / 1024 / 1024}MB"
                                    )
                                }
                            }
                        }
                        completeSuccessfully()
                    } catch (e: Exception) {
                        completeWithError(e)
                    }
                }
            })
        } catch (e: Exception) {
            completeWithError(e)
        }
    }

    /**
     * Cache the APK this process was installed from only when it is both
     * standalone and universal. A base APK from a split install is incomplete,
     * while an ABI-specific APK would unnecessarily limit recipients.
     */
    private fun cacheInstalledApkIfPreferred(): ApkInfo? {
        return try {
            val applicationInfo = context.applicationInfo
            if (!applicationInfo.splitSourceDirs.isNullOrEmpty()) {
                return null
            }

            val installedApk = File(applicationInfo.sourceDir)
            if (!installedApk.isFile || installedApk.length() <= 0L) {
                return null
            }
            if (!DistributionInfoProvider.isUniversalApk(installedApk)) {
                Log.d(TAG, "Installed APK is architecture-specific; using GitHub universal APK")
                discardArchitectureLimitedInstalledCache()
                return null
            }

            val installedVersion = installedVersionName()
            val cachedInfo = getCachedApkInfo()

            // Keep an already cached artifact if it is the same version or
            // newer. Otherwise prefer the running build so sharing cannot
            // silently downgrade recipients to an older GitHub release.
            if (cachedInfo != null &&
                !GitHubReleaseClient.isNewerVersion(cachedInfo.version, installedVersion)
            ) {
                return cachedInfo
            }

            checkDiskSpace(installedApk.length())
            val safeVersion = installedVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val finalFileName = "$APK_FILE_PREFIX$safeVersion.apk"
            val finalFile = File(cacheDir, finalFileName)
            val pendingFile = File(cacheDir, "$finalFileName.new")

            installedApk.inputStream().use { input ->
                FileOutputStream(pendingFile).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
            replaceFileSafely(pendingFile, finalFile)

            val checksum = calculateChecksum(finalFile)
            saveMetadata(
                version = installedVersion,
                checksum = checksum,
                size = finalFile.length(),
                fileName = finalFileName,
                source = ApkSource.INSTALLED
            )
            cleanupOldApks(except = finalFile)

            Log.d(TAG, "Cached running standalone APK for offline sharing")
            getCachedApkInfo()
        } catch (e: Exception) {
            Log.w(TAG, "Running APK cannot be used as a standalone sharing artifact", e)
            null
        }
    }

    private fun discardArchitectureLimitedInstalledCache() {
        val cachedInfo = getCachedApkInfo() ?: return
        if (cachedInfo.source != ApkSource.INSTALLED ||
            DistributionInfoProvider.isUniversalApk(cachedInfo.file)
        ) {
            return
        }

        cachedInfo.file.delete()
        metadataFile.delete()
        Log.d(TAG, "Removed architecture-specific installed APK from universal sharing cache")
    }

    private fun installedVersionName(): String {
        return context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.VERSION_NAME
    }

    private fun isOlderThanInstalledVersion(candidateVersion: String): Boolean {
        return GitHubReleaseClient.isNewerVersion(candidateVersion, installedVersionName())
    }

    /**
     * Verify the downloaded APK against either the running app's signing lineage
     * or the pinned GitHub release certificate. The latter supports Play installs
     * when GitHub distribution uses a separate, explicitly trusted release key.
     * Debug builds without a configured pin accept any signed (never unsigned) APK.
     */
    private fun verifyApkSignature(apkFile: File): Boolean {
        return try {
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

            val ownCerts = signatureDigests(
                context.packageManager.getPackageInfo(context.packageName, signingFlags())
            )
            val pinnedReleaseCert = normalizeCertificateDigest(
                BuildConfig.GITHUB_RELEASE_CERT_SHA256
            )
            val trustedCerts = ownCerts + listOfNotNull(pinnedReleaseCert)

            // Debug builds may use a different local signing key, but still
            // require the downloaded artifact itself to be signed. Production
            // builds must match either this installation's signing lineage or
            // the explicitly pinned GitHub release certificate.
            if (BuildConfig.DEBUG && pinnedReleaseCert == null) {
                Log.w(TAG, "Debug build has no pinned release certificate; accepting signed APK")
                return true
            }

            if (trustedCerts.isEmpty()) {
                Log.e(TAG, "No trusted APK signing certificates are configured")
                return false
            }

            val matches = apkCerts.intersect(trustedCerts).isNotEmpty()
            if (!matches) {
                Log.e(TAG, "Signature mismatch!")
                Log.e(TAG, "Trusted cert(s): $trustedCerts")
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

    private fun normalizeCertificateDigest(value: String): String? {
        return value
            .replace(":", "")
            .trim()
            .lowercase()
            .takeIf { it.matches(Regex("[a-f0-9]{64}")) }
    }

    /**
     * Verify the SHA256 checksum of a file.
     */
    suspend fun verifyChecksum(file: File, expectedSha256: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val checksum = calculateChecksum(file)
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

    private fun calculateChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
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
    private fun cleanupOldApks(except: File) {
        try {
            cacheDir.listFiles()?.forEach { file ->
                if (file != except &&
                    file.name.startsWith(APK_FILE_PREFIX) &&
                    file.name.endsWith(".apk")
                ) {
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
    private fun saveMetadata(
        version: String,
        checksum: String,
        size: Long,
        fileName: String,
        source: ApkSource
    ) {
        val json = JSONObject().apply {
            put("version", version)
            put("checksum", checksum)
            put("downloadDate", System.currentTimeMillis())
            put("size", size)
            put("fileName", fileName)
            put("source", source.name)
        }

        val pendingMetadata = File(cacheDir, "$METADATA_FILE_NAME.new")
        pendingMetadata.writeText(json.toString())
        replaceFileSafely(pendingMetadata, metadataFile)
        Log.d(TAG, "Saved metadata: $version")
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
     * Commit [source] to [target] without removing a valid target first.
     * Both files live in the same cache directory, so this is a rename, not a
     * copy — no extra disk space is needed and ATOMIC_MOVE either fully
     * succeeds or leaves both files intact.
     */
    private fun replaceFileSafely(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
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
        val file: File,
        val source: ApkSource
    )

    enum class ApkSource {
        INSTALLED,
        GITHUB
    }

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
