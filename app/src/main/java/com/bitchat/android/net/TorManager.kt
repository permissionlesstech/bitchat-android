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
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages embedded Tor lifecycle & provides SOCKS proxy address.
 * Uses Arti (Tor in Rust) for improved security and reliability.
 */
object TorManager {
    private const val TAG = "TorManager"
    private const val DEFAULT_SOCKS_PORT = 9060
    private const val RESTART_DELAY_MS = 2000L // 2 seconds between stop/start
    private const val INACTIVITY_TIMEOUT_MS = 5000L // 5 seconds of no activity before restart
    private const val MAX_RETRY_ATTEMPTS = 5

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var initialized = false
    @Volatile private var socksAddr: InetSocketAddress? = null
    private val artiProxyRef = AtomicReference<ArtiProxy?>(null)
    @Volatile private var lastMode: TorMode = TorMode.OFF
    private val applyMutex = Mutex()
    @Volatile private var desiredMode: TorMode = TorMode.OFF
    @Volatile private var currentSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile private var nextSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile private var lastLogTime = AtomicLong(0L)
    @Volatile private var retryAttempts = 0
    private var inactivityJob: Job? = null
    private var retryJob: Job? = null
    private var currentApplication: Application? = null

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
            currentApplication = application
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
                        // For OFF->ON, no delay needed
                        val needsDelay = s.mode == TorMode.ISOLATION
                        startArti(application, isolation = false, useDelay = needsDelay)
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
                        // For ON->ISOLATION, immediate status change and delay
                        _status.value = _status.value.copy(running = false, bootstrapPercent = 0)
                        val needsDelay = s.mode == TorMode.ON
                        startArti(application, isolation = true, useDelay = needsDelay)
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

    private suspend fun startArti(application: Application, isolation: Boolean, useDelay: Boolean = false) {
        try {
            stopArtiInternal()

            Log.i(TAG, "Starting Arti…")
            if (useDelay) {
                delay(RESTART_DELAY_MS)
            }

            // Determine port
            val port = nextSocksPort
            currentSocksPort = port

            val logListener = ArtiLogListener { logLine ->
                val text = logLine ?: return@ArtiLogListener
                val s = text.toString()
                Log.i(TAG, "arti: $s")
                lastLogTime.set(System.currentTimeMillis())
                _status.value = _status.value.copy(lastLogLine = s)
                if (
                    s.contains("Sufficiently bootstrapped", ignoreCase = true) ||
                    s.contains("AMEx: state changed to Running", ignoreCase = true)
                ) {
                    _status.value = _status.value.copy(bootstrapPercent = 100)
                    retryAttempts = 0 // Reset retry attempts on successful bootstrap
                    startInactivityMonitoring()
                }
            }

            val proxy = ArtiProxy.Builder(application)
                .setSocksPort(port)
                .setDnsPort(port + 1)
                .setLogListener(logListener)
                .build()

            artiProxyRef.set(proxy)
            proxy.start()
            lastLogTime.set(System.currentTimeMillis())

            _status.value = _status.value.copy(running = true, bootstrapPercent = 0)
            lifecycleState = LifecycleState.RUNNING
            startInactivityMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting Arti: ${e.message}")
            scheduleRetry(application, isolation)
        }
    }

    private fun stopArtiInternal() {
        try {
            val proxy = artiProxyRef.getAndSet(null)
            if (proxy != null) {
                Log.i(TAG, "Stopping Arti…")
                try { proxy.stop() } catch (_: Throwable) {}
            }
            stopInactivityMonitoring()
            stopRetryMonitoring()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Arti: ${e.message}")
        }
    }

    private fun stopArti() {
        stopArtiInternal()
        socksAddr = null
        _status.value = _status.value.copy(running = false, bootstrapPercent = 0)
    }

    private suspend fun restartArti(application: Application, isolation: Boolean) {
        Log.i(TAG, "Restarting Arti (keeping SOCKS proxy enabled)...")
        stopArtiInternal()
        delay(RESTART_DELAY_MS)
        startArti(application, isolation, useDelay = false) // Already delayed above
    }

    private fun startInactivityMonitoring() {
        inactivityJob?.cancel()
        inactivityJob = appScope.launch {
            while (true) {
                delay(INACTIVITY_TIMEOUT_MS)
                val currentTime = System.currentTimeMillis()
                val lastActivity = lastLogTime.get()
                val timeSinceLastActivity = currentTime - lastActivity
                
                if (timeSinceLastActivity > INACTIVITY_TIMEOUT_MS) {
                    val currentMode = _status.value.mode
                    if (currentMode == TorMode.ON || currentMode == TorMode.ISOLATION) {
                        val bootstrapPercent = _status.value.bootstrapPercent
                        if (bootstrapPercent < 100) {
                            Log.w(TAG, "Inactivity detected (${timeSinceLastActivity}ms), restarting Arti")
                            currentApplication?.let { app ->
                                appScope.launch {
                                    restartArti(app, currentMode == TorMode.ISOLATION)
                                }
                            }
                            break
                        }
                    }
                }
            }
        }
    }

    private fun stopInactivityMonitoring() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    private fun scheduleRetry(application: Application, isolation: Boolean) {
        retryJob?.cancel()
        if (retryAttempts < MAX_RETRY_ATTEMPTS) {
            retryAttempts++
            val delayMs = (1000L * (1 shl retryAttempts)).coerceAtMost(30000L) // Exponential backoff, max 30s
            Log.w(TAG, "Scheduling Arti retry attempt $retryAttempts in ${delayMs}ms")
            retryJob = appScope.launch {
                delay(delayMs)
                val currentMode = _status.value.mode
                if (currentMode == TorMode.ON || currentMode == TorMode.ISOLATION) {
                    Log.i(TAG, "Retrying Arti start (attempt $retryAttempts)")
                    restartArti(application, currentMode == TorMode.ISOLATION)
                }
            }
        } else {
            Log.e(TAG, "Max retry attempts reached, giving up on Arti connection")
        }
    }

    private fun stopRetryMonitoring() {
        retryJob?.cancel()
        retryJob = null
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
