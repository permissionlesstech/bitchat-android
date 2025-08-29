package com.bitchat.android.net

import android.app.Application
import android.util.Log
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
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
    private val torProcessRef = AtomicReference<Process?>(null)
    private val logReaderJobRef = AtomicReference<Job?>(null)
    @Volatile private var lastMode: TorMode = TorMode.OFF
    private val applyMutex = Mutex()
    @Volatile private var desiredMode: TorMode = TorMode.OFF
    @Volatile private var currentSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile private var nextSocksPort: Int = DEFAULT_SOCKS_PORT
    @Volatile private var bindRetryScheduled: Boolean = false

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
                        stopTor()
                        socksAddr = null
                        _status.value = _status.value.copy(mode = TorMode.OFF, running = false, bootstrapPercent = 0)
                        nextSocksPort = DEFAULT_SOCKS_PORT
                        lifecycleState = LifecycleState.STOPPED
                    }
                    TorMode.ON -> {
                        Log.i(TAG, "applyMode: ON -> starting tor")
                        nextSocksPort = if (currentSocksPort < DEFAULT_SOCKS_PORT) DEFAULT_SOCKS_PORT else currentSocksPort
                        lifecycleState = LifecycleState.STARTING
                        startTor(application, isolation = false)
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
                        Log.i(TAG, "applyMode: ISOLATION -> starting tor")
                        nextSocksPort = if (currentSocksPort < DEFAULT_SOCKS_PORT) DEFAULT_SOCKS_PORT else currentSocksPort
                        lifecycleState = LifecycleState.STARTING
                        startTor(application, isolation = true)
                        _status.value = _status.value.copy(mode = TorMode.ISOLATION)
                        appScope.launch {
                            waitUntilBootstrapped()
                            if (_status.value.running && desiredMode == TorMode.ISOLATION) {
                                socksAddr = InetSocketAddress("127.0.0.1", currentSocksPort)
                                Log.i(TAG, "Tor ISOLATION: proxy set to ${socksAddr}")
                                OkHttpProvider.reset()
                                try { com.bitchat.android.nostr.NostrRelayManager.shared.resetAllConnections() } catch (_: Throwable) { }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to apply Tor mode: ${e.message}")
            }
        }
    }

    private fun startTor(application: Application, isolation: Boolean) {
        try {
            // If running, stop and restart with new config
            stopTor()

            val appBinHome = File(application.filesDir, "tor_bin").apply { mkdirs() }
            val appDataDir = File(application.filesDir, "tor_data").apply { mkdirs() }

            // Install tor binary & geoip files from the AAR (with one retry)
            if (!installTorResources(application, appBinHome)) {
                return
            }

            val torBin = File(appBinHome, "tor")
            try {
                android.system.Os.chmod(appBinHome.absolutePath, 448)
                android.system.Os.chmod(torBin.absolutePath, 448)
                val geoipF = File(appBinHome, "geoip")
                val geoip6F = File(appBinHome, "geoip6")
                if (geoipF.exists()) android.system.Os.chmod(geoipF.absolutePath, 384)
                if (geoip6F.exists()) android.system.Os.chmod(geoip6F.absolutePath, 384)
            } catch (_: Throwable) { }

            val geoip = File(appBinHome, "geoip")
            val geoip6 = File(appBinHome, "geoip6")

            // Determine port
            val port = nextSocksPort
            currentSocksPort = port
            bindRetryScheduled = false

            // Write torrc
            val torrc = File(application.filesDir, "torrc").apply {
                FileWriter(this, false).use { w ->
                    w.appendLine("RunAsDaemon 0")
                    w.appendLine("ClientOnly 1")
                    w.appendLine("AvoidDiskWrites 1")
                    w.appendLine("DataDirectory ${appDataDir.absolutePath}")
                    w.appendLine("DisableNetwork 0")
                    val isoFlags = if (isolation) " IsolateDestAddr IsolateDestPort" else ""
                    w.appendLine("SOCKSPort 127.0.0.1:${port}${isoFlags}")
                    // Prefer IPv6 where available to improve bootstrap on IPv6-only networks
                    w.appendLine("ClientUseIPv6 1")
                    w.appendLine("ClientPreferIPv6ORPort 1")
                    if (geoip.exists()) w.appendLine("GeoIPFile ${geoip.absolutePath}")
                    if (geoip6.exists()) w.appendLine("GeoIPv6File ${geoip6.absolutePath}")
                    w.appendLine("Log notice stdout")
                }
            }

            val cmd = buildExecCommand(torBin, listOf("-f", torrc.absolutePath))
            val pb = ProcessBuilder(cmd)
                .directory(appBinHome)
                .redirectErrorStream(true)
            // Provide environment hints
            val env = pb.environment()
            env["HOME"] = application.filesDir.absolutePath
            env["TMPDIR"] = application.cacheDir.absolutePath
            // Some devices require explicit linker search path for any side libs
            env["LD_LIBRARY_PATH"] = appBinHome.absolutePath

            val process = pb.start()
            torProcessRef.set(process)

            _status.value = _status.value.copy(running = true, bootstrapPercent = 0)
            lifecycleState = LifecycleState.RUNNING

            val readerJob = appScope.launch {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { rawLine ->
                            val line = rawLine.trim()
                            if (line.isNotEmpty()) {
                                Log.i(TAG, "tor: $line")
                                _status.value = _status.value.copy(lastLogLine = line)
                                val idx = line.indexOf("Bootstrapped ")
                                if (idx >= 0) {
                                    val pctStr = line.substring(idx + 13).takeWhile { it.isDigit() }
                                    val pct = pctStr.toIntOrNull()
                                    if (pct != null) {
                                        _status.value = _status.value.copy(bootstrapPercent = pct)
                                    }
                                }
                                if (!bindRetryScheduled && (line.contains("Could not bind", ignoreCase = true) || line.contains("Address already in use", ignoreCase = true) || line.contains("Failed to bind", ignoreCase = true))) {
                                    bindRetryScheduled = true
                                    Log.w(TAG, "Detected bind error on port $port; scheduling restart on next port")
                                    appScope.launch {
                                        applyMutex.withLock {
                                            try {
                                                stopTor()
                                                nextSocksPort = port + 1
                                                Log.i(TAG, "Retrying Tor start on port ${nextSocksPort}")
                                                if (desiredMode != TorMode.OFF) {
                                                    lifecycleState = LifecycleState.STARTING
                                                    startTor(application, isolation)
                                                }
                                            } catch (t: Throwable) {
                                                Log.e(TAG, "Error during bind-retry restart: ${t.message}")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (t: Throwable) {
                    // Swallow stream close / interruption during stop
                }
            }
            logReaderJobRef.getAndSet(readerJob)?.cancel()

            // Watch for process exit (no automatic restart; we keep state consistent)
            appScope.launch {
                try {
                    val exitCode = process.waitFor()
                    Log.i(TAG, "Tor process exited with code $exitCode")
                } catch (_: Throwable) { }
                _status.value = _status.value.copy(running = false)
                lifecycleState = LifecycleState.STOPPED
            }

            Log.i(TAG, "Launched Tor process")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting Tor: ${e.message}")
        }
    }

    private fun stopTor() {
        try {
            val p = torProcessRef.getAndSet(null)
            val readerJob = logReaderJobRef.getAndSet(null)
            if (p != null) {
                Log.i(TAG, "Stopping Tor process…")
                try { readerJob?.cancel() } catch (_: Throwable) {}
                try { p.destroy() } catch (_: Throwable) {}
                // Best-effort wait for exit
                appScope.launch {
                    try { p.waitFor() } catch (_: Throwable) {}
                }
            }
            socksAddr = null
            _status.value = _status.value.copy(running = false, bootstrapPercent = 0)
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping Tor: ${e.message}")
        }
    }

    private fun installTorResources(application: Application, appBinHome: File): Boolean {
        var lastError: Throwable? = null
        repeat(2) { attempt ->
            try {
                val installer = org.torproject.android.binary.TorResourceInstaller(application, appBinHome)
                installer.installResources()
                Log.i(TAG, "Installed Tor resources into ${appBinHome.absolutePath} (attempt=${attempt + 1})")
                return true
            } catch (e: Throwable) {
                lastError = e
                Log.w(TAG, "Tor resource install failed (attempt=${attempt + 1}): ${e.message}")
                // Permission repair then retry
                try {
                    android.system.Os.chmod(appBinHome.absolutePath, 448)
                    listOf("tor", "geoip", "geoip6").forEach { name ->
                        val f = File(appBinHome, name)
                        if (f.exists()) {
                            try { android.system.Os.chmod(f.absolutePath, 384) } catch (_: Throwable) {}
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
        Log.e(TAG, "Failed to install Tor resources after retry: ${lastError?.message}")
        return false
    }

    /**
     * Build an execution command that works on Android 10+ where app data dirs are mounted noexec.
     * We invoke the platform dynamic linker and pass the PIE binary path as its first arg.
     */
    private fun buildExecCommand(binary: File, args: List<String>): List<String> {
        val linker = if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()) "/system/bin/linker64" else "/system/bin/linker"
        val command = ArrayList<String>(2 + args.size)
        command.add(linker)
        command.add(binary.absolutePath)
        command.addAll(args)
        return command
    }

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
    fun installResourcesForTest(application: Application): Boolean {
        val dir = File(application.filesDir, "tor_bin").apply { mkdirs() }
        try { android.system.Os.chmod(dir.absolutePath, 448) } catch (_: Throwable) {}
        return installTorResources(application, dir)
    }
}
