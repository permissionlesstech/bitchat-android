package com.bitchat.android.util

import kotlinx.coroutines.flow.Flow

/**
 * Interface for APK download operations.
 * Abstracts the download mechanism so it can be swapped
 * (e.g., WorkManager, ForegroundService, plain coroutine).
 */
interface ApkDownloader {

    /**
     * Current download state as an observable flow.
     */
    val downloadState: Flow<DownloadState>

    /**
     * Start or resume a download. If a partial download exists, it resumes automatically.
     */
    fun startDownload()

    /**
     * Cancel an in-progress download. The partial file is kept for future resume.
     */
    fun cancelDownload()

    /**
     * Download state reported by the downloader.
     */
    sealed class DownloadState {
        object Idle : DownloadState()
        data class Downloading(
            val progressPercent: Int,
            val phase: DownloadPhase = DownloadPhase.Transferring
        ) : DownloadState()
        data class Success(val version: String, val sizeMB: Int) : DownloadState()
        /**
         * [reason] and [messageArgs] stay structured until the presentation boundary. Carrying
         * them instead of formatted text keeps the failure localizable across WorkManager.
         */
        data class Failed(
            val reason: ApkDownloadFailureReason,
            val messageArgs: List<String>,
            val resumablePercent: Int?
        ) : DownloadState()
    }

    /**
     * What a download is actually doing.
     *
     * Only [Transferring] has meaningful percentage progress; selecting a mirror,
     * waiting for connectivity, and checking the signature are indeterminate.
     */
    enum class DownloadPhase {
        AwaitingConnectivity,
        /**
         * Waiting out the backoff before another attempt. Distinct from
         * [AwaitingConnectivity] because WorkManager parks a retry in ENQUEUED
         * whether or not the device is online, and claiming a network wait there
         * would be false on a connected device.
         */
        Retrying,
        SelectingSource,
        AwaitingNetworkRoute,
        Transferring,
        VerifyingSignature;

        /** A percentage is only honest while bytes are actually moving. */
        val hasMeasurableProgress: Boolean get() = this == Transferring

        companion object {
            /** Tolerates an unknown or absent key, since it crosses a WorkManager Data boundary. */
            fun fromKey(key: String?): DownloadPhase = when (key) {
                // Work created by the previous implementation may still be observable.
                "ResolvingRelease" -> SelectingSource
                "VerifyingChecksum" -> VerifyingSignature
                else -> entries.firstOrNull { it.name == key } ?: Transferring
            }
        }
    }
}

/**
 * What a queued work record is actually waiting for.
 *
 * WorkManager parks both cases in ENQUEUED, so the state alone cannot tell them apart. A non-zero
 * [runAttemptCount] means the work already ran and failed, which makes this the retry backoff
 * rather than an unmet network constraint.
 */
internal fun queuedPhase(runAttemptCount: Int): ApkDownloader.DownloadPhase =
    if (runAttemptCount > 0) {
        ApkDownloader.DownloadPhase.Retrying
    } else {
        ApkDownloader.DownloadPhase.AwaitingConnectivity
    }

/** Shared by the notification and the About sheet so both name a phase identically. */
internal fun downloadPhaseLabel(phase: ApkDownloader.DownloadPhase): Int = when (phase) {
    ApkDownloader.DownloadPhase.AwaitingConnectivity ->
        com.bitchat.android.R.string.prepare_apk_phase_awaiting_connectivity
    ApkDownloader.DownloadPhase.Retrying ->
        com.bitchat.android.R.string.prepare_apk_phase_retrying
    ApkDownloader.DownloadPhase.SelectingSource ->
        com.bitchat.android.R.string.prepare_apk_phase_selecting_source
    ApkDownloader.DownloadPhase.AwaitingNetworkRoute ->
        com.bitchat.android.R.string.prepare_apk_phase_awaiting_route
    ApkDownloader.DownloadPhase.Transferring ->
        com.bitchat.android.R.string.prepare_apk_phase_transferring
    ApkDownloader.DownloadPhase.VerifyingSignature ->
        com.bitchat.android.R.string.prepare_apk_phase_verifying_signature
}
