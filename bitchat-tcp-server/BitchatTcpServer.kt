#!/usr/bin/env kotlin

@file:DependsOn("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Bitchat TCP Mesh Server
 * 
 * Серверная часть для координации TCP mesh chat между Android клиентами.
 * Обеспечивает ретрансляцию сообщений, управление каналами и peer discovery.
 */
class BitchatTcpServer(
    private val port: Int = 9090,
    private val maxClients: Int = 1000,
    private val messageHistorySize: Int = 100,
    private val debug: Boolean = false
) {
    private val isRunning = AtomicBoolean(false)
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    
    // Состояние сервера
    private val clients = ConcurrentHashMap<String, ClientHandler>()
    private val channels = ConcurrentHashMap<String, ChannelInfo>()
    private val messageHistory = ConcurrentHashMap<String, ArrayDeque<MessageInfo>>()
    private val clientCounter = AtomicInteger(0)
    private val messageCounter = AtomicLong(0)
    private val startTime = System.currentTimeMillis()
    
    data class ClientHandler(
        val id: String,
        val socket: Socket,
        val inputStream: InputStream,
        val outputStream: OutputStream,
        val lastActivity: AtomicLong = AtomicLong(System.currentTimeMillis()),
        var nickname: String? = null,
        val joinedChannels: MutableSet<String> = mutableSetOf(),
        val isConnected: AtomicBoolean = AtomicBoolean(true)
    )
    
    data class ChannelInfo(
        val name: String,
        val createdAt: Long = System.currentTimeMillis(),
        val members: MutableSet<String> = mutableSetOf(),
        var owner: String? = null,
        var hasPassword: Boolean = false
    )
    
    data class MessageInfo(
        val timestamp: Long,
        val senderId: String,
        val data: ByteArray,
        val channel: String? = null
    )

    fun start() {
        if (isRunning.get()) {
            println("Сервер уже запущен")
            return
        }

        try {
            serverSocket = ServerSocket(port)
            isRunning.set(true)
            
            println("=".repeat(50))
            println("Bitchat TCP Server запущен")
            println("Порт: $port")
            println("Максимум клиентов: $maxClients")
            println("История сообщений: $messageHistorySize")
            println("Время запуска: ${LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)}")
            println("=".repeat(50))
            
            // Принимаем входящие соединения
            serverScope.launch {
                acceptConnections()
            }
            
            // Мониторинг клиентов
            serverScope.launch {
                monitorClients()
            }
            
            // Периодическая статистика
            if (debug) {
                serverScope.launch {
                    printStatistics()
                }
            }
            
        } catch (e: Exception) {
            log("Ошибка запуска сервера: ${e.message}")
            stop()
        }
    }

    fun stop() {
        if (!isRunning.get()) return
        
        log("Останавливаем сервер...")
        isRunning.set(false)
        
        // Отключаем всех клиентов
        clients.values.forEach { client ->
            disconnectClient(client.id, "Сервер останавливается")
        }
        
        // Закрываем серверный сокет
        serverSocket?.close()
        serverSocket = null
        
        // Очищаем состояние
        clients.clear()
        channels.clear()
        messageHistory.clear()
        
        serverScope.cancel()
        log("Сервер остановлен")
    }

    private suspend fun acceptConnections() {
        while (isRunning.get() && serverSocket?.isClosed == false) {
            try {
                val clientSocket = serverSocket?.accept() ?: break
                
                if (clients.size >= maxClients) {
                    log("Превышен лимит клиентов, отклоняем подключение от ${clientSocket.remoteSocketAddress}")
                    clientSocket.close()
                    continue
                }
                
                handleNewClient(clientSocket)
                
            } catch (e: Exception) {
                if (isRunning.get()) {
                    log("Ошибка при принятии соединения: ${e.message}")
                }
            }
        }
    }

    private suspend fun handleNewClient(socket: Socket) {
        val clientId = "client_${clientCounter.incrementAndGet()}_${System.currentTimeMillis()}"
        
        try {
            val client = ClientHandler(
                id = clientId,
                socket = socket,
                inputStream = socket.getInputStream(),
                outputStream = socket.getOutputStream()
            )
            
            clients[clientId] = client
            
            log("Новый клиент подключен: $clientId от ${socket.remoteSocketAddress}")
            
            // Запускаем обработчик сообщений клиента
            serverScope.launch {
                handleClientMessages(client)
            }
            
            // Отправляем приветственное сообщение
            sendWelcomeMessage(client)
            
        } catch (e: Exception) {
            log("Ошибка обработки нового клиента: ${e.message}")
            socket.close()
        }
    }

    private suspend fun handleClientMessages(client: ClientHandler) {
        val buffer = ByteArray(8192)
        
        try {
            while (isRunning.get() && client.isConnected.get() && !client.socket.isClosed) {
                val bytesRead = client.inputStream.read(buffer)
                if (bytesRead <= 0) break
                
                client.lastActivity.set(System.currentTimeMillis())
                messageCounter.incrementAndGet()
                
                val messageData = buffer.sliceArray(0 until bytesRead)
                processClientMessage(client, messageData)
            }
        } catch (e: Exception) {
            if (client.isConnected.get()) {
                log("Ошибка чтения от клиента ${client.id}: ${e.message}")
            }
        } finally {
            disconnectClient(client.id, "Соединение потеряно")
        }
    }

    private fun processClientMessage(client: ClientHandler, data: ByteArray) {
        try {
            // Пытаемся парсить как текст для команд
            val textMessage = String(data, Charsets.UTF_8)
            
            when {
                textMessage.startsWith("/nick ") -> {
                    handleNicknameChange(client, textMessage.substring(6).trim())
                }
                textMessage.startsWith("/join #") -> {
                    val channelName = textMessage.substring(7).trim()
                    handleJoinChannel(client, channelName)
                }
                textMessage.startsWith("/leave #") -> {
                    val channelName = textMessage.substring(8).trim()
                    handleLeaveChannel(client, channelName)
                }
                textMessage.startsWith("/msg @") -> {
                    handlePrivateMessage(client, textMessage)
                }
                textMessage == "/who" || textMessage == "/users" -> {
                    handleListUsers(client)
                }
                textMessage == "/channels" -> {
                    handleListChannels(client)
                }
                textMessage == "/help" -> {
                    handleHelp(client)
                }
                textMessage.startsWith("/") -> {
                    sendToClient(client, "Неизвестная команда: $textMessage")
                }
                else -> {
                    // Обычное сообщение - ретранслируем всем
                    relayMessage(client, data, null)
                }
            }
        } catch (e: Exception) {
            log("Ошибка обработки сообщения от ${client.id}: ${e.message}")
        }
    }

    private fun handleNicknameChange(client: ClientHandler, nickname: String) {
        if (nickname.isBlank()) {
            sendToClient(client, "Ошибка: Пустой никнейм")
            return
        }
        
        val oldNick = client.nickname
        client.nickname = nickname
        
        val message = if (oldNick != null) {
            "$oldNick сменил никнейм на $nickname"
        } else {
            "$nickname присоединился к серверу"
        }
        
        broadcastServerMessage(message, excludeClient = client.id)
        sendToClient(client, "Никнейм изменен на: $nickname")
        
        log("Клиент ${client.id} сменил никнейм на $nickname")
    }

    private fun handleJoinChannel(client: ClientHandler, channelName: String) {
        if (channelName.isBlank()) {
            sendToClient(client, "Ошибка: Пустое имя канала")
            return
        }
        
        val channel = channels.computeIfAbsent(channelName) { 
            ChannelInfo(name = channelName, owner = client.id)
        }
        
        channel.members.add(client.id)
        client.joinedChannels.add(channelName)
        
        // Отправляем историю канала
        messageHistory[channelName]?.forEach { msg ->
            sendToClient(client, msg.data)
        }
        
        val nickname = client.nickname ?: client.id
        val joinMessage = "$nickname присоединился к каналу #$channelName"
        
        broadcastToChannel(channelName, joinMessage.toByteArray(), excludeClient = client.id)
        sendToClient(client, "Вы присоединились к каналу #$channelName")
        
        log("$nickname присоединился к каналу #$channelName")
    }

    private fun handleLeaveChannel(client: ClientHandler, channelName: String) {
        val channel = channels[channelName]
        if (channel == null || client.id !in channel.members) {
            sendToClient(client, "Вы не состоите в канале #$channelName")
            return
        }
        
        channel.members.remove(client.id)
        client.joinedChannels.remove(channelName)
        
        val nickname = client.nickname ?: client.id
        val leaveMessage = "$nickname покинул канал #$channelName"
        
        broadcastToChannel(channelName, leaveMessage.toByteArray(), excludeClient = client.id)
        sendToClient(client, "Вы покинули канал #$channelName")
        
        // Удаляем пустые каналы
        if (channel.members.isEmpty()) {
            channels.remove(channelName)
            messageHistory.remove(channelName)
        }
        
        log("$nickname покинул канал #$channelName")
    }

    private fun handlePrivateMessage(client: ClientHandler, message: String) {
        val parts = message.substring(6).split(" ", limit = 2)
        if (parts.size < 2) {
            sendToClient(client, "Формат: /msg @nickname сообщение")
            return
        }
        
        val targetNick = parts[0]
        val messageText = parts[1]
        
        val targetClient = clients.values.find { it.nickname == targetNick }
        if (targetClient == null) {
            sendToClient(client, "Пользователь $targetNick не найден")
            return
        }
        
        val senderNick = client.nickname ?: client.id
        val privateMsg = "Личное от $senderNick: $messageText"
        
        sendToClient(targetClient, privateMsg)
        sendToClient(client, "Отправлено $targetNick: $messageText")
        
        log("Личное сообщение от $senderNick к $targetNick")
    }

    private fun handleListUsers(client: ClientHandler) {
        val users = clients.values.mapNotNull { it.nickname ?: it.id }
        val userList = if (users.isEmpty()) "Нет пользователей онлайн" else "Пользователи онлайн: ${users.joinToString(", ")}"
        sendToClient(client, userList)
    }

    private fun handleListChannels(client: ClientHandler) {
        val channelList = if (channels.isEmpty()) {
            "Активных каналов нет"
        } else {
            "Активные каналы: ${channels.keys.joinToString(", ") { "#$it" }}"
        }
        sendToClient(client, channelList)
    }

    private fun handleHelp(client: ClientHandler) {
        val help = """
            Доступные команды:
            /nick <имя> - сменить никнейм
            /join #<канал> - присоединиться к каналу  
            /leave #<канал> - покинуть канал
            /msg @<пользователь> <сообщение> - личное сообщение
            /who - список пользователей
            /channels - список каналов
            /help - эта справка
        """.trimIndent()
        sendToClient(client, help)
    }

    private fun relayMessage(sender: ClientHandler, data: ByteArray, targetChannel: String?) {
        val messageInfo = MessageInfo(
            timestamp = System.currentTimeMillis(),
            senderId = sender.id,
            data = data,
            channel = targetChannel
        )
        
        if (targetChannel != null) {
            // Сообщение в канал
            addToMessageHistory(targetChannel, messageInfo)
            broadcastToChannel(targetChannel, data, excludeClient = sender.id)
        } else {
            // Общее сообщение - ретранслируем всем
            broadcastToAll(data, excludeClient = sender.id)
        }
    }

    private fun addToMessageHistory(channel: String, message: MessageInfo) {
        val history = messageHistory.computeIfAbsent(channel) { ArrayDeque() }
        history.addLast(message)
        
        while (history.size > messageHistorySize) {
            history.removeFirst()
        }
    }

    private fun sendToClient(client: ClientHandler, message: String) {
        sendToClient(client, message.toByteArray())
    }

    private fun sendToClient(client: ClientHandler, data: ByteArray) {
        if (!client.isConnected.get() || client.socket.isClosed) return
        
        try {
            client.outputStream.write(data)
            client.outputStream.flush()
        } catch (e: Exception) {
            log("Ошибка отправки сообщения клиенту ${client.id}: ${e.message}")
            disconnectClient(client.id, "Ошибка отправки")
        }
    }

    private fun broadcastToAll(data: ByteArray, excludeClient: String? = null) {
        clients.values.forEach { client ->
            if (client.id != excludeClient) {
                sendToClient(client, data)
            }
        }
    }

    private fun broadcastToChannel(channelName: String, data: ByteArray, excludeClient: String? = null) {
        val channel = channels[channelName] ?: return
        
        channel.members.forEach { clientId ->
            if (clientId != excludeClient) {
                clients[clientId]?.let { client ->
                    sendToClient(client, data)
                }
            }
        }
    }

    private fun broadcastServerMessage(message: String, excludeClient: String? = null) {
        val serverMsg = "СЕРВЕР: $message"
        broadcastToAll(serverMsg.toByteArray(), excludeClient)
    }

    private fun sendWelcomeMessage(client: ClientHandler) {
        val welcome = """
            Добро пожаловать на Bitchat TCP Server!
            Подключенных клиентов: ${clients.size}
            Активных каналов: ${channels.size}
            Введите /help для списка команд
        """.trimIndent()
        sendToClient(client, welcome)
    }

    private fun disconnectClient(clientId: String, reason: String) {
        val client = clients.remove(clientId) ?: return
        
        if (client.isConnected.compareAndSet(true, false)) {
            // Удаляем из всех каналов
            client.joinedChannels.forEach { channelName ->
                val channel = channels[channelName]
                channel?.members?.remove(clientId)
                
                if (channel?.members?.isEmpty() == true) {
                    channels.remove(channelName)
                    messageHistory.remove(channelName)
                }
            }
            
            try {
                client.socket.close()
            } catch (e: Exception) {
                // Игнорируем ошибки закрытия
            }
            
            val nickname = client.nickname ?: clientId
            broadcastServerMessage("$nickname отключился ($reason)", excludeClient = clientId)
            
            log("Клиент $clientId отключен: $reason")
        }
    }

    private suspend fun monitorClients() {
        while (isRunning.get()) {
            val currentTime = System.currentTimeMillis()
            val timeoutClients = mutableListOf<String>()
            
            clients.forEach { (id, client) ->
                if (currentTime - client.lastActivity.get() > 60000) { // 1 минута таймаут
                    timeoutClients.add(id)
                }
            }
            
            timeoutClients.forEach { clientId ->
                disconnectClient(clientId, "Таймаут")
            }
            
            delay(10000) // Проверяем каждые 10 секунд
        }
    }

    private suspend fun printStatistics() {
        while (isRunning.get()) {
            delay(30000) // Каждые 30 секунд
            
            val uptime = (System.currentTimeMillis() - startTime) / 1000
            log("Статистика: ${clients.size} клиентов, ${channels.size} каналов, ${messageCounter.get()} сообщений, время работы: ${uptime}с")
        }
    }

    private fun log(message: String) {
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        println("[$timestamp] $message")
    }
}

// Главная функция запуска сервера
fun main(args: Array<String>) {
    var port = 9090
    var maxClients = 1000
    var debug = false
    
    // Парсим аргументы командной строки
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port" -> {
                if (i + 1 < args.size) {
                    port = args[++i].toIntOrNull() ?: 9090
                }
            }
            "--max-clients" -> {
                if (i + 1 < args.size) {
                    maxClients = args[++i].toIntOrNull() ?: 1000
                }
            }
            "--debug" -> {
                debug = true
            }
            else -> {
                // Если просто число - это порт
                args[i].toIntOrNull()?.let { port = it }
            }
        }
        i++
    }
    
    // Проверяем переменные окружения
    System.getenv("BITCHAT_SERVER_PORT")?.toIntOrNull()?.let { port = it }
    System.getenv("BITCHAT_MAX_CLIENTS")?.toIntOrNull()?.let { maxClients = it }
    
    val server = BitchatTcpServer(port, maxClients, debug = debug)
    
    // Обработчик завершения
    Runtime.getRuntime().addShutdownHook(Thread {
        println("\nПолучен сигнал завершения...")
        server.stop()
    })
    
    try {
        server.start()
        
        // Ждем ввода для остановки
        println("Нажмите Enter для остановки сервера...")
        readLine()
        
    } catch (e: Exception) {
        println("Критическая ошибка: ${e.message}")
        e.printStackTrace()
    } finally {
        server.stop()
    }
}