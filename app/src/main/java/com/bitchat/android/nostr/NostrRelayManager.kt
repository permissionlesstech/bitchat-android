package com.bitchat.android.nostr

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

/**
 * Manages WebSocket connections to Nostr relays
 * Compatible with iOS implementation with Android-specific optimizations
 */
class NostrRelayManager private constructor() {
    
    companion object {
        @JvmStatic
        val shared = NostrRelayManager()
        
        private const val TAG = "NostrRelayManager"
        
        // Default relay list (same as iOS)
        private val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://relay.primal.net",
            "wss://offchain.pub",
            "wss://nostr21.com"
        )
        
        // Exponential backoff configuration (same as iOS)
        private const val INITIAL_BACKOFF_INTERVAL = 1000L  // 1 second
        private const val MAX_BACKOFF_INTERVAL = 300000L    // 5 minutes
        private const val BACKOFF_MULTIPLIER = 2.0
        private const val MAX_RECONNECT_ATTEMPTS = 10
        
        // Track gift-wraps we initiated for logging
        private val pendingGiftWrapIDs = ConcurrentHashMap.newKeySet<String>()
        
        fun registerPendingGiftWrap(id: String) {
            pendingGiftWrapIDs.add(id)
        }
    }
    
    /**
     * Relay status information
     */
    data class Relay(
        val url: String,
        var isConnected: Boolean = false,
        var lastError: Throwable? = null,
        var lastConnectedAt: Long? = null,
        var messagesSent: Int = 0,
        var messagesReceived: Int = 0,
        var reconnectAttempts: Int = 0,
        var lastDisconnectedAt: Long? = null,
        var nextReconnectTime: Long? = null
    )
    
    // Published state
    private val _relays = MutableLiveData<List<Relay>>()
    val relays: LiveData<List<Relay>> = _relays
    
    private val _isConnected = MutableLiveData<Boolean>()
    val isConnected: LiveData<Boolean> = _isConnected
    
    // Internal state
    private val relaysList = mutableListOf<Relay>()
    private val connections = ConcurrentHashMap<String, WebSocket>()
    private val subscriptions = ConcurrentHashMap<String, Set<String>>() // relay URL -> subscription IDs
    private val messageHandlers = ConcurrentHashMap<String, (NostrEvent) -> Unit>()
    
    // Message queue for reliability
    private val messageQueue = mutableListOf<Pair<NostrEvent, List<String>>>()
    private val messageQueueLock = Any()
    
    // Coroutine scope for background operations
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // OkHttp client for WebSocket connections
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS) // No read timeout for WebSocket
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    
    private val gson by lazy { NostrRequest.createGson() }
    
    init {
        // Initialize with default relays - avoid static initialization order issues
        try {
            val defaultRelayUrls = listOf(
                "wss://relay.damus.io",
                "wss://relay.primal.net",
                "wss://offchain.pub",
                "wss://nostr21.com"
            )
            relaysList.addAll(defaultRelayUrls.map { Relay(it) })
            _relays.postValue(relaysList.toList())
            updateConnectionStatus()
            Log.d(TAG, "✅ NostrRelayManager initialized with ${relaysList.size} default relays")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize NostrRelayManager: ${e.message}", e)
            // Initialize with empty list as fallback
            _relays.postValue(emptyList())
            _isConnected.postValue(false)
        }
    }
    
    /**
     * Connect to all configured relays
     */
    fun connect() {
        Log.d(TAG, "🌐 Connecting to ${relaysList.size} Nostr relays")
        
        scope.launch {
            relaysList.forEach { relay ->
                launch {
                    connectToRelay(relay.url)
                }
            }
        }
    }
    
    /**
     * Disconnect from all relays
     */
    fun disconnect() {
        Log.d(TAG, "Disconnecting from all relays")
        
        connections.values.forEach { webSocket ->
            webSocket.close(1000, "Manual disconnect")
        }
        connections.clear()
        
        // Clear subscriptions
        subscriptions.clear()
        
        updateConnectionStatus()
    }
    
    /**
     * Send an event to specified relays (or all if none specified)
     */
    fun sendEvent(event: NostrEvent, relayUrls: List<String>? = null) {
        val targetRelays = relayUrls ?: relaysList.map { it.url }
        
        // Add to queue for reliability
        synchronized(messageQueueLock) {
            messageQueue.add(Pair(event, targetRelays))
        }
        
        // Attempt immediate send
        scope.launch {
            targetRelays.forEach { relayUrl ->
                val webSocket = connections[relayUrl]
                if (webSocket != null) {
                    sendToRelay(event, webSocket, relayUrl)
                }
            }
        }
    }
    
    /**
     * Subscribe to events matching a filter
     */
    fun subscribe(
        filter: NostrFilter,
        id: String = generateSubscriptionId(),
        handler: (NostrEvent) -> Unit
    ) {
        messageHandlers[id] = handler
        
        Log.d(TAG, "📡 Subscribing to Nostr filter id=$id ${filter.getDebugDescription()}")
        
        val request = NostrRequest.Subscribe(id, listOf(filter))
        val message = gson.toJson(request, NostrRequest::class.java)
        
        // DEBUG: Log the actual serialized message format
        Log.d(TAG, "🔍 DEBUG: Serialized subscription message: $message")
        
        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                try {
                    val success = webSocket.send(message)
                    if (success) {
                        // Track subscription for this relay
                        val currentSubs = subscriptions[relayUrl] ?: emptySet()
                        subscriptions[relayUrl] = currentSubs + id
                        
                        Log.v(TAG, "✅ Subscription '$id' sent to relay: $relayUrl")
                    } else {
                        Log.w(TAG, "❌ Failed to send subscription to $relayUrl: WebSocket send failed")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Failed to send subscription to $relayUrl: ${e.message}")
                }
            }
            
            if (connections.isEmpty()) {
                Log.w(TAG, "⚠️ No relay connections available for subscription")
            }
        }
    }
    
    /**
     * Unsubscribe from a subscription
     */
    fun unsubscribe(id: String) {
        messageHandlers.remove(id)
        
        val request = NostrRequest.Close(id)
        val message = gson.toJson(request, NostrRequest::class.java)
        
        scope.launch {
            connections.forEach { (relayUrl, webSocket) ->
                val currentSubs = subscriptions[relayUrl]
                if (currentSubs?.contains(id) == true) {
                    try {
                        webSocket.send(message)
                        subscriptions[relayUrl] = currentSubs - id
                        Log.v(TAG, "Unsubscribed '$id' from relay: $relayUrl")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to unsubscribe from $relayUrl: ${e.message}")
                    }
                }
            }
        }
    }
    
    /**
     * Manually retry connection to a specific relay
     */
    fun retryConnection(relayUrl: String) {
        val relay = relaysList.find { it.url == relayUrl } ?: return
        
        // Reset reconnection attempts
        relay.reconnectAttempts = 0
        relay.nextReconnectTime = null
        
        // Disconnect if connected
        connections[relayUrl]?.close(1000, "Manual retry")
        connections.remove(relayUrl)
        
        // Attempt immediate reconnection
        scope.launch {
            connectToRelay(relayUrl)
        }
    }
    
    /**
     * Reset all relay connections
     */
    fun resetAllConnections() {
        disconnect()
        
        // Reset all relay states
        relaysList.forEach { relay ->
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
            relay.lastError = null
        }
        
        // Reconnect
        connect()
    }
    
    /**
     * Get detailed status for all relays
     */
    fun getRelayStatuses(): List<Relay> {
        return relaysList.toList()
    }
    
    // MARK: - Private Methods
    
    private suspend fun connectToRelay(urlString: String) {
        // Skip if we already have a connection
        if (connections.containsKey(urlString)) {
            return
        }
        
        Log.v(TAG, "Attempting to connect to Nostr relay: $urlString")
        
        try {
            val request = Request.Builder()
                .url(urlString)
                .build()
            
            val webSocket = httpClient.newWebSocket(request, RelayWebSocketListener(urlString))
            connections[urlString] = webSocket
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to create WebSocket connection to $urlString: ${e.message}")
            handleDisconnection(urlString, e)
        }
    }
    
    private fun sendToRelay(event: NostrEvent, webSocket: WebSocket, relayUrl: String) {
        try {
            val request = NostrRequest.Event(event)
            val message = gson.toJson(request, NostrRequest::class.java)
            
            Log.v(TAG, "📤 Sending Nostr event (kind: ${event.kind}) to relay: $relayUrl")
            
            val success = webSocket.send(message)
            if (success) {
                // Update relay stats
                val relay = relaysList.find { it.url == relayUrl }
                relay?.messagesSent = (relay?.messagesSent ?: 0) + 1
                updateRelaysList()
            } else {
                Log.e(TAG, "❌ Failed to send event to $relayUrl: WebSocket send failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to send event to $relayUrl: ${e.message}")
        }
    }
    
    private fun handleMessage(message: String, relayUrl: String) {
        try {
            val jsonElement = JsonParser.parseString(message)
            if (!jsonElement.isJsonArray) {
                Log.w(TAG, "Received non-array message from $relayUrl")
                return
            }
            
            val response = NostrResponse.fromJsonArray(jsonElement.asJsonArray)
            
            when (response) {
                is NostrResponse.Event -> {
                    // Update relay stats
                    val relay = relaysList.find { it.url == relayUrl }
                    relay?.messagesReceived = (relay?.messagesReceived ?: 0) + 1
                    updateRelaysList()
                    
                    // Only log non-gift-wrap events to reduce noise
                    if (response.event.kind != NostrKind.GIFT_WRAP) {
                        Log.v(TAG, "📥 Received Nostr event (kind: ${response.event.kind}) from relay: $relayUrl")
                    }
                    
                    // Call handler
                    val handler = messageHandlers[response.subscriptionId]
                    if (handler != null) {
                        scope.launch(Dispatchers.Main) {
                            handler(response.event)
                        }
                    } else {
                        Log.w(TAG, "⚠️ No handler for subscription ${response.subscriptionId}")
                    }
                }
                
                is NostrResponse.EndOfStoredEvents -> {
                    Log.v(TAG, "End of stored events for subscription: ${response.subscriptionId}")
                }
                
                is NostrResponse.Ok -> {
                    val wasGiftWrap = pendingGiftWrapIDs.remove(response.eventId)
                    if (response.accepted) {
                        Log.d(TAG, "✅ Event accepted id=${response.eventId.take(16)}... by relay: $relayUrl")
                    } else {
                        val level = if (wasGiftWrap) Log.WARN else Log.ERROR
                        Log.println(level, TAG, "📮 Event ${response.eventId.take(16)}... rejected by relay: ${response.message ?: "no reason"}")
                    }
                }
                
                is NostrResponse.Notice -> {
                    Log.i(TAG, "📢 Notice from $relayUrl: ${response.message}")
                }
                
                is NostrResponse.Unknown -> {
                    Log.v(TAG, "Unknown message type from $relayUrl: ${response.raw}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from $relayUrl: ${e.message}")
        }
    }
    
    private fun handleDisconnection(relayUrl: String, error: Throwable) {
        connections.remove(relayUrl)
        subscriptions.remove(relayUrl)
        
        updateRelayStatus(relayUrl, false, error)
        
        // Check if this is a DNS error
        val errorMessage = error.message?.lowercase() ?: ""
        if (errorMessage.contains("hostname could not be found") || 
            errorMessage.contains("dns") ||
            errorMessage.contains("unable to resolve host")) {
            
            val relay = relaysList.find { it.url == relayUrl }
            if (relay?.lastError == null) {
                Log.w(TAG, "Nostr relay DNS failure for $relayUrl - not retrying")
            }
            return
        }
        
        // Implement exponential backoff for non-DNS errors
        val relay = relaysList.find { it.url == relayUrl } ?: return
        relay.reconnectAttempts++
        
        // Stop attempting after max attempts
        if (relay.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.w(TAG, "Max reconnection attempts ($MAX_RECONNECT_ATTEMPTS) reached for $relayUrl")
            return
        }
        
        // Calculate backoff interval
        val backoffInterval = min(
            INITIAL_BACKOFF_INTERVAL * BACKOFF_MULTIPLIER.pow(relay.reconnectAttempts - 1.0),
            MAX_BACKOFF_INTERVAL.toDouble()
        ).toLong()
        
        relay.nextReconnectTime = System.currentTimeMillis() + backoffInterval
        
        Log.d(TAG, "Scheduling reconnection to $relayUrl in ${backoffInterval / 1000}s (attempt ${relay.reconnectAttempts})")
        
        // Schedule reconnection
        scope.launch {
            delay(backoffInterval)
            connectToRelay(relayUrl)
        }
    }
    
    private fun updateRelayStatus(url: String, isConnected: Boolean, error: Throwable? = null) {
        val relay = relaysList.find { it.url == url } ?: return
        
        relay.isConnected = isConnected
        relay.lastError = error
        
        if (isConnected) {
            relay.lastConnectedAt = System.currentTimeMillis()
            relay.reconnectAttempts = 0
            relay.nextReconnectTime = null
        } else {
            relay.lastDisconnectedAt = System.currentTimeMillis()
        }
        
        updateRelaysList()
        updateConnectionStatus()
    }
    
    private fun updateRelaysList() {
        _relays.postValue(relaysList.toList())
    }
    
    private fun updateConnectionStatus() {
        val connected = relaysList.any { it.isConnected }
        _isConnected.postValue(connected)
    }
    
    private fun generateSubscriptionId(): String {
        return "sub-${System.currentTimeMillis()}-${(Math.random() * 1000).toInt()}"
    }
    
    /**
     * WebSocket listener for relay connections
     */
    private inner class RelayWebSocketListener(private val relayUrl: String) : WebSocketListener() {
        
        override fun onOpen(webSocket: WebSocket, response: Response) {
            Log.d(TAG, "✅ Connected to Nostr relay: $relayUrl")
            updateRelayStatus(relayUrl, true)
            
            // Process any queued messages for this relay
            synchronized(messageQueueLock) {
                val iterator = messageQueue.iterator()
                while (iterator.hasNext()) {
                    val (event, targetRelays) = iterator.next()
                    if (relayUrl in targetRelays) {
                        sendToRelay(event, webSocket, relayUrl)
                    }
                }
            }
        }
        
        override fun onMessage(webSocket: WebSocket, text: String) {
            handleMessage(text, relayUrl)
        }
        
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closing for $relayUrl: $code $reason")
        }
        
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.d(TAG, "WebSocket closed for $relayUrl: $code $reason")
            val error = Exception("WebSocket closed: $code $reason")
            handleDisconnection(relayUrl, error)
        }
        
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "❌ WebSocket failure for $relayUrl: ${t.message}")
            handleDisconnection(relayUrl, t)
        }
    }
}
