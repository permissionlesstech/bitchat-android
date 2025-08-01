package com.bitchat.android.tcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP Mesh Service для Android клиента
 * 
 * Обеспечивает TCP соединения для mesh сети, совместимые с существующим
 * Bluetooth mesh протоколом и шифрованием.
 */
class TcpMeshService : Service() {
    private val binder = TcpMeshBinder()
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    
    // Сетевая конфигурация
    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null
    private val peers = ConcurrentHashMap<String, TcpPeer>()
    private val listeners = mutableSetOf<TcpMeshListener>()
    
    // Настройки
    private var localPort: Int = DEFAULT_PORT
    private var serverHost: String? = DEFAULT_SERVER_HOST
    private var serverPort: Int = DEFAULT_SERVER_PORT
    private var enableDiscovery = true
    private var connectionMode = ConnectionMode.P2P
    
    enum class ConnectionMode {
        P2P,        // Прямые P2P соединения
        SERVER,     // Подключение к центральному серверу
        HYBRID      // Комбинированный режим
    }
    
    companion object {
        private const val TAG = "TcpMeshService"
        private const val DEFAULT_PORT = 8080
        private const val DEFAULT_SERVER_HOST = "31.207.75.164"
        private const val DEFAULT_SERVER_PORT = 9090
        private const val DISCOVERY_PORT = 8081
        private const val DISCOVERY_INTERVAL_MS = 5000L
        private const val PEER_TIMEOUT_MS = 30000L
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "tcp_mesh_channel"
    }

