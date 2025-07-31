package com.bitchat.android.tcp

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class TcpMeshService : Service() {
    private val binder = TcpMeshBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    
    // Network configuration
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private val peers = ConcurrentHashMap<String, TcpPeer>()
    private val listeners = mutableSetOf<TcpMeshListener>()
    
    // Configuration
    private var localPort: Int = DEFAULT_PORT
    private var serverHost: String? = null
    private var serverPort: Int = DEFAULT_SERVER_PORT
    private var enableServerMode = false
    
    companion object {
        private const val TAG = "TcpMeshService"
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_SERVER_PORT = 9090
        private const val DISCOVERY_PORT = 8081
        private const val DISCOVERY_INTERVAL_MS = 5000L
        private const val PEER_TIMEOUT_MS = 30000L
    }

    inner class TcpMeshBinder : Binder() {
        fun getService(): TcpMeshService = this@TcpMeshService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "TcpMeshService created")
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMesh()
        serviceScope.cancel()
        Log.d(TAG, "TcpMeshService destroyed")
    }

    fun startMesh(port: Int = DEFAULT_PORT, serverHost: String? = null, serverPort: Int = DEFAULT_SERVER_PORT) {
        if (isRunning.get()) {
            Log.w(TAG, "TCP mesh already running")
            return
        }

        this.localPort = port
        this.serverHost = serverHost
        this.serverPort = serverPort
        this.enableServerMode = serverHost == null

        serviceScope.launch {
            try {
                isRunning.set(true)
                
                if (enableServerMode) {
                    startServer()
                } else {
                    connectToServer()
                }
                
                startPeerDiscovery()
                startPeerMonitoring()
                
                notifyListeners { onMeshStarted() }
                Log.i(TAG, "TCP mesh started successfully")
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start TCP mesh", e)
                isRunning.set(false)
                notifyListeners { onMeshError(e) }
            }
        }
    }

    fun stopMesh() {
        if (!isRunning.get()) return
        
        isRunning.set(false)
        
        // Close all connections
        peers.values.forEach { it.disconnect() }
        peers.clear()
        
        // Close server socket
        serverSocket?.close()
        serverSocket = null
        
        // Close discovery socket
        discoverySocket?.close()
        discoverySocket = null
        
        notifyListeners { onMeshStopped() }
        Log.i(TAG, "TCP mesh stopped")
    }

    private suspend fun startServer() {
        serverSocket = ServerSocket(localPort)
        Log.i(TAG, "TCP server started on port $localPort")
        
        // Accept incoming connections
        serviceScope.launch {
            while (isRunning.get() && serverSocket?.isClosed == false) {
                try {
                    val clientSocket = serverSocket?.accept()
                    clientSocket?.let { socket ->
                        handleIncomingConnection(socket)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
        }
    }

    private suspend fun connectToServer() {
        if (serverHost == null) return
        
        try {
            val socket = Socket(serverHost, serverPort)
            val peer = TcpPeer("server", socket, this)
            peers["server"] = peer
            peer.start()
            
            Log.i(TAG, "Connected to server at $serverHost:$serverPort")
            notifyListeners { onPeerConnected(peer) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to server", e)
            throw e
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        val peerAddress = socket.remoteSocketAddress.toString()
        Log.d(TAG, "Incoming connection from $peerAddress")
        
        val peer = TcpPeer(peerAddress, socket, this)
        peers[peerAddress] = peer
        peer.start()
        
        notifyListeners { onPeerConnected(peer) }
    }

    private suspend fun startPeerDiscovery() {
        if (enableServerMode) return // Server mode doesn't need discovery
        
        discoverySocket = DatagramSocket(DISCOVERY_PORT)
        discoverySocket?.broadcast = true
        
        // Send discovery broadcasts
        serviceScope.launch {
            while (isRunning.get()) {
                try {
                    sendDiscoveryBroadcast()
                    delay(DISCOVERY_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error in discovery broadcast", e)
                }
            }
        }
        
        // Listen for discovery messages
        serviceScope.launch {
            val buffer = ByteArray(1024)
            while (isRunning.get() && discoverySocket?.isClosed == false) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    discoverySocket?.receive(packet)
                    handleDiscoveryMessage(packet)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error in discovery listener", e)
                    }
                }
            }
        }
    }

    private fun sendDiscoveryBroadcast() {
        try {
            val message = "BITCHAT_TCP_DISCOVERY:$localPort".toByteArray()
            val packet = DatagramPacket(
                message, 
                message.size,
                InetAddress.getByName("255.255.255.255"),
                DISCOVERY_PORT
            )
            discoverySocket?.send(packet)
        } catch (e: Exception) {
            Log.e(TAG, "Error sending discovery broadcast", e)
        }
    }

    private suspend fun handleDiscoveryMessage(packet: DatagramPacket) {
        try {
            val message = String(packet.data, 0, packet.length)
            if (message.startsWith("BITCHAT_TCP_DISCOVERY:")) {
                val port = message.substring("BITCHAT_TCP_DISCOVERY:".length).toInt()
                val peerHost = packet.address.hostAddress
                val peerKey = "$peerHost:$port"
                
                if (!peers.containsKey(peerKey) && peerHost != getLocalIpAddress()) {
                    connectToPeer(peerHost, port)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling discovery message", e)
        }
    }

    private suspend fun connectToPeer(host: String, port: Int) {
        try {
            val socket = Socket(host, port)
            val peerKey = "$host:$port"
            val peer = TcpPeer(peerKey, socket, this)
            
            peers[peerKey] = peer
            peer.start()
            
            Log.i(TAG, "Connected to peer $peerKey")
            notifyListeners { onPeerConnected(peer) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to peer $host:$port", e)
        }
    }

    private suspend fun startPeerMonitoring() {
        serviceScope.launch {
            while (isRunning.get()) {
                val currentTime = System.currentTimeMillis()
                val disconnectedPeers = mutableListOf<String>()
                
                peers.forEach { (key, peer) ->
                    if (currentTime - peer.lastActivity > PEER_TIMEOUT_MS || !peer.isConnected()) {
                        disconnectedPeers.add(key)
                    }
                }
                
                disconnectedPeers.forEach { key ->
                    val peer = peers.remove(key)
                    peer?.disconnect()
                    peer?.let { notifyListeners { onPeerDisconnected(it) } }
                }
                
                delay(5000) // Check every 5 seconds
            }
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isLoopback && networkInterface.isUp) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            return address.hostAddress ?: ""
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local IP address", e)
        }
        return "127.0.0.1"
    }

    // Message handling
    internal fun onMessageReceived(peer: TcpPeer, data: ByteArray) {
        notifyListeners { onMessageReceived(peer, data) }
    }

    internal fun onPeerError(peer: TcpPeer, error: Exception) {
        Log.e(TAG, "Peer error from ${peer.id}", error)
        peers.remove(peer.id)
        notifyListeners { onPeerDisconnected(peer) }
    }

    fun sendMessage(data: ByteArray, targetPeer: String? = null) {
        if (targetPeer != null) {
            peers[targetPeer]?.sendMessage(data)
        } else {
            // Broadcast to all peers
            peers.values.forEach { peer ->
                peer.sendMessage(data)
            }
        }
    }

    fun addListener(listener: TcpMeshListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TcpMeshListener) {
        listeners.remove(listener)
    }

    private inline fun notifyListeners(action: TcpMeshListener.() -> Unit) {
        listeners.forEach { listener ->
            try {
                listener.action()
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying listener", e)
            }
        }
    }

    fun getPeers(): List<TcpPeer> = peers.values.toList()
    fun isRunning(): Boolean = isRunning.get()
    fun getLocalPort(): Int = localPort
}

interface TcpMeshListener {
    fun onMeshStarted()
    fun onMeshStopped()
    fun onMeshError(error: Exception)
    fun onPeerConnected(peer: TcpPeer)
    fun onPeerDisconnected(peer: TcpPeer)
    fun onMessageReceived(peer: TcpPeer, data: ByteArray)
}