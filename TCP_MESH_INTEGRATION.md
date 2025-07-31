# TCP Mesh Chat Integration для Bitchat Android

## Обзор

Эта интеграция добавляет TCP mesh networking функциональность к существующему Bitchat Android приложению, сохраняя полную совместимость с Bluetooth mesh протоколом.

## Структура файлов

### Серверная часть (отдельный проект)
```
bitchat-tcp-server/
├── BitchatTcpServer.kt     # Основной серверный файл
├── Dockerfile              # Docker контейнер
├── docker-compose.yml      # Docker Compose конфигурация
└── README.md              # Документация сервера
```

### Клиентская часть (Android проект)
```
app/src/main/java/com/bitchat/android/tcp/
├── TcpMeshService.kt       # Основной TCP mesh сервис
├── TcpPeer.kt             # Класс TCP peer соединения
└── TcpProtocolAdapter.kt   # Адаптер для существующего протокола
```

## Возможности

### ✅ Реализовано

1. **TCP Mesh Service** - Основной сервис для TCP networking
   - P2P режим для прямых соединений
   - Server режим для подключения к центральному серверу
   - Hybrid режим для одновременной работы P2P и server
   - UDP discovery для автоматического поиска peers в локальной сети

2. **TCP Peer Management** - Управление TCP соединениями
   - Автоматическое переподключение
   - Ping/pong для проверки состояния соединения
   - Безопасное отключение и очистка ресурсов

3. **Protocol Compatibility** - Совместимость с существующим протоколом
   - Тот же binary формат сообщений
   - TTL и маршрутизация сообщений
   - Дедупликация сообщений
   - Поддержка всех типов сообщений (chat, private, channels, etc.)

4. **Standalone Server** - Отдельный серверный компонент
   - Kotlin server для координации TCP mesh
   - Docker поддержка для простого развертывания
   - Управление каналами и пользователями
   - Ретрансляция зашифрованных сообщений

5. **Security & Permissions** - Безопасность
   - Необходимые Android permissions добавлены
   - Foreground service для стабильной работы
   - Поддержка существующего E2E шифрования

### 🔄 Требуется доработка

1. **UI Integration** - Интеграция с пользовательским интерфейсом
2. **Settings Screen** - Экран настроек TCP mesh
3. **Bluetooth Integration** - Интеграция с существующим Bluetooth mesh
4. **Encryption Adapter** - Адаптер для существующей системы шифрования

## Развертывание сервера

### Локальный запуск
```bash
cd bitchat-tcp-server
kotlin BitchatTcpServer.kt [port]
```

### Docker
```bash
cd bitchat-tcp-server
docker build -t bitchat-tcp-server .
docker run -p 9090:9090 bitchat-tcp-server
```

### Docker Compose
```bash
cd bitchat-tcp-server
docker-compose up -d
```

## Использование в Android

### Инициализация TCP Mesh

```kotlin
// Получаем сервис
val intent = Intent(this, TcpMeshService::class.java)
bindService(intent, tcpServiceConnection, Context.BIND_AUTO_CREATE)

// Запускаем в P2P режиме
tcpMeshService.startMesh(
    mode = TcpMeshService.ConnectionMode.P2P,
    localPort = 8080,
    enableDiscovery = true
)

// Запускаем в server режиме
tcpMeshService.startMesh(
    mode = TcpMeshService.ConnectionMode.SERVER,
    serverHost = "your-server.com",
    serverPort = 9090
)
```

### Отправка сообщений

```kotlin
// Через протокол адаптер
val adapter = TcpProtocolAdapter(tcpMeshService)

// Текстовое сообщение
adapter.sendTextMessage("Привет!", channel = "general")

// Личное сообщение
adapter.sendTextMessage("Привет!", targetPeer = "peer_id")

// Зашифрованное сообщение
adapter.sendEncryptedMessage(encryptedData)

// Присоединиться к каналу
adapter.joinChannel("general")
```

### Получение событий

```kotlin
adapter.addListener(object : TcpProtocolListener {
    override fun onTextMessageReceived(peer: TcpPeer, text: String, channel: String?, isPrivate: Boolean) {
        // Обработка текстового сообщения
    }
    
    override fun onTcpPeerConnected(peer: TcpPeer) {
        // Новый peer подключен
    }
    
    override fun onTcpMeshConnected(mode: TcpMeshService.ConnectionMode) {
        // TCP mesh запущен
    }
})
```

## Конфигурация

### Android Manifest
```xml
<!-- TCP networking permissions уже добавлены -->
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
<uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />

<!-- TCP Mesh Service уже добавлен -->
<service android:name=".tcp.TcpMeshService" ... />
```

### Порты
- **TCP Server**: 8080 (по умолчанию для P2P)
- **UDP Discovery**: 8081 (для поиска peers)
- **Central Server**: 9090 (по умолчанию для server режима)

## Режимы работы

### 1. P2P Mode (Peer-to-Peer)
- Прямые TCP соединения между устройствами
- UDP broadcast discovery в локальной сети
- Не требует интернет или сервер
- Идеально для локальных сетей

### 2. Server Mode
- Все клиенты подключаются к центральному серверу
- Сервер ретранслирует сообщения между клиентами
- Работает через интернет
- Масштабируемо для большого количества пользователей

### 3. Hybrid Mode
- Комбинирует P2P и server режимы
- Автоматический failover между режимами
- Максимальная надежность и покрытие

## Совместимость

- ✅ **Binary Protocol**: Полная совместимость с Bluetooth mesh протоколом
- ✅ **Message Types**: Поддержка всех типов сообщений
- ✅ **Encryption**: Совместимость с существующим E2E шифрованием
- ✅ **Commands**: Те же IRC-style команды (/join, /msg, etc.)
- ✅ **Cross-Platform**: Работает с iOS версией через сервер

## Дальнейшие шаги

1. **Завершить UI интеграцию** - добавить настройки TCP в основное приложение
2. **Bluetooth + TCP Hybrid** - объединить оба mesh типа
3. **Advanced Features** - NAT traversal, WebRTC support
4. **Testing** - unit и integration тесты
5. **Performance** - оптимизация для мобильных устройств

## Поддержка

Для вопросов по TCP mesh интеграции обращайтесь к разработчикам или создавайте issues в репозитории проекта.