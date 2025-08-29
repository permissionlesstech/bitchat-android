package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*

/**
 * Test manager for Nostr functionality
 * Use this to verify the Nostr client works correctly
 */
class NostrTestManager(private val context: Context) {
    
    companion object {
        private const val TAG = "NostrTestManager"
    }
    
    private val testScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private lateinit var nostrClient: NostrClient
    
    /**
     * Run comprehensive Nostr tests
     */
    fun runTests() {
        Log.i(TAG, "🧪 Starting Nostr functionality tests...")
        
        testScope.launch {
            try {
                // Test 1: Initialize client
                testClientInitialization()
                
                // Test 2: Test identity generation and storage
                testIdentityManagement()
                
                // Test 3: Test relay connections
                testRelayConnections()
                
                // Test 4: Test cryptography
                testCryptography()
                
                // Test 5: Test Bech32 encoding
                testBech32()
                
                // Test 6: Test message subscription (without sending)
                testMessageSubscription()
                
                Log.i(TAG, "✅ All Nostr tests completed successfully!")
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Nostr tests failed: ${e.message}", e)
            }
        }
    }
    
    private suspend fun testClientInitialization() {
        Log.d(TAG, "Testing client initialization...")
        
        nostrClient = NostrClient.getInstance(context)
        nostrClient.initialize()
        
        // Wait for initialization
        delay(2000)
        
        val isInitialized = nostrClient.isInitialized.value ?: false
        require(isInitialized) { "Client failed to initialize" }
        
        Log.d(TAG, "✅ Client initialization successful")
    }
    
    private suspend fun testIdentityManagement() {
        Log.d(TAG, "Testing identity management...")
        
        // Test current identity
        val identity = nostrClient.getCurrentIdentity()
        requireNotNull(identity) { "No current identity" }
        
        Log.d(TAG, "Current identity npub: ${identity.getShortNpub()}")
        require(identity.npub.startsWith("npub1")) { "Invalid npub format" }
        require(identity.publicKeyHex.length == 64) { "Invalid public key length" }
        require(identity.privateKeyHex.length == 64) { "Invalid private key length" }
        
        // Test geohash identity derivation
        val geohashIdentity = NostrIdentityBridge.deriveIdentity("u4pruydq", context)
        require(geohashIdentity.npub.startsWith("npub1")) { "Invalid geohash identity npub" }
        require(geohashIdentity.publicKeyHex != identity.publicKeyHex) { "Geohash identity should be different" }
        
        Log.d(TAG, "Geohash identity npub: ${geohashIdentity.getShortNpub()}")
        Log.d(TAG, "✅ Identity management test successful")
    }
    
    private suspend fun testRelayConnections() {
        Log.d(TAG, "Testing relay connections...")
        
        // Wait for potential relay connections
        delay(3000)
        
        val relayInfo = nostrClient.relayInfo.value ?: emptyList()
        require(relayInfo.isNotEmpty()) { "No relays configured" }
        
        Log.d(TAG, "Configured relays: ${relayInfo.size}")
        relayInfo.forEach { relay ->
            Log.d(TAG, "Relay: ${relay.url} - Connected: ${relay.isConnected}")
        }
        
        Log.d(TAG, "✅ Relay configuration test successful")
    }
    
    private suspend fun testCryptography() {
        Log.d(TAG, "Testing cryptography functions...")
        
        // Test key generation
        val (privateKey, publicKey) = NostrCrypto.generateKeyPair()
        require(privateKey.length == 64) { "Invalid private key length" }
        require(publicKey.length == 64) { "Invalid public key length" }
        require(NostrCrypto.isValidPrivateKey(privateKey)) { "Generated private key is invalid" }
        require(NostrCrypto.isValidPublicKey(publicKey)) { "Generated public key is invalid" }
        
        // Test key derivation
        val derivedPublic = NostrCrypto.derivePublicKey(privateKey)
        require(derivedPublic == publicKey) { "Key derivation mismatch" }
        
        // Test encryption/decryption
        val (recipientPrivate, recipientPublic) = NostrCrypto.generateKeyPair()
        val plaintext = "Hello, Nostr world! This is a test message."
        
        val encrypted = NostrCrypto.encryptNIP44(
            plaintext,
            recipientPublic,
            privateKey
        )
        require(encrypted.isNotEmpty()) { "Encryption failed" }

        val decrypted = NostrCrypto.decryptNIP44(encrypted, publicKey, recipientPrivate)
        require(decrypted == plaintext) { "Decryption failed: expected '$plaintext', got '$decrypted'" }
        
        Log.d(TAG, "✅ Cryptography test successful")
    }
    
