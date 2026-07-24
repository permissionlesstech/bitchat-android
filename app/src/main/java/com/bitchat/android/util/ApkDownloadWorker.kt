package com.bitchat.android.util

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters

/**
 * WorkManager worker that downloads the universal APK in the background.
 * Survives app backgrounding and process death. Transient network errors are
 * retried with backoff; partial downloads resume via HTTP Range requests.
 */
class ApkDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        const val TAG = "ApkDownloadWorker"
        const val WORK_NAME = "apk_download"

        // Progress keys
        const val KEY_PROGRESS = "progress"
        const val KEY_VERSION = "version"
        const val KEY_SIZE_MB = "size_mb"
        const val KEY_ERROR = "error"
        const val KEY_RESUMABLE_PERCENT = "resumable_percent"

        private const val MAX_RETRIES = 3
    }

    private val apkManager = UniversalApkManager(applicationContext)

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting APK download work")

        val result = apkManager.downloadUniversalApk { progress ->
            setProgressAsync(Data.Builder().putInt(KEY_PROGRESS, progress).build())
        }

        return if (result.isSuccess) {
            val info = apkManager.getCachedApkInfo()
            val outputData = Data.Builder()
                .putString(KEY_VERSION, info?.version ?: "")
                .putInt(KEY_SIZE_MB, ((info?.size ?: 0L) / 1024 / 1024).toInt())
                .build()
            Result.success(outputData)
        } else {
            val error = result.exceptionOrNull()

            // Retry transient network errors with backoff; the partial file
            // is kept on disk, so the retry resumes where it left off.
            val isRetryable = when (error) {
                is GitHubReleaseClient.ReleaseFetchException -> error.retryable
                is java.io.IOException -> true
                else -> false
            }
            if (isRetryable && runAttemptCount < MAX_RETRIES) {
                Log.w(TAG, "Transient download error (attempt $runAttemptCount), retrying", error)
                return Result.retry()
            }

            val partial = apkManager.getPartialDownloadProgress()
            val outputData = Data.Builder()
                .putString(KEY_ERROR, error?.message ?: "Download failed")
                .putInt(KEY_RESUMABLE_PERCENT, partial ?: -1)
                .build()
            Result.failure(outputData)
        }
    }
}
