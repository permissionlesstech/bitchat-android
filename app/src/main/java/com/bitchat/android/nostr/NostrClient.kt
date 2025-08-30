package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.*

/**
 * High-level Nostr client that manages identity, connections, and messaging
 * Provides a simple API for the rest of the application
 */
class NostrClient private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NostrClient"
        
        @Volatile
        private var INSTANCE: NostrClient? = null
        
        fun getInstance(context: Context): NostrClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NostrClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components
    private val relayManager = NostrRelayManager.shared
    private var currentIdentity: NostrIdentity? = null
    
    // Client state
    private val _isInitialized = MutableLiveData<Boolean>()
    val isInitialized: LiveData<Boolean> = _isInitialized
    
    private val _currentNpub = MutableLiveData<String>()
    val currentNpub: LiveData<String> = _currentNpub
    
    // Message processing
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    init {
        Log.d(TAG, "Initializing Nostr client")
    }
    
    /**
     * Initialize the Nostr client with identity and relay connections
     */
    fun initialize() {
        scope.launch {
            try {
                // Load or create identity
                currentIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                
                if (currentIdentity != null) {
                    _currentNpub.postValue(currentIdentity!!.npub)
                    Log.i(TAG, "✅ Nostr identity loaded: ${currentIdentity!!.getShortNpub()}")
                    
                    // Connect to relays
                    relayManager.connect()
                    
                    _isInitialized.postValue(true)
                    Log.i(TAG, "✅ Nostr client initialized successfully")
                } else {
                    Log.e(TAG, "❌ Failed to load/create Nostr identity")
                    _isInitialized.postValue(false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize Nostr client: ${e.message}")
                _isInitialized.postValue(false)
            }
        }
    }
    
    /**
     * Shutdown the client and disconnect from relays
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down Nostr client")
        relayManager.disconnect()
        _isInitialized.postValue(false)
    }
    
    /**
     * Send a private message using NIP-17
     */
    fun sendPrivateMessage(
        content: String,
        recipientNpub: String,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        val identity = currentIdentity
        if (identity == null) {
            onError?.invoke("Nostr client not initialized")
            return
        }
        
        scope.launch {
            try {
                // Decode recipient npub to hex pubkey
                val (hrp, pubkeyBytes) = Bech32.decode(recipientNpub)
                if (hrp != "npub") {
                    onError?.invoke("Invalid npub format")
                    return@launch
                }
                
                val recipientPubkeyHex = pubkeyBytes.toHexString()
                
                // Create and send gift wraps (receiver and sender copies)
                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = content,
                    recipientPubkey = recipientPubkeyHex,
                    senderIdentity = identity
                )
                
                // Track and send all gift wraps
                giftWraps.forEach { wrap ->
                    NostrRelayManager.registerPendingGiftWrap(wrap.id)
                    relayManager.sendEvent(wrap)
                }
                
                Log.i(TAG, "📤 Sent private message to ${recipientNpub.take(16)}...")
                onSuccess?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send private message: ${e.message}")
                onError?.invoke("Failed to send message: ${e.message}")
            }
        }
    }
    
    /**
     * Subscribe to private messages for current identity
     */
    fun subscribeToPrivateMessages(handler: (content: String, senderNpub: String, timestamp: Int) -> Unit) {
        val identity = currentIdentity
        if (identity == null) {
            Log.e(TAG, "Cannot subscribe to private messages: client not initialized")
            return
        }
        
        val filter = NostrFilter.giftWrapsFor(
            pubkey = identity.publicKeyHex,
            since = System.currentTimeMillis() - 172800000L // Last 48 hours (align with NIP-17 randomization)
        )
        
        relayManager.subscribe(filter, "private-messages", { giftWrap ->
            scope.launch {
                handlePrivateMessage(giftWrap, handler)
            }
        })
        
        Log.i(TAG, "🔑 Subscribed to private messages for: ${identity.getShortNpub()}")
    }
    
    /**
     * Send a public message to a geohash channel
     */
    fun sendGeohashMessage(
        content: String,
        geohash: String,
        nickname: String? = null,
        onSuccess: (() -> Unit)? = null,
        onError: ((String) -> Unit)? = null
    ) {
        scope.launch {
            try {
                // Derive geohash-specific identity
                val geohashIdentity = NostrIdentityBridge.deriveIdentity(geohash, context)
                
                // Create ephemeral event (with PoW if enabled)
                val event = NostrProtocol.createEphemeralGeohashEvent(
                    content = content,
                    geohash = geohash,
                    senderIdentity = geohashIdentity,
                    nickname = nickname
                )
                
                relayManager.sendEvent(event)
                
                Log.i(TAG, "📤 Sent geohash message to #$geohash")
                onSuccess?.invoke()
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to send geohash message: ${e.message}")
                onError?.invoke("Failed to send message: ${e.message}")
            }
        }
    }
    
    /**
     * Subscribe to public messages in a geohash channel
     */
    fun subscribeToGeohash(
        geohash: String,
        handler: (content: String, senderPubkey: String, nickname: String?, timestamp: Int) -> Unit
    ) {
        val filter = NostrFilter.geohashEphemeral(
            geohash = geohash,
            since = System.currentTimeMillis() - 3600000L, // Last hour
            limit = 200
        )
        
        relayManager.subscribe(filter, "geohash-$geohash", { event ->
            scope.launch {
                handleGeohashMessage(event, handler)
            }
        })
        
        Log.i(TAG, "🌍 Subscribed to geohash channel: #$geohash")
    }
    
    /**
     * Unsubscribe from a geohash channel
     */
    fun unsubscribeFromGeohash(geohash: String) {
        relayManager.unsubscribe("geohash-$geohash")
        Log.i(TAG, "Unsubscribed from geohash channel: #$geohash")
    }
    
    /**
     * Get current identity information
     */
    fun getCurrentIdentity(): NostrIdentity? = currentIdentity
    
    /**
     * Get relay connection status
     */
    val relayConnectionStatus: LiveData<Boolean> = relayManager.isConnected
    
    /**
     * Get relay information
     */
    val relayInfo: LiveData<List<NostrRelayManager.Relay>> = relayManager.relays
    
    // MARK: - Private Methods
    
    private suspend fun handlePrivateMessage(
        giftWrap: NostrEvent,
        handler: (content: String, senderNpub: String, timestamp: Int) -> Unit
    ) {
        // Age filtering (24h + 15min buffer for randomized timestamps)
        val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
        if (messageAge > 173700) { // 48 hours + 15 minutes
            Log.v(TAG, "Ignoring old private message")
            return
        }
        
        val identity = currentIdentity ?: return
        
        try {
            val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
            if (decryptResult != null) {
                val (content, senderPubkey, timestamp) = decryptResult
                
                // Convert sender pubkey to npub
                val senderNpub = try {
                    Bech32.encode("npub", senderPubkey.hexToByteArray())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to encode sender npub: ${e.message}")
                    "npub_decode_error"
                }
                
                Log.d(TAG, "📥 Received private message from ${senderNpub.take(16)}...")
                
                // Dispatch to main thread for handler
                withContext(Dispatchers.Main) {
                    handler(content, senderNpub, timestamp)
                }
            } else {
                Log.w(TAG, "Failed to decrypt private message")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling private message: ${e.message}")
        }
    }
    
    private suspend fun handleGeohashMessage(
        event: NostrEvent,
        handler: (content: String, senderPubkey: String, nickname: String?, timestamp: Int) -> Unit
    ) {
        try {
            // Check Proof of Work validation for incoming geohash events
            val powSettings = PoWPreferenceManager.getCurrentSettings()
            if (powSettings.enabled && powSettings.difficulty > 0) {
                if (!NostrProofOfWork.validateDifficulty(event, powSettings.difficulty)) {
                    Log.w(TAG, "🚫 Rejecting geohash event ${event.id.take(8)}... due to insufficient PoW (required: ${powSettings.difficulty})")
                    return
                }
                Log.v(TAG, "✅ PoW validation passed for geohash event ${event.id.take(8)}...")
            }
            
            // Extract nickname from tags
            val nickname = event.tags.find { it.size >= 2 && it[0] == "n" }?.get(1)
            
            Log.v(TAG, "📥 Received geohash message from ${event.pubkey.take(16)}...")
            
            // Dispatch to main thread for handler
            withContext(Dispatchers.Main) {
                handler(event.content, event.pubkey, nickname, event.createdAt)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling geohash message: ${e.message}")
        }
    }
}