    inner class TcpMeshBinder : Binder() {
        fun getService(): TcpMeshService = this@TcpMeshService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.d(TAG, "TcpMeshService создан")
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, createNotification("TCP Mesh запущен"))
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopMesh()
        serviceScope.cancel()
        Log.d(TAG, "TcpMeshService уничтожен")
    }

    /**
     * Запускает TCP mesh в заданном режиме
     */
    fun startMesh(
        mode: ConnectionMode = ConnectionMode.P2P,
        localPort: Int = DEFAULT_PORT,
        serverHost: String? = null,
        serverPort: Int = DEFAULT_SERVER_PORT,
        enableDiscovery: Boolean = true
    ) {
        if (isRunning.get()) {
            Log.w(TAG, "TCP mesh уже запущен")
            return
        }

        this.connectionMode = mode
        this.localPort = localPort
        this.serverHost = serverHost
        this.serverPort = serverPort
        this.enableDiscovery = enableDiscovery

        serviceScope.launch {
            try {
                isRunning.set(true)
                
                when (mode) {
                    ConnectionMode.P2P -> startP2PMode()
                    ConnectionMode.SERVER -> startServerMode()
                    ConnectionMode.HYBRID -> startHybridMode()
                }
                
                if (enableDiscovery && mode != ConnectionMode.SERVER) {
                    startPeerDiscovery()
                }
                
                startPeerMonitoring()
                updateNotification("TCP Mesh активен (${mode.name})")
                
                notifyListeners { onMeshStarted(mode) }
                Log.i(TAG, "TCP mesh запущен в режиме $mode")
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка запуска TCP mesh", e)
                isRunning.set(false)
                notifyListeners { onMeshError(e) }
            }
        }
    }

    fun stopMesh() {
        if (!isRunning.get()) return
        
        isRunning.set(false)
        
        // Закрываем все соединения
        peers.values.forEach { it.disconnect() }
        peers.clear()
        
        // Закрываем серверный сокет
        serverSocket?.close()
        serverSocket = null
        
        // Закрываем discovery сокет
        discoverySocket?.close()
        discoverySocket = null
        
        updateNotification("TCP Mesh остановлен")
        notifyListeners { onMeshStopped() }
        Log.i(TAG, "TCP mesh остановлен")
    }

    private suspend fun startP2PMode() {
        // Запускаем TCP сервер для входящих соединений
        serverSocket = ServerSocket(localPort)
        Log.i(TAG, "P2P TCP сервер запущен на порту $localPort")
        
        serviceScope.launch {
            acceptIncomingConnections()
        }
    }

    private suspend fun startServerMode() {
        if (serverHost == null) {
            throw IllegalArgumentException("Server host не указан для server режима")
        }
        
        connectToServer()
    }

    private suspend fun startHybridMode() {
        // Запускаем и P2P и server режимы
        startP2PMode()
        if (serverHost != null) {
            connectToServer()
        }
    }

    private suspend fun acceptIncomingConnections() {
        while (isRunning.get() && serverSocket?.isClosed == false) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                handleIncomingConnection(clientSocket)
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Ошибка принятия соединения", e)
                }
            }
        }
    }

    private suspend fun connectToServer() {
        try {
            val socket = Socket(serverHost, serverPort)
            val peer = TcpPeer("server", socket, this)
            peers["server"] = peer
            peer.start()
            
            Log.i(TAG, "Подключен к серверу $serverHost:$serverPort")
            notifyListeners { onPeerConnected(peer) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подключения к серверу", e)
            throw e
        }
    }

    private suspend fun handleIncomingConnection(socket: Socket) {
        val peerAddress = socket.remoteSocketAddress.toString()
        Log.d(TAG, "Входящее соединение от $peerAddress")
        
        val peer = TcpPeer(peerAddress, socket, this)
        peers[peerAddress] = peer
        peer.start()
        
        notifyListeners { onPeerConnected(peer) }
    }

    private suspend fun startPeerDiscovery() {
        try {
            discoverySocket = DatagramSocket(DISCOVERY_PORT)
            discoverySocket?.broadcast = true
        } catch (e: Exception) {
            Log.w(TAG, "Не удалось создать discovery сокет на порту $DISCOVERY_PORT", e)
            return
        }
        
        // Отправляем discovery broadcasts
        serviceScope.launch {
            while (isRunning.get()) {
                try {
                    sendDiscoveryBroadcast()
                    delay(DISCOVERY_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Ошибка discovery broadcast", e)
                }
            }
        }
        
        // Слушаем discovery сообщения
        serviceScope.launch {
            val buffer = ByteArray(1024)
            while (isRunning.get() && discoverySocket?.isClosed == false) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    discoverySocket?.receive(packet)
                    handleDiscoveryMessage(packet)
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Ошибка discovery listener", e)
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
            Log.e(TAG, "Ошибка отправки discovery broadcast", e)
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
            Log.e(TAG, "Ошибка обработки discovery сообщения", e)
        }
    }

    private suspend fun connectToPeer(host: String, port: Int) {
        try {
            val socket = Socket(host, port)
            val peerKey = "$host:$port"
            val peer = TcpPeer(peerKey, socket, this)
            
            peers[peerKey] = peer
            peer.start()
            
            Log.i(TAG, "Подключен к peer $peerKey")
            notifyListeners { onPeerConnected(peer) }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка подключения к peer $host:$port", e)
        }
    }

    private suspend fun startPeerMonitoring() {
        serviceScope.launch {
            while (isRunning.get()) {
                val currentTime = System.currentTimeMillis()
                val disconnectedPeers = mutableListOf<String>()
                
                peers.forEach { (key, peer) ->
                    if (currentTime - peer.lastActivity.get() > PEER_TIMEOUT_MS || !peer.isConnected()) {
                        disconnectedPeers.add(key)
                    }
                }
                
                disconnectedPeers.forEach { key ->
                    val peer = peers.remove(key)
                    peer?.disconnect()
                    peer?.let { notifyListeners { onPeerDisconnected(it) } }
                }
                
                delay(5000) // Проверяем каждые 5 секунд
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
            Log.e(TAG, "Ошибка получения локального IP", e)
        }
        return "127.0.0.1"
    }

    // Обработка сообщений от peers
    internal fun onMessageReceived(peer: TcpPeer, data: ByteArray) {
        notifyListeners { onMessageReceived(peer, data) }
    }

    internal fun onPeerError(peer: TcpPeer, error: Exception) {
        Log.e(TAG, "Ошибка peer ${peer.id}", error)
        peers.remove(peer.id)
        notifyListeners { onPeerDisconnected(peer) }
    }

    /**
     * Отправляет сообщение через TCP mesh
     */
    fun sendMessage(data: ByteArray, targetPeer: String? = null) {
        if (targetPeer != null) {
            peers[targetPeer]?.sendMessage(data)
        } else {
            // Broadcast всем peers
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
                Log.e(TAG, "Ошибка уведомления listener", e)
            }
        }
    }

    // Публичные методы для получения статуса
    fun getPeers(): List<TcpPeer> = peers.values.toList()
    fun isRunning(): Boolean = isRunning.get()
    fun getLocalPort(): Int = localPort
    fun getConnectionMode(): ConnectionMode = connectionMode
    fun getConnectedPeersCount(): Int = peers.size

    // Notification helpers
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "TCP Mesh Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "TCP Mesh networking service"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bitchat TCP Mesh")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
}

/**
 * Интерфейс для получения событий TCP mesh
 */
interface TcpMeshListener {
    fun onMeshStarted(mode: TcpMeshService.ConnectionMode)
    fun onMeshStopped()
    fun onMeshError(error: Exception)
    fun onPeerConnected(peer: TcpPeer)
    fun onPeerDisconnected(peer: TcpPeer)
    fun onMessageReceived(peer: TcpPeer, data: ByteArray)
}
