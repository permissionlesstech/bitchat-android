package com.bitchat.android.tcp

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * TCP Peer - представляет одно TCP соединение с peer в mesh сети
 * 
 * Совместим с существующим binary протоколом Bluetooth mesh
 */
class TcpPeer(
    val id: String,
    private val socket: Socket,
    private val service: TcpMeshService
) {
    private val isConnected = AtomicBoolean(true)
    private val peerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    
    val lastActivity = AtomicLong(System.currentTimeMillis())
    
    companion object {
        private const val TAG = "TcpPeer"
        private const val BUFFER_SIZE = 8192
        private const val PING_INTERVAL_MS = 15000L
        private const val MESSAGE_HEADER_SIZE = 4 // 4 байта для длины сообщения
        
        // Служебные типы сообщений
        private const val PING_MESSAGE: Byte = 0x01
        private const val PONG_MESSAGE: Byte = 0x02
    }

    /**
     * Запускает peer соединение
     */
    fun start() {
        try {
            inputStream = socket.getInputStream()
            outputStream = socket.getOutputStream()
            
            // Запускаем чтение сообщений
            peerScope.launch {
                readMessages()
            }
            
            // Запускаем отправку периодических ping
            peerScope.launch {
                sendPeriodicPings()
            }
            
            Log.d(TAG, "Peer $id запущен")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка запуска peer $id", e)
            disconnect()
            service.onPeerError(this, e)
        }
    }

    /**
     * Читает входящие сообщения от peer
     */
    private suspend fun readMessages() {
        val buffer = ByteArray(BUFFER_SIZE)
        var messageBuffer = ByteArray(0)
        var expectedLength = -1
        
        try {
            while (isConnected.get() && !socket.isClosed) {
                val bytesRead = inputStream?.read(buffer) ?: -1
                if (bytesRead <= 0) {
                    Log.d(TAG, "Соединение закрыто peer $id")
                    break
                }
                
                lastActivity.set(System.currentTimeMillis())
                
                // Добавляем в буфер сообщений
                messageBuffer += buffer.sliceArray(0 until bytesRead)
                
                // Обрабатываем полные сообщения
                while (messageBuffer.size >= MESSAGE_HEADER_SIZE) {
                    if (expectedLength == -1) {
                        // Читаем длину сообщения из заголовка
                        expectedLength = bytesToInt(messageBuffer.sliceArray(0 until MESSAGE_HEADER_SIZE))
                        messageBuffer = messageBuffer.sliceArray(MESSAGE_HEADER_SIZE until messageBuffer.size)
                        
                        // Проверяем корректность длины
                        if (expectedLength < 0 || expectedLength > 1024 * 1024) { // Макс 1MB
                            Log.w(TAG, "Некорректная длина сообщения: $expectedLength")
                            break
                        }
                    }
                    
                    if (messageBuffer.size >= expectedLength) {
                        // Полное сообщение получено
                        val messageData = messageBuffer.sliceArray(0 until expectedLength)
                        messageBuffer = messageBuffer.sliceArray(expectedLength until messageBuffer.size)
                        expectedLength = -1
                        
                        // Обрабатываем сообщение
                        processMessage(messageData)
                    } else {
                        // Ждем больше данных
                        break
                    }
                }
            }
        } catch (e: Exception) {
            if (isConnected.get()) {
                Log.e(TAG, "Ошибка чтения от peer $id", e)
                service.onPeerError(this, e)
            }
        } finally {
            disconnect()
        }
    }

    /**
     * Обрабатывает полученное сообщение
     */
    private fun processMessage(data: ByteArray) {
        try {
            if (data.isEmpty()) return
            
            // Проверяем служебные сообщения
            when {
                data.size == 1 && data[0] == PING_MESSAGE -> {
                    // Отвечаем на ping
                    sendPong()
                    return
                }
                data.size == 1 && data[0] == PONG_MESSAGE -> {
                    // Получен pong, обновляем активность
                    lastActivity.set(System.currentTimeMillis())
                    return
                }
                else -> {
                    // Обычное сообщение - передаем в сервис
                    service.onMessageReceived(this, data)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка обработки сообщения от peer $id", e)
        }
    }

    /**
     * Отправляет сообщение peer'у
     */
    fun sendMessage(data: ByteArray) {
        if (!isConnected.get() || socket.isClosed) {
            Log.w(TAG, "Нельзя отправить сообщение отключенному peer $id")
            return
        }
        
        peerScope.launch {
            try {
                val messageLength = data.size
                val header = intToBytes(messageLength)
                
                synchronized(outputStream!!) {
                    outputStream?.write(header)
                    outputStream?.write(data)
                    outputStream?.flush()
                }
                
                if (data.size > 1) { // Не логируем ping/pong сообщения
                    Log.d(TAG, "Отправлено ${data.size} байт peer $id")
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка отправки сообщения peer $id", e)
                service.onPeerError(this@TcpPeer, e)
            }
        }
    }

    /**
     * Отправляет периодические ping сообщения
     */
    private suspend fun sendPeriodicPings() {
        while (isConnected.get() && !socket.isClosed) {
            try {
                delay(PING_INTERVAL_MS)
                if (isConnected.get()) {
                    sendPing()
                }
            } catch (e: Exception) {
                if (isConnected.get()) {
                    Log.e(TAG, "Ошибка отправки ping peer $id", e)
                }
                break
            }
        }
    }

    /**
     * Отправляет ping сообщение
     */
    private fun sendPing() {
        sendRawMessage(byteArrayOf(PING_MESSAGE))
    }

    /**
     * Отправляет pong сообщение
     */
    private fun sendPong() {
        sendRawMessage(byteArrayOf(PONG_MESSAGE))
    }

    /**
     * Отправляет сырое сообщение без обработки через основной sendMessage
     */
    private fun sendRawMessage(data: ByteArray) {
        if (!isConnected.get() || socket.isClosed) return
        
        try {
            val header = intToBytes(data.size)
            synchronized(outputStream!!) {
                outputStream?.write(header)
                outputStream?.write(data)
                outputStream?.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отправки сырого сообщения peer $id", e)
            service.onPeerError(this, e)
        }
    }

    /**
     * Отключает peer соединение
     */
    fun disconnect() {
        if (!isConnected.compareAndSet(true, false)) {
            return // Уже отключен
        }
        
        try {
            inputStream?.close()
            outputStream?.close()
            socket.close()
            peerScope.cancel()
            
            Log.d(TAG, "Peer $id отключен")
            
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка отключения peer $id", e)
        }
    }

    /**
     * Проверяет статус соединения
     */
    fun isConnected(): Boolean = isConnected.get() && !socket.isClosed

    /**
     * Получает удаленный адрес
     */
    fun getRemoteAddress(): String = socket.remoteSocketAddress?.toString() ?: "unknown"

    /**
     * Получает локальный адрес
     */
    fun getLocalAddress(): String = socket.localSocketAddress?.toString() ?: "unknown"

    /**
     * Получает время последней активности
     */
    fun getLastActivityTime(): Long = lastActivity.get()

    /**
     * Проверяет, является ли это соединением с сервером
     */
    fun isServerConnection(): Boolean = id == "server"

    // Утилитарные функции для конвертации int <-> bytes
    private fun intToBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value shr 24).toByte(),
            (value shr 16).toByte(),
            (value shr 8).toByte(),
            value.toByte()
        )
    }

    private fun bytesToInt(bytes: ByteArray): Int {
        if (bytes.size < 4) return 0
        return ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
    }

    override fun toString(): String {
        return "TcpPeer(id='$id', address='${getRemoteAddress()}', connected=${isConnected()})"
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TcpPeer) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}