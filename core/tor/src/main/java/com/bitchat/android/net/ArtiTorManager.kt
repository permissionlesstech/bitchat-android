package com.bitchat.android.net

import android.app.Application
import android.util.Log
import info.guardianproject.arti.ArtiLogListener
import info.guardianproject.arti.ArtiProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CompletableDeferred

import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import dagger.Lazy

/**
 * Tor provider implementation using custom-built Arti (Tor-in-Rust).
 *
 * This singleton provides Tor anonymity features using a custom Arti build
 * compiled with 16KB page size support for Google Play compliance.
 *
 * Based on the original TorManager implementation.
 */
@Singleton
class ArtiTorManager internal constructor(
    // Lazy: OkHttpProvider reads this manager, and this manager resets it synchronously.
    private val okHttp: Lazy<OkHttpProvider>,
    private val torPreferences: TorPreferenceManager,
    private val appScope: CoroutineScope,
    private val proxyFactory: (Application, Int, ArtiLogListener) -> ArtiProxy,
    // Whether the app is in the background; once it is, a running Arti goes soft-dormant.
    private val backgroundState: Flow<Boolean> = emptyFlow(),
    private val backgroundDormantDelayMs: Long = BACKGROUND_DORMANT_DELAY_MS,
) {
    @Inject
    constructor(
        okHttp: Lazy<OkHttpProvider>,
        torPreferences: TorPreferenceManager,
        appBackground: TorAppBackground,
    ) : this(
        okHttp,
        torPreferences,
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
        { application, socksPort, logListener ->
            ArtiProxy.Builder(application)
                .setSocksPort(socksPort)
                .setDnsPort(socksPort + 1)
                .setLogListener(logListener)
                .build()
        },
        appBackground.inBackground.distinctUntilChanged(),
    )

    enum class TorState {
        OFF,
        STARTING,
        BOOTSTRAPPING,
        RUNNING,
        STOPPING,
        ERROR
    }

    data class TorStatus(
        val mode: TorMode = TorMode.OFF,
        val running: Boolean = false,
        val bootstrapPercent: Int = 0,
        val lastLogLine: String = "",
        val state: TorState = TorState.OFF
    )

    companion object {
        private const val TAG = "ArtiTorManager"
        internal const val DEFAULT_SOCKS_PORT: Int = 9060
        internal const val RESTART_DELAY_MS: Long = 2_000L
        internal const val INACTIVITY_TIMEOUT_MS: Long = 5_000L
        internal const val MAX_RETRY_ATTEMPTS: Int = 5
        internal const val STOP_TIMEOUT_MS: Long = 7_000L
        internal const val BACKGROUND_DORMANT_DELAY_MS: Long = 15_000L
    }

    @Volatile
    private var initialized = false
    @Volatile
    private var socksAddr: InetSocketAddress? = null
    @Volatile
    private var artiProxy: ArtiProxy? = null
    @Volatile
    private var artiProxyPort: Int = DEFAULT_SOCKS_PORT
    @Volatile
    private var lastMode: TorMode = TorMode.OFF
    private val applyMutex = Mutex()
    @Volatile
    private var desiredMode: TorMode = TorMode.OFF
    @Volatile
    private var currentSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile
    private var lastLogTime = AtomicLong(0L)
    @Volatile
    private var retryAttempts = 0
    @Volatile
    private var bindRetryAttempts = 0
    private var inactivityJob: Job? = null
    private var retryJob: Job? = null
    private val dormantLock = Any()
    private var dormantJob: Job? = null
    private val restartInFlight = AtomicBoolean(false)
    private var currentApplication: Application? = null
    @Volatile
    private var isAppInBackground = false
    @Volatile
    private var artiDormantApplied = false

    private enum class LifecycleState { STOPPED, STARTING, RUNNING, STOPPING }

    @Volatile
    private var lifecycleState: LifecycleState = LifecycleState.STOPPED

    private val _statusFlow = MutableStateFlow(
        TorStatus(
            mode = TorMode.OFF,
            running = false,
            bootstrapPercent = 0,
            lastLogLine = "",
            state = TorState.OFF
        )
    )

    val statusFlow: StateFlow<TorStatus> = _statusFlow.asStateFlow()

    private val _routeChanges = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * Emits after every route change, once [OkHttpProvider] has dropped its cached clients.
     * Whoever holds connections opened on the old route closes them here. Emission never
     * suspends, and a signal sent while the collector is busy is kept, not lost.
     *
     * The relay reset runs later, on the relay manager's own coroutine, so do not assume
     * relays have reset when a mode change returns; new connections already take the new
     * route, because [OkHttpProvider] is reset before the emission.
     */
    val routeChanges: SharedFlow<Unit> = _routeChanges.asSharedFlow()

    private val routeGenerationCounter = AtomicLong(0)

    /**
     * Counts route changes, advanced once [OkHttpProvider] has dropped the old route's clients
     * and before [routeChanges] emits. A connection reads it before it takes a client and sends
     * only while it still matches, so nothing leaves on the old route in the time between a mode
     * change returning and the holder of the connection closing it.
     */
    val routeGeneration: Long get() = routeGenerationCounter.get()

    private val stateChangeDeferred = AtomicReference<CompletableDeferred<TorState>?>(null)

    private val artiLogListener = ArtiLogListener { logLine ->
        val text = logLine ?: return@ArtiLogListener
        val s = text
        lastLogTime.set(System.currentTimeMillis())
        val current = _statusFlow.value
        val alreadyRunning = current.state == TorState.RUNNING && current.bootstrapPercent >= 100
        // Avoid allocating TorStatus copies and emitting StateFlow updates on every SOCKS log
        // line once Tor is healthy and no UI (e.g. AboutSheet) is actively observing statusFlow.
        if (!alreadyRunning || _statusFlow.subscriptionCount.value > 0) {
            _statusFlow.update { it.copy(lastLogLine = s) }
        }
        handleArtiLogLine(s, alreadyRunning)
        if (!alreadyRunning) {
            armBootstrapInactivityWatchdog()
        }
    }

    fun isProxyEnabled(): Boolean {
        val s = _statusFlow.value
        return s.mode != TorMode.OFF && s.running && s.bootstrapPercent >= 100 &&
                socksAddr != null && s.state == TorState.RUNNING
    }

    fun init(application: Application) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            initialized = true
            currentApplication = application
            torPreferences.init(application)

            artiProxy = buildArtiProxy(application)

            val savedMode = torPreferences.get(application)
            if (savedMode == TorMode.ON) {
                if (currentSocksPort < DEFAULT_SOCKS_PORT) {
                    currentSocksPort = DEFAULT_SOCKS_PORT
                }
                desiredMode = savedMode
                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                try {
                    okHttp.get().reset()
                } catch (_: Throwable) {
                }  // Only reset OkHttp during init
            }
            appScope.launch {
                applyMode(application, savedMode)
            }

            appScope.launch {
                torPreferences.modeFlow.collect { mode ->
                    applyMode(application, mode)
                }
            }

            appScope.launch {
                backgroundState.collect { inBackground ->
                    onAppBackgroundStateChanged(inBackground)
                }
            }
        }
    }

    internal fun onAppBackgroundStateChanged(inBackground: Boolean) {
        synchronized(dormantLock) {
            isAppInBackground = inBackground
            syncDormantModeLocked()
        }
    }

    private fun cancelDormantTimer() {
        synchronized(dormantLock) {
            dormantJob?.cancel()
            dormantJob = null
            artiDormantApplied = false
        }
    }

    /**
     * Keep Arti in `DormantMode::Normal` while foregrounded or bootstrapping, and transition to
     * `DormantMode::Soft` after [backgroundDormantDelayMs] once backgrounded and fully running.
     * All state checks and `proxy.setDormant(...)` calls are serialized under [dormantLock] so a
     * delayed background timer cannot apply `Soft` after a foreground wake has applied `Normal`.
     */
    private fun syncDormantMode() {
        synchronized(dormantLock) {
            syncDormantModeLocked()
        }
    }

    private fun syncDormantModeLocked() {
        dormantJob?.cancel()
        dormantJob = null

        val proxy = artiProxy ?: return
        if (!isAppInBackground) {
            if (artiDormantApplied) {
                artiDormantApplied = false
                proxy.setDormant(false)
            }
            return
        }

        if (!isProxyEnabled() || artiDormantApplied) {
            return
        }

        val scheduledJob = appScope.launch {
            if (backgroundDormantDelayMs > 0L) {
                delay(backgroundDormantDelayMs)
            }
            synchronized(dormantLock) {
                if (isAppInBackground && isProxyEnabled() && !artiDormantApplied) {
                    artiDormantApplied = true
                    artiProxy?.setDormant(true)
                }
            }
        }
        dormantJob = scheduledJob
    }

    fun currentSocksAddress(): InetSocketAddress? = socksAddr

    /**
     * Wait until the currently selected HTTP route can be used.
     *
     * When Tor mode is enabled, [socksAddr] is intentionally published before
     * bootstrap completes so clients fail closed instead of leaking traffic
     * directly. Callers that initiate one-shot HTTP work should wait here rather
     * than repeatedly connecting to a SOCKS port that is not listening yet.
     */
    suspend fun awaitSelectedRoute(timeoutMs: Long): Boolean {
        if (currentSocksAddress() == null || isProxyEnabled()) {
            return true
        }

        return withTimeoutOrNull(timeoutMs) {
            statusFlow.first {
                currentSocksAddress() == null || isProxyEnabled()
            }
            true
        } ?: false
    }

    suspend fun applyMode(application: Application, mode: TorMode) {
        applyMutex.withLock {
            try {
                desiredMode = mode
                lastMode = mode
                val s = _statusFlow.value
                if (mode == s.mode && mode != TorMode.OFF &&
                    (lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING)
                ) {
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
                            state = TorState.STOPPING
                        )
                        stopArti()
                        waitForStateTransition(target = TorState.OFF, timeoutMs = STOP_TIMEOUT_MS)
                        socksAddr = null
                        _statusFlow.value = _statusFlow.value.copy(
                            mode = TorMode.OFF,
                            running = false,
                            bootstrapPercent = 0,
                            state = TorState.OFF
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
                            state = TorState.STARTING
                        )
                        socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                        resetNetworkConnections()
                        startArti(application, useDelay = false)
                        launchPostBootstrapNetworkReset("Tor ON")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Arti mode: ${e.message}")
            }
        }
    }

    /**
     * Wait for Tor bootstrap to reach RUNNING, then republish [socksAddr] and reset clients
     * strictly under [applyMutex] so a concurrent `applyMode(TorMode.OFF)` can never be
     * overwritten by a finishing startup or automatic restart.
     */
    private fun launchPostBootstrapNetworkReset(reason: String) {
        appScope.launch {
            waitUntilBootstrapped()
            applyMutex.withLock {
                if (
                    desiredMode == TorMode.ON &&
                    lifecycleState == LifecycleState.RUNNING &&
                    _statusFlow.value.running &&
                    _statusFlow.value.state == TorState.RUNNING
                ) {
                    socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                    Log.i(TAG, "$reason: proxy set to $socksAddr")
                    resetNetworkConnections()
                }
            }
        }
    }

    private suspend fun startArti(application: Application, useDelay: Boolean = false) {
        try {
            stopArtiAndWait()
            if (useDelay) {
                delay(RESTART_DELAY_MS)
            }

            val proxy = proxyForCurrentPort(application) ?: run {
                Log.e(TAG, "ArtiProxy not initialized! This should not happen.")
                _statusFlow.update { it.copy(state = TorState.ERROR) }
                return
            }

            cancelDormantTimer()
            proxy.start()
            lastLogTime.set(System.currentTimeMillis())

            _statusFlow.update {
                it.copy(
                    running = true,
                    bootstrapPercent = 0,
                    state = TorState.STARTING
                )
            }
            lifecycleState = LifecycleState.RUNNING
            startInactivityMonitoring()

        } catch (e: Exception) {
            Log.e(TAG, "Error starting Arti on port $currentSocksPort: ${e.message}")
            _statusFlow.update { it.copy(state = TorState.ERROR) }

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
                _statusFlow.update {
                    it.copy(
                        running = false,
                        bootstrapPercent = 0,
                        state = TorState.ERROR
                    )
                }
            } else {
                scheduleRetry(application)
            }
        }
    }

    private fun buildArtiProxy(application: Application): ArtiProxy {
        val port = currentSocksPort
        return proxyFactory(application, port, artiLogListener).also { artiProxyPort = port }
    }

    /**
     * ArtiProxy fixes its ports at construction, so it must be rebuilt whenever
     * [currentSocksPort] moves or Tor would bind a different port than [socksAddr].
     * Call only after the previous proxy has been stopped.
     */
    private fun proxyForCurrentPort(application: Application): ArtiProxy? {
        if (artiProxy != null && artiProxyPort != currentSocksPort) {
            artiProxy = buildArtiProxy(application)
        }
        return artiProxy
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
     * Rebuilds OkHttp clients, then signals [routeChanges] so open connections are closed.
     */
    internal fun resetNetworkConnections() {
        try {
            okHttp.get().reset()
        } catch (_: Throwable) {
        }
        routeGenerationCounter.incrementAndGet()
        _routeChanges.tryEmit(Unit)
    }

    private fun stopArtiInternal() {
        try {
            cancelDormantTimer()
            val proxy = artiProxy
            if (proxy != null) {
                try {
                    proxy.stop()
                } catch (_: Throwable) {
                }
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
            state = TorState.STOPPING
        )
    }

    private suspend fun stopArtiAndWait(timeoutMs: Long = STOP_TIMEOUT_MS) {
        stopArtiInternal()
        waitForStateTransition(target = TorState.OFF, timeoutMs = timeoutMs)
        delay(200)
    }

    private suspend fun restartArti(application: Application) {
        Log.i(TAG, "Restarting Arti (keeping SOCKS proxy enabled)...")
        stopArtiAndWait()
        delay(RESTART_DELAY_MS)
        startArti(application, useDelay = false)
        launchPostBootstrapNetworkReset("Tor restarted")
    }

    /**
     * Runs an automatic restart in its own coroutine. The watchdog and retry jobs request restarts,
     * and stopping Arti cancels both jobs, so a restart running inside either would cancel itself.
     * Holding [applyMutex] serializes the restart with mode changes, so turning Tor off always
     * ends with Arti stopped.
     */
    private fun launchRestart(application: Application) {
        if (!restartInFlight.compareAndSet(false, true)) return
        appScope.launch {
            try {
                applyMutex.withLock {
                    if (
                        desiredMode == TorMode.ON &&
                        (lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING)
                    ) {
                        restartArti(application)
                    }
                }
            } finally {
                restartInFlight.set(false)
            }
        }
    }

    private fun startInactivityMonitoring() {
        armBootstrapInactivityWatchdog()
    }

    /**
     * One-shot startup watchdog. Arti log activity re-arms it and successful bootstrap cancels it,
     * so a healthy background Tor process has no permanent five-second polling coroutine.
     */
    private fun armBootstrapInactivityWatchdog() {
        inactivityJob?.cancel()
        if (lifecycleState != LifecycleState.RUNNING || _statusFlow.value.bootstrapPercent >= 100) {
            inactivityJob = null
            return
        }
        inactivityJob = appScope.launch {
            delay(INACTIVITY_TIMEOUT_MS)
            val timeSinceLastActivity = System.currentTimeMillis() - lastLogTime.get()
            if (
                timeSinceLastActivity >= INACTIVITY_TIMEOUT_MS &&
                _statusFlow.value.mode == TorMode.ON &&
                _statusFlow.value.bootstrapPercent < 100 &&
                lifecycleState == LifecycleState.RUNNING
            ) {
                Log.w(TAG, "Bootstrap inactivity detected (${timeSinceLastActivity}ms), restarting Arti")
                currentApplication?.let { launchRestart(it) }
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
                    launchRestart(application)
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

    private fun handleArtiLogLine(s: String, alreadyRunning: Boolean = false) {
        val currentLifecycle = lifecycleState
        if (alreadyRunning && currentLifecycle == LifecycleState.RUNNING) {
            // Fast path when steady-state RUNNING: only check for fatal lock errors.
            if (s.contains("Another process has the lock on our state files", ignoreCase = true)) {
                _statusFlow.update { it.copy(state = TorState.ERROR) }
            }
            return
        }

        when {
            s.contains("AMEx: state changed to Initialized", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STARTING && currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update { it.copy(state = TorState.STARTING) }
                completeWaitersIf(TorState.STARTING)
            }

            s.contains("AMEx: state changed to Starting", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STARTING && currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update { it.copy(state = TorState.STARTING) }
                completeWaitersIf(TorState.STARTING)
            }

            s.contains(
                "Sufficiently bootstrapped; system SOCKS now functional",
                ignoreCase = true
            ) -> {
                if (currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        bootstrapPercent = 75,
                        state = TorState.BOOTSTRAPPING
                    )
                }
                retryAttempts = 0
                bindRetryAttempts = 0
                startInactivityMonitoring()
            }

            s.contains("We have found that guard [scrubbed] is usable.", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.RUNNING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.RUNNING,
                        bootstrapPercent = 100,
                        running = true
                    )
                }
                stopInactivityMonitoring()
                completeWaitersIf(TorState.RUNNING)
                syncDormantMode()
            }

            s.contains("AMEx: state changed to Stopping", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STOPPING) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.STOPPING,
                        running = false
                    )
                }
            }

            s.contains("AMEx: state changed to Stopped", ignoreCase = true) -> {
                if (currentLifecycle != LifecycleState.STOPPING && currentLifecycle != LifecycleState.STOPPED) {
                    return
                }
                _statusFlow.update {
                    it.copy(
                        state = TorState.OFF,
                        running = false,
                        bootstrapPercent = 0
                    )
                }
                completeWaitersIf(TorState.OFF)
            }

            s.contains("Another process has the lock on our state files", ignoreCase = true) -> {
                _statusFlow.update { it.copy(state = TorState.ERROR) }
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
            val cur = _statusFlow.value.state
            if (cur == target) return@withTimeoutOrNull cur
            def.await()
        }
    }

    fun isTorAvailable(): Boolean = true
}
