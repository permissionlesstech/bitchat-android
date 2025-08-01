package com.bitchat.android.tcp

import android.util.Log
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

/**
 * TCP Protocol Adapter
 * 
 * Адаптер для интеграции TCP mesh с существующим binary протоколом
 * и инфраструктурой Bluetooth mesh. Обеспечивает совместимость форматов
 * сообщений и маршрутизации.
 */
class TcpProtocolAdapter(
    private val tcpMeshService: TcpMeshService
) : TcpMeshListener {
    
    private val listeners = mutableSetOf<TcpProtocolListener>()
    private val messageCache = ConcurrentHashMap<String, Long>() // Для дедупликации
    private val adapterScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    companion object {
        private const val TAG = "TcpProtocolAdapter"
        private const val MESSAGE_CACHE_TTL_MS = 30000L // 30 секунд
        private const val CACHE_CLEANUP_INTERVAL_MS = 60000L // 1 минута
        
        // Типы сообщений (совместимые с Bluetooth mesh)
        const val MESSAGE_TYPE_CHAT = 0x01.toByte()
        const val MESSAGE_TYPE_PRIVATE = 0x02.toByte()
        const val MESSAGE_TYPE_CHANNEL_JOIN = 0x03.toByte()
        const val MESSAGE_TYPE_CHANNEL_LEAVE = 0x04.toByte()
        const val MESSAGE_TYPE_PEER_DISCOVERY = 0x05.toByte()
        const val MESSAGE_TYPE_HEARTBEAT = 0x06.toByte()
        const val MESSAGE_TYPE_ENCRYPTED = 0x07.toByte()
    }

    init {
        tcpMeshService.addListener(this)
        startCacheCleanup()
    }

    /**
     * Отправляет сообщение через TCP mesh с форматированием под существующий протокол
     */
    fun sendMessage(
        messageType: Byte,
        payload: ByteArray,
        targetPeer: String? = null,
        ttl: Int = 7
    ) {
        val messageId = generateMessageId()
        val formattedMessage = formatMessage(messageType, payload, messageId, ttl)
        
        // Добавляем в кэш для предотвращения петель
        messageCache[messageId] = System.currentTimeMillis()
        
        tcpMeshService.sendMessage(formattedMessage, targetPeer)
        
        Log.d(TAG, "Отправлено сообщение типа ${messageType.toInt()} размером ${payload.size} байт")
    }

    /**
     * Отправляет текстовое сообщение
     */
    fun sendTextMessage(text: String, channel: String? = null, targetPeer: String? = null) {
        val messageType = if (targetPeer != null) MESSAGE_TYPE_PRIVATE else MESSAGE_TYPE_CHAT
        val payload = createTextPayload(text, channel)
        sendMessage(messageType, payload, targetPeer)
    }

    /**
     * Отправляет зашифрованное сообщение
     */
    fun sendEncryptedMessage(encryptedData: ByteArray, targetPeer: String? = null) {
        sendMessage(MESSAGE_TYPE_ENCRYPTED, encryptedData, targetPeer)
    }

    /**
     * Присоединяется к каналу
     */
    fun joinChannel(channelName: String) {
        val payload = channelName.toByteArray(Charsets.UTF_8)
        sendMessage(MESSAGE_TYPE_CHANNEL_JOIN, payload)
    }

    /**
     * Покидает канал
     */
    fun leaveChannel(channelName: String) {
        val payload = channelName.toByteArray(Charsets.UTF_8)
        sendMessage(MESSAGE_TYPE_CHANNEL_LEAVE, payload)
    }

    /**
     * Отправляет heartbeat сообщение
     */
    fun sendHeartbeat() {
        val payload = createHeartbeatPayload()
        sendMessage(MESSAGE_TYPE_HEARTBEAT, payload)
    }

    // TcpMeshListener implementation
    override fun onMeshStarted(mode: TcpMeshService.ConnectionMode) {
        Log.i(TAG, "TCP mesh запущен в режиме $mode")
        notifyListeners { onTcpMeshConnected(mode) }
    }

    override fun onMeshStopped() {
        Log.i(TAG, "TCP mesh остановлен")
        notifyListeners { onTcpMeshDisconnected() }
    }

    override fun onMeshError(error: Exception) {
        Log.e(TAG, "Ошибка TCP mesh", error)
        notifyListeners { onTcpMeshError(error) }
    }

    override fun onPeerConnected(peer: TcpPeer) {
        Log.i(TAG, "TCP peer подключен: ${peer.id}")
        notifyListeners { onTcpPeerConnected(peer) }
        
        // Отправляем heartbeat новому peer
        sendHeartbeat()
    }

    override fun onPeerDisconnected(peer: TcpPeer) {
        Log.i(TAG, "TCP peer отключен: ${peer.id}")
        notifyListeners { onTcpPeerDisconnected(peer) }
    }

    override fun onMessageReceived(peer: TcpPeer, data: ByteArray) {
        try {
            val parsedMessage = parseMessage(data)
            if (parsedMessage == null) {
                Log.w(TAG, "Не удалось парсить сообщение от ${peer.id}")
                return
            }
            
            // Проверяем дедупликацию
            if (messageCache.containsKey(parsedMessage.messageId)) {
                Log.d(TAG, "Дублирующееся сообщение ${parsedMessage.messageId}, игнорируем")
                return
            }
            
            // Добавляем в кэш
            messageCache[parsedMessage.messageId] = System.currentTimeMillis()
            
            // Обрабатываем сообщение
            processReceivedMessage(peer, parsedMessage)
            
            // Ретранслируем если TTL > 0
            if (parsedMessage.ttl > 0) {
                relayMessage(parsedMessage, peer.id)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки сообщения от ${peer.id}", e)
        }
    }

    private fun processReceivedMessage(peer: TcpPeer, message: ParsedMessage) {
        when (message.messageType) {
            MESSAGE_TYPE_CHAT -> {
                val textInfo = parseTextPayload(message.payload)
                notifyListeners { 
                    onTextMessageReceived(peer, textInfo.text, textInfo.channel, false) 
                }
            }
            
            MESSAGE_TYPE_PRIVATE -> {
                val textInfo = parseTextPayload(message.payload)
                notifyListeners { 
                    onTextMessageReceived(peer, textInfo.text, null, true) 
                }
            }
            
            MESSAGE_TYPE_CHANNEL_JOIN -> {
                val channelName = String(message.payload, Charsets.UTF_8)
                notifyListeners { 
                    onChannelJoin(peer, channelName) 
                }
            }
            
            MESSAGE_TYPE_CHANNEL_LEAVE -> {
                val channelName = String(message.payload, Charsets.UTF_8)
                notifyListeners { 
                    onChannelLeave(peer, channelName) 
                }
            }
            
            MESSAGE_TYPE_PEER_DISCOVERY -> {
                notifyListeners { 
                    onPeerDiscovery(peer, message.payload) 
                }
            }
            
            MESSAGE_TYPE_HEARTBEAT -> {
                val heartbeatInfo = parseHeartbeatPayload(message.payload)
                notifyListeners { 
                    onHeartbeat(peer, heartbeatInfo) 
                }
            }
            
            MESSAGE_TYPE_ENCRYPTED -> {
                notifyListeners { 
                    onEncryptedMessageReceived(peer, message.payload) 
                }
            }
            
            else -> {
                Log.w(TAG, "Неизвестный тип сообщения: ${message.messageType}")
                notifyListeners { 
                    onUnknownMessageReceived(peer, message.messageType, message.payload) 
                }
            }
        }
    }

    private fun relayMessage(message: ParsedMessage, excludePeer: String) {
        if (message.ttl <= 1) return // TTL исчерпан
        
        val newTtl = message.ttl - 1
        val relayedMessage = formatMessage(
            message.messageType, 
            message.payload, 
            message.messageId, 
            newTtl
        )
        
        // Отправляем всем peers кроме отправителя
        tcpMeshService.getPeers().forEach { peer ->
            if (peer.id != excludePeer) {
                peer.sendMessage(relayedMessage)
            }
        }
        
        Log.d(TAG, "Ретранслировано сообщение ${message.messageId} с TTL $newTtl")
    }

    private fun formatMessage(
        messageType: Byte,
        payload: ByteArray,
        messageId: String,
        ttl: Int
    ): ByteArray {
        val messageIdBytes = messageId.toByteArray(Charsets.UTF_8)
        val timestampBytes = longToBytes(System.currentTimeMillis())
        
        return byteArrayOf(messageType) +
                byteArrayOf(ttl.toByte()) +
                intToBytes(messageIdBytes.size) +
                messageIdBytes +
                timestampBytes +
                intToBytes(payload.size) +
                payload
    }

    private fun parseMessage(data: ByteArray): ParsedMessage? {
        try {
            if (data.size < 14) return null // Минимальный размер заголовка
            
            var offset = 0
            val messageType = data[offset++]
            val ttl = data[offset++].toInt()
            
            val messageIdLength = bytesToInt(data, offset)
            offset += 4
            
            if (offset + messageIdLength > data.size) return null
            val messageId = String(data, offset, messageIdLength, Charsets.UTF_8)
            offset += messageIdLength
            
            if (offset + 8 > data.size) return null
            val timestamp = bytesToLong(data, offset)
            offset += 8
            
            if (offset + 4 > data.size) return null
            val payloadLength = bytesToInt(data, offset)
            offset += 4
            
            if (offset + payloadLength != data.size) return null
            val payload = data.sliceArray(offset until offset + payloadLength)
            
            return ParsedMessage(messageType, ttl, messageId, timestamp, payload)
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга сообщения", e)
            return null
        }
    }

    private fun createTextPayload(text: String, channel: String?): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val channelBytes = channel?.toByteArray(Charsets.UTF_8) ?: byteArrayOf()
        
        return intToBytes(channelBytes.size) + channelBytes + textBytes
    }

    private fun parseTextPayload(payload: ByteArray): TextInfo {
        if (payload.size < 4) return TextInfo("", null)
        
        val channelLength = bytesToInt(payload, 0)
        if (4 + channelLength > payload.size) return TextInfo("", null)
        
        val channel = if (channelLength > 0) {
            String(payload, 4, channelLength, Charsets.UTF_8)
        } else null
        
        val textBytes = payload.sliceArray(4 + channelLength until payload.size)
        val text = String(textBytes, Charsets.UTF_8)
        
        return TextInfo(text, channel)
    }

    private fun createHeartbeatPayload(): ByteArray {
        val localInfo = mapOf(
            "peers" to tcpMeshService.getConnectedPeersCount(),
            "port" to tcpMeshService.getLocalPort(),
            "mode" to tcpMeshService.getConnectionMode().name
        )
        
        // Простая сериализация в JSON-подобный формат
        val jsonString = localInfo.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return "{$jsonString}".toByteArray(Charsets.UTF_8)
    }

    private fun parseHeartbeatPayload(payload: ByteArray): Map<String, String> {
        return try {
            val jsonString = String(payload, Charsets.UTF_8)
            // Простой парсинг JSON-подобной строки
            val pairs = jsonString.removeSurrounding("{", "}").split(",")
            pairs.associate { pair ->
                val (key, value) = pair.split(":")
                key.removeSurrounding("\"") to value.removeSurrounding("\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка парсинга heartbeat", e)
            emptyMap()
        }
    }

    private fun generateMessageId(): String {
        return "${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}"
    }

    private fun startCacheCleanup() {
        adapterScope.launch {
            while (true) {
                delay(CACHE_CLEANUP_INTERVAL_MS)
                cleanupMessageCache()
            }
        }
    }

    private fun cleanupMessageCache() {
        val currentTime = System.currentTimeMillis()
        val expiredKeys = messageCache.entries
            .filter { currentTime - it.value > MESSAGE_CACHE_TTL_MS }
            .map { it.key }
        
        expiredKeys.forEach { messageCache.remove(it) }
        
        if (expiredKeys.isNotEmpty()) {
            Log.d(TAG, "Очищено ${expiredKeys.size} старых сообщений из кэша")
        }
    }

    // Utility functions
    private fun intToBytes(value: Int): ByteArray = byteArrayOf(
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )

    private fun bytesToInt(bytes: ByteArray, offset: Int = 0): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun longToBytes(value: Long): ByteArray = byteArrayOf(
        (value shr 56).toByte(),
        (value shr 48).toByte(),
        (value shr 40).toByte(),
        (value shr 32).toByte(),
        (value shr 24).toByte(),
        (value shr 16).toByte(),
        (value shr 8).toByte(),
        value.toByte()
    )

    private fun bytesToLong(bytes: ByteArray, offset: Int = 0): Long {
        return ((bytes[offset].toLong() and 0xFF) shl 56) or
                ((bytes[offset + 1].toLong() and 0xFF) shl 48) or
                ((bytes[offset + 2].toLong() and 0xFF) shl 40) or
                ((bytes[offset + 3].toLong() and 0xFF) shl 32) or
                ((bytes[offset + 4].toLong() and 0xFF) shl 24) or
                ((bytes[offset + 5].toLong() and 0xFF) shl 16) or
                ((bytes[offset + 6].toLong() and 0xFF) shl 8) or
                (bytes[offset + 7].toLong() and 0xFF)
    }

    // Listener management
    fun addListener(listener: TcpProtocolListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: TcpProtocolListener) {
        listeners.remove(listener)
    }

    private inline fun notifyListeners(action: TcpProtocolListener.() -> Unit) {
        listeners.forEach { listener ->
            try {
                listener.action()
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка уведомления listener", e)
            }
        }
    }

    fun destroy() {
        tcpMeshService.removeListener(this)
        adapterScope.cancel()
        listeners.clear()
        messageCache.clear()
    }

    // Data classes
    data class ParsedMessage(
        val messageType: Byte,
        val ttl: Int,
        val messageId: String,
        val timestamp: Long,
        val payload: ByteArray
    )

    data class TextInfo(
        val text: String,
        val channel: String?
    )
}

/**
 * Интерфейс для получения событий от TCP protocol adapter
 */
interface TcpProtocolListener {
    fun onTcpMeshConnected(mode: TcpMeshService.ConnectionMode)
    fun onTcpMeshDisconnected()
    fun onTcpMeshError(error: Exception)
    fun onTcpPeerConnected(peer: TcpPeer)
    fun onTcpPeerDisconnected(peer: TcpPeer)
    fun onTextMessageReceived(peer: TcpPeer, text: String, channel: String?, isPrivate: Boolean)
    fun onEncryptedMessageReceived(peer: TcpPeer, encryptedData: ByteArray)
    fun onChannelJoin(peer: TcpPeer, channelName: String)
    fun onChannelLeave(peer: TcpPeer, channelName: String)
    fun onPeerDiscovery(peer: TcpPeer, discoveryData: ByteArray)
    fun onHeartbeat(peer: TcpPeer, info: Map<String, String>)
    fun onUnknownMessageReceived(peer: TcpPeer, messageType: Byte, payload: ByteArray)
}