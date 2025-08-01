package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryAck
import com.bitchat.android.model.NoiseIdentityAnnouncement
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.util.toHexString
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
    
    // Reference to PacketProcessor for recursive packet handling
    var packetProcessor: PacketProcessor? = null
    
    // Coroutines
    private val handlerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    /**
     * Handle Noise encrypted transport message
     */
    suspend fun handleNoiseEncrypted(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.d(TAG, "Processing Noise encrypted message from $peerID (${packet.payload.size} bytes)")
        
        // Skip our own messages
        if (peerID == myPeerID) return
        
        try {
            // Decrypt the message using the Noise service
            val decryptedData = delegate?.decryptFromPeer(packet.payload, peerID)
            if (decryptedData == null) {
                Log.w(TAG, "Failed to decrypt Noise message from $peerID - may need handshake")
                return
            }
            
            // Check if it's a special format message (type marker + payload)
            if (decryptedData.size > 1) {
                val typeMarker = decryptedData[0].toUByte()
                
                // Check if this is a delivery ACK with the new format
                if (typeMarker == MessageType.DELIVERY_ACK.value) {
                    handleDeliveryAck(decryptedData)
                    Log.d(TAG, "Processed delivery ACK from $peerID")
                }
                
                // Check for read receipt with type marker
                if (typeMarker == MessageType.READ_RECEIPT.value) {
                    val receiptData = decryptedData.sliceArray(1 until decryptedData.size)
                    val receipt = ReadReceipt.decode(receiptData)
                    if (receipt != null) {
                        delegate?.onReadReceiptReceived(receipt)
                        Log.d(TAG, "Processed read receipt from $peerID")
                        return
                    }
                }
            }
            
            // Try to parse as a full inner packet (for compatibility with other message types)
            val innerPacket = BitchatPacket.fromBinaryData(decryptedData)
            if (innerPacket != null) {
                Log.d(TAG, "Decrypted inner packet type ${innerPacket.type} from $peerID")
                
                // Create a new routed packet with the decrypted inner packet
                val innerRouted = RoutedPacket(innerPacket, peerID, routed.relayAddress)
                
                // Use PacketProcessor to handle the inner packet recursively
                if (packetProcessor != null) {
                    packetProcessor!!.processPacket(innerRouted)
                } else {
                    Log.w(TAG, "PacketProcessor reference is null; cannot recursively process inner packet.")
                }
            } else {
                Log.w(TAG, "Failed to parse decrypted data as packet from $peerID")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Noise encrypted message from $peerID: ${e.message}")
        }
    }
    
    /**
     * Handle Noise identity announcement - supports peer ID rotation
     */
    suspend fun handleNoiseIdentityAnnouncement(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        
        Log.d(TAG, "Processing Noise identity announcement from $peerID (${packet.payload.size} bytes)")
        
        // Skip our own announcements
        if (peerID == myPeerID) return
        
        try {
            // Parse the identity announcement
            val announcement = NoiseIdentityAnnouncement.fromBinaryData(packet.payload)
            if (announcement == null) {
                Log.w(TAG, "Failed to parse Noise identity announcement from $peerID")
                return
            }
            
            Log.d(TAG, "Parsed identity announcement: peerID=${announcement.peerID}, " +
                    "nickname=${announcement.nickname}, fingerprint=${announcement.fingerprint?.take(16)}...")
            
            // Verify the announcement signature using Ed25519 (iOS compatibility)
            if (announcement.signature.isEmpty()) {
                Log.w(TAG, "❌ Identity announcement from $peerID has no signature - rejecting")
                return
            }
            
            // Verify signature using the same format as iOS
            val timestampMs = announcement.timestamp.time
            val bindingData = announcement.peerID.toByteArray(Charsets.UTF_8) + 
                            announcement.publicKey + 
                            timestampMs.toString().toByteArray(Charsets.UTF_8)
            
            val isSignatureValid = delegate?.verifyEd25519Signature(announcement.signature, bindingData, announcement.signingPublicKey) ?: false
            
            if (!isSignatureValid) {
                Log.w(TAG, "❌ Signature verification failed for identity announcement from $peerID - rejecting")
                return
            }
            
            Log.d(TAG, "✅ Signature verification successful for identity announcement from $peerID")
            
            // Update peer binding in the delegate (ChatViewModel/BluetoothMeshService)
            delegate?.updatePeerIDBinding(
                newPeerID = announcement.peerID,
                nickname = announcement.nickname,
                publicKey = announcement.publicKey,
                previousPeerID = announcement.previousPeerID
            )
            
            // Check if we need to initiate a handshake with this peer
            val hasSession = delegate?.hasNoiseSession(announcement.peerID) ?: false
            if (!hasSession) {
                Log.d(TAG, "No session with ${announcement.peerID}, may need handshake")
                
                // Use lexicographic comparison to decide who initiates (prevents both sides from initiating)
                if (myPeerID < announcement.peerID) {
                    delegate?.initiateNoiseHandshake(announcement.peerID)
                }
            }
            
            Log.d(TAG, "Successfully processed identity announcement from $peerID")
            
        } catch (e: Exception) {
            Log.e(TAG, "Error processing Noise identity announcement from $peerID: ${e.message}")
        }
    }
    
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
        
        // Announce relay is now handled by centralized PacketRelayManager
        
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
        } else if (recipientID.toHexString() == myPeerID) {
            // PRIVATE MESSAGE FOR US
            handlePrivateMessage(packet, peerID)
        }
        // Message relay is now handled by centralized PacketRelayManager
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
            
            // Broadcast message relay is now handled by centralized PacketRelayManager
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process broadcast message: ${e.message}")
        }
    }
    
    /**
     * Handle (decrypted) private message addressed to us
     */
    private suspend fun handlePrivateMessage(packet: BitchatPacket, peerID: String) {
        try {
            // Verify signature if present
            if (packet.signature != null && !delegate?.verifySignature(packet, peerID)!!) {
                Log.w(TAG, "Invalid signature for private message from $peerID")
                return
            }

            // Parse message
            val message = BitchatMessage.fromBinaryPayload(packet.payload)
            if (message != null) {
                // Check for cover traffic (dummy messages)
                if (message.content.startsWith("☂DUMMY☂")) {
                    Log.d(TAG, "Discarding private cover traffic from $peerID")
                    return // Silently discard
                }
                
                delegate?.updatePeerNickname(peerID, message.sender)
                delegate?.onMessageReceived(message)
                
                // Send delivery ACK
                delegate?.sendDeliveryAck(message, peerID)
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
        
        // Leave message relay is now handled by centralized PacketRelayManager
    }
    
    /**
     * Handle delivery acknowledgment
     */
    suspend fun handleDeliveryAck(decryptedData: ByteArray) {
        val ackData = decryptedData.sliceArray(1 until decryptedData.size)
        val ack = DeliveryAck.decode(ackData)
        if (ack != null) {
            delegate?.onDeliveryAckReceived(ack)
        }
        return
    }
    
    /**
     * Handle read receipt
     */
    suspend fun handleReadReceipt(routed: RoutedPacket) {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"
        val receipt = ReadReceipt.decode(routed.packet.payload)
        if (receipt != null) {
            delegate?.onReadReceiptReceived(receipt)
        }
        return
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
     * Convert hex string peer ID to binary data (8 bytes) - same as iOS implementation
     */
    private fun hexStringToByteArray(hexString: String): ByteArray {
        val result = ByteArray(8) { 0 } // Initialize with zeros, exactly 8 bytes
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
    fun verifyEd25519Signature(signature: ByteArray, data: ByteArray, publicKey: ByteArray): Boolean
    
    // Noise protocol operations
    fun hasNoiseSession(peerID: String): Boolean
    fun initiateNoiseHandshake(peerID: String)
    fun updatePeerIDBinding(newPeerID: String, nickname: String,
                           publicKey: ByteArray, previousPeerID: String?)
    
    // Message operations
    fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String?
    fun sendDeliveryAck(message: BitchatMessage, senderPeerID: String)
    
    // Callbacks
    fun onMessageReceived(message: BitchatMessage)
    fun onChannelLeave(channel: String, fromPeer: String)
    fun onPeerDisconnected(nickname: String)
    fun onDeliveryAckReceived(ack: DeliveryAck)
    fun onReadReceiptReceived(receipt: ReadReceipt)
}
