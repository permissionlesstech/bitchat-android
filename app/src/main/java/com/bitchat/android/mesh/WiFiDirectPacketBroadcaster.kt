package com.bitchat.android.mesh

import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pInfo
import android.util.Log
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import kotlinx.coroutines.*
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Handles broadcasting packets over Wi-Fi Direct connections
 */
class WiFiDirectPacketBroadcaster(
    private val scope: CoroutineScope,
) {

    companion object {
        private const val TAG = "WiFiDirectPacketBroadcaster"
        private const val SERVER_PORT = 8988
    }

    // Connection state
    private var serverSocket: ServerSocket? = null
    private var isGroupOwner = false
    private var groupOwnerAddress: String? = null

    // Active connections
    private val clientSockets = ConcurrentHashMap<String, Socket>() // deviceAddress -> socket
    private val activeTransfers = ConcurrentHashMap<String, Job>() // transferId -> job

    // Nickname resolver for logging
    private var nicknameResolver: ((String) -> String?)? = null

    // Delegate for forwarding received packets
    var packetDelegate: WiFiDirectPacketDelegate? = null

    /**
     * Set nickname resolver for better logging
     */
    fun setNicknameResolver(resolver: (String) -> String?) {
        nicknameResolver = resolver
    }

    /**
     * Called when connection is established
     */
    fun onConnectionEstablished(info: WifiP2pInfo) {
        isGroupOwner = info.isGroupOwner
        groupOwnerAddress = info.groupOwnerAddress?.hostAddress

        Log.d(TAG, "Connection established. Is group owner: $isGroupOwner, Owner address: $groupOwnerAddress")

        if (isGroupOwner) {
            // Start server socket
            startServer()
        } else {
            // Connect to group owner as client
            groupOwnerAddress?.let { connectToServer(it) }
        }
    }

    /**
     * Start server socket for group owner
     */
    private fun startServer() {
        scope.launch(Dispatchers.IO) {
            try {
                serverSocket = ServerSocket(SERVER_PORT).apply {
                    reuseAddress = true
                }

                Log.d(TAG, "Server socket started on port $SERVER_PORT")

                while (isActive) {
                    try {
                        val clientSocket = serverSocket?.accept()
                        clientSocket?.let { socket ->
                            Log.d(TAG, "Accepted connection from ${socket.inetAddress}")
                            handleClientConnection(socket)
                        }
                    } catch (e: Exception) {
                        if (isActive) {
                            Log.e(TAG, "Error accepting client connection", e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error starting server socket", e)
            }
        }
    }

    /**
     * Connect to server as client
     */
    private fun connectToServer(serverAddress: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(serverAddress, SERVER_PORT), 5000)

                Log.d(TAG, "Connected to server at $serverAddress:$SERVER_PORT")
                handleServerConnection(socket)
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to server", e)
            }
        }
    }

    /**
     * Handle incoming client connection (server side)
     */
    private fun handleClientConnection(socket: Socket) {
        val deviceAddress = socket.inetAddress.hostAddress ?: "unknown"
        clientSockets[deviceAddress] = socket

        scope.launch(Dispatchers.IO) {
            try {
                val input = DataInputStream(BufferedInputStream(socket.getInputStream()))

                while (isActive && !socket.isClosed) {
                    try {
                        // Read packet length
                        val packetLength = input.readInt()
                        if (packetLength <= 0 || packetLength > 1024 * 1024) { // 1MB max
                            Log.w(TAG, "Invalid packet length: $packetLength")
                            break
                        }

                        // Read packet data
                        val packetData = ByteArray(packetLength)
                        input.readFully(packetData)

                        // Parse packet
                        val packet = BitchatPacket.fromBinaryData(packetData)
                        if (packet != null) {
                            Log.d(TAG, "Received packet from $deviceAddress: type=${packet.type}")

                            // Forward packet to connection manager via delegate
                            packetDelegate?.onPacketReceived(packet, deviceAddress)
                        }
                    } catch (e: EOFException) {
                        Log.d(TAG, "Client $deviceAddress disconnected")
                        break
                    } catch (e: Exception) {
                        Log.e(TAG, "Error reading from client $deviceAddress", e)
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling client connection", e)
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) { }
                clientSockets.remove(deviceAddress)
            }
        }
    }

    /**
     * Handle connection to server (client side)
     */
    private fun handleServerConnection(socket: Socket) {
        val serverAddress = socket.inetAddress.hostAddress ?: "unknown"
        clientSockets[serverAddress] = socket

        // Client-side connection handling
        // Similar to server but for receiving responses
    }

    /**
     * Broadcast packet to all connected devices
     */
    fun broadcastPacket(routed: RoutedPacket, devices: List<WifiP2pDevice>) {
        if (devices.isEmpty()) return

        val packetData = routed.packet.toBinaryData()
        if (packetData == null) {
            Log.w(TAG, "Failed to serialize packet for broadcast")
            return
        }

        Log.d(TAG, "Broadcasting packet to ${devices.size} Wi-Fi Direct devices")

        for (device in devices) {
            sendPacketToDevice(routed.packet, device)
        }
    }

    /**
     * Send packet to specific device
     */
    fun sendPacketToDevice(packet: BitchatPacket, device: WifiP2pDevice) {
        val deviceAddress = device.deviceAddress
        val socket = clientSockets[deviceAddress]

        if (socket == null || socket.isClosed) {
            Log.w(TAG, "No active connection to device ${device.deviceName} ($deviceAddress)")
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))

                // Write packet length
                val packetData = packet.toBinaryData()
                if (packetData != null) {
                    output.writeInt(packetData.size)
                    output.write(packetData)
                    output.flush()

                    Log.d(TAG, "Sent packet to ${device.deviceName} ($deviceAddress), size: ${packetData.size} bytes")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error sending packet to device ${device.deviceName}", e)
            }
        }
    }

    /**
     * Cancel specific transfer
     */
    fun cancelTransfer(transferId: String): Boolean {
        val job = activeTransfers.remove(transferId)
        job?.cancel()
        return job != null
    }

    /**
     * Cancel all transfers
     */
    fun cancelAllTransfers() {
        activeTransfers.values.forEach { it.cancel() }
        activeTransfers.clear()
    }

    /**
     * Close all connections
     */
    fun closeAllConnections() {
        cancelAllTransfers()

        clientSockets.values.forEach { socket ->
            try {
                socket.close()
            } catch (_: Exception) { }
        }
        clientSockets.clear()

        try {
            serverSocket?.close()
        } catch (_: Exception) { }
        serverSocket = null
    }

    /**
     * Check if broadcaster is active
     */
    private val isActive: Boolean
        get() = serverSocket != null || clientSockets.isNotEmpty()
}

/**
 * Delegate interface for forwarding received packets
 */
interface WiFiDirectPacketDelegate {
    fun onPacketReceived(packet: BitchatPacket, deviceAddress: String)
}
