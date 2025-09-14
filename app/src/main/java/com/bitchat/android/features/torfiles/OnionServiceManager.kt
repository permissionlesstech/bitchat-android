package com.bitchat.android.features.torfiles

import android.app.Application
import android.util.Log
import com.bitchat.android.ui.debug.DebugMessage
import com.bitchat.android.ui.debug.DebugSettingsManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages our Tor Onion Service and embedded local HTTP server for file transfer.
 *
 * Notes:
 * - Uses reflection to call Arti hidden service APIs if present in arti-mobile-ex.
 * - Starts a small HTTP server bound to 127.0.0.1 and maps it into the onion service.
 * - Persists the current onion address for sharing with favorites.
 */
object OnionServiceManager {
    private const val TAG = "OnionServiceManager"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val started = AtomicBoolean(false)
    private val _onionAddress = MutableStateFlow<String?>(null)
    val onionAddressFlow: StateFlow<String?> = _onionAddress.asStateFlow()

    // Local embedded HTTP server for inbound transfers
    private var server: TorFileTransferServer? = null
    private var serverPort: Int = 0

    fun getMyOnionAddress(): String? = _onionAddress.value

    fun ensureStarted(application: Application) {
        if (started.get()) return
        synchronized(this) {
            if (started.get()) return
            started.set(true)
        }
        scope.launch {
            try {
                // Start local HTTP server on an ephemeral port
                if (server == null) {
                    val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))
                    serverPort = ss.localPort
                    ss.close()
                    val s = TorFileTransferServer(application, serverPort)
                    s.start()
                    server = s
                    Log.i(TAG, "TorFileTransferServer started on 127.0.0.1:$serverPort")
                }

                // Create/restore onion service pointing to our local HTTP server
                val onion = createOrRestoreOnionService(application, serverPort)
                if (!onion.isNullOrBlank()) {
                    _onionAddress.value = onion
                    DebugSettingsManager.getInstance().addDebugMessage(
                        DebugMessage.SystemMessage("🧅 Onion service active: $onion")
                    )
                    Log.i(TAG, "🧅 Onion service address: $onion")
                    // Persist for later sharing
                    OnionStorage.saveMyOnionAddress(application, onion)
                } else {
                    Log.w(TAG, "Onion service not available (arti hidden service API not found)")
                    DebugSettingsManager.getInstance().addDebugMessage(
                        DebugMessage.SystemMessage("🧅 Onion service unavailable (no arti HS API)")
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start onion service: ${e.message}")
            }
        }
    }

    private fun createOrRestoreOnionService(application: Application, localPort: Int): String? {
        // If we previously stored an address, keep using it (assumes arti persists keys)
        val existing = OnionStorage.loadMyOnionAddress(application)

        return try {
            // Try to reach into TorManager's ArtiProxy via reflection by name
            val torManagerClass = Class.forName("com.bitchat.android.net.TorManager")
            val getInstanceField = torManagerClass.getDeclaredField("artiProxyRef")
            getInstanceField.isAccessible = true
            val atomicRef = getInstanceField.get(null)
            val getMethod = atomicRef.javaClass.getMethod("get")
            val proxy = getMethod.invoke(atomicRef) ?: return existing

            // Attempt common method names via reflection
            val methods = listOf(
                "createOnionService", // (int remotePort, String localHost, int localPort) -> String onion
                "addOnionService",
                "startOnionService"
            )

            var onion: String? = existing
            for (name in methods) {
                try {
                    // Try signatures: (Int, String, Int) mapping onion:80 -> 127.0.0.1:localPort
                    val m = proxy.javaClass.getMethod(name, Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType)
                    val addr = m.invoke(proxy, 80, "127.0.0.1", localPort) as? String
                    if (!addr.isNullOrBlank()) {
                        onion = normalizeOnion(addr)
                        break
                    }
                } catch (_: NoSuchMethodException) {
                    // Try alternative signature: (String localHost, int localPort) -> String
                    try {
                        val m2 = proxy.javaClass.getMethod(name, String::class.java, Int::class.javaPrimitiveType)
                        val addr = m2.invoke(proxy, "127.0.0.1", localPort) as? String
                        if (!addr.isNullOrBlank()) {
                            onion = normalizeOnion(addr)
                            break
                        }
                    } catch (_: Exception) { }
                } catch (_: Exception) {
                    // keep trying next
                }
            }
            onion ?: existing
        } catch (_: Exception) {
            existing
        }
    }

    private fun normalizeOnion(addr: String): String {
        var s = addr.trim()
        if (s.startsWith("http://")) s = s.removePrefix("http://")
        if (s.startsWith("https://")) s = s.removePrefix("https://")
        // Remove trailing slash
        s = s.trimEnd('/')
        return s
    }
}

