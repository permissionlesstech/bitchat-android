package com.bitchat.android.net

import android.app.Activity
import android.app.Application
import android.util.Log
import com.bitchat.android.util.AppConstants
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
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
 * Real Tor provider implementation using Guardian Project Arti.
 *
 * This implementation is used in the tor flavor, which includes the full Arti library
 * and provides complete Tor anonymity features.
 *
 * Based on the original TorManager implementation.
 */
class RealTorProvider : TorProvider {
    companion object {
        private const val TAG = "RealTorProvider"
        private const val DEFAULT_SOCKS_PORT = AppConstants.Tor.DEFAULT_SOCKS_PORT
        private const val RESTART_DELAY_MS = AppConstants.Tor.RESTART_DELAY_MS
        private const val INACTIVITY_TIMEOUT_MS = AppConstants.Tor.INACTIVITY_TIMEOUT_MS
        private const val MAX_RETRY_ATTEMPTS = AppConstants.Tor.MAX_RETRY_ATTEMPTS
        private const val STOP_TIMEOUT_MS = AppConstants.Tor.STOP_TIMEOUT_MS
    }

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

    private val _statusFlow = MutableStateFlow(
        TorProvider.TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            lastLogLine = "",
            state = TorProvider.TorState.OFF,
            isAvailable = true
        )
    )

    override val statusFlow: StateFlow<TorProvider.TorStatus> = _statusFlow.asStateFlow()

    private val stateChangeDeferred = AtomicReference<CompletableDeferred<TorProvider.TorState>?>(null)

    override fun isProxyEnabled(): Boolean {
        val s = _statusFlow.value
        return s.mode != TorMode.OFF && s.running && s.bootstrapPercent >= 100 &&
               socksAddr != null && s.state == TorProvider.TorState.RUNNING
    }

    override fun init(application: Application) {
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
                try { OkHttpProvider.reset() } catch (_: Throwable) { }  // Only reset OkHttp during init
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

    override fun currentSocksAddress(): InetSocketAddress? = socksAddr

    override suspend fun applyMode(application: Application, mode: TorMode) {
        applyMutex.withLock {
            try {
                desiredMode = mode
                lastMode = mode
                val s = _statusFlow.value
                if (mode == s.mode && mode != TorMode.OFF &&
                    (lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING)) {
                    Log.i(TAG, "applyMode: already in progress/running mode=$mode, state=$lifecycleState; skip")
                    return
                }
                when (mode) {
                    TorMode.OFF -> {
                        Log.i(TAG, "applyMode: OFF -> stopping tor")
                        lifecycleState = LifecycleState.STOPPING
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorProvider.TorState.STOPPING
                        )
                        stopArti()
                        waitForStateTransition(target = TorProvider.TorState.OFF, timeoutMs = STOP_TIMEOUT_MS)
                        socksAddr = null
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorProvider.TorState.OFF
                        )
                        currentSocksPort = DEFAULT_SOCKS_PORT
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STOPPED
                        resetNetworkConnections()
                    }
                    TorMode.ON -> {
                        Log.i(TAG, "applyMode: ON -> starting arti")
                        if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                            currentSocksPort = DEFAULT_SOCKS_PORT
                        }
                        bindRetryAttempts = 0
                        lifecycleState = LifecycleState.STARTING
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.ON,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorProvider.TorState.STARTING
                        )
                        socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                        resetNetworkConnections()
                        startArti(application, useDelay = false)
                        appScope.launch {
                            waitUntilBootstrapped()
                            if (_statusFlow.value.running && desiredMode == TorMode.ON) {
                                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                                Log.i(TAG, "Tor ON: proxy set to ${socksAddr}")
                                resetNetworkConnections()
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
                _statusFlow.update { it.copy(lastLogLine = s) }
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

            _statusFlow.update { it.copy(
                running = true,
                bootstrapPercent = 0,
                state = TorProvider.TorState.STARTING
            ) }
            lifecycleState = LifecycleState.RUNNING
            startInactivityMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting Arti on port $currentSocksPort: ${e.message}")
            _statusFlow.update { it.copy(state = TorProvider.TorState.ERROR) }

            val isBindError = isBindError(e)
            if (isBindError && bindRetryAttempts < MAX_RETRY_ATTEMPTS) {
                bindRetryAttempts++
                currentSocksPort++
                Log.w(TAG, "Port bind failed (attempt $bindRetryAttempts/$MAX_RETRY_ATTEMPTS), retrying with port $currentSocksPort")
                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                resetNetworkConnections()
                startArti(application, useDelay = false)
            } else if (isBindError) {
                Log.e(TAG, "Max bind retry attempts reached ($MAX_RETRY_ATTEMPTS), giving up")
                lifecycleState = LifecycleState.STOPPED
                _statusFlow.update { it.copy(
                    running = false,
                    bootstrapPercent = 0,
                    state = TorProvider.TorState.ERROR
                ) }
            } else {
                scheduleRetry(application)
            }
        }
    }

    private fun isBindError(exception: Exception): Boolean {
        val message = exception.message?.lowercase() ?: ""
        return message.contains("bind") ||
               message.contains("address already in use") ||
               message.contains("port") && message.contains("use") ||
               message.contains("permission denied") && message.contains("port") ||
               message.contains("could not bind")
    }

    /**
     * Reset network connections after Tor state changes.
     * Rebuilds OkHttp clients and reconnects Nostr relays.
     */
    private fun resetNetworkConnections() {
        try { OkHttpProvider.reset() } catch (_: Throwable) { }
        try { com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections() } catch (_: Throwable) { }
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
        _statusFlow.value = _statusFlow.value.copy(
            running = false,
            bootstrapPercent = 0,
            state = TorProvider.TorState.STOPPING
        )
    }

    private suspend fun stopArtiAndWait(timeoutMs: Long = STOP_TIMEOUT_MS) {
        stopArtiInternal()
        waitForStateTransition(target = TorProvider.TorState.OFF, timeoutMs = timeoutMs)
        delay(200)
    }

    private suspend fun restartArti(application: Application) {
        Log.i(TAG, "Restarting Arti (keeping SOCKS proxy enabled)...")
        stopArtiAndWait()
        delay(RESTART_DELAY_MS)
        startArti(application, useDelay = false)
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
                    val currentMode = _statusFlow.value.mode
                    if (currentMode == TorMode.ON) {
                        val bootstrapPercent = _statusFlow.value.bootstrapPercent
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
            val delayMs = (1000L * (1 shl retryAttempts)).coerceAtMost(30000L)
            Log.w(TAG, "Scheduling Arti retry attempt $retryAttempts in ${delayMs}ms")
            retryJob = appScope.launch {
                delay(delayMs)
                val currentMode = _statusFlow.value.mode
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
        val current = _statusFlow.value
        if (!current.running) return
        if (current.bootstrapPercent >= 100 && current.state == TorProvider.TorState.RUNNING) return
        while (true) {
            val s = statusFlow.first {
                (it.bootstrapPercent >= 100 && it.state == TorProvider.TorState.RUNNING) ||
                !it.running ||
                it.state == TorProvider.TorState.ERROR
            }
            if (!s.running || s.state == TorProvider.TorState.ERROR) return
            if (s.bootstrapPercent >= 100 && s.state == TorProvider.TorState.RUNNING) return
        }
    }

    private fun handleArtiLogLine(s: String) {
        when {
            s.contains("AMEx: state changed to Initialized", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorProvider.TorState.STARTING) }
                completeWaitersIf(TorProvider.TorState.STARTING)
            }
            s.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorProvider.TorState.STARTING) }
                completeWaitersIf(TorProvider.TorState.STARTING)
            }
            s.contains("Sufficiently bootstrapped; system SOCKS now functional", ignoreCase = true) -> {
                _statusFlow.update { it.copy(
                    bootstrapPercent = 75,
                    state = TorProvider.TorState.BOOTSTRAPPING
                ) }
                retryAttempts = 0
                bindRetryAttempts = 0
                startInactivityMonitoring()
            }
            s.contains("We have found that guard [scrubbed] is usable.", ignoreCase = true) -> {
                _statusFlow.update { it.copy(
                    state = TorProvider.TorState.RUNNING,
                    bootstrapPercent = 100,
                    running = true
                ) }
                completeWaitersIf(TorProvider.TorState.RUNNING)
            }
            s.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                _statusFlow.update { it.copy(
                    state = TorProvider.TorState.STOPPING,
                    running = false
                ) }
            }
            s.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                _statusFlow.update { it.copy(
                    state = TorProvider.TorState.OFF,
                    running = false,
                    bootstrapPercent = 0
                ) }
                completeWaitersIf(TorProvider.TorState.OFF)
            }
            s.contains("Another process has the lock on our state files", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorProvider.TorState.ERROR) }
            }
        }
    }

    private fun completeWaitersIf(state: TorProvider.TorState) {
        stateChangeDeferred.getAndSet(null)?.let { def ->
            def.complete(state)
        }
    }

    private suspend fun waitForStateTransition(target: TorProvider.TorState, timeoutMs: Long): TorProvider.TorState? {
        val def = CompletableDeferred<TorProvider.TorState>()
        stateChangeDeferred.getAndSet(def)?.cancel()
        return withTimeoutOrNull(timeoutMs) {
            val cur = _statusFlow.value.state
            if (cur == target) return@withTimeoutOrNull cur
            def.await()
        }
    }

    override fun isTorAvailable(): Boolean = true

    override fun isModuleInstalled(): Boolean = true // Always bundled in tor flavor

    override fun requestModuleInstall(activity: Activity, listener: TorProvider.InstallStatusListener) {
        // No-op: Tor is already bundled in this flavor
        Log.i(TAG, "Tor module already bundled - no installation needed")
        listener.onInstallCompleted()
    }
}
