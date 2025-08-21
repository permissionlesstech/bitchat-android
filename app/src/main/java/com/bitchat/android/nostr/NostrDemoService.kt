package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Demo service for testing Nostr implementation with event kind 10066
 */
class NostrDemoService private constructor(private val context: Context) {
    
    companion object {
        private const val TAG = "NostrDemoService"
        private const val DEMO_EVENT_KIND = 10066
        
        @Volatile
        private var INSTANCE: NostrDemoService? = null
        
        fun getInstance(context: Context): NostrDemoService {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NostrDemoService(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Core components - initialized lazily to avoid static initialization issues
    private val relayManager by lazy { NostrRelayManager.shared }
    private var currentIdentity: NostrIdentity? = null
    
    // Demo state
    private var demoActive = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Message handler callback
    private var onMessageReceived: ((String, String, Int) -> Unit)? = null
    
    /**
     * Initialize the demo service
     */
    fun initialize(onMessage: (content: String, senderNpub: String, timestamp: Int) -> Unit) {
        Log.d(TAG, "Initializing Nostr demo service with kind $DEMO_EVENT_KIND")
        
        onMessageReceived = onMessage
        
        scope.launch {
            try {
                // Get current identity using the existing bridge
                currentIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                
                if (currentIdentity != null) {
                    Log.i(TAG, "✅ Demo service identity: ${currentIdentity!!.getShortNpub()}")
                    
                    // CRITICAL: Connect to Nostr relays
                    Log.i(TAG, "🌐 Connecting to Nostr relays for demo...")
                    relayManager.connect()
                    
                    // Wait a moment for relay connections to establish
                    delay(2000)
                    
                    // Mark service as active
                    demoActive = true
                    
                    // Subscribe to our own kind 10066 events
                    subscribeToDemo()
                    
                    Log.i(TAG, "✅ Nostr demo service initialized and active with relay connections")
                } else {
                    Log.e(TAG, "❌ Failed to load identity for demo service")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to initialize demo service: ${e.message}")
            }
        }
    }
    
    /**
     * Publish a message as a kind 10066 Nostr event
     */
    fun publishDemoMessage(content: String) {
        val identity = currentIdentity
        if (!demoActive || identity == null) {
            Log.w(TAG, "Demo service not active, cannot publish message")
            return
        }
        
        scope.launch {
            try {
                // Check relay connections
                val relayStatuses = relayManager.getRelayStatuses()
                val connectedRelays = relayStatuses.count { it.isConnected }
                
                Log.i(TAG, "📡 Connected to $connectedRelays/${relayStatuses.size} Nostr relays")
                
                if (connectedRelays == 0) {
                    Log.w(TAG, "⚠️ No relay connections available for demo message")
                }
                
                // Create kind 10066 event
                val event = NostrEvent(
                    pubkey = identity.publicKeyHex,
                    createdAt = (System.currentTimeMillis() / 1000).toInt(),
                    kind = DEMO_EVENT_KIND,
                    tags = listOf(
                        listOf("demo", "bitchat-android"),
                        listOf("t", "demo") // topic tag
                    ),
                    content = content
                ).sign(identity.privateKeyHex)
                
                // Send to relays
                relayManager.sendEvent(event)
                
                Log.i(TAG, "📤 Published demo message (kind $DEMO_EVENT_KIND) to $connectedRelays relays: ${content.take(50)}...")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to publish demo message: ${e.message}")
            }
        }
    }
    
    /**
     * Subscribe to kind 10066 events from our own public key
     */
    private fun subscribeToDemo() {
        val identity = currentIdentity ?: return
        
        val filter = NostrFilter.Builder()
            .kinds(DEMO_EVENT_KIND)
            .authors(identity.publicKeyHex)
            .since(System.currentTimeMillis() - 3600000) // Last hour in milliseconds
            .limit(100)
            .build()
        
        relayManager.subscribe(filter, "demo-10066") { event ->
            scope.launch {
                handleDemoEvent(event)
            }
        }
        
        Log.i(TAG, "🔄 Subscribed to kind $DEMO_EVENT_KIND events from: ${identity.getShortNpub()}")
    }
    
    /**
     * Handle received kind 10066 events
     */
    private suspend fun handleDemoEvent(event: NostrEvent) {
        try {
            val identity = currentIdentity ?: return
            
            // Only process events from our own public key (for demo purposes)
            if (event.pubkey != identity.publicKeyHex) {
                return
            }
            
            // Convert pubkey to npub for display
            val senderNpub = try {
                Bech32.encode("npub", event.pubkey.hexToByteArray())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to encode npub: ${e.message}")
                "npub_error"
            }
            
            Log.i(TAG, "📥 Received demo event (kind ${event.kind}): ${event.content.take(50)}...")
            
            // Dispatch to main thread for UI callback
            withContext(Dispatchers.Main) {
                onMessageReceived?.invoke(event.content, senderNpub, event.createdAt)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error handling demo event: ${e.message}")
        }
    }
    
    /**
     * Check if demo service is active
     */
    fun isActive(): Boolean = demoActive
    
    /**
     * Get current identity info for demo
     */
    fun getCurrentNpub(): String? = currentIdentity?.npub
    
    /**
     * Get relay connection status for debugging
     */
    fun getRelayStatus(): String {
        val relayStatuses = relayManager.getRelayStatuses()
        val connected = relayStatuses.count { it.isConnected }
        val total = relayStatuses.size
        
        val statusDetails = relayStatuses.map { relay ->
            val status = if (relay.isConnected) "✅" else "❌"
            "$status ${relay.url.substringAfter("wss://")}"
        }.joinToString("\n")
        
        return "Connected: $connected/$total relays\n$statusDetails"
    }
    
    /**
     * Shutdown the demo service
     */
    fun shutdown() {
        Log.d(TAG, "Shutting down demo service")
        relayManager.unsubscribe("demo-10066")
        relayManager.disconnect()
        demoActive = false
    }
}
