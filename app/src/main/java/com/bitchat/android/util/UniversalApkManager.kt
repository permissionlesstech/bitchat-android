package com.bitchat.android.util

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.bitchat.android.BuildConfig
import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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
 * Manages local and downloaded APK artifacts for offline sharing.
 */
class UniversalApkManager(
    private val context: Context,
    private val downloadSources: List<ApkDownloadSource> = DefaultApkDownloadSources.all
) {
    init {
        require(downloadSources.map { it.id }.distinct().size == downloadSources.size) {
            "APK download source ids must be unique"
        }
    }

    companion object {
        private const val TAG = "UniversalApk"
        private const val CACHE_DIR_NAME = "universal_apk"
        private const val METADATA_FILE_NAME = "universal_apk_info.json"
        private const val PROGRESS_FILE_NAME = "download_progress.json"
        private const val APK_FILE_PREFIX = "bitchat-universal-"
        private const val TEMP_FILE_NAME = "download_temp.apk"
        private const val ROUTE_READY_TIMEOUT_MILLIS = 60_000L

        // Download buffer size (128KB)
        private const val BUFFER_SIZE = 128 * 1024
    }

    private val cacheDir: File
        get() = File(context.cacheDir, CACHE_DIR_NAME).also { it.mkdirs() }

    private val metadataFile: File get() = File(cacheDir, METADATA_FILE_NAME)
    private val progressFile: File get() = File(cacheDir, PROGRESS_FILE_NAME)
    private val rateLimits = ApkRateLimitStore(context)

    // Download client: inherits the current client's actual route but has no call timeout for
    // large files that can take minutes. Keep the route attached for route-specific cooldowns.
    private fun downloadClient(): OkHttpProvider.RoutedClient {
        val routed = OkHttpProvider.routedHttpClient()
        return routed.copy(
            client = routed.client.newBuilder()
                .callTimeout(0, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        )
    }

    /**
     * Get information about the cached sharing APK, if it exists.
     */
    fun getCachedApkInfo(): ApkInfo? {
        return try {
            if (!metadataFile.exists()) {
                return null
            }

            val json = JSONObject(metadataFile.readText())
            val version = json.optString("version", "")
            val downloadDate = json.optLong("downloadDate", 0L)
            val size = json.optLong("size", 0L)
            val fileName = json.optString("fileName", "")
            val source = when (json.optString("source")) {
                ApkSource.INSTALLED.name -> ApkSource.INSTALLED
                // Migrate metadata written before downloads became mirror-agnostic.
                else -> ApkSource.DOWNLOADED
            }
            val downloadSourceId = json.optString("downloadSourceId")
                .takeIf { it.isNotBlank() }

            if (version.isBlank() || fileName.isBlank()) {
                return null
            }

            val apkFile = File(cacheDir, fileName)
            if (!apkFile.exists()) {
                Log.w(TAG, "Metadata exists but APK file not found: ${apkFile.path}")
                return null
            }
            val variant = runCatching {
                ShareableApkVariant.valueOf(json.optString("variant"))
            }.getOrNull() ?: DistributionInfoProvider.shareableApkVariant(apkFile)
            if (variant == null) {
                Log.w(TAG, "Cached APK is not a supported sharing variant")
                return null
            }

            ApkInfo(
                version = version,
                downloadDate = downloadDate,
                size = size,
                file = apkFile,
                source = source,
                variant = variant,
                downloadSourceId = downloadSourceId
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
        val tempFile = File(cacheDir, TEMP_FILE_NAME)
        val resumeInfo = loadResumeInfo()
        if (tempFile.exists() && resumeInfo != null) {
            val expectedSize = resumeInfo.expectedSize
            if (expectedSize > 0) {
                return ((tempFile.length() * 100) / expectedSize).toInt().coerceIn(0, 99)
            }
        }
        return null
    }

    /**
     * Prepare or read the best local sharing artifact. This never performs a
     * network request, so opening the About sheet cannot consume API quota or
     * wait for Tor.
     */
    suspend fun prepareLocalApkInfo(): ApkInfo? = withContext(Dispatchers.IO) {
        cacheInstalledApkIfPreferred() ?: getCachedApkInfo()
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
            throw ApkDownloadException(
                message = error,
                reason = ApkDownloadFailureReason.InsufficientStorage,
                messageArgs = listOf(requiredMB.toString(), availableMB.toString()),
                retryable = false
            )
        }
    }

    /**
     * Download from the configured sources. Each source gets one attempt in this
     * worker run; WorkManager owns retry/backoff across runs.
     */
    suspend fun downloadUniversalApk(
        progressCallback: ((Int) -> Unit)? = null,
        phaseCallback: ((ApkDownloader.DownloadPhase) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (downloadSources.isEmpty()) {
            return@withContext Result.failure(
                ApkDownloadException(
                    message = "No APK download sources are configured.",
                    reason = ApkDownloadFailureReason.NoSources,
                    retryable = false
                )
            )
        }

        try {
            phaseCallback?.invoke(ApkDownloader.DownloadPhase.AwaitingNetworkRoute)
            if (!ArtiTorManager.getInstance().awaitSelectedRoute(ROUTE_READY_TIMEOUT_MILLIS)) {
                return@withContext Result.failure(
                    ApkDownloadException(
                        message = "Tor is still connecting.",
                        reason = ApkDownloadFailureReason.TorConnecting,
                        retryable = true
                    )
                )
            }

            val failures = mutableListOf<ApkDownloadException>()
            val sources = sourcesWithResumeFirst()
            for ((index, source) in sources.withIndex()) {
                phaseCallback?.invoke(ApkDownloader.DownloadPhase.SelectingSource)
                if (index > 0) clearPartialDownload()

                try {
                    Log.d(TAG, "Downloading universal APK from ${source.displayName}")
                    phaseCallback?.invoke(ApkDownloader.DownloadPhase.Transferring)
                    val tempFile = downloadFromSource(source, progressCallback)

                    phaseCallback?.invoke(ApkDownloader.DownloadPhase.VerifyingSignature)
                    validateDownloadedApk(tempFile, source)

                    // Everything from here to the metadata write is plain blocking code, so a
                    // cancellation arriving during the (slow) signature check would otherwise go
                    // unobserved and commit the APK anyway. The verified temp file survives for
                    // resume; only the promotion is abandoned.
                    ensureActive()

                    val version = downloadedVersionName(tempFile)
                    val safeVersion = version.replace(Regex("[^A-Za-z0-9._-]"), "_")
                    val finalFileName = "$APK_FILE_PREFIX$safeVersion.apk"
                    val finalFile = File(cacheDir, finalFileName)
                    // Package parsing above is blocking. A cancellation arriving while it runs
                    // must not promote the verified temporary file into the shareable slot.
                    ensureActive()
                    replaceFileSafely(tempFile, finalFile)
                    progressFile.delete()

                    saveMetadata(
                        version = version,
                        size = finalFile.length(),
                        fileName = finalFileName,
                        source = ApkSource.DOWNLOADED,
                        variant = ShareableApkVariant.UNIVERSAL,
                        downloadSourceId = source.id
                    )
                    cleanupOldApks(except = finalFile)
                    Log.d(TAG, "Universal APK downloaded successfully from ${source.displayName}")
                    return@withContext Result.success(finalFile)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val failure = e.asDownloadException(source)
                    failures += failure
                    Log.w(TAG, "${source.displayName} download failed", failure)
                    if (index < sources.lastIndex) {
                        Log.i(TAG, "Trying the next configured APK source")
                    }
                }
            }

            Result.failure(combineSourceFailures(failures))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading APK", e)
            Result.failure(e)
        }
    }

    private fun sourcesWithResumeFirst(): List<ApkDownloadSource> {
        val tempFile = File(cacheDir, TEMP_FILE_NAME)
        val resume = loadResumeInfo()
        if (!tempFile.exists() || resume == null) return downloadSources

        val resumedSource = downloadSources.firstOrNull { it.id == resume.sourceId }
        if (resumedSource == null) {
            clearPartialDownload()
            return downloadSources
        }
        return listOf(resumedSource) + downloadSources.filterNot { it.id == resumedSource.id }
    }

    private suspend fun downloadFromSource(
        source: ApkDownloadSource,
        progressCallback: ((Int) -> Unit)?
    ): File {
        val tempFile = File(cacheDir, TEMP_FILE_NAME)
        var resume = loadResumeInfo()
        if (resume?.sourceId != source.id) {
            clearPartialDownload()
            resume = null
        }

        var existingBytes = if (resume != null && tempFile.exists()) tempFile.length() else 0L
        if (resume != null && resume.expectedSize > 0L && existingBytes == resume.expectedSize) {
            Log.d(TAG, "Partial file is complete; continuing with APK verification")
            return tempFile
        }
        if (resume != null && resume.expectedSize > 0L && existingBytes > resume.expectedSize) {
            clearPartialDownload()
            resume = null
            existingBytes = 0L
        }

        val resumeUrl = resume?.endpointUrl?.takeIf { it in source.latestApkUrls }
        if (resume != null && resumeUrl == null) {
            clearPartialDownload()
            resume = null
            existingBytes = 0L
        }

        val endpoints = listOfNotNull(resumeUrl) +
            source.latestApkUrls.filterNot { it == resumeUrl }
        var lastFailure: ApkDownloadException? = null
        for ((index, endpointUrl) in endpoints.withIndex()) {
            if (index > 0) {
                clearPartialDownload()
                resume = null
                existingBytes = 0L
            }

            // A Range request is only safe with a validator. Without If-Range, a
            // newly published release could be appended to bytes from the old one.
            if (existingBytes > 0L && resume?.validator == null) {
                clearPartialDownload()
                resume = null
                existingBytes = 0L
            }

            try {
                executeDownloadRequest(
                    source = source,
                    endpointUrl = endpointUrl,
                    tempFile = tempFile,
                    existingBytes = existingBytes,
                    resume = resume,
                    progressCallback = progressCallback
                )
                return tempFile
            } catch (e: ApkDownloadException) {
                lastFailure = e
                val assetNameFallback = shouldTryNextSourceUrl(
                    error = e,
                    hasMoreUrls = index < endpoints.lastIndex
                )
                if (!assetNameFallback) throw e
                Log.i(TAG, "APK filename not found; trying ${source.displayName}'s fallback URL")
            }
        }
        throw lastFailure
            ?: ApkDownloadException(
                message = "${source.id} has no usable APK URL.",
                reason = ApkDownloadFailureReason.NoUsableUrl,
                messageArgs = listOf(source.displayName),
                retryable = false
            )
    }

    private suspend fun executeDownloadRequest(
        source: ApkDownloadSource,
        endpointUrl: String,
        tempFile: File,
        existingBytes: Long,
        resume: ResumeInfo?,
        progressCallback: ((Int) -> Unit)?
    ) {
        val routedClient = downloadClient()
        val rateLimitScope = "apk_asset_${source.id}"
        val now = System.currentTimeMillis()
        rateLimits.retryAtMillis(rateLimitScope, routedClient.route, now)?.let { deadline ->
            throw rateLimits.blockedException(source, deadline)
        }

        val request = Request.Builder()
            // Always start from the configured source endpoint. If a release changed,
            // If-Range makes the server return 200 and we overwrite the partial.
            .url(endpointUrl)
            .addHeader("User-Agent", "BitChat-Android")
            .apply {
                if (existingBytes > 0L) {
                    addHeader("Range", "bytes=$existingBytes-")
                    resume?.validator?.let { addHeader("If-Range", it) }
                }
            }
            .build()

        downloadToTempFile(
            call = routedClient.client.newCall(request),
            source = source,
            rateLimitScope = rateLimitScope,
            route = routedClient.route,
            endpointUrl = endpointUrl,
            tempFile = tempFile,
            existingBytes = existingBytes,
            previousResume = resume,
            progressCallback = progressCallback
        )
    }

    /**
     * Streams an HTTP response into [tempFile]. Cancellation cancels the OkHttp
     * call, and resume metadata is committed before bytes are appended.
     */
    private suspend fun downloadToTempFile(
        call: Call,
        source: ApkDownloadSource,
        rateLimitScope: String,
        route: OkHttpProvider.Route,
        endpointUrl: String,
        tempFile: File,
        existingBytes: Long,
        previousResume: ResumeInfo?,
        progressCallback: ((Int) -> Unit)?
    ) = suspendCancellableCoroutine { continuation ->
        fun completeSuccessfully() {
            if (continuation.isActive) continuation.resumeWith(Result.success(Unit))
        }

        fun completeWithError(error: Throwable) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(error))
        }

        continuation.invokeOnCancellation { call.cancel() }

        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    completeWithError(
                        ApkDownloadException(
                            message = "${source.id} could not be reached" +
                                (e.message?.let { ": $it" } ?: "."),
                            reason = ApkDownloadFailureReason.Unreachable,
                            messageArgs = listOf(source.displayName),
                            retryable = true,
                            sourceId = source.id,
                            cause = e
                        )
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!response.request.url.isHttps) {
                                throw ApkDownloadException(
                                    message = "${source.id} redirected to an insecure URL.",
                                    reason = ApkDownloadFailureReason.InsecureRedirect,
                                    messageArgs = listOf(source.displayName),
                                    retryable = false,
                                    sourceId = source.id
                                )
                            }
                            if (response.code == 416) {
                                val total = parseUnsatisfiedContentRangeTotal(
                                    response.header("Content-Range")
                                )
                                if (total != null && total == existingBytes && tempFile.length() == total) {
                                    completeSuccessfully()
                                    return
                                }
                                clearPartialDownload()
                                throw ApkDownloadException(
                                    message = "${source.id} rejected the saved download position.",
                                    reason = ApkDownloadFailureReason.ResumeRejected,
                                    messageArgs = listOf(source.displayName),
                                    retryable = true,
                                    sourceId = source.id,
                                    httpCode = response.code
                                )
                            }
                            if (!response.isSuccessful) {
                                val failure = ApkDownloadHttpErrors.fromResponse(
                                    source = source,
                                    code = response.code,
                                    responseMessage = response.message,
                                    retryAfter = response.header("Retry-After"),
                                    rateLimitRemaining = response.header("X-RateLimit-Remaining"),
                                    rateLimitResetEpochSeconds =
                                        response.header("X-RateLimit-Reset")
                                )
                                if (failure.reason == ApkDownloadFailureReason.RateLimited) {
                                    val now = System.currentTimeMillis()
                                    val deadline = rateLimits.recordRateLimit(
                                        rateLimitScope,
                                        route,
                                        failure.retryAtMillis,
                                        now
                                    )
                                    throw rateLimits.blockedException(source, deadline)
                                }
                                throw failure
                            }

                            rateLimits.clear(rateLimitScope, route)

                            val body = response.body
                            val range = if (response.code == 206) {
                                parseContentRange(response.header("Content-Range"))
                                    ?: throw invalidResumeResponse(source, tempFile)
                            } else {
                                null
                            }
                            if (range != null && range.start != existingBytes) {
                                throw invalidResumeResponse(source, tempFile)
                            }

                            val append = range != null
                            val resumedBytes = if (append) existingBytes else 0L
                            val expectedSize = range?.total
                                ?: body.contentLength().takeIf { it >= 0L }?.let { length ->
                                    resumedBytes + length
                                }
                                ?: previousResume?.expectedSize?.takeIf { append }
                                ?: 0L
                            if (expectedSize > 0L) {
                                checkDiskSpace((expectedSize - resumedBytes).coerceAtLeast(0L))
                            }

                            val validator = response.header("ETag")
                                ?: response.header("Last-Modified")
                                ?: previousResume?.validator?.takeIf { append }

                            // A server may ignore Range and return a replacement 200 response.
                            // Remove the old bytes before recording the new validator. If the
                            // process dies at any later point, old and new release bytes cannot be
                            // combined on the next resume.
                            prepareApkTempFileForResponse(tempFile, append)
                            if (validator != null) {
                                saveResumeInfo(
                                    ResumeInfo(
                                        sourceId = source.id,
                                        endpointUrl = endpointUrl,
                                        expectedSize = expectedSize,
                                        validator = validator
                                    )
                                )
                            } else {
                                progressFile.delete()
                            }

                            if (resumedBytes > 0L && expectedSize > 0L) {
                                progressCallback?.invoke(
                                    ((resumedBytes * 100L) / expectedSize).toInt()
                                )
                            }

                            body.byteStream().use { input ->
                                // Non-resume responses were already truncated above; always append
                                // after resume metadata is safely committed.
                                FileOutputStream(tempFile, true).use { output ->
                                    val buffer = ByteArray(BUFFER_SIZE)
                                    var bytesRead: Int
                                    var totalBytesRead = resumedBytes
                                    var lastProgress = if (expectedSize > 0L) {
                                        ((resumedBytes * 100L) / expectedSize).toInt()
                                    } else {
                                        0
                                    }

                                    while (input.read(buffer).also { bytesRead = it } != -1) {
                                        output.write(buffer, 0, bytesRead)
                                        totalBytesRead += bytesRead
                                        if (expectedSize > 0L) {
                                            val progress = (
                                                (totalBytesRead * 100L) / expectedSize
                                                ).toInt().coerceIn(0, 100)
                                            if (progress != lastProgress) {
                                                lastProgress = progress
                                                progressCallback?.invoke(progress)
                                            }
                                        }
                                    }
                                }
                            }

                            if (expectedSize > 0L && tempFile.length() != expectedSize) {
                                if (tempFile.length() > expectedSize) clearPartialDownload()
                                throw ApkDownloadException(
                                    message = "${source.id} download ended before all bytes arrived.",
                                    reason = ApkDownloadFailureReason.Incomplete,
                                    messageArgs = listOf(source.displayName),
                                    retryable = true,
                                    sourceId = source.id
                                )
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

    private fun invalidResumeResponse(
        source: ApkDownloadSource,
        tempFile: File
    ): ApkDownloadException {
        tempFile.delete()
        progressFile.delete()
        return ApkDownloadException(
            message = "${source.id} returned an invalid resume response.",
            reason = ApkDownloadFailureReason.InvalidResume,
            messageArgs = listOf(source.displayName),
            retryable = true,
            sourceId = source.id
        )
    }

    private fun validateDownloadedApk(tempFile: File, source: ApkDownloadSource) {
        if (!verifyApkSignature(tempFile)) {
            clearPartialDownload()
            throw ApkDownloadException(
                message = "APK from ${source.id} is not signed by a trusted BitChat release key.",
                reason = ApkDownloadFailureReason.UntrustedKey,
                messageArgs = listOf(source.displayName),
                retryable = false,
                sourceId = source.id
            )
        }
        if (!DistributionInfoProvider.isUniversalApk(tempFile)) {
            clearPartialDownload()
            throw ApkDownloadException(
                message = "${source.id} returned an architecture-specific APK.",
                reason = ApkDownloadFailureReason.NotUniversal,
                messageArgs = listOf(source.displayName),
                retryable = false,
                sourceId = source.id
            )
        }
    }

    private fun downloadedVersionName(apkFile: File): String {
        val packageInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
            ?: invalidDownloadedApk(ApkDownloadFailureReason.ApkUnreadable, "unreadable APK")
        if (packageInfo.packageName != context.packageName) {
            invalidDownloadedApk(ApkDownloadFailureReason.NotBitchat, "wrong package")
        }
        return packageInfo.versionName
            ?.takeIf { it.isNotBlank() }
            ?: invalidDownloadedApk(ApkDownloadFailureReason.NoVersion, "no version name")
    }

    private fun invalidDownloadedApk(
        reason: ApkDownloadFailureReason,
        logReason: String
    ): Nothing {
        clearPartialDownload()
        throw ApkDownloadException(
            message = "Downloaded APK rejected: $logReason",
            reason = reason,
            retryable = false
        )
    }

    private fun Exception.asDownloadException(source: ApkDownloadSource): ApkDownloadException {
        if (this is ApkDownloadException) return this
        return ApkDownloadException(
            message = "${source.id} download failed" + (message?.let { ": $it" } ?: "."),
            reason = ApkDownloadFailureReason.SourceFailed,
            messageArgs = listOf(source.displayName),
            retryable = this is IOException,
            sourceId = source.id,
            cause = this
        )
    }

    private fun combineSourceFailures(
        failures: List<ApkDownloadException>
    ): ApkDownloadException {
        if (failures.size == 1) return failures.single()
        if (failures.isEmpty()) {
            return ApkDownloadException(
                message = "APK download failed with no recorded source failure.",
                reason = ApkDownloadFailureReason.Generic,
                retryable = false
            )
        }
        // The per-source detail stays in the log line. Concatenating each source's sentence would
        // mean re-assembling localized text here, where there is no Context to resolve it with.
        return ApkDownloadException(
            message = "All configured APK sources failed: " +
                failures.joinToString(" • ") { it.message ?: "Unknown error" },
            reason = ApkDownloadFailureReason.AllSourcesFailed,
            retryable = failures.any { it.retryable },
            cause = failures.last()
        )
    }

    private fun clearPartialDownload() {
        File(cacheDir, TEMP_FILE_NAME).delete()
        progressFile.delete()
    }

    /**
     * Cache the APK this process was installed from when it is a standalone
     * universal or ARM64 artifact. A base APK from a split install is incomplete.
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
            val installedVariant = DistributionInfoProvider.shareableApkVariant(installedApk)
            if (installedVariant == null) {
                Log.d(TAG, "Installed APK is not a supported sharing variant")
                return null
            }

            val installedVersion = installedVersionName()
            val cachedInfo = getCachedApkInfo()

            // Downloading the universal release is an explicit compatibility
            // choice. Keep it even when the running ARM64 build is newer; the
            // user can delete it from the UI to return to the local artifact.
            if (installedVariant == ShareableApkVariant.ARM64 &&
                cachedInfo?.source == ApkSource.DOWNLOADED &&
                cachedInfo.variant == ShareableApkVariant.UNIVERSAL
            ) {
                return cachedInfo
            }

            // Keep an already cached artifact if it is the same version or
            // newer. Otherwise prefer the running build so sharing cannot
            // silently downgrade recipients to an older downloadable release.
            if (cachedInfo != null &&
                !AppVersion.isNewer(cachedInfo.version, installedVersion)
            ) {
                return cachedInfo
            }

            checkDiskSpace(installedApk.length())
            val safeVersion = installedVersion.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val variantSuffix = when (installedVariant) {
                ShareableApkVariant.UNIVERSAL -> ""
                ShareableApkVariant.ARM64 -> "-arm64-v8a"
            }
            val finalFileName = "$APK_FILE_PREFIX$safeVersion$variantSuffix.apk"
            val finalFile = File(cacheDir, finalFileName)
            val pendingFile = File(cacheDir, "$finalFileName.new")

            installedApk.inputStream().use { input ->
                FileOutputStream(pendingFile).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
            replaceFileSafely(pendingFile, finalFile)

            saveMetadata(
                version = installedVersion,
                size = finalFile.length(),
                fileName = finalFileName,
                source = ApkSource.INSTALLED,
                variant = installedVariant,
                downloadSourceId = null
            )
            cleanupOldApks(except = finalFile)

            Log.d(TAG, "Cached running standalone APK for offline sharing")
            getCachedApkInfo()
        } catch (e: Exception) {
            Log.w(TAG, "Running APK cannot be used as a standalone sharing artifact", e)
            null
        }
    }

    private fun installedVersionName(): String {
        return context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName
            ?.takeIf { it.isNotBlank() }
            ?: BuildConfig.VERSION_NAME
    }

    /**
     * Verify the downloaded APK against either the running app's signing lineage
     * or the pinned release certificate. The latter supports Play installs when
     * downloadable artifacts use a separate, explicitly trusted release key.
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
            // Every mirror must serve the same official release-signed APK.
            // The BuildConfig field keeps its historical name for configuration compatibility.
            val pinnedReleaseCert = normalizeCertificateDigest(
                BuildConfig.GITHUB_RELEASE_CERT_SHA256
            )
            val trustedCerts = ownCerts + listOfNotNull(pinnedReleaseCert)

            // Debug builds may use a different local signing key, but still
            // require the downloaded artifact itself to be signed. Production
            // builds must match either this installation's signing lineage or
            // the explicitly pinned release certificate.
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
        size: Long,
        fileName: String,
        source: ApkSource,
        variant: ShareableApkVariant,
        downloadSourceId: String?
    ) {
        val json = JSONObject().apply {
            put("version", version)
            put("downloadDate", System.currentTimeMillis())
            put("size", size)
            put("fileName", fileName)
            put("source", source.name)
            put("variant", variant.name)
            downloadSourceId?.let { put("downloadSourceId", it) }
        }

        val pendingMetadata = File(cacheDir, "$METADATA_FILE_NAME.new")
        pendingMetadata.writeText(json.toString())
        replaceFileSafely(pendingMetadata, metadataFile)
        Log.d(TAG, "Saved metadata: $version")
    }

    private fun saveResumeInfo(info: ResumeInfo) {
        try {
            val json = JSONObject().apply {
                put("sourceId", info.sourceId)
                put("endpointUrl", info.endpointUrl)
                put("expectedSize", info.expectedSize)
                info.validator?.let { put("validator", it) }
            }
            progressFile.writeText(json.toString())
        } catch (e: Exception) {
            Log.e(TAG, "Error saving resume info", e)
        }
    }

    private fun loadResumeInfo(): ResumeInfo? {
        return try {
            if (!progressFile.exists()) return null
            val json = JSONObject(progressFile.readText())
            val endpointUrl = json.optString("endpointUrl")
                .ifBlank { json.optString("url") }
            if (endpointUrl.isBlank()) return null
            ResumeInfo(
                sourceId = json.optString("sourceId")
                    .ifBlank { DefaultApkDownloadSources.GITHUB_ID },
                endpointUrl = endpointUrl,
                expectedSize = json.optLong("expectedSize", 0L),
                validator = json.optString("validator").takeIf { it.isNotBlank() }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error loading resume info", e)
            null
        }
    }

    private data class ResumeInfo(
        val sourceId: String,
        val endpointUrl: String,
        val expectedSize: Long,
        val validator: String?
    )

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
        val downloadDate: Long,
        val size: Long,
        val file: File,
        val source: ApkSource,
        val variant: ShareableApkVariant,
        val downloadSourceId: String?
    )

    enum class ApkSource {
        INSTALLED,
        DOWNLOADED
    }
}
