package com.bitchat.android.mesh

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Local Network Connection Manager
 *
 * Handles TCP connections over local Wi-Fi networks for peer-to-peer communication.
 * This provides an alternative to Wi-Fi Direct that works with iPhone hotspots and
 * Android emulators.
 *
 * Features:
 * - Automatic peer discovery via UDP broadcast
 * - TCP connections for reliable data transfer
 * - Self-connection prevention (devices don't connect to themselves)
 * - Manual IP connection for testing and direct connections
 * - Network diagnostics for troubleshooting
 *
 * Architecture:
 * - Uses UDP port 8988 for discovery broadcasts
 * - Uses TCP port 8989 for data connections
 * - Implements PowerManagerDelegate for power-aware operation
 * - Integrates with BluetoothMeshService for unified mesh networking
 */
class LocalNetworkConnectionManager(
    private val context: Context,
    private val myPeerID: String,
    private val fragmentManager: FragmentManager? = null
) : PowerManagerDelegate {

    companion object {
        private const val TAG = "LocalNetworkConnectionManager"
        private const val SERVER_PORT = 8989 // TCP port for data connections
        private const val DISCOVERY_PORT = 8988 // UDP port for peer discovery
        private const val DISCOVERY_MESSAGE = "BITCHAT_DISCOVERY"
    }

    // Network components
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    // Power management
    private val powerManager = PowerManager(context.applicationContext)

    // Coroutines
    private val connectionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Component managers
    private val packetBroadcaster = LocalNetworkPacketBroadcaster(connectionScope)

    // Connection state
    private val connectedSockets = ConcurrentHashMap<String, Socket>() // ipAddress -> socket
    private val serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private var broadcastSocket: DatagramSocket? = null

    // Service state
    private var isActive = false

    // Delegate for callbacks
    var delegate: LocalNetworkConnectionManagerDelegate? = null

    init {
        powerManager.delegate = this
    }

    /**
     * Start local network services
     */
    fun startServices(): Boolean {
        if (isActive) {
            Log.w(TAG, "Local network services already active")
            return true
        }

        Log.i(TAG, "Starting local network services with peer ID: $myPeerID")

        try {
            // Start server for incoming connections
            startServer()
            Log.d(TAG, "✅ Server started")

            // Start discovery broadcasting
            startDiscoveryBroadcast()
            Log.d(TAG, "✅ Discovery broadcast started")

            // Start listening for discovery messages
            startDiscoveryListener()
            Log.d(TAG, "✅ Discovery listener started")

            isActive = true
            powerManager.start()

            Log.i(TAG, "🎉 Local network services started successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to start local network services", e)
            return false
        }
    }

    /**
     * Stop local network services
     */
    fun stopServices() {
        if (!isActive) {
            Log.w(TAG, "Local network services not active")
            return
        }

        Log.i(TAG, "Stopping local network services")
        isActive = false

        // Stop all components
        stopServer()
        stopDiscoveryBroadcast()
        stopDiscoveryListener()

        // Close all connections
        connectedSockets.values.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) { }
        }
        connectedSockets.clear()

        powerManager.stop()
    }

    /**
     * Start server socket for incoming connections
     */
    private fun startServer() {
        connectionScope.launch(Dispatchers.IO) {
            try {
                val server = ServerSocket(SERVER_PORT).apply {
                    reuseAddress = true
                    soTimeout = 5000 // 5 second timeout for accept()
                }

                Log.d(TAG, "Local network server started on port $SERVER_PORT")

                while (isActive) {
                    try {
                        val clientSocket = server.accept()
                        Log.d(TAG, "Accepted connection from ${clientSocket.inetAddress}")
                        handleClientConnection(clientSocket)
                    } catch (e: SocketTimeoutException) {
                        // Timeout is expected, continue listening
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                        break
                    }
                }

                try {
                    server.close()
                } catch (_: Exception) { }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting local network server", e)
            }
        }
    }

    /**
     * Stop server socket
     */
    private fun stopServer() {
        // Server socket will be closed when the coroutine ends
    }

    /**
     * Start broadcasting discovery messages
     */
    private fun startDiscoveryBroadcast() {
        connectionScope.launch(Dispatchers.IO) {
            try {
                broadcastSocket = DatagramSocket().apply {
                    broadcast = true
                    soTimeout = 1000 // 1 second timeout
                }

                val broadcastAddress = getBroadcastAddress()
                if (broadcastAddress != null) {
                    val message = "$DISCOVERY_MESSAGE:$myPeerID".toByteArray()
                    val packet = DatagramPacket(message, message.size, broadcastAddress, DISCOVERY_PORT)

                    Log.d(TAG, "📡 Starting discovery broadcast to ${broadcastAddress.hostAddress}:$DISCOVERY_PORT with peer ID: $myPeerID")

                    var broadcastCount = 0
                    while (isActive) {
                        try {
                            broadcastSocket?.send(packet)
                            broadcastCount++
                            Log.v(TAG, "📡 Sent discovery broadcast #$broadcastCount to ${broadcastAddress.hostAddress}")
                            delay(2000) // Broadcast every 2 seconds
                        } catch (e: Exception) {
                            if (isActive) {
                                Log.e(TAG, "❌ Error sending discovery broadcast", e)
                            }
                            break
                        }
                    }
                } else {
                    Log.e(TAG, "❌ Could not determine broadcast address")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting discovery broadcast", e)
            } finally {
                try {
                    broadcastSocket?.close()
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Stop discovery broadcasting
     */
    private fun stopDiscoveryBroadcast() {
        try {
            broadcastSocket?.close()
        } catch (_: Exception) { }
        broadcastSocket = null
    }

    /**
     * Start listening for discovery messages
     */
    private fun startDiscoveryListener() {
        connectionScope.launch(Dispatchers.IO) {
            try {
                discoverySocket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    soTimeout = 5000 // 5 second timeout for receive
                }

                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.d(TAG, "👂 Listening for discovery messages on port $DISCOVERY_PORT")

                var receivedCount = 0
                while (isActive) {
                    try {
                        discoverySocket?.receive(packet)
                        val message = String(packet.data, 0, packet.length)
                        receivedCount++

                        Log.v(TAG, "📨 Received UDP packet #$receivedCount from ${packet.address.hostAddress}: '$message'")

                        if (message.startsWith(DISCOVERY_MESSAGE)) {
                            val parts = message.split(":")
                            if (parts.size >= 2) {
                                val peerID = parts[1]
                                val peerAddress = packet.address

                                Log.i(TAG, "🔍 Discovered peer $peerID at ${peerAddress.hostAddress}")

                                // Get our own IP addresses to avoid connecting to self
                                val myIpAddresses = getMyIpAddresses()
                                val isSelfConnection = myIpAddresses.contains(peerAddress.hostAddress)

                                // Try to connect to this peer if not already connected and not self
                                if (!connectedSockets.containsKey(peerAddress.hostAddress) && peerID != myPeerID && !isSelfConnection) {
                                    Log.d(TAG, "🔗 Attempting to connect to peer $peerID at ${peerAddress.hostAddress}")
                                    connectToPeer(peerAddress.hostAddress, peerID)
                                } else {
                                    val reason = when {
                                        isSelfConnection -> "self (same IP address)"
                                        peerID == myPeerID -> "self (same peer ID)"
                                        connectedSockets.containsKey(peerAddress.hostAddress) -> "already connected"
                                        else -> "unknown"
                                    }
                                    Log.d(TAG, "⏭️ Skipping connection to $peerID at ${peerAddress.hostAddress} ($reason)")
                                }
                            } else {
                                Log.w(TAG, "⚠️ Invalid discovery message format: '$message'")
                            }
                        } else {
                            Log.v(TAG, "⏭️ Ignoring non-discovery message: '$message'")
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error receiving discovery message", e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting discovery listener", e)
            } finally {
                try {
                    discoverySocket?.close()
                } catch (_: Exception) { }
            }
        }
    }

    /**
     * Stop discovery listening
     */
    private fun stopDiscoveryListener() {
        try {
            discoverySocket?.close()
        } catch (_: Exception) { }
        discoverySocket = null
    }

    /**
     * Connect to a specific peer
     */
    private fun connectToPeer(ipAddress: String, peerID: String) {
        if (!isActive || connectedSockets.containsKey(ipAddress)) {
            Log.v(TAG, "⏭️ Skipping connection to $ipAddress:$SERVER_PORT (inactive or already connected)")
            return
        }

        // Additional check for self-connection by IP
        val myAddresses = getMyIpAddresses()
        if (myAddresses.contains(ipAddress)) {
            Log.d(TAG, "⏭️ Skipping connection to $ipAddress:$SERVER_PORT (self IP address)")
            return
        }

        Log.i(TAG, "🔗 Connecting to peer $peerID at $ipAddress:$SERVER_PORT")

        connectionScope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, SERVER_PORT), 5000) // Increased timeout

                Log.i(TAG, "✅ Successfully connected to peer $peerID at $ipAddress")
                connectedSockets[ipAddress] = socket

                // Notify delegate
                delegate?.onDeviceConnected(ipAddress, peerID)

                // Handle the connection
                handleServerConnection(socket, peerID)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to connect to peer $peerID at $ipAddress", e)
            }
        }
    }

    /**
     * Validate IP address format
     */
    private fun isValidIpAddress(ipAddress: String): Boolean {
        val ipPattern = Regex("^\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$")
        if (!ipPattern.matches(ipAddress)) return false

        return ipAddress.split(".").all { part ->
            part.toIntOrNull()?.let { it in 0..255 } ?: false
        }
    }

    /**
     * Check if IP address is one of our own addresses
     */
    private fun isSelfAddress(ipAddress: String): Boolean {
        return getMyIpAddresses().contains(ipAddress)
    }

    /**
     * Manually connect to a specific IP address (for testing/debugging)
     */
    fun connectToIpAddress(ipAddress: String) {
        if (!isActive) {
            Log.w(TAG, "Local network not active")
            return
        }

        if (!isValidIpAddress(ipAddress)) {
            Log.w(TAG, "Invalid IP address format: $ipAddress")
            return
        }

        if (isSelfAddress(ipAddress)) {
            Log.w(TAG, "Cannot connect to own IP address: $ipAddress")
            return
        }

        if (connectedSockets.containsKey(ipAddress)) {
            Log.w(TAG, "Already connected to: $ipAddress")
            return
        }

        Log.i(TAG, "🔗 Manual connection attempt to $ipAddress:$SERVER_PORT")

        connectionScope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(ipAddress, SERVER_PORT), 5000)

                Log.i(TAG, "✅ Manual connection successful to $ipAddress")
                connectedSockets[ipAddress] = socket

                // For manual connections, we don't know the peer ID yet
                // It will be sent in the first message or we can use a placeholder
                val placeholderPeerId = "manual_$ipAddress".replace(".", "_")

                // Notify delegate
                delegate?.onDeviceConnected(ipAddress, placeholderPeerId)

                // Handle the connection
                handleServerConnection(socket, placeholderPeerId)

            } catch (e: Exception) {
                Log.e(TAG, "❌ Manual connection failed to $ipAddress", e)
            }
        }
    }

    /**
     * Handle incoming client connection
     */
    private fun handleClientConnection(socket: Socket) {
        val clientAddress = socket.inetAddress.hostAddress
        connectedSockets[clientAddress] = socket

        // For incoming connections, we don't know the peer ID yet
        // It will be sent in the first message
        connectionScope.launch(Dispatchers.IO) {
            handleIncomingConnection(socket)
        }
    }

    /**
     * Handle connection to server (outgoing)
     */
    private fun handleServerConnection(socket: Socket, peerID: String) {
        connectionScope.launch(Dispatchers.IO) {
            handleOutgoingConnection(socket, peerID)
        }
    }

    /**
     * Handle incoming connection (as server)
     */
    private fun handleIncomingConnection(socket: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            var peerID: String? = null

            while (isActive && !socket.isClosed) {
                try {
                    // Read packet length
                    val packetLength = input.readInt()
                    if (packetLength <= 0 || packetLength > 1024 * 1024) {
                        Log.w(TAG, "Invalid packet length: $packetLength")
                        break
                    }

                    // Read packet data
                    val packetData = ByteArray(packetLength)
                    input.readFully(packetData)

                    // Parse packet
                    val packet = BitchatPacket.fromBinaryData(packetData)
                    if (packet != null) {
                        // Extract peer ID from packet if not known
                        if (peerID == null) {
                            peerID = packet.senderID?.let { byteArrayToHexString(it).takeLast(16) }
                        }

                        Log.d(TAG, "Received packet from ${socket.inetAddress}: type=${packet.type}")
                        delegate?.onPacketReceived(packet, peerID ?: "unknown", socket.inetAddress.hostAddress)
                    }
                } catch (e: EOFException) {
                    Log.d(TAG, "Client ${socket.inetAddress} disconnected")
                    break
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading from client ${socket.inetAddress}", e)
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming connection", e)
        } finally {
            val address = socket.inetAddress.hostAddress
            connectedSockets.remove(address)
            delegate?.onDeviceDisconnected(address)
            try {
                socket.close()
            } catch (_: Exception) { }
        }
    }

    /**
     * Handle outgoing connection (as client)
     */
    private fun handleOutgoingConnection(socket: Socket, peerID: String) {
        // Similar to incoming, but we already know the peer ID
        handleIncomingConnection(socket)
    }

    /**
     * Broadcast packet to all connected peers
     */
    fun broadcastPacket(routed: RoutedPacket) {
        if (!isActive) return

        val connectedAddresses = connectedSockets.keys.toList()
        packetBroadcaster.broadcastPacket(routed, connectedAddresses)
    }

    /**
     * Send packet to specific peer
     */
    fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        // Find socket for this peer ID (this is simplified - we'd need a mapping)
        val socket = connectedSockets.values.firstOrNull()
        return if (socket != null) {
            packetBroadcaster.sendPacketToSocket(packet, socket)
            true
        } else {
            false
        }
    }

    /**
     * Cancel file transfer
     */
    fun cancelTransfer(transferId: String): Boolean {
        return packetBroadcaster.cancelTransfer(transferId)
    }

    /**
     * Get all IP addresses of this device
     */
    private fun getMyIpAddresses(): Set<String> {
        val addresses = mutableSetOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback) continue

                val inetAddresses = networkInterface.inetAddresses
                while (inetAddresses.hasMoreElements()) {
                    val addr = inetAddresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        addresses.add(addr.hostAddress)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error getting my IP addresses", e)
        }
        return addresses
    }

    /**
     * Get broadcast address from network interfaces
     */
    private fun getBroadcastFromNetworkInterfaces(): InetAddress? {
        return try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { networkInterface ->
                    networkInterface.inetAddresses.asSequence()
                        .filterIsInstance<java.net.Inet4Address>()
                        .filter { !it.isLoopbackAddress }
                        .mapNotNull { address ->
                            networkInterface.interfaceAddresses
                                .firstOrNull { it.address == address }
                                ?.broadcast
                                ?.also { broadcast ->
                                    Log.d(TAG, "📡 Found broadcast address from interface ${networkInterface.name}: ${broadcast.hostAddress} (IP: ${address.hostAddress})")
                                }
                        }
                }
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Error getting broadcast from network interfaces", e)
            null
        }
    }

    /**
     * Calculate broadcast address from DHCP info
     */
    private fun getBroadcastFromDhcp(): InetAddress? {
        return try {
            wifiManager?.dhcpInfo?.let { dhcp ->
                if (dhcp.ipAddress != 0 && dhcp.netmask != 0) {
                    val broadcast = (dhcp.ipAddress and dhcp.netmask) or dhcp.netmask.inv()
                    val bytes = ByteArray(4)
                    for (i in 0..3) {
                        bytes[i] = (broadcast shr (i * 8) and 0xFF).toByte()
                    }
                    val broadcastAddr = InetAddress.getByAddress(bytes)
                    Log.d(TAG, "📡 Calculated broadcast address: ${broadcastAddr.hostAddress} (from DHCP: ${formatIpAddress(dhcp.ipAddress)}/${formatIpAddress(dhcp.netmask)})")
                    broadcastAddr
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error calculating broadcast from DHCP", e)
            null
        }
    }

    /**
     * Get broadcast address for the current network
     */
    private fun getBroadcastAddress(): InetAddress? {
        return getBroadcastFromNetworkInterfaces()
            ?: getBroadcastFromDhcp()
            ?: run {
                Log.w(TAG, "⚠️ Using global broadcast address 255.255.255.255")
                try {
                    InetAddress.getByName("255.255.255.255")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error getting global broadcast address", e)
                    null
                }
            }
    }

    /**
     * Format IP address from integer to string
     */
    private fun formatIpAddress(ip: Int): String {
        return "${(ip and 0xFF)}.${(ip shr 8 and 0xFF)}.${(ip shr 16 and 0xFF)}.${(ip shr 24 and 0xFF)}"
    }

    /**
     * Convert byte array to hex string
     */
    private fun byteArrayToHexString(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    // PowerManagerDelegate implementation
    override fun onPowerModeChanged(newMode: PowerManager.PowerMode) {
        Log.i(TAG, "Power mode changed to: $newMode")
        // Adjust behavior based on power mode if needed
    }

    override fun onScanStateChanged(shouldScan: Boolean) {
        // Local network doesn't have scanning in the same way
    }

    /**
     * Get basic connection info
     */
    private fun getBasicConnectionInfo(): String = buildString {
        appendLine("🌐 Local Network Diagnostics:")
        appendLine("Active: $isActive")
        appendLine("My Peer ID: $myPeerID")
        appendLine("Connected sockets: ${connectedSockets.size}")
        connectedSockets.keys.forEach { addr ->
            appendLine("  - $addr")
        }
    }

    /**
     * Get IP address information
     */
    private fun getIpAddressInfo(): String = buildString {
        val myAddresses = getMyIpAddresses()
        appendLine("My IP addresses: ${myAddresses.joinToString(", ")}")
        if (myAddresses.isEmpty()) {
            appendLine("⚠️ WARNING: Could not determine device IP addresses!")
            appendLine("   This may prevent proper peer discovery.")
        }
        appendLine("Broadcast address: ${getBroadcastAddress()?.hostAddress ?: "null"}")
    }

    /**
     * Get detailed network interface information
     */
    private fun getNetworkInterfaceInfo(): String = buildString {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            appendLine("Network interfaces:")
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                appendLine("  - ${ni.name}: ${ni.displayName} (up: ${ni.isUp}, loopback: ${ni.isLoopback})")
                val addresses = ni.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    appendLine("    ${addr.hostAddress} (loopback: ${addr.isLoopbackAddress})")
                }
            }
        } catch (e: Exception) {
            appendLine("Error getting network info: ${e.message}")
        }
    }

    /**
     * Get diagnostic information for troubleshooting
     */
    fun getNetworkDiagnostics(): String = buildString {
        append(getBasicConnectionInfo())
        append(getIpAddressInfo())
        append(getNetworkInterfaceInfo())
    }
}

/**
 * Delegate interface for local network connection manager callbacks
 */
interface LocalNetworkConnectionManagerDelegate {
    fun onPacketReceived(packet: BitchatPacket, peerID: String, address: String)
    fun onDeviceConnected(address: String, peerID: String)
    fun onDeviceDisconnected(address: String)
}
