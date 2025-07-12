package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.crypto.MessagePadding
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.*
import java.util.*
import kotlin.random.Random

/**
 * Handles processing of different message types
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class MessageHandler(private val myPeerID: String) {
    
    companion object {
        private const val TAG = "MessageHandler"
    }
    
    // Delegate for callbacks
    var delegate: MessageHandlerDelegate? = null
    
    // Coroutines
    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Handle announce message
     */
    suspend fun handleAnnounce(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        if (peerID == myPeerID) return false
        
        val nickname = String(packet.payload, Charsets.UTF_8)
        Log.d(TAG, "Received announce from $peerID: $nickname")
        
        // Notify delegate to handle peer management
        val isFirstAnnounce = delegate?.addOrUpdatePeer(peerID, nickname) ?: false
        
        // Relay announce if TTL > 0
        if (packet.ttl > 1u) {
            val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
            delay(Random.nextLong(100, 300))
            delegate?.relayPacket(RoutedPacket(relayPacket, peerID, routed.relayAddress))
        }
        
        return isFirstAnnounce
    }
    
    /**
     * Handle broadcast or private message
     */
    suspend fun handleMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (peerID == myPeerID) return
        
        val recipientID = packet.recipientID?.takeIf { !it.contentEquals(delegate?.getBroadcastRecipient()) }
        
        if (recipientID == null) {
            // BROADCAST MESSAGE
            handleBroadcastMessage(routed)
        } else if (String(recipientID).replace("\u0000", "") == myPeerID) {
            // PRIVATE MESSAGE FOR US
            handlePrivateMessage(packet, peerID)
        } else if (packet.ttl > 0u) {
            // RELAY MESSAGE
            relayMessage(routed)
        }
    }
    
    /**
     * Handle broadcast message
     */
    private suspend fun handleBroadcastMessage(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        try {
            // Parse message
            val message = BitchatMessage.fromBinaryPayload(packet.payload)
            if (message != null) {
                // Check for cover traffic (dummy messages)
                if (message.content.startsWith("☂DUMMY☂")) {
                    Log.d(TAG, "Discarding cover traffic from $peerID")
                    return // Silently discard
                }
                
                delegate?.updatePeerNickname(peerID, message.sender)
                
                // Handle encrypted channel messages
                val finalContent = if (message.channel != null && message.isEncrypted && message.encryptedContent != null) {
                    delegate?.decryptChannelMessage(message.encryptedContent, message.channel)
                    ?: "[Encrypted message - password required]"
                } else {
                    message.content
                }
                
                // Replace timestamp with current time (same as iOS)
                val messageWithCurrentTime = message.copy(
                    content = finalContent,
                    senderPeerID = peerID,
                    timestamp = Date() // Use current time instead of original timestamp
                )
                
                delegate?.onMessageReceived(messageWithCurrentTime)
            }
            
            // Relay broadcast messages
            relayMessage(routed)
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process broadcast message: ${e.message}")
        }
    }
    
    /**
     * Handle private message addressed to us
     */
    private suspend fun handlePrivateMessage(packet: BitchatPacket, peerID: String) {
        try {
            // Verify signature if present
            if (packet.signature != null && !delegate?.verifySignature(packet, peerID)!!) {
                Log.w(TAG, "Invalid signature for private message from $peerID")
                return
            }
            
            // Decrypt message
            val decryptedData = delegate?.decryptFromPeer(packet.payload, peerID)
            if (decryptedData == null) {
                Log.e(TAG, "Failed to decrypt private message from $peerID")
                return
            }
            
            val unpaddedData = MessagePadding.unpad(decryptedData)
            
            // Parse message
            val message = BitchatMessage.fromBinaryPayload(unpaddedData)
            if (message != null) {
                // Check for cover traffic (dummy messages)
                if (message.content.startsWith("☂DUMMY☂")) {
                    Log.d(TAG, "Discarding private cover traffic from $peerID")
                    return // Silently discard
                }
                
                delegate?.updatePeerNickname(peerID, message.sender)
    
                // Replace timestamp with current time (same as iOS)
                val messageWithCurrentTime = message.copy(
                    senderPeerID = peerID,
                    timestamp = Date() // Use current time instead of original timestamp
                )
                
                delegate?.onMessageReceived(messageWithCurrentTime)
                
                // Send delivery ACK
                sendDeliveryAck(message, peerID)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process private message from $peerID: ${e.message}")
        }
    }
    
    /**
     * Handle leave message
     */
    suspend fun handleLeave(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val content = String(packet.payload, Charsets.UTF_8)
        
        if (content.startsWith("#")) {
            // Channel leave
            delegate?.onChannelLeave(content, peerID)
        } else {
            // Peer disconnect
            val nickname = delegate?.getPeerNickname(peerID)
            delegate?.removePeer(peerID)
            if (nickname != null) {
                delegate?.onPeerDisconnected(nickname)
            }
        }
        
        // Relay if TTL > 0
        if (packet.ttl > 1u) {
            val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
            delegate?.relayPacket(routed.copy(packet = relayPacket))
        }
    }
    
    /**
     * Handle delivery acknowledgment
     */
    suspend fun handleDeliveryAck(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (packet.recipientID != null && String(packet.recipientID).replace("\u0000", "") == myPeerID) {
            try {
                val decryptedData = delegate?.decryptFromPeer(packet.payload, peerID)
                if (decryptedData != null) {
                    val ack = DeliveryAck.decode(decryptedData)
                    if (ack != null) {
                        delegate?.onDeliveryAckReceived(ack)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt delivery ACK: ${e.message}")
            }
        } else if (packet.ttl > 0u && String(packet.senderID).replace("\u0000", "") != myPeerID) {
            // Relay 
            val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
            delegate?.relayPacket(routed.copy(packet = relayPacket))
        }
    }
    
    /**
     * Handle read receipt
     */
    suspend fun handleReadReceipt(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        if (packet.recipientID != null && String(packet.recipientID).replace("\u0000", "") == myPeerID) {
            try {
                val decryptedData = delegate?.decryptFromPeer(packet.payload, peerID)
                if (decryptedData != null) {
                    val receipt = ReadReceipt.decode(decryptedData)
                    if (receipt != null) {
                        delegate?.onReadReceiptReceived(receipt)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decrypt read receipt: ${e.message}")
            }
        } else if (packet.ttl > 0u && String(packet.senderID).replace("\u0000", "") != myPeerID) {
            // Relay
            val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
            delegate?.relayPacket(routed.copy(packet = relayPacket))
        }
    }
    
    /**
     * Relay message with adaptive probability (same as iOS)
     */
    private suspend fun relayMessage(routed: RoutedPacket) {
        val packet = routed.packet
        if (packet.ttl == 0u.toUByte()) return
        
        val relayPacket = packet.copy(ttl = (packet.ttl - 1u).toUByte())
        
        // Check network size and apply adaptive relay probability
        val networkSize = delegate?.getNetworkSize() ?: 1
        val relayProb = when {
            networkSize <= 10 -> 1.0
            networkSize <= 30 -> 0.85
            networkSize <= 50 -> 0.7
            networkSize <= 100 -> 0.55
            else -> 0.4
        }
        
        val shouldRelay = relayPacket.ttl >= 4u || networkSize <= 3 || Random.nextDouble() < relayProb
        
        if (shouldRelay) {
            val delay = Random.nextLong(50, 500) // Random delay like iOS
            delay(delay)
            delegate?.relayPacket(routed.copy(packet = relayPacket))
        }
    }
    
    /**
     * Send delivery acknowledgment for a received private message
     */
    private fun sendDeliveryAck(message: BitchatMessage, senderPeerID: String) {
        handlerScope.launch {
            val nickname = delegate?.getMyNickname() ?: myPeerID
            val ack = DeliveryAck(
                originalMessageID = message.id,
                recipientID = myPeerID,
                recipientNickname = nickname,
                hopCount = 0u // Will be calculated during relay
            )
            
            try {
                val ackData = ack.encode() ?: return@launch
                val encryptedPayload = delegate?.encryptForPeer(ackData, senderPeerID)
                if (encryptedPayload != null) {
                    val packet = BitchatPacket(
                        type = MessageType.DELIVERY_ACK.value,
                        senderID = myPeerID.toByteArray(),
                        recipientID = senderPeerID.toByteArray(),
                        timestamp = System.currentTimeMillis().toULong(),
                        payload = encryptedPayload,
                        signature = null,
                        ttl = 3u
                    )
                    
                    delegate?.sendPacket(packet)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send delivery ACK: ${e.message}")
            }
        }
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Message Handler Debug Info ===")
            appendLine("Handler Scope Active: ${handlerScope.isActive}")
            appendLine("My Peer ID: $myPeerID")
        }
    }
    
    /**
     * Shutdown the handler
     */
    fun shutdown() {
        handlerScope.cancel()
    }
}

/**
 * Delegate interface for message handler callbacks
 */
interface MessageHandlerDelegate {
    // Peer management
    fun addOrUpdatePeer(peerID: String, nickname: String): Boolean
    fun removePeer(peerID: String)
    fun updatePeerNickname(peerID: String, nickname: String)
    fun getPeerNickname(peerID: String): String?
    fun getNetworkSize(): Int
    fun getMyNickname(): String?
    
    // Packet operations
    fun sendPacket(packet: BitchatPacket)
    fun relayPacket(routed: RoutedPacket)
    fun getBroadcastRecipient(): ByteArray
    
    // Cryptographic operations
    fun verifySignature(packet: BitchatPacket, peerID: String): Boolean
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray?
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): ByteArray?
    
    // Message operations
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    
    // Callbacks
    fun onMessageReceived(message: BitchatMessage)
    fun onChannelLeave(channel: String, fromPeer: String)
    fun onPeerDisconnected(nickname: String)
    fun onDeliveryAckReceived(ack: DeliveryAck)
    fun onReadReceiptReceived(receipt: ReadReceipt)
}
