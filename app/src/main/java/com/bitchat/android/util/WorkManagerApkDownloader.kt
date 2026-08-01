package com.bitchat.android.util

import android.content.Context
import androidx.work.Constraints
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit

/**
 * WorkManager-backed implementation of [ApkDownloader].
 * Downloads survive app backgrounding, process death, and device reboots.
 */
class WorkManagerApkDownloader(context: Context) : ApkDownloader {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val apkManager = UniversalApkManager(appContext)

    override val downloadState: Flow<ApkDownloader.DownloadState> =
        workManager.getWorkInfosForUniqueWorkFlow(ApkDownloadWorker.WORK_NAME)
            .map { workInfos -> mapWorkInfoToState(workInfos.firstOrNull()) }

    override fun startDownload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = OneTimeWorkRequestBuilder<ApkDownloadWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                15,
                TimeUnit.SECONDS
            )
            .addTag(ApkDownloadWorker.TAG)
            .build()

        workManager.enqueueUniqueWork(
            ApkDownloadWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    override fun cancelDownload() {
        workManager.cancelUniqueWork(ApkDownloadWorker.WORK_NAME)
    }

    private fun mapWorkInfoToState(workInfo: WorkInfo?): ApkDownloader.DownloadState {
        if (workInfo == null) return ApkDownloader.DownloadState.Idle

        return when (workInfo.state) {
            WorkInfo.State.ENQUEUED,
            WorkInfo.State.BLOCKED -> {
                // ENQUEUED covers two different waits. A non-zero attempt count means the work
                // already ran and failed, so this is the retry backoff rather than a missing
                // network — saying "waiting for network" there would be false while online.
                val partial = apkManager.getPartialDownloadProgress()
                ApkDownloader.DownloadState.Downloading(
                    partial ?: 0,
                    queuedPhase(workInfo.runAttemptCount)
                )
            }
            WorkInfo.State.RUNNING -> {
                val progress = workInfo.progress.getInt(ApkDownloadWorker.KEY_PROGRESS, 0)
                val phase = ApkDownloader.DownloadPhase.fromKey(
                    workInfo.progress.getString(ApkDownloadWorker.KEY_PHASE)
                )
                ApkDownloader.DownloadState.Downloading(progress, phase)
            }
            WorkInfo.State.SUCCEEDED -> {
                val version = workInfo.outputData.getString(ApkDownloadWorker.KEY_VERSION) ?: ""
                val sizeMB = workInfo.outputData.getInt(ApkDownloadWorker.KEY_SIZE_MB, 0)
                ApkDownloader.DownloadState.Success(version, sizeMB)
            }
            WorkInfo.State.FAILED -> {
                // Tolerates a missing or retired reason from an older build's record.
                val reason = ApkDownloadFailureReason.fromKey(
                    workInfo.outputData.getString(ApkDownloadWorker.KEY_ERROR_REASON)
                )
                val args = workInfo.outputData
                    .getStringArray(ApkDownloadWorker.KEY_ERROR_ARGS)
                    ?.toList()
                    .orEmpty()
                val resumable = workInfo.outputData.getInt(ApkDownloadWorker.KEY_RESUMABLE_PERCENT, -1)
                ApkDownloader.DownloadState.Failed(
                    reason = reason,
                    messageArgs = args,
                    resumablePercent = if (resumable >= 0) resumable else null
                )
            }
            WorkInfo.State.CANCELLED -> {
                val partial = apkManager.getPartialDownloadProgress()
                if (partial != null) {
                    ApkDownloader.DownloadState.Failed(
                        reason = ApkDownloadFailureReason.Cancelled,
                        messageArgs = emptyList(),
                        resumablePercent = partial
                    )
                } else {
                    ApkDownloader.DownloadState.Idle
                }
            }
        }
    }
}
