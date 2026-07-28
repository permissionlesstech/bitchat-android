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
        data class Failed(val message: String, val resumablePercent: Int?) : DownloadState()
    }

    /**
     * What a download is actually doing.
     *
     * Preparing an APK is a five-stage operation that was being rendered as a single 0-100 bar,
     * so it sat at 0% through a release lookup and a Tor bootstrap, then at 100% through a
     * SHA-256 pass and a signature check over ~100MB. Only [Transferring] has meaningful
     * percentage progress; the rest should read as indeterminate.
     */
    enum class DownloadPhase {
        ResolvingRelease,
        AwaitingNetworkRoute,
        Transferring,
        VerifyingChecksum,
        VerifyingSignature;

        /** A percentage is only honest while bytes are actually moving. */
        val hasMeasurableProgress: Boolean get() = this == Transferring

        companion object {
            /** Tolerates an unknown or absent key, since it crosses a WorkManager Data boundary. */
            fun fromKey(key: String?): DownloadPhase =
                entries.firstOrNull { it.name == key } ?: Transferring
        }
    }
}

/** Shared by the notification and the About sheet so both name a phase identically. */
internal fun downloadPhaseLabel(phase: ApkDownloader.DownloadPhase): Int = when (phase) {
    ApkDownloader.DownloadPhase.ResolvingRelease ->
        com.bitchat.android.R.string.prepare_apk_phase_resolving
    ApkDownloader.DownloadPhase.AwaitingNetworkRoute ->
        com.bitchat.android.R.string.prepare_apk_phase_awaiting_route
    ApkDownloader.DownloadPhase.Transferring ->
        com.bitchat.android.R.string.prepare_apk_phase_transferring
    ApkDownloader.DownloadPhase.VerifyingChecksum ->
        com.bitchat.android.R.string.prepare_apk_phase_verifying_checksum
    ApkDownloader.DownloadPhase.VerifyingSignature ->
        com.bitchat.android.R.string.prepare_apk_phase_verifying_signature
}