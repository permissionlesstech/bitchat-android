package com.bitchat.android.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bitchat.android.R

/**
 * WorkManager worker that downloads the universal APK in the background.
 * Survives app backgrounding and process death. Transient network errors are
 * retried with backoff; partial downloads resume via HTTP Range requests.
 *
 * Runs as foreground (dataSync) work when possible so slow transfers (e.g.
 * over Tor) are not killed by WorkManager's background execution window.
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
        const val KEY_PHASE = "phase"
        const val KEY_VERSION = "version"
        const val KEY_SIZE_MB = "size_mb"
        const val KEY_ERROR_REASON = "error_reason"
        const val KEY_ERROR_ARGS = "error_args"
        const val KEY_RESUMABLE_PERCENT = "resumable_percent"

        private const val CHANNEL_ID = "apk_download"
        private const val NOTIFICATION_ID = 4201
        private const val NOTIFY_STEP_PERCENT = 5
    }

    private val apkManager = UniversalApkManager(applicationContext)
    private val notificationManager =
        applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private var lastNotifiedProgress = -NOTIFY_STEP_PERCENT
    private var lastProgress = 0
    private var currentPhase = ApkDownloader.DownloadPhase.SelectingSource

    override suspend fun doWork(): Result {
        Log.d(TAG, "Starting APK download work")

        // Promote to foreground so long transfers aren't stopped by the
        // ~10-minute background execution window. Android 12+ can reject the
        // promotion when the app is backgrounded — continue as regular
        // background work and rely on Range-resume in that case.
        try {
            setForeground(createForegroundInfo(apkManager.getPartialDownloadProgress() ?: 0))
        } catch (e: Exception) {
            Log.w(TAG, "Could not promote download to foreground work", e)
        }

        val result = apkManager.downloadUniversalApk(
            progressCallback = { progress ->
                lastProgress = progress
                publishProgress(progress, currentPhase)
                updateNotification(progress)
            },
            phaseCallback = { phase ->
                currentPhase = phase
                publishProgress(lastProgress, phase)
                // Forced: a phase change is exactly the moment the percentage stops meaning
                // anything, so the every-5% threshold must not suppress the redraw.
                updateNotification(lastProgress, force = true)
            }
        )

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
            val attemptNumber = runAttemptCount + 1
            if (ApkDownloadRetryPolicy.shouldRetry(runAttemptCount, error)) {
                Log.w(
                    TAG,
                    "Transient download error " +
                        "(attempt $attemptNumber/${ApkDownloadRetryPolicy.MAX_ATTEMPTS}), retrying",
                    error
                )
                return Result.retry()
            }

            val partial = apkManager.getPartialDownloadProgress()
            // Only a named failure carries a localizable message; anything else falls back to a
            // generic one rather than leaking an untranslated exception string to the user.
            val failure = error as? ApkDownloadException
            val outputData = Data.Builder()
                // The reason's name, never its resource id: this record can outlive the build
                // that wrote it, and resource ids are reassigned on every build.
                .putString(
                    KEY_ERROR_REASON,
                    (failure?.reason ?: ApkDownloadFailureReason.Generic).name
                )
                .putStringArray(
                    KEY_ERROR_ARGS,
                    failure?.messageArgs.orEmpty().toTypedArray()
                )
                .putInt(KEY_RESUMABLE_PERCENT, partial ?: -1)
                // No retry deadline is recorded: a rate-limit cooldown belongs to the route it was
                // earned on, and ApkRateLimitStore already keeps it that way. A copy frozen here
                // would outlive both the route and the cooldown.
                .build()
            Result.failure(outputData)
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        return createForegroundInfo(apkManager.getPartialDownloadProgress() ?: 0)
    }

    private fun createForegroundInfo(progress: Int): ForegroundInfo {
        ensureChannel()
        val notification = buildNotification(progress)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun publishProgress(progress: Int, phase: ApkDownloader.DownloadPhase) {
        setProgressAsync(
            Data.Builder()
                .putInt(KEY_PROGRESS, progress)
                .putString(KEY_PHASE, phase.name)
                .build()
        )
    }

    private fun buildNotification(progress: Int): android.app.Notification {
        val cancelIntent = WorkManager.getInstance(applicationContext)
            .createCancelPendingIntent(id)

        return NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.apk_download_notification_title))
            .setContentText(applicationContext.getString(downloadPhaseLabel(currentPhase)))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            // A percentage is a lie outside the transfer: the release lookup, the Tor bootstrap
            // and both verification passes have no measurable progress at all.
            .setProgress(100, progress, !currentPhase.hasMeasurableProgress || progress <= 0)
            .addAction(
                android.R.drawable.ic_delete,
                applicationContext.getString(android.R.string.cancel),
                cancelIntent
            )
            .build()
    }

    private fun updateNotification(progress: Int, force: Boolean = false) {
        if (!force && progress - lastNotifiedProgress < NOTIFY_STEP_PERCENT) return
        lastNotifiedProgress = progress
        try {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(progress))
        } catch (e: Exception) {
            // Missing POST_NOTIFICATIONS permission just drops the update;
            // the download itself is unaffected.
            Log.w(TAG, "Could not update download notification", e)
        }
    }

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.apk_download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
    }
}
