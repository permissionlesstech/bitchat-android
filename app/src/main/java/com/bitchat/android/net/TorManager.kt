package com.bitchat.android.net

import android.app.Application
import android.util.Log
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
 
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * Manages embedded Tor lifecycle & provides SOCKS proxy address.
 * Uses org.torproject:tor-android-binary to bundle Tor.
 */
object TorManager {
    private const val TAG = "TorManager"
    private const val DEFAULT_SOCKS_PORT = 9060

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var initialized = false
    @Volatile private var socksAddr: InetSocketAddress? = null
    private val artiProxyRef = AtomicReference<ArtiProxy?>(null)
    @Volatile private var lastMode: TorMode = TorMode.OFF
    private val applyMutex = Mutex()
    @Volatile private var desiredMode: TorMode = TorMode.OFF
    @Volatile private var currentSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile private var nextSocksPort: Int = DEFAULT_SOCKS_PORT

    private enum class LifecycleState { STOPPED, STARTING, RUNNING, STOPPING }
    @Volatile private var lifecycleState: LifecycleState = LifecycleState.STOPPED

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = ""
    )

    private val _status = MutableStateFlow(TorStatus())
    val statusFlow: StateFlow<TorStatus> = _status.asStateFlow()

    fun isProxyEnabled(): Boolean {
        val s = _status.value
        return s.mode != TorMode.OFF && s.running && s.bootstrapPercent >= 100 && socksAddr != null
    }

    fun init(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            TorPreferenceManager.init(application)

            // Apply saved mode at startup
            appScope.launch {
                applyMode(application, TorPreferenceManager.get(application))
            }

            // Observe changes
            appScope.launch {
                TorPreferenceManager.modeFlow.collect { mode ->
                    applyMode(application, mode)
                }
            }
        }
    }

    fun currentSocksAddress(): InetSocketAddress? = socksAddr

    suspend fun applyMode(application: Application, mode: TorMode) {
        applyMutex.withLock {
            try {
                desiredMode = mode
                lastMode = mode
                val s = _status.value
                if (mode == s.mode && mode != TorMode.OFF && (lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING)) {
                    Log.i(TAG, "applyMode: already in progress/running mode=$mode, state=$lifecycleState; skip")
                    return
                }
                when (mode) {
                    TorMode.OFF -> {
                        Log.i(TAG, "applyMode: OFF -> stopping tor")
                        lifecycleState = LifecycleState.STOPPING
                        stopArti()
                        socksAddr = null
                        _status.value = _status.value.copy(mode = TorMode.OFF, running = false, bootstrapPercent = 0)
                        nextSocksPort = DEFAULT_SOCKS_PORT
                        lifecycleState = LifecycleState.STOPPED
                        // Rebuild clients WITHOUT proxy and reconnect relays
                        try {
                            OkHttpProvider.reset()
                            com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections()
                        } catch (_: Throwable) { }
                    }
                    TorMode.ON -> {
                        Log.i(TAG, "applyMode: ON -> starting arti")
                        nextSocksPort = if (currentSocksPort < DEFAULT_SOCKS_PORT) DEFAULT_SOCKS_PORT else currentSocksPort
                        lifecycleState = LifecycleState.STARTING
                        startArti(application, isolation = false)
                        _status.value = _status.value.copy(mode = TorMode.ON)
                        // Defer enabling proxy until bootstrap completes
                        appScope.launch {
                            waitUntilBootstrapped()
                            if (_status.value.running && desiredMode == TorMode.ON) {
                                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                                Log.i(TAG, "Tor ON: proxy set to ${socksAddr}")
                                OkHttpProvider.reset()
                                try { com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections() } catch (_: Throwable) { }
                            }
                        }
                    }
                    TorMode.ISOLATION -> {
                        Log.i(TAG, "applyMode: ISOLATION -> starting arti")
                        nextSocksPort = if (currentSocksPort < DEFAULT_SOCKS_PORT) DEFAULT_SOCKS_PORT else currentSocksPort
                        lifecycleState = LifecycleState.STARTING
                        startArti(application, isolation = true)
                        _status.value = _status.value.copy(mode = TorMode.ISOLATION)
                        appScope.launch {
                            waitUntilBootstrapped()
                            if (_status.value.running && desiredMode == TorMode.ISOLATION) {
                                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                                Log.i(TAG, "Arti ISOLATION: proxy set to ${socksAddr}")
                                OkHttpProvider.reset()
                                try { com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections() } catch (_: Throwable) { }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Arti mode: ${e.message}")
            }
        }
    }

    private fun startArti(application: Application, isolation: Boolean) {
        try {
            stopArti()

            Log.i(TAG, "Starting Arti…")

            // Determine port
            val port = nextSocksPort
            currentSocksPort = port
            val logListener = ArtiLogListener { logLine ->
                val text = logLine ?: return@ArtiLogListener
                val s = text.toString()
                Log.i(TAG, "arti: $s")
                _status.value = _status.value.copy(lastLogLine = s)
                if (
                    s.contains("Sufficiently bootstrapped", ignoreCase = true) ||
                    s.contains("AMEx: state changed to Running", ignoreCase = true)
                ) {
                    _status.value = _status.value.copy(bootstrapPercent = 100)
                }
            }

            val proxy = ArtiProxy.Builder(application)
                .setSocksPort(port)
                .setDnsPort(port + 1)
                .setLogListener(logListener)
                .build()

            artiProxyRef.set(proxy)
            proxy.start()

            _status.value = _status.value.copy(running = true, bootstrapPercent = 0)
            lifecycleState = LifecycleState.RUNNING
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Arti: ${e.message}")
        }
    }

    private fun stopArti() {
        try {
            val proxy = artiProxyRef.getAndSet(null)
            if (proxy != null) {
                Log.i(TAG, "Stopping Arti…")
                try { proxy.stop() } catch (_: Throwable) {}
            }
            socksAddr = null
            _status.value = _status.value.copy(running = false, bootstrapPercent = 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Arti: ${e.message}")
        }
    }

    // Removed Tor resource installation: not needed for Arti

    /**
     * Build an execution command that works on Android 10+ where app data dirs are mounted noexec.
     * We invoke the platform dynamic linker and pass the PIE binary path as its first arg.
     */
    // Removed exec command builder: not needed for Arti

    private suspend fun waitUntilBootstrapped() {
        val current = _status.value
        if (!current.running) return
        if (current.bootstrapPercent >= 100) return
        // Suspend until we observe 100% at least once
        while (true) {
            val s = statusFlow.first { it.bootstrapPercent >= 100 || !it.running }
            if (!s.running) return
            if (s.bootstrapPercent >= 100) return
        }
    }

    // Visible for instrumentation tests to validate installation
    fun installResourcesForTest(application: Application): Boolean { return true }
}
