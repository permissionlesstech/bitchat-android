package com.bitchat.android.net

import android.app.Activity
import android.app.Application
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress

/**
 * No-op Tor provider implementation for standard flavor.
 *
 * This implementation is used when the app is built without Tor support,
 * resulting in a much smaller APK size (~3-4MB vs ~40-50MB).
 *
 * All methods are no-ops or return "not available" states.
 * The UI should hide or disable Tor-related controls when this provider is active.
 */
class StandardTorProvider : TorProvider {
    companion object {
        private const val TAG = "StandardTorProvider"
    }

    private val _statusFlow = MutableStateFlow(
        TorProvider.TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            lastLogLine = "Tor not available in this build",
            state = TorProvider.TorState.NOT_AVAILABLE,
            isAvailable = false
        )
    )

    override val statusFlow: StateFlow<TorProvider.TorStatus> = _statusFlow.asStateFlow()

    override fun init(application: Application) {
        Log.i(TAG, "StandardTorProvider initialized - Tor not available in this build")
        TorPreferenceManager.init(application)

        // If user had Tor enabled previously, reset to OFF since it's not available
        val savedMode = TorPreferenceManager.get(application)
        if (savedMode == TorMode.ON) {
            Log.w(TAG, "Tor was previously enabled but is not available in this build - disabling")
            TorPreferenceManager.set(application, TorMode.OFF)
        }
    }

    override suspend fun applyMode(application: Application, mode: TorMode) {
        if (mode == TorMode.ON) {
            Log.w(TAG, "Attempted to enable Tor but it's not available in this build")
            // Force mode back to OFF
            TorPreferenceManager.set(application, TorMode.OFF)
        }

        // Always stay in NOT_AVAILABLE state
        _statusFlow.value = _statusFlow.value.copy(
            mode = TorMode.OFF,
            running = false,
            state = TorProvider.TorState.NOT_AVAILABLE
        )
    }

    override fun currentSocksAddress(): InetSocketAddress? {
        return null // No SOCKS proxy available
    }

    override fun isProxyEnabled(): Boolean {
        return false // Proxy never enabled in standard build
    }

    override fun isTorAvailable(): Boolean {
        return false // Tor not available in standard build
    }

    override fun isModuleInstalled(): Boolean {
        return false // No dynamic module in standard build
    }

    override fun requestModuleInstall(
        activity: Activity,
        listener: TorProvider.InstallStatusListener
    ) {
        Log.w(TAG, "Dynamic feature module installation not supported in standard build")
        listener.onInstallFailed(
            UnsupportedOperationException("Tor is not available in this build variant")
        )
    }
}
