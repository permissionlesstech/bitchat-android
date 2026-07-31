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
        data class Downloading(val progressPercent: Int) : DownloadState()
        data class Success(val version: String, val sizeMB: Int) : DownloadState()
        data class Failed(val message: String, val resumablePercent: Int?) : DownloadState()
    }
}