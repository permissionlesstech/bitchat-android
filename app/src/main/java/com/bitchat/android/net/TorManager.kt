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
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages embedded Tor lifecycle & provides SOCKS proxy address.
 * Uses Arti (Tor in Rust) for improved security and reliability.
 */
object TorManager {
    private const val TAG = "TorManager"
    private const val DEFAULT_SOCKS_PORT = com.bitchat.android.util.AppConstants.Tor.DEFAULT_SOCKS_PORT
    private const val RESTART_DELAY_MS = com.bitchat.android.util.AppConstants.Tor.RESTART_DELAY_MS // 2 seconds between stop/start
    private const val INACTIVITY_TIMEOUT_MS = com.bitchat.android.util.AppConstants.Tor.INACTIVITY_TIMEOUT_MS // 5 seconds of no activity before restart
    private const val MAX_RETRY_ATTEMPTS = com.bitchat.android.util.AppConstants.Tor.MAX_RETRY_ATTEMPTS
    private const val STOP_TIMEOUT_MS = com.bitchat.android.util.AppConstants.Tor.STOP_TIMEOUT_MS

    private val appScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var initialized = false
    @Volatile private var socksAddr: InetSocketAddress? = null
    private val artiProxyRef = AtomicReference<ArtiProxy?>(null)
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
        val bootstrapPercent: Int = 0, // kept for backwards compatibility with UI; 0 or 100 only
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

            // Apply saved mode at startup. If ON, set planned SOCKS immediately to avoid any leak.
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
                        stopArti() // non-suspending immediate request
                        // Best-effort wait for STOPPED before we declare OFF
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
                        Log.i(TAG, "applyMode: ON -> starting arti")
                        // Reset port to default unless we're already using a higher port
                        if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                            currentSocksPort = DEFAULT_SOCKS_PORT
                        }
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STARTING
                        _status.value = _status.value.copy(mode = TorMode.ON, running = false, bootstrapPercent = 0, state = TorState.STARTING)
                        // Immediately set the planned SOCKS address so all traffic is forced through it,
                        // even before Tor is fully bootstrapped. This prevents any direct connections.
                        socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                        try { OkHttpProvider.reset() } catch (_: Throwable) { }
                        try { com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections() } catch (_: Throwable) { }
                        startArti(application, useDelay = false)
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
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Arti mode: ${e.message}")
            }
        }
    }

    private suspend fun startArti(application: Application, useDelay: Boolean = false) {
        try {
            // Ensure any previous instance is fully stopped before starting a new one
            stopArtiAndWait()

            Log.i(TAG, "Starting Arti on port $currentSocksPort…")
            if (useDelay) {
                delay(RESTART_DELAY_MS)
            }

            val logListener = ArtiLogListener { logLine ->
                val text = logLine ?: return@ArtiLogListener
                val s = text.toString()
                Log.i(TAG, "arti: $s")
                lastLogTime.set(System.currentTimeMillis())
                _status.value = _status.value.copy(lastLogLine = s)
                handleArtiLogLine(s)
            }

            val proxy = ArtiProxy.Builder(application)
                .setSocksPort(currentSocksPort)
                .setDnsPort(currentSocksPort + 1)
                .setLogListener(logListener)
                .build()

            artiProxyRef.set(proxy)
            proxy.start()
            lastLogTime.set(System.currentTimeMillis())

            _status.value = _status.value.copy(running = true, bootstrapPercent = 0, state = TorState.STARTING)
            lifecycleState = LifecycleState.RUNNING
            startInactivityMonitoring()

            // Removed onion service startup (BLE-only file transfer in this branch)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting Arti on port $currentSocksPort: ${e.message}")
            _status.value = _status.value.copy(state = TorState.ERROR)
            
            // Check if this is a bind error
            val isBindError = isBindError(e)
            if (isBindError && bindRetryAttempts < MAX_RETRY_ATTEMPTS) {
                bindRetryAttempts++
                currentSocksPort++
                Log.w(TAG, "Port bind failed (attempt $bindRetryAttempts/$MAX_RETRY_ATTEMPTS), retrying with port $currentSocksPort")
                // Update planned SOCKS address immediately so all new connections target the new port
                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                try { OkHttpProvider.reset() } catch (_: Throwable) { }
                try { com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections() } catch (_: Throwable) { }
                // Immediate retry with incremented port, no exponential backoff for bind errors
                startArti(application, useDelay = false)
            } else if (isBindError) {
                Log.e(TAG, "Max bind retry attempts reached ($MAX_RETRY_ATTEMPTS), giving up")
                lifecycleState = LifecycleState.STOPPED
                _status.value = _status.value.copy(running = false, bootstrapPercent = 0, state = TorState.ERROR)
            } else {
                // For non-bind errors, use the existing retry mechanism
                scheduleRetry(application)
            }
        }
    }
    
    /**
     * Checks if the exception indicates a port binding failure
     */
    private fun isBindError(exception: Exception): Boolean {
        val message = exception.message?.lowercase() ?: ""
        return message.contains("bind") ||
               message.contains("address already in use") ||
               message.contains("port") && message.contains("use") ||
               message.contains("permission denied") && message.contains("port") ||
               message.contains("could not bind")
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
        _status.value = _status.value.copy(running = false, bootstrapPercent = 0, state = TorState.STOPPING)
    }

    private suspend fun stopArtiAndWait(timeoutMs: Long = STOP_TIMEOUT_MS) {
        // Request stop
        stopArtiInternal()
        // Wait for confirmation via logs (Stopped) or timeout
        waitForStateTransition(target = TorState.OFF, timeoutMs = timeoutMs)
        // Small grace period before relaunch to let file locks clear
        delay(200)
    }

    private suspend fun restartArti(application: Application) {
        Log.i(TAG, "Restarting Arti (keeping SOCKS proxy enabled)...")
        stopArtiAndWait()
        delay(RESTART_DELAY_MS)
        startArti(application, useDelay = false) // Already delayed above
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
                    if (currentMode == TorMode.ON) {
                        val bootstrapPercent = _status.value.bootstrapPercent
                        if (bootstrapPercent < 100) {
                            Log.w(TAG, "Inactivity detected (${timeSinceLastActivity}ms), restarting Arti")
                            currentApplication?.let { app ->
                                appScope.launch {
                                    restartArti(app)
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

    private fun scheduleRetry(application: Application) {
        retryJob?.cancel()
        if (retryAttempts < MAX_RETRY_ATTEMPTS) {
            retryAttempts++
            val delayMs = (1000L * (1 shl retryAttempts)).coerceAtMost(30000L) // Exponential backoff, max 30s
            Log.w(TAG, "Scheduling Arti retry attempt $retryAttempts in ${delayMs}ms")
            retryJob = appScope.launch {
                delay(delayMs)
                val currentMode = _status.value.mode
                if (currentMode == TorMode.ON) {
                    Log.i(TAG, "Retrying Arti start (attempt $retryAttempts)")
                    restartArti(application)
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

    private suspend fun waitUntilBootstrapped() {
        val current = _status.value
        if (!current.running) return
        if (current.bootstrapPercent >= 100 && current.state == TorState.RUNNING) return
        // Suspend until we observe RUNNING at least once
        while (true) {
            val s = statusFlow.first { (it.bootstrapPercent >= 100 && it.state == TorState.RUNNING) || !it.running || it.state == TorState.ERROR }
            if (!s.running || s.state == TorState.ERROR) return
            if (s.bootstrapPercent >= 100 && s.state == TorState.RUNNING) return
        }
    }

    private fun handleArtiLogLine(s: String) {
        when {
            s.contains("AMEx: state changed to Initialized", ignoreCase = true) -> {
                _status.value = _status.value.copy(state = TorState.STARTING)
                completeWaitersIf(TorState.STARTING)
            }
            s.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                _status.value = _status.value.copy(state = TorState.STARTING)
                completeWaitersIf(TorState.STARTING)
            }
            s.contains("Sufficiently bootstrapped; system SOCKS now functional", ignoreCase = true) -> {
                _status.value = _status.value.copy(bootstrapPercent = 75, state = TorState.BOOTSTRAPPING)
                retryAttempts = 0
                bindRetryAttempts = 0
                startInactivityMonitoring()
            }
            //s.contains("AMEx: state changed to Running", ignoreCase = true) -> {
            s.contains("We have found that guard [scrubbed] is usable.", ignoreCase = true) -> {
                // If we already saw Sufficiently bootstrapped, mark as RUNNING and ready.
                val bp = if (_status.value.bootstrapPercent >= 100) 100 else 100 // treat Running as ready
                _status.value = _status.value.copy(state = TorState.RUNNING, bootstrapPercent = bp, running = true)
                completeWaitersIf(TorState.RUNNING)
            }
            s.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                _status.value = _status.value.copy(state = TorState.STOPPING, running = false)
            }
            s.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                _status.value = _status.value.copy(state = TorState.OFF, running = false, bootstrapPercent = 0)
                completeWaitersIf(TorState.OFF)
            }
            s.contains("Another process has the lock on our state files", ignoreCase = true) -> {
                // Signal error; we'll likely need to wait longer before restart
                _status.value = _status.value.copy(state = TorState.ERROR)
            }
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
            // Fast-path: if we're already there
            val cur = _status.value.state
            if (cur == target) return@withTimeoutOrNull cur
            def.await()
        }
    }

    // Visible for instrumentation tests to validate installation
    fun installResourcesForTest(application: Application): Boolean { return true }
}
