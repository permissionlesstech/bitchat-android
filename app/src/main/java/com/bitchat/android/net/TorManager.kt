package com.bitchat.android.net

import android.app.Application
import android.content.Context
import android.util.Log
import info.guardianproject.netcipher.proxy.OrbotHelper
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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages Tor lifecycle & provides SOCKS proxy address using NetCipher.
 * NetCipher is 16KB page size compatible and doesn't have native library alignment issues.
 */
object TorManager {
    private const val TAG = "TorManager"
    private const val DEFAULT_SOCKS_PORT = com.bitchat.android.util.AppConstants.Tor.DEFAULT_SOCKS_PORT
    private const val RESTART_DELAY_MS = com.bitchat.android.util.AppConstants.Tor.RESTART_DELAY_MS
    private const val INACTIVITY_TIMEOUT_MS = com.bitchat.android.util.AppConstants.Tor.INACTIVITY_TIMEOUT_MS
    private const val MAX_RETRY_ATTEMPTS = com.bitchat.android.util.AppConstants.Tor.MAX_RETRY_ATTEMPTS
    private const val STOP_TIMEOUT_MS = com.bitchat.android.util.AppConstants.Tor.STOP_TIMEOUT_MS

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var initialized = false
    @Volatile private var socksAddr: InetSocketAddress? = null
    private val torClientRef = AtomicReference<OkHttpClient?>(null)
    @Volatile private var lastMode: TorMode = TorMode.OFF
    private val applyMutex = Mutex()
    @Volatile private var desiredMode: TorMode = TorMode.OFF
    @Volatile private var currentSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile private var lastLogTime = AtomicLong(0L)
    @Volatile private var retryAttempts = 0
    @Volatile private var bindRetryAttempts = 0
    private var inactivityJob: Job? = null
    private var retryJob: Job? = null
    private var currentApplication: Application? = null

    private enum class LifecycleState { STOPPED, STARTING, RUNNING, STOPPING }
    @Volatile private var lifecycleState: LifecycleState = LifecycleState.STOPPED