    private suspend fun testBech32() {
        Log.d(TAG, "Testing Bech32 encoding...")
        
        val testData = "hello world test data for bech32".toByteArray()
        val encoded = Bech32.encode("test", testData)
        require(encoded.startsWith("test1")) { "Invalid bech32 encoding" }
        
        val (hrp, decoded) = Bech32.decode(encoded)
        require(hrp == "test") { "HRP mismatch" }
        require(decoded.contentEquals(testData)) { "Data mismatch after decode" }
        
        // Test with actual public key
        val (_, publicKey) = NostrCrypto.generateKeyPair()
        val npub = Bech32.encode("npub", publicKey.hexToByteArray())
        require(npub.startsWith("npub1")) { "Invalid npub encoding" }
        
        val (npubHrp, npubData) = Bech32.decode(npub)
        require(npubHrp == "npub") { "npub HRP mismatch" }
        require(npubData.toHexString() == publicKey) { "npub data mismatch" }
        
        Log.d(TAG, "✅ Bech32 test successful")
    }
    
    private suspend fun testMessageSubscription() {
        Log.d(TAG, "Testing message subscription...")
        
        var messageReceived = false
        
        // Subscribe to private messages (won't receive any in test, but tests the subscription mechanism)
        nostrClient.subscribeToPrivateMessages { content, senderNpub, timestamp ->
            Log.d(TAG, "📥 Received test private message from $senderNpub: $content")
            messageReceived = true
        }
        
        // Subscribe to a test geohash
        nostrClient.subscribeToGeohash("u4pru") { content, senderPubkey, nickname, timestamp ->
            Log.d(TAG, "📥 Received test geohash message from ${senderPubkey.take(16)}...: $content")
            messageReceived = true
        }
        
        // Wait a bit to see if any messages come through
        delay(2000)
        
        Log.d(TAG, "✅ Message subscription test successful (no messages expected in test)")
    }
    
    /**
     * Test sending a message to yourself (loopback test)
     */
    fun testLoopbackMessage() {
        testScope.launch {
            try {
                val identity = nostrClient.getCurrentIdentity()
                requireNotNull(identity) { "No identity available for loopback test" }
                
                Log.i(TAG, "🔄 Testing loopback private message...")
                
                // Send message to ourselves
                nostrClient.sendPrivateMessage(
                    content = "Test loopback message at ${System.currentTimeMillis()}",
                    recipientNpub = identity.npub,
                    onSuccess = {
                        Log.i(TAG, "✅ Loopback message sent successfully")
                    },
                    onError = { error ->
                        Log.e(TAG, "❌ Loopback message failed: $error")
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Loopback test failed: ${e.message}", e)
            }
        }
    }
    
    /**
     * Test sending a geohash message
     */
    fun testGeohashMessage() {
        testScope.launch {
            try {
                Log.i(TAG, "🌍 Testing geohash message...")
                
                nostrClient.sendGeohashMessage(
                    content = "Test geohash message from Android at ${System.currentTimeMillis()}",
                    geohash = "u4pru",
                    nickname = "android-test",
                    onSuccess = {
                        Log.i(TAG, "✅ Geohash message sent successfully")
                    },
                    onError = { error ->
                        Log.e(TAG, "❌ Geohash message failed: $error")
                    }
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "❌ Geohash test failed: ${e.message}", e)
            }
        }
    }
    
    /**
     * Get debug information about the Nostr client
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Nostr Client Debug Info ===")
            
            val identity = nostrClient.getCurrentIdentity()
            if (identity != null) {
                appendLine("Identity: ${identity.getShortNpub()}")
                appendLine("Public Key: ${identity.publicKeyHex.take(16)}...")
                appendLine("Created: ${java.util.Date(identity.createdAt)}")
            } else {
                appendLine("No identity loaded")
            }
            
            val isInitialized = nostrClient.isInitialized.value ?: false
            appendLine("Initialized: $isInitialized")
            
            val isConnected = nostrClient.relayConnectionStatus.value ?: false
            appendLine("Relay Connected: $isConnected")
            
            val relays = nostrClient.relayInfo.value ?: emptyList()
            appendLine("Relays (${relays.size}):")
            relays.forEach { relay ->
                appendLine("  ${relay.url}: ${if (relay.isConnected) "✅" else "❌"} (sent: ${relay.messagesSent}, received: ${relay.messagesReceived})")
            }
        }
    }
    
    /**
     * Shutdown test manager
     */
    fun shutdown() {
        testScope.cancel()
        nostrClient.shutdown()
    }
}
