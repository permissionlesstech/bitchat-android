package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.model.NoisePayloadType
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Minimal Nostr transport for offline sending
 * Direct port from iOS NostrTransport for 100% compatibility
 */
class NostrTransport(
    private val context: Context,
    var senderPeerID: String = ""
) {
    
    companion object {
        private const val TAG = "NostrTransport"
        private const val READ_ACK_INTERVAL = 350L // ~3 per second (0.35s interval like iOS)
        
        @Volatile
        private var INSTANCE: NostrTransport? = null
        
        fun getInstance(context: Context): NostrTransport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NostrTransport(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Throttle READ receipts to avoid relay rate limits (like iOS)
    private data class QueuedRead(
        val receipt: ReadReceipt,
        val peerID: String
    )
    
    private val readQueue = ConcurrentLinkedQueue<QueuedRead>()
    private var isSendingReadAcks = false
    private val transportScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // MARK: - Transport Interface Methods
    
    val myPeerID: String get() = senderPeerID
    
    fun sendPrivateMessage(
        content: String,
        to: String,
        recipientNickname: String,
        messageID: String
    ) {
        transportScope.launch {
            try {
                // Resolve favorite by full noise key or by short peerID fallback
                var recipientNostrPubkey: String? = null
                
                // Try to resolve from favorites persistence service
                // This would need integration with the existing favorites system
                recipientNostrPubkey = resolveNostrPublicKey(to)
                
                if (recipientNostrPubkey == null) {
                    Log.w(TAG, "No Nostr public key found for peerID: $to")
                    return@launch
                }
                
                val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                if (senderIdentity == null) {
                    Log.e(TAG, "No Nostr identity available")
                    return@launch
                }
                
                Log.d(TAG, "NostrTransport: preparing PM to ${recipientNostrPubkey.take(16)}... for peerID ${to.take(8)}... id=${messageID.take(8)}...")
                
                // Convert recipient npub -> hex (x-only)
                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") {
                        Log.e(TAG, "NostrTransport: recipient key not npub (hrp=$hrp)")
                        return@launch
                    }
                    data.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    Log.e(TAG, "NostrTransport: failed to decode npub -> hex: $e")
                    return@launch
                }
                
                val embedded = NostrEmbeddedBitChat.encodePMForNostr(
                    content = content,
                    messageID = messageID,
                    recipientPeerID = to,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) {
                    Log.e(TAG, "NostrTransport: failed to embed PM packet")
                    return@launch
                }
                
                val event = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )
                
                Log.d(TAG, "NostrTransport: sending PM giftWrap id=${event.id.take(16)}...")
                NostrRelayManager.getInstance(context).sendEvent(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send private message via Nostr: ${e.message}")
            }
        }
    }
    
    fun sendReadReceipt(receipt: ReadReceipt, to: String) {
        // Enqueue and process with throttling to avoid relay rate limits
        readQueue.offer(QueuedRead(receipt, to))
        processReadQueueIfNeeded()
    }
    
    private fun processReadQueueIfNeeded() {
        if (isSendingReadAcks) return
        if (readQueue.isEmpty()) return
        
        isSendingReadAcks = true
        sendNextReadAck()
    }
    
    private fun sendNextReadAck() {
        val item = readQueue.poll()
        if (item == null) {
            isSendingReadAcks = false
            return
        }
        
        transportScope.launch {
            try {
                var recipientNostrPubkey: String? = null
                
                // Try to resolve from favorites persistence service
                recipientNostrPubkey = resolveNostrPublicKey(item.peerID)
                
                if (recipientNostrPubkey == null) {
                    Log.w(TAG, "No Nostr public key found for read receipt to: ${item.peerID}")
                    scheduleNextReadAck()
                    return@launch
                }
                
                val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                if (senderIdentity == null) {
                    Log.e(TAG, "No Nostr identity available for read receipt")
                    scheduleNextReadAck()
                    return@launch
                }
                
                Log.d(TAG, "NostrTransport: preparing READ ack for id=${item.receipt.originalMessageID.take(8)}... to ${recipientNostrPubkey.take(16)}...")
                
                // Convert recipient npub -> hex
                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") {
                        scheduleNextReadAck()
                        return@launch
                    }
                    data.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    scheduleNextReadAck()
                    return@launch
                }
                
                val ack = NostrEmbeddedBitChat.encodeAckForNostr(
                    type = NoisePayloadType.READ_RECEIPT,
                    messageID = item.receipt.originalMessageID,
                    recipientPeerID = item.peerID,
                    senderPeerID = senderPeerID
                )
                
                if (ack == null) {
                    Log.e(TAG, "NostrTransport: failed to embed READ ack")
                    scheduleNextReadAck()
                    return@launch
                }
                
                val event = NostrProtocol.createPrivateMessage(
                    content = ack,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )
                
                Log.d(TAG, "NostrTransport: sending READ ack giftWrap id=${event.id.take(16)}...")
                NostrRelayManager.getInstance(context).sendEvent(event)
                
                scheduleNextReadAck()
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt via Nostr: ${e.message}")
                scheduleNextReadAck()
            }
        }
    }
    
    private fun scheduleNextReadAck() {
        transportScope.launch {
            delay(READ_ACK_INTERVAL)
            isSendingReadAcks = false
            processReadQueueIfNeeded()
        }
    }
    
    fun sendFavoriteNotification(to: String, isFavorite: Boolean) {
        transportScope.launch {
            try {
                var recipientNostrPubkey: String? = null
                
                // Try to resolve from favorites persistence service
                recipientNostrPubkey = resolveNostrPublicKey(to)
                
                if (recipientNostrPubkey == null) {
                    Log.w(TAG, "No Nostr public key found for favorite notification to: $to")
                    return@launch
                }
                
                val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                if (senderIdentity == null) {
                    Log.e(TAG, "No Nostr identity available for favorite notification")
                    return@launch
                }
                
                val content = if (isFavorite) {
                    "[FAVORITED]:${senderIdentity.npub}"
                } else {
                    "[UNFAVORITED]:${senderIdentity.npub}"
                }
                
                Log.d(TAG, "NostrTransport: preparing FAVORITE($isFavorite) to ${recipientNostrPubkey.take(16)}...")
                
                // Convert recipient npub -> hex
                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") return@launch
                    data.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    return@launch
                }
                
                val embedded = NostrEmbeddedBitChat.encodePMForNostr(
                    content = content,
                    messageID = UUID.randomUUID().toString(),
                    recipientPeerID = to,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) {
                    Log.e(TAG, "NostrTransport: failed to embed favorite notification")
                    return@launch
                }
                
                val event = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )
                
                Log.d(TAG, "NostrTransport: sending favorite giftWrap id=${event.id.take(16)}...")
                NostrRelayManager.getInstance(context).sendEvent(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send favorite notification via Nostr: ${e.message}")
            }
        }
    }
    
    fun sendDeliveryAck(messageID: String, to: String) {
        transportScope.launch {
            try {
                var recipientNostrPubkey: String? = null
                
                // Try to resolve from favorites persistence service
                recipientNostrPubkey = resolveNostrPublicKey(to)
                
                if (recipientNostrPubkey == null) {
                    Log.w(TAG, "No Nostr public key found for delivery ack to: $to")
                    return@launch
                }
                
                val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                if (senderIdentity == null) {
                    Log.e(TAG, "No Nostr identity available for delivery ack")
                    return@launch
                }
                
                Log.d(TAG, "NostrTransport: preparing DELIVERED ack for id=${messageID.take(8)}... to ${recipientNostrPubkey.take(16)}...")
                
                val recipientHex = try {
                    val (hrp, data) = Bech32.decode(recipientNostrPubkey)
                    if (hrp != "npub") return@launch
                    data.joinToString("") { "%02x".format(it) }
                } catch (e: Exception) {
                    return@launch
                }
                
                val ack = NostrEmbeddedBitChat.encodeAckForNostr(
                    type = NoisePayloadType.DELIVERED,
                    messageID = messageID,
                    recipientPeerID = to,
                    senderPeerID = senderPeerID
                )
                
                if (ack == null) {
                    Log.e(TAG, "NostrTransport: failed to embed DELIVERED ack")
                    return@launch
                }
                
                val event = NostrProtocol.createPrivateMessage(
                    content = ack,
                    recipientPubkey = recipientHex,
                    senderIdentity = senderIdentity
                )
                
                Log.d(TAG, "NostrTransport: sending DELIVERED ack giftWrap id=${event.id.take(16)}...")
                NostrRelayManager.getInstance(context).sendEvent(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send delivery ack via Nostr: ${e.message}")
            }
        }
    }
    
    // MARK: - Geohash ACK helpers (for per-geohash identity DMs)
    
    fun sendDeliveryAckGeohash(
        messageID: String,
        toRecipientHex: String,
        fromIdentity: NostrIdentity
    ) {
        transportScope.launch {
            try {
                Log.d(TAG, "GeoDM: send DELIVERED -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}...")
                
                val embedded = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(
                    type = NoisePayloadType.DELIVERED,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) return@launch
                
                val event = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )
                
                // Register pending gift wrap for deduplication (like iOS)
                NostrRelayManager.registerPendingGiftWrap(event.id)
                NostrRelayManager.getInstance(context).sendEvent(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash delivery ack: ${e.message}")
            }
        }
    }
    
    fun sendReadReceiptGeohash(
        messageID: String,
        toRecipientHex: String,
        fromIdentity: NostrIdentity
    ) {
        transportScope.launch {
            try {
                Log.d(TAG, "GeoDM: send READ -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}...")
                
                val embedded = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(
                    type = NoisePayloadType.READ_RECEIPT,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) return@launch
                
                val event = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )
                
                // Register pending gift wrap for deduplication (like iOS)
                NostrRelayManager.registerPendingGiftWrap(event.id)
                NostrRelayManager.getInstance(context).sendEvent(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash read receipt: ${e.message}")
            }
        }
    }
    
    // MARK: - Geohash DMs (per-geohash identity)
    
    fun sendPrivateMessageGeohash(
        content: String,
        toRecipientHex: String,
        fromIdentity: NostrIdentity,
        messageID: String
    ) {
        transportScope.launch {
            try {
                if (toRecipientHex.isEmpty()) return@launch
                
                Log.d(TAG, "GeoDM: send PM -> recip=${toRecipientHex.take(8)}... mid=${messageID.take(8)}... from=${fromIdentity.publicKeyHex.take(8)}...")
                
                // Build embedded BitChat packet without recipient peer ID
                val embedded = NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                    content = content,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) {
                    Log.e(TAG, "NostrTransport: failed to embed geohash PM packet")
                    return@launch
                }
                
                val event = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )
                
                Log.d(TAG, "NostrTransport: sending geohash PM giftWrap id=${event.id.take(16)}...")
                
                // Register pending gift wrap for deduplication (like iOS)
                NostrRelayManager.registerPendingGiftWrap(event.id)
                NostrRelayManager.getInstance(context).sendEvent(event)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash private message: ${e.message}")
            }
        }
    }
    
    // MARK: - Helper Methods
    
    /**
     * Resolve Nostr public key for a peer ID
     */
    private fun resolveNostrPublicKey(peerID: String): String? {
        try {
            // Try to resolve from favorites persistence service
            val noiseKey = hexStringToByteArray(peerID)
            val favoriteStatus = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
            if (favoriteStatus?.peerNostrPublicKey != null) {
                return favoriteStatus.peerNostrPublicKey
            }
            
            // Fallback: try with 16-hex peerID lookup
            if (peerID.length == 16) {
                val fallbackStatus = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                return fallbackStatus?.peerNostrPublicKey
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Nostr public key for $peerID: ${e.message}")
            return null
        }
    }
    
    /**
     * Convert hex string to byte array (8 bytes)
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        if (hexString.length % 2 != 0) {
            return ByteArray(8) // Return 8-byte array filled with zeros
        }
        
        val result = ByteArray(8) { 0 }
        var tempID = hexString
        var index = 0
        
        while (tempID.length >= 2 && index < 8) {
            val hexByte = tempID.substring(0, 2)
            val byte = hexByte.toIntOrNull(16)?.toByte()
            if (byte != null) {
                result[index] = byte
            }
            tempID = tempID.substring(2)
            index++
        }
        
        return result
    }
    
    fun cleanup() {
        transportScope.cancel()
    }
}