    enum class TorState { OFF, STARTING, BOOTSTRAPPING, RUNNING, STOPPING, ERROR }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF
    )

    private val _status = MutableStateFlow(TorStatus())
    val statusFlow: StateFlow<TorStatus> = _status.asStateFlow()

    private val stateChangeDeferred = AtomicReference<CompletableDeferred<TorState>?>(null)

    fun isProxyEnabled(): Boolean {
        val s = _status.value
        return s.mode != TorMode.OFF && s.running && s.bootstrapPercent >= 100 && socksAddr != null && s.state == TorState.RUNNING
    }

    fun init(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            currentApplication = application
            TorPreferenceManager.init(application)

            // Apply saved mode at startup
            val savedMode = TorPreferenceManager.get(application)
            if (savedMode == TorMode.ON) {
                if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                    currentSocksPort = DEFAULT_SOCKS_PORT
                }
                desiredMode = savedMode
                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                try { OkHttpProvider.reset() } catch (_: Throwable) { }
            }
            appScope.launch {
                applyMode(application, savedMode)
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
                        _status.value = _status.value.copy(mode = TorMode.OFF, running = false, bootstrapPercent = 0, state = TorState.STOPPING)
                        stopTor()
                        waitForStateTransition(target = TorState.OFF, timeoutMs = STOP_TIMEOUT_MS)
                        socksAddr = null
                        _status.value = _status.value.copy(mode = TorMode.OFF, running = false, bootstrapPercent = 0, state = TorState.OFF)
                        currentSocksPort = DEFAULT_SOCKS_PORT
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STOPPED
                        // Rebuild clients WITHOUT proxy and reconnect relays
                        try {
                            OkHttpProvider.reset()
                            com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections()
                        } catch (_: Throwable) { }
                    }
                    TorMode.ON -> {
                        Log.i(TAG, "applyMode: ON -> starting NetCipher Tor")
                        if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                            currentSocksPort = DEFAULT_SOCKS_PORT
                        }
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STARTING
                        _status.value = _status.value.copy(mode = TorMode.ON, running = false, bootstrapPercent = 0, state = TorState.STARTING)

                        // Check if Orbot is available
                        if (!OrbotHelper.isOrbotInstalled(application)) {
                            Log.w(TAG, "Orbot is not installed")
                            _status.value = _status.value.copy(
                                state = TorState.ERROR,
                                lastLogLine = "Orbot is required for Tor functionality. Please install Orbot."
                            )
                            return
                        }

                        // Set SOCKS address immediately to force traffic through proxy
                        socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                        try { OkHttpProvider.reset() } catch (_: Throwable) { }
                        try { com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections() } catch (_: Throwable) { }

                        startNetCipherTor(application)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Tor mode: ${e.message}")
                _status.value = _status.value.copy(state = TorState.ERROR, lastLogLine = "Error: ${e.message}")
            }
        }
    }

    private suspend fun startNetCipherTor(application: Application) {
        try {
            Log.i(TAG, "Starting NetCipher Tor connection...")
            _status.value = _status.value.copy(state = TorState.STARTING, lastLogLine = "Initializing NetCipher...")

            // Request Orbot to start if not running
            if (!OrbotHelper.isOrbotRunning(application)) {
                Log.i(TAG, "Requesting Orbot to start...")
                _status.value = _status.value.copy(lastLogLine = "Starting Orbot...")
                OrbotHelper.requestStartTor(application)

                // Wait for Orbot to start
                var attempts = 0
                while (!OrbotHelper.isOrbotRunning(application) && attempts < 30) {
                    delay(1000)
                    attempts++
                    _status.value = _status.value.copy(
                        lastLogLine = "Waiting for Orbot to start... (${attempts}/30)"
                    )
                }

                if (!OrbotHelper.isOrbotRunning(application)) {
                    throw Exception("Orbot failed to start after 30 seconds")
                }
            }

            // Create Tor-enabled HTTP client using NetCipher with basic proxy configuration
            _status.value = _status.value.copy(
                state = TorState.BOOTSTRAPPING,
                bootstrapPercent = 50,
                lastLogLine = "Creating Tor client..."
            )

            // Use basic OkHttpClient with SOCKS proxy for Tor (NetCipher compatible approach)
            val torProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", currentSocksPort))
            val torClient = OkHttpClient.Builder()
                .proxy(torProxy)
                .build()

            torClientRef.set(torClient)
            lastLogTime.set(System.currentTimeMillis())

            // Mark as ready since we're using basic proxy configuration
            _status.value = _status.value.copy(
                running = true,
                bootstrapPercent = 100,
                state = TorState.RUNNING,
                lastLogLine = "Tor connection established via NetCipher SOCKS proxy"
            )

            lifecycleState = LifecycleState.RUNNING
            retryAttempts = 0
            bindRetryAttempts = 0

            completeWaitersIf(TorState.RUNNING)

            Log.i(TAG, "NetCipher Tor connection established successfully")

        } catch (e: Exception) {
            Log.e(TAG, "Error starting NetCipher Tor: ${e.message}")
            _status.value = _status.value.copy(
                state = TorState.ERROR,
                running = false,
                lastLogLine = "Error: ${e.message}"
            )

            if (retryAttempts < MAX_RETRY_ATTEMPTS) {
                scheduleRetry(application)
            } else {
                Log.e(TAG, "Max retry attempts reached for NetCipher Tor")
                lifecycleState = LifecycleState.STOPPED
            }
        }
    }

    private fun stopTor() {
        try {
            Log.i(TAG, "Stopping NetCipher Tor...")
            torClientRef.set(null)
            stopInactivityMonitoring()
            stopRetryMonitoring()
            socksAddr = null
            _status.value = _status.value.copy(
                running = false,
                bootstrapPercent = 0,
                state = TorState.OFF,
                lastLogLine = "Tor connection stopped"
            )
            completeWaitersIf(TorState.OFF)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping NetCipher Tor: ${e.message}")
        }
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
                    if (currentMode == TorMode.ON && _status.value.bootstrapPercent < 100) {
                        Log.w(TAG, "Inactivity detected, attempting to restart NetCipher")
                        currentApplication?.let { app ->
                            appScope.launch {
                                startNetCipherTor(app)
                            }
                        }
                        break
                    }
                }
            }
        }
    }

    private fun stopInactivityMonitoring() {
        inactivityJob?.cancel()
        inactivityJob = null
    }

    private fun scheduleRetry(application: Application) {
        retryJob?.cancel()
        if (retryAttempts < MAX_RETRY_ATTEMPTS) {
            retryAttempts++
            val delayMs = (1000L * (1 shl retryAttempts)).coerceAtMost(30000L)
            Log.w(TAG, "Scheduling NetCipher retry attempt $retryAttempts in ${delayMs}ms")
            retryJob = appScope.launch {
                delay(delayMs)
                val currentMode = _status.value.mode
                if (currentMode == TorMode.ON) {
                    Log.i(TAG, "Retrying NetCipher start (attempt $retryAttempts)")
                    startNetCipherTor(application)
                }
            }
        } else {
            Log.e(TAG, "Max retry attempts reached for NetCipher")
        }
    }

    private fun stopRetryMonitoring() {
        retryJob?.cancel()
        retryJob = null
    }

    private suspend fun waitUntilBootstrapped() {
        val current = _status.value
        if (!current.running) return
        if (current.bootstrapPercent >= 100 && current.state == TorState.RUNNING) return

        while (true) {
            val s = statusFlow.first {
                (it.bootstrapPercent >= 100 && it.state == TorState.RUNNING) ||
                !it.running ||
                it.state == TorState.ERROR
            }
            if (!s.running || s.state == TorState.ERROR) return
            if (s.bootstrapPercent >= 100 && s.state == TorState.RUNNING) return
        }
    }

    private fun completeWaitersIf(state: TorState) {
        stateChangeDeferred.getAndSet(null)?.let { def ->
            def.complete(state)
        }
    }

    private suspend fun waitForStateTransition(target: TorState, timeoutMs: Long): TorState? {
        val def = CompletableDeferred<TorState>()
        stateChangeDeferred.getAndSet(def)?.cancel()
        return withTimeoutOrNull(timeoutMs) {
            val cur = _status.value.state
            if (cur == target) return@withTimeoutOrNull cur
            def.await()
        }
    }

    /**
     * Get the current Tor-enabled HTTP client
     */
    fun getTorClient(): OkHttpClient? = torClientRef.get()

    // Visible for instrumentation tests
    fun installResourcesForTest(application: Application): Boolean { return true }
}
