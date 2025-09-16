package com.bitchat.android.mesh

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.WifiP2pGroup
import android.os.Build
import android.util.Log
import com.bitchat.android.ui.debug.DebugSettingsManager
import com.bitchat.android.ui.debug.DebugMessage

import androidx.core.content.ContextCompat
import com.bitchat.android.protocol.BitchatPacket

import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.*
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wi‑Fi Direct single-link bridge for the mesh.
 * - Discovers peers and maintains at most one P2P link.
 * - On link, runs overlap gate handshake, then carries BitchatPacket frames.
 */

class WifiDirectConnectionManager(
    private val context: Context,
    private val myPeerID: String
) {

    companion object {
        private const val TAG = "WifiDirectConnMgr"
        private val PORTS = intArrayOf(49537, 49577, 49613, 49681, 49753)
        private const val RESCAN_BACKOFF_MS = 10_000L
        private const val DISCOVER_INTERVAL_MS = 30_000L
        private const val ATTEMPT_TTL_MS = 60_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private const val HANDSHAKE_TIMEOUT_MS = 5_000L
        private const val NULL_MAC = "02:00:00:00:00:00"
    }

    interface WifiDirectDelegate {
        fun onMeshFrameReceived(frame: BitchatPacket, peerID: String, linkId: String)
        fun onLinkEstablished(linkId: String)
        fun onLinkClosed(linkId: String, reason: String? = null)
    }

// Removed: preferGo(); GO/Client is decided by Android's negotiation.

    var delegate: WifiDirectDelegate? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val active = AtomicBoolean(false)

    // P2P device info
    @Volatile private var myP2pMac: String? = null

    private val roleOverride = ConcurrentHashMap<String, Boolean>() // deviceAddress -> preferGO override
    private val failureCount = ConcurrentHashMap<String, Int>()
    private val backoffUntil = ConcurrentHashMap<String, Long>() // deviceAddress -> next-allowed-time ms
    private val backoffLevel = ConcurrentHashMap<String, Int>() // deviceAddress -> retry exponent


    // Single link only
    @Volatile private var socket: Socket? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var linkId: String? = null

    // Wi‑Fi P2P
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private val invitedHoldUntil = ConcurrentHashMap<String, Long>() // deviceAddress -> hold-until timestamp

    private var receiver: BroadcastReceiver? = null
    private var isDiscovering = false
    private var isConnecting = false

    private val attempted = ConcurrentHashMap<String, Long>() // deviceAddress -> timestamp

    private var nicknameResolver: ((String) -> String?)? = null
    fun setNicknameResolver(resolver: (String) -> String?) { nicknameResolver = resolver }

    fun start() {
        if (!active.compareAndSet(false, true)) return
        if (!hasWifiDirectPermissions()) {
            Log.w(TAG, "Missing Wi‑Fi Direct permissions; manager will be idle")
            try {
                DebugSettingsManager.getInstance().addDebugMessage(
                    DebugMessage.SystemMessage("[Wi‑Fi Direct] not starting: missing permission (Android 13+: NEARBY_WIFI_DEVICES; pre‑13: ACCESS_FINE_LOCATION)"))
            } catch (_: Exception) {}
            return
        }
        try {
            DebugSettingsManager.getInstance().addDebugMessage(
                DebugMessage.SystemMessage("[Wi‑Fi Direct] starting…"))
        } catch (_: Exception) {}
        manager = context.getSystemService(Context.WIFI_P2P_SERVICE) as WifiP2pManager
        channel = manager?.initialize(context, context.mainLooper) {
            Log.w(TAG, "Wi‑Fi P2P channel disconnected")
        }
        registerReceiver()
        // Try to detect existing P2P group immediately (app may have started after the link formed)
        try { manager?.requestConnectionInfo(channel) { info -> onConnectionInfo(info) } } catch (_: Exception) {}
        // Removed unconditional group creation and automatic discovery loop.
        // New model: user opens system Wi‑Fi Direct settings to pick a peer.
        // We only observe connection changes and proceed to socket handshake when group is formed.

        try { DebugSettingsManager.getInstance().setWifiDirectActive(true) } catch (_: Exception) {}
        Log.i(TAG, "Wi‑Fi Direct manager started")
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        try { manager?.stopPeerDiscovery(channel, null) } catch (_: Exception) {}
        stopDiscovery()
        disconnect()
        scope.coroutineContext.cancelChildren()
        try { DebugSettingsManager.getInstance().setWifiDirectActive(false) } catch (_: Exception) {}
        Log.i(TAG, "Wi‑Fi Direct manager stopped")
    }

    // ConnectivityManager (for optional network binding)
    private val cm: android.net.ConnectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
    }

    // Try to find the P2P network for binding client sockets (best-effort)
    private fun findP2pNetwork(goAddr: java.net.InetAddress?): android.net.Network? {
        return try {
            val nets = cm.allNetworks ?: return null
            val ga = goAddr
            nets.firstOrNull { net ->
                val lp = cm.getLinkProperties(net) ?: return@firstOrNull false
                val ifn = lp.interfaceName ?: ""
                // Heuristics: interface name contains "p2p" OR the routes/addresses mention 192.168.49.x OR match GO address
                val hasP2pIf = ifn.contains("p2p", ignoreCase = true)
                val has49 = lp.linkAddresses.any { it.address.hostAddress?.startsWith("192.168.49.") == true }
                val routesGo = if (ga != null) lp.routes.any { r -> containsAddress(r.destination, ga) } else false
                hasP2pIf || has49 || routesGo
            }
        } catch (_: Exception) { null }
    }

    // Helper: safe IpPrefix.contains wrapper
    private fun containsAddress(prefix: android.net.IpPrefix?, addr: java.net.InetAddress?): Boolean {
        if (prefix == null || addr == null) return false
        return try { prefix.contains(addr) } catch (_: Exception) { false }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        try { server?.close() } catch (_: Exception) {}
        val id = linkId
        socket = null; server = null; linkId = null; isConnecting = false
        id?.let { delegate?.onLinkClosed(it, "manual disconnect") }
        // Also remove P2P group if any (best-effort)
        try { manager?.removeGroup(channel, null) } catch (_: Exception) {}
        // Resume discovery after backoff
        scheduleRescan()
    }

    private fun startDiscoveryLoop() { /* no-op in manual mode */ }
    private fun stopDiscovery() { isDiscovering = false }
    private fun discoverPeersOnce() { /* no-op in manual mode */ }

    // Backoff helper
    private fun scheduleRetryWithBackoff(addr: String) {
        val level = (backoffLevel[addr] ?: 0) + 1
        backoffLevel[addr] = level
        val delays = longArrayOf(10_000L, 20_000L, 40_000L, 60_000L)
        val base = if (level - 1 in delays.indices) delays[level - 1] else 60_000L
        val jitter = (0L..1_000L).random()
        val delayMs = base + jitter
        backoffUntil[addr] = System.currentTimeMillis() + delayMs
        try {
            DebugSettingsManager.getInstance()
                .addDebugMessage(
                    DebugMessage.SystemMessage("[Wi‑Fi Direct] Backoff for $addr = ${delayMs}ms (level=$level)"))
        } catch (_: Exception) {}
        scope.launch { delay(delayMs); requestPeersAndMaybeConnect() }
    }

    private fun requestPeersAndMaybeConnect() {
        val m = manager ?: return
        val ch = channel ?: return
        try {
            m.requestConnectionInfo(ch) { info -> onConnectionInfo(info) }
        } catch (e: Exception) {
            Log.w(TAG, "requestConnectionInfo error: ${e.message}")
        }
    }

    private fun logAllNetworks(tag: String) {
        try {
            val nets = cm.allNetworks ?: return
            for (n in nets) {
                val lp = cm.getLinkProperties(n)
                val nc = cm.getNetworkCapabilities(n)
                val ifn = lp?.interfaceName
                val addrs = lp?.linkAddresses?.joinToString { it.address.hostAddress ?: "?" }
                val transports = buildList {
                    if (nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) add("WIFI")
                    if (nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("CELL")
                    if (nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_BLUETOOTH) == true) add("BT")
                    if (nc?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) == true) add("ETH")
                }.joinToString("+")
                Log.d(TAG, "$tag: net=$n if=$ifn addrs=[$addrs] transports=$transports")
            }
        } catch (e: Exception) {
            Log.w(TAG, "logAllNetworks error: ${e.message}")
        }
    }

    private fun logAllInterfaces(tag: String) {
        try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                val addrs = ni.inetAddresses.toList().joinToString { it.hostAddress ?: "?" }
                Log.d(TAG, "$tag: if=${ni.name} addrs=[$addrs]")
            }
        } catch (e: Exception) {
            Log.w(TAG, "logAllInterfaces error: ${e.message}")
        }
    }

    private fun getP2pInet4FromInterfaces(): java.net.Inet4Address? {
        return try {
            val ifs = java.net.NetworkInterface.getNetworkInterfaces()
            while (ifs.hasMoreElements()) {
                val ni = ifs.nextElement()
                val name = ni.name?.lowercase() ?: ""
                if (!name.contains("p2p")) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (a is java.net.Inet4Address) {
                        val s = a.hostAddress ?: continue
                        if (s.startsWith("192.168.49.")) return a
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun getP2pLocalAddress(net: android.net.Network?): java.net.InetAddress? {
        return try {
            val lp = if (net != null) cm.getLinkProperties(net) else null
            val cand = lp?.linkAddresses?.mapNotNull { it.address }?.firstOrNull { addr ->
                addr.hostAddress?.startsWith("192.168.49.") == true ||
                (lp.interfaceName?.contains("p2p", ignoreCase = true) == true)
            }
            cand
        } catch (_: Exception) { null }
    }

    // Broadcast receiver
    private fun registerReceiver() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val act = intent?.action ?: return
                when (act) {
                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                        Log.d(TAG, "WIFI_P2P_STATE_CHANGED_ACTION")
                        val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                        val enabled = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                        DebugSettingsManager.getInstance().addDebugMessage(
                            DebugMessage.SystemMessage("[Wi‑Fi Direct] state=${if (enabled) "ENABLED" else "DISABLED"}"))
                        if (enabled) startDiscoveryLoop()
                    }
                    WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                        Log.d(TAG, "WIFI_P2P_STATE_CHANGED_ACTION")
                        requestPeersAndMaybeConnect()
                    }
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        Log.d(TAG, "WIFI_P2P_CONNECTION_CHANGED_ACTION")
                        val info = intent.getParcelableExtra<android.net.NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                        handleConnectionChanged(info)
                    }
                    WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                        val dev = intent.getParcelableExtra<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                        myP2pMac = dev?.deviceAddress
                        try { DebugSettingsManager.getInstance().setWifiDirectMyMac(myP2pMac) } catch (_: Exception) {}
                    }
                }
            }
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun ensureGroupVisibility() {
        // Removed any forcing of GO/Client. Android framework negotiates the role.
        try { manager?.removeGroup(channel, null) } catch (_: Exception) {}
    }

    private fun handleConnectionChanged(ninfo: NetworkInfo?) {
        val connected = ninfo?.isConnected == true
        if (!connected) {
            // Disconnected
            if (socket != null || linkId != null) {
                val id = linkId
                try { socket?.close() } catch (_: Exception) {}
                try { server?.close() } catch (_: Exception) {}
                socket = null; server = null; linkId = null; isConnecting = false
                id?.let { delegate?.onLinkClosed(it, "wifi disconnect") }
            }
            scheduleRescan()
            return
        }
        // Connected: get connection info
        try {
            manager?.requestConnectionInfo(channel) { info -> onConnectionInfo(info) }
        } catch (e: Exception) {
            Log.w(TAG, "requestConnectionInfo error: ${e.message}")
        }
        logAllNetworks("onBroadcast")
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info == null) return
        if (socket != null) return // already attached
        if (!info.groupFormed) {
            Log.d(TAG, "Connection info: group not formed yet")
            return
        }
        if (info.isGroupOwner) {
            Log.d(TAG, "Connection info: We are GO; starting server")
            startServerIfNeeded(PORTS.first())
        } else {
            val host = info.groupOwnerAddress?.hostAddress
            if (host != null) {
                Log.d(TAG, "Connection info: GO at $host; attempting client dial")
                connectToHost(host, PORTS)
            } else {
                Log.w(TAG, "Connection info: Missing GO hostAddress; cannot dial")
            }
        }
    }

    // GO: Accept exactly one client (idempotent)
    private fun startServerIfNeeded(port: Int = PORTS.first()) {
        // If already listening and not closed, do nothing
        try {
            val s = server
            if (s != null && !s.isClosed) {
                Log.d(TAG, "Server already listening")
                return
            }
        } catch (_: Exception) {}
        scope.launch {
            for (p in PORTS) {
                try {
                    try { server?.close() } catch (_: Exception) {}
                    server = ServerSocket(p)
                    Log.i(TAG, "[Wi‑Fi Direct] Listening on port $p (single client)")
                    val s = withContext(Dispatchers.IO) { server!!.accept() }
                    if (!active.get()) { s.close(); return@launch }
                    attachSocket(s, isServer = true)
                    return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Server bind failed on port $p: ${e.message}")
                    try { server?.close() } catch (_: Exception) {}
                    server = null
                    continue
                }
            }
            Log.e(TAG, "Server error: no ports available to bind")
            scheduleRescan()
        }
    }

    // Client: connect to GO
    private fun connectToHost(host: String, ports: IntArray = PORTS, allowRetry: Boolean = true) {
        scope.launch {
            try {
                // Give P2P stack a moment to surface routes
                delay(800)
                // Log networks before dialing
                logAllNetworks("dialPrep")
                val addr = java.net.InetAddress.getByName(host)
                // Fallback: if ConnectivityManager network not present yet, try raw interface list
                val fallbackAddr = getP2pInet4FromInterfaces()
                val localBind = getP2pLocalAddress(net) ?: fallbackAddr
                for ((i, port) in ports.withIndex()) {
                    try {
                        val s = withContext(Dispatchers.IO) {
                            val sock = java.net.Socket()
                            try {
                                // Prefer per-socket binding to the P2P network
                                if (net != null) {
                                    net.bindSocket(sock)
                                    // Bind local source to p2p interface address if available
                                    getP2pLocalAddress(net)?.let { la ->
                                        try { sock.bind(java.net.InetSocketAddress(la, 0)) } catch (_: Exception) {}
                                    }
                                }
                            } catch (_: Exception) {}
                            try {
                                sock.connect(java.net.InetSocketAddress(addr, port), HANDSHAKE_TIMEOUT_MS.toInt().coerceAtLeast(5000))
                                sock
                            } catch (e: Exception) {
                                try { sock.close() } catch (_: Exception) {}
                                throw e
                            }
                        }
                        if (!active.get()) { s.close(); return@launch }
                        attachSocket(s, isServer = false)
                        return@launch
                    } catch (e: Exception) {
                        Log.w(TAG, "Client connect error to $host:$port — ${e.message}")
                        // Small stagger between attempts
                        delay(200)
                        // continue to next port
                    }
                }
                // All ports failed on this cycle
                DebugSettingsManager.getInstance().logWifiConnectionResult(host, false, "all ports failed")
                if (allowRetry) {
                    // Retry once after a short wait; re-request connection info to refresh host/route
                    delay(1500)
                    try { manager?.requestConnectionInfo(channel) { info ->
                        val h = info?.groupOwnerAddress?.hostAddress
                        if (!h.isNullOrEmpty()) connectToHost(h, ports, allowRetry = false)
                    } } catch (_: Exception) {}
                } else {
                    scheduleRescan()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Client connect flow error: ${e.message}")
                scheduleRescan()
            }
        }
    }

    private fun attachSocket(s: Socket, isServer: Boolean) {
        // Run handshake first, then only attach and start reader if accepted.
        scope.launch {
            try {
                // Apply a read timeout just for the handshake
                try { s.soTimeout = HANDSHAKE_TIMEOUT_MS.toInt() } catch (_: Exception) {}
                Log.d(TAG, "Starting handshake (isServer=$isServer)")
                val accepted = runOverlapGateHandshake(s)
                if (!accepted) {
                    Log.w(TAG, "Handshake failed (overlap/timeout)")
                    try { s.close() } catch (_: Exception) {}
                    DebugSettingsManager.getInstance().logWifiConnectionResult("handshake", false, "overlap/timeout")
                    scheduleRescan()
                    return@launch
                }
                // Clear handshake timeout for normal stream
                try { s.soTimeout = 0 } catch (_: Exception) {}

                // Now attach the socket and start reader
                socket = s
                linkId = (if (isServer) "WFD:GO:" else "WFD:CL:") + runCatching { s.inetAddress.hostAddress }.getOrNull()
                Log.i(TAG, "Handshake succeeded; linkId=${linkId}")
                DebugSettingsManager.getInstance().logWifiConnectionResult(linkId ?: "?", true)
                delegate?.onLinkEstablished(linkId!!)

                readerLoop(s)
            } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                Log.e(TAG, "attachSocket/handshake error: ${e.message}")
                scheduleRescan()
            }
        }
    }

    private suspend fun runOverlapGateHandshake(s: Socket): Boolean {
        return try {
            val dout = DataOutputStream(s.getOutputStream())
            val din = DataInputStream(s.getInputStream())

            val locals = try { PeerManagerReflection.activePeerIDs() } catch (_: Exception) { emptyList() }
            val localCount = locals.size

            val header = byteArrayOf(0x01.toByte())
            val buf = ByteBuffer.allocate(1 + 2 + (8 * localCount)).order(ByteOrder.BIG_ENDIAN)
            buf.put(header)
            buf.putShort(localCount.toShort())
            locals.forEach { pidHex -> buf.put(hexPeerTo8(pidHex)) }
            dout.write(buf.array())
            dout.flush()

            // Receive counterpart summary with a deadline
            val start = System.currentTimeMillis()
            while (din.available() <= 0 && (System.currentTimeMillis() - start) < HANDSHAKE_TIMEOUT_MS) {
                delay(10)
            }
            if (din.available() <= 0) {
                Log.w(TAG, "Handshake timeout waiting for remote summary")
                return false
            }
            val t = din.readUnsignedByte()
            if (t != 0x01) throw IllegalStateException("unexpected handshake message type $t")
            val rc = din.readUnsignedShort()
            val rem = ArrayList<String>(rc)
            repeat(rc) {
                val id = ByteArray(8)
                din.readFully(id)
                rem.add(id.toHexString())
            }

            val remoteCount = rem.size
            val overlap = locals.intersect(rem.toSet()).size
            val threshold = try { DebugSettingsManager.getInstance().wifiDirectOverlapThreshold.value } catch (_: Exception) { 3 }

            // Optional: fancier metric example (Jaccard similarity)
            // val union = (locals.toSet() + rem.toSet()).size
            // val jaccard = if (union == 0) 0.0 else overlap.toDouble() / union.toDouble()
            // Decide using threshold as absolute for now; can switch to ratio later.

            val action = if (overlap > threshold) "DROP" else "ACCEPT"
            DebugSettingsManager.getInstance().logWifiOverlapDecision(localCount, remoteCount, overlap, threshold, action)

            action != "DROP"
        } catch (e: Exception) {
            Log.w(TAG, "Overlap handshake error: ${e.message}")
            false
        }
    }

    private suspend fun readerLoop(s: Socket) {
        try {
            val din = DataInputStream(s.getInputStream())
            while (active.get()) {
                val len = try { din.readUnsignedShort() } catch (e: Exception) { break }
                val frame = ByteArray(len)
                din.readFully(frame)
                val pkt = BitchatPacket.fromBinaryData(frame) ?: continue
                val peerID = pkt.senderID.toHexString()
                val id = linkId ?: "WFD:?"
                delegate?.onMeshFrameReceived(pkt, peerID, id)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Reader loop error: ${e.message}")
        } finally {
            val id = linkId
            try { s.close() } catch (_: Exception) {}
            linkId = null; socket = null; isConnecting = false
            id?.let { delegate?.onLinkClosed(it, "reader closed") }

            // Attempt fast recovery without waiting for a full P2P reconnect event
            val m = manager
            val ch = channel
            if (m != null && ch != null) {
                try {
                    // Clear attempt suppression so we can retry quickly
                    attempted.clear()
                    m.requestConnectionInfo(ch) { info ->
                        try {
                            if (info != null && info.groupFormed) {
                                if (info.isGroupOwner) {
                                    // Re-open server accept for the next client
                                    try { server?.close() } catch (_: Exception) {}
                                    server = null
                                startServerIfNeeded(PORTS.first())
                                } else {
                                    // Reconnect TCP to existing GO
                                    val host = info.groupOwnerAddress?.hostAddress
                                    if (host != null) {
                                        connectToHost(host, PORTS)
                                    } else {
                                        // No host info; rescan
                                        discoverPeersOnce()
                                        scheduleRescan()
                                    }
                                }
                            } else {
                                // Group not formed; kick a discover and rescan
                                discoverPeersOnce()
                                scheduleRescan()
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "post-drop recovery error: ${e.message}")
                            scheduleRescan()
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "requestConnectionInfo (post-drop) error: ${e.message}")
                    scheduleRescan()
                }
            } else {
                scheduleRescan()
            }
        }
    }

    // Sending APIs
    fun sendPacketToPeer(@Suppress("UNUSED_PARAMETER") peerID: String, packet: BitchatPacket): Boolean {
        val s = socket ?: return false
        return try {
            val bytes = packet.toBinaryData() ?: return false
            val dout = DataOutputStream(s.getOutputStream())
            dout.writeShort(bytes.size)
            dout.write(bytes)
            dout.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendPacketToPeer error: ${e.message}")
            false
        }
    }

    fun broadcastPacket(routed: RoutedPacket) {
        val s = socket ?: return
        // Avoid echo back on the same link if it’s a relay
        if (routed.relayAddress != null && routed.relayAddress == linkId) return
        try {
            val bytes = routed.packet.toBinaryData() ?: return
            val dout = DataOutputStream(s.getOutputStream())
            dout.writeShort(bytes.size)
            dout.write(bytes)
            dout.flush()
            // best-effort relay debug log
            try {
                val dbg = DebugSettingsManager.getInstance()
                val type = MessageType.fromValue(routed.packet.type)?.name
                val sender = routed.peerID ?: routed.packet.senderID.toHexString()
                dbg.logPacketRelayDetailed(type ?: "?", sender, nicknameResolver?.invoke(sender), null, null, routed.relayAddress, null, null, linkId, routed.packet.ttl, true)
            } catch (_: Exception) { }
        } catch (e: Exception) {
            Log.w(TAG, "broadcast over Wi‑Fi error: ${e.message}")
        }
    }

    fun getDebugInfo(): String {
        return buildString {
            appendLine("Active: ${active.get()}")
            appendLine("LinkId: ${linkId ?: "<none>"}")
        }
    }

    private fun deviceStatusToString(s: Int): String = when (s) {
        WifiP2pDevice.CONNECTED -> "CONNECTED"
        WifiP2pDevice.INVITED -> "INVITED"
        WifiP2pDevice.FAILED -> "FAILED"
        WifiP2pDevice.AVAILABLE -> "AVAILABLE"
        WifiP2pDevice.UNAVAILABLE -> "UNAVAILABLE"
        else -> s.toString()
    }

    private fun scheduleRescan() {
        if (!active.get()) return
        scope.launch {
            delay(RESCAN_BACKOFF_MS)
            requestPeersAndMaybeConnect()
        }
    }

    private fun hexPeerTo8(peerID: String): ByteArray {
        val out = ByteArray(8)
        var idx = 0; var i = 0
        while (i + 2 <= peerID.length && idx < 8) {
            out[idx++] = peerID.substring(i, i+2).toInt(16).toByte(); i += 2
        }
        return out
    }

    private fun hasWifiDirectPermissions(): Boolean {
        // Android 13+: NEARBY_WIFI_DEVICES; older: ACCESS_FINE_LOCATION often required
        return if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }
}

/**
 * Reflection bridge to reach PeerManager active peer list.
 * BluetoothMeshService installs the provider.
 */
internal object PeerManagerReflection {
    @Volatile private var provider: (() -> List<String>)? = null
    fun setProvider(p: () -> List<String>) { provider = p }
    fun activePeerIDs(): List<String> = try { provider?.invoke() ?: emptyList() } catch (_: Exception) { emptyList() }
}
