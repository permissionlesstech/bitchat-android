package com.bitchat.android.net

import android.app.Activity
import android.app.Application
import kotlinx.coroutines.flow.StateFlow
import java.net.InetSocketAddress

/**
 * Abstraction layer for Tor functionality.
 *
 * Implementations:
 * - StandardTorProvider: No-op (standard flavor)
 * - RealTorProvider: Guardian Project Arti (tor flavor)
 * - DynamicTorProvider: On-demand loading (Phase 2, Play Store)
 */
interface TorProvider {
    enum class TorState {
        OFF,
        STARTING,
        BOOTSTRAPPING,
        RUNNING,
        STOPPING,
        ERROR,
        NOT_AVAILABLE
    }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF,
        val isAvailable: Boolean = false
    )

    val statusFlow: StateFlow<TorStatus>

    fun init(application: Application)
    suspend fun applyMode(application: Application, mode: TorMode)
    fun currentSocksAddress(): InetSocketAddress?
    fun isProxyEnabled(): Boolean
    fun isTorAvailable(): Boolean

    // TODO: Phase 2: Dynamic Feature Module support (no-op for Phase 1)
    fun isModuleInstalled(): Boolean = false
    fun requestModuleInstall(activity: Activity, listener: InstallStatusListener) {}

    interface InstallStatusListener {
        fun onInstallStarted(sessionId: Int)
        fun onInstallProgress(bytesDownloaded: Long, totalBytes: Long)
        fun onInstallCompleted()
        fun onInstallFailed(exception: Exception)
        fun onInstallCanceled()
    }

    enum class InstallStatus {
        NOT_INSTALLED,
        DOWNLOADING,
        INSTALLED,
        FAILED
    }
}
