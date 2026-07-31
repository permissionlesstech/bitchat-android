package com.bitchat.android.mesh

import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.util.toHexString
import kotlinx.coroutines.*
import java.util.*
import kotlin.collections.mutableSetOf

/**
 * Manages security aspects of the mesh network including duplicate detection,
 * replay attack protection, and key exchange handling
 * Extracted from BluetoothMeshService for better separation of concerns
 */
class SecurityManager(private val encryptionService: EncryptionService, private val myPeerID: String) {
    
    companion object {
        private const val TAG = "SecurityManager"
        private const val MESSAGE_TIMEOUT = com.bitchat.android.util.AppConstants.Security.MESSAGE_TIMEOUT_MS // 5 minutes (same as iOS)
        private const val CLEANUP_INTERVAL = com.bitchat.android.util.AppConstants.Security.CLEANUP_INTERVAL_MS // 5 minutes
        private const val MAX_PROCESSED_MESSAGES = com.bitchat.android.util.AppConstants.Security.MAX_PROCESSED_MESSAGES
        private const val MAX_PROCESSED_KEY_EXCHANGES = com.bitchat.android.util.AppConstants.Security.MAX_PROCESSED_KEY_EXCHANGES
        private const val KEY_EXCHANGE_DEDUP_TIMEOUT = com.bitchat.android.util.AppConstants.Security.KEY_EXCHANGE_DEDUP_TIMEOUT_MS
    }
    
    // Security tracking
    private val processedMessages = Collections.synchronizedSet(mutableSetOf<String>())
    private val processedKeyExchanges = Collections.synchronizedSet(mutableSetOf<String>())
    private val messageTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val keyExchangeTimestamps = Collections.synchronizedMap(mutableMapOf<String, Long>())
    
    // Delegate for callbacks
    var delegate: SecurityManagerDelegate? = null
    
    // Coroutines
    private val managerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    init {
        startPeriodicCleanup()
    }
    
    /**
     * Validate packet security (timestamp, replay attacks, duplicates, signatures)
     */
    fun validatePacket(packet: BitchatPacket, peerID: String): Boolean {
        // Skip validation for our own packets
        if (peerID == myPeerID) {
            return false
        }
        
        // Replay attack protection (same 5-minute window as iOS)
        val currentTime = System.currentTimeMillis()
        val messageType = MessageType.fromValue(packet.type)

        // LEAVE mutates presence immediately and cannot be safely replayed after the in-memory
        // duplicate cache expires (or after an app restart). Bound it to the same five-minute
        // security window used for duplicate retention while tolerating symmetric clock skew.
        if (messageType == MessageType.LEAVE) {
            val now = currentTime.coerceAtLeast(0).toULong()
            val clockSkew = if (packet.timestamp >= now) {
                packet.timestamp - now
            } else {
                now - packet.timestamp
            }
            if (clockSkew > MESSAGE_TIMEOUT.toULong()) {
                Log.w(TAG, "Dropping stale or future-dated LEAVE from $peerID")
                return false
            }
        }

        // Duplicate detection
        val messageID = generateMessageID(packet, peerID)
        
        if (processedMessages.contains(messageID)) {
            // Check for ANNOUNCE exception: allow if it looks like a direct neighbor (max TTL)
            // This ensures we observe the same peer on a new direct transport connection,
            // while still dropping looped/relayed duplicates.
            val isFreshAnnounce = messageType == MessageType.ANNOUNCE &&
                    packet.ttl >= com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS

            if (!isFreshAnnounce) {
                return false
            }
        }

        // Enforce mandatory signature verification
        if (!verifyPacketSignature(packet, peerID)) {
            return false
        }

        // Record only authenticated packets. Recording an attacker-controlled
        // invalid packet first would let it poison duplicate detection for a
        // later legitimate packet with the same timestamp and payload.
        processedMessages.add(messageID)
        messageTimestamps[messageID] = currentTime

        return true
    }
    
    /**
     * Handle Noise handshake packet - SIMPLIFIED iOS-compatible version
     * Single handshake type with automatic response handling
     */
    suspend fun handleNoiseHandshake(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        val peerID = routed.peerID ?: "unknown"

        // Skip handshakes not addressed to us
        if (packet.recipientID?.toHexString() != myPeerID) {
            return false
        }

        // Skip our own handshake messages
        if (peerID == myPeerID) return false

        if (packet.payload.isEmpty()) {
            Log.d(TAG, "Noise handshake packet has empty payload")
            return false
        }

        // Prevent duplicate handshake processing
        val exchangeKey = "$peerID-${packet.payload.sliceArray(0 until minOf(16, packet.payload.size)).contentHashCode()}"

        if (processedKeyExchanges.contains(exchangeKey)) {
            return false
        }

        try {
            // The session manager preserves an existing transport in a separate responder-candidate
            // flow and reports whether this exact frame completed authentication. Never infer that
            // from ambient session state: a rejected replacement may leave the old session active.
            val result = encryptionService.processHandshakeMessageWithResult(packet.payload, peerID)
            processedKeyExchanges.add(exchangeKey)
            keyExchangeTimestamps[exchangeKey] = System.currentTimeMillis()
            
            if (result.response != null) {
                // Send handshake response through delegate
                delegate?.sendHandshakeResponse(peerID, result.response)
            }
            if (result.establishedNow) {
                val authenticatedRemoteStaticKey = result.authenticatedRemoteStaticKey
                if (authenticatedRemoteStaticKey == null) {
                    Log.e(TAG, "Bound Noise completion for $peerID omitted its authenticated static key")
                    return false
                }
                val authenticatedSessionToken = result.authenticatedSessionToken
                if (authenticatedSessionToken?.size != 32 ||
                    authenticatedSessionToken.all { it == 0.toByte() }
                ) {
                    Log.e(TAG, "Bound Noise completion for $peerID omitted its generation token")
                    return false
                }
                val isDirectIngress = packet.ttl == com.bitchat.android.util.AppConstants.MESSAGE_TTL_HOPS
                Log.i(TAG, "Noise handshake completed with $peerID")
                delegate?.onKeyExchangeCompleted(
                    peerID = peerID,
                    authenticatedRemoteStaticKey = authenticatedRemoteStaticKey,
                    authenticatedSessionToken = authenticatedSessionToken,
                    directRelayAddress = routed.relayAddress.takeIf { isDirectIngress },
                    ingressLinkID = routed.ingressLinkID.takeIf { isDirectIngress }
                )
            }
            return true

            
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process Noise handshake from $peerID: ${e.message}")
            return false
        }
    }

    /**
     * Verify a packet signature against the signing key learned from the
     * peer's verified announcement. Signatures cover the canonical packet,
     * not only its payload; otherwise routing and recipient fields could be
     * changed without invalidating the signature.
     */
    fun verifySignature(packet: BitchatPacket, peerID: String): Boolean {
        return verifyPacketSignature(packet, peerID)
    }
    
    /**
     * Sign packet payload
     */
    fun signPacket(payload: ByteArray): ByteArray? {
        return try {
            encryptionService.sign(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign packet: ${e.message}")
            null
        }
    }
    
    /**
     * Encrypt payload for specific peer
     */
    fun encryptForPeer(data: ByteArray, recipientPeerID: String): ByteArray? {
        return try {
            encryptionService.encrypt(data, recipientPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encrypt for $recipientPeerID: ${e.message}")
            null
        }
    }

    fun encryptForPeer(
        data: ByteArray,
        recipientPeerID: String,
        expectedSession: AuthenticatedNoiseSession
    ): ByteArray? = try {
        encryptionService.encryptForSession(data, recipientPeerID, expectedSession)
    } catch (e: Exception) {
        Log.e(TAG, "Noise generation changed before encrypting for $recipientPeerID: ${e.message}")
        null
    }
    
    /**
     * Decrypt payload from specific peer
     */
    fun decryptFromPeer(encryptedData: ByteArray, senderPeerID: String): NoiseDecryptionResult? {
        return try {
            encryptionService.decryptWithSession(encryptedData, senderPeerID)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decrypt from $senderPeerID: ${e.message}")
            null
        }
    }
    
    /**
     * Get combined public key data for key exchange
     */
    fun getCombinedPublicKeyData(): ByteArray {
        return encryptionService.getCombinedPublicKeyData()
    }
    
    /**
     * Generate message ID for duplicate detection
     */
    private fun generateMessageID(packet: BitchatPacket, peerID: String): String {
        return when (MessageType.fromValue(packet.type)) {
            MessageType.FRAGMENT -> {
                // For fragments, include the payload hash to distinguish different fragments
                "${packet.timestamp}-$peerID-${packet.type}-${packet.payload.contentHashCode()}"
            }
            else -> {
                // For other messages, use a truncated payload hash
                val payloadHash = packet.payload.sliceArray(0 until minOf(64, packet.payload.size)).contentHashCode()
                "${packet.timestamp}-$peerID-$payloadHash"
            }
        }
    }
    
    /**
     * Verify packet signature using peer's signing public key
     * Returns true only if signature is present and valid
     */
    private fun verifyPacketSignature(packet: BitchatPacket, peerID: String): Boolean {
        try {
            // Public packets that mutate identity, presence, or user-visible state must prove the
            // signing key learned from a verified announcement. LEAVE is included so an attacker
            // cannot evict a claimed peer or amplify a forged departure through relay.
            if (MessageType.fromValue(packet.type) !in setOf(
                    MessageType.ANNOUNCE,
                    MessageType.MESSAGE,
                    MessageType.FILE_TRANSFER,
                    MessageType.VOICE_FRAME,
                    MessageType.LEAVE
                )) {
                return true
            }

            if (MessageType.fromValue(packet.type) == MessageType.ANNOUNCE) {
                val announcement = AnnouncementIdentityValidator.verify(packet, peerID) { signature, data, key ->
                    encryptionService.verifyEd25519Signature(signature, data, key)
                } ?: run {
                    Log.w(TAG, "Rejecting malformed, unbound, or invalidly signed ANNOUNCE from $peerID")
                    return false
                }

                val persistedSigningKey = delegate?.getAuthenticatedSigningKey(announcement.noisePublicKey)
                if (persistedSigningKey != null &&
                    !persistedSigningKey.contentEquals(announcement.signingPublicKey)
                ) {
                    Log.w(TAG, "Rejecting ANNOUNCE Ed key that conflicts with authenticated peer state for $peerID")
                    return false
                }

                val existingPeer = delegate?.getPeerInfo(peerID)
                if (
                    existingPeer?.noisePublicKey != null &&
                    !existingPeer.noisePublicKey!!.contentEquals(announcement.noisePublicKey)
                ) {
                    Log.w(TAG, "Rejecting ANNOUNCE Noise-key replacement for $peerID")
                    return false
                }
                if (
                    existingPeer?.signingPublicKey != null &&
                    !existingPeer.signingPublicKey!!.contentEquals(announcement.signingPublicKey)
                ) {
                    Log.w(TAG, "Rejecting ANNOUNCE signing-key replacement without authenticated peer state for $peerID")
                    return false
                }
                return true
            }

            // 1. Mandatory Signature Check
            if (packet.signature == null) {
                Log.w(TAG, "Signature check for $peerID: NO_SIGNATURE (packet type ${packet.type})")
                return false
            }
            
            // 2. Get Signing Public Key
            val peerInfo = delegate?.getPeerInfo(peerID)
            val signingPublicKey = peerInfo?.signingPublicKey
            
            if (signingPublicKey == null) {
                // If we don't have a key (and it's not an announce), we can't verify.
                // For security, we must reject packets from unknown peers unless it's an announce.
                Log.w(TAG, "Signature check for $peerID: NO_SIGNING_KEY_AVAILABLE (packet type ${packet.type})")
                return false
            }
            
            // 3. Get Canonical Data
            val packetDataForSigning = packet.toBinaryDataForSigning()
            if (packetDataForSigning == null) {
                Log.w(TAG, "Signature check for $peerID: ENCODING_ERROR (packet type ${packet.type})")
                return false
            }
            
            // 4. Verify Signature
            val signature = packet.signature!!
            val isSignatureValid = encryptionService.verifyEd25519Signature(
                signature,
                packetDataForSigning,
                signingPublicKey
            )
            
            if (isSignatureValid) {
                return true
            } else {
                Log.w(TAG, "Signature INVALID for $peerID (type ${packet.type})")
                return false
            }

        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error for $peerID: ${e.message}")
            return false
        }
    }
    
    /**
     * Check if we have encryption keys for a peer
     */
    fun hasKeysForPeer(peerID: String): Boolean {
        return encryptionService.hasEstablishedSession(peerID)
    }
    
    /**
     * Get debug information
     */
    fun getDebugInfo(): String {
        return buildString {
            appendLine("=== Security Manager Debug Info ===")
            appendLine("Processed Messages: ${processedMessages.size}")
            appendLine("Processed Key Exchanges: ${processedKeyExchanges.size}")
            appendLine("Message Timestamps: ${messageTimestamps.size}")
            
            if (processedKeyExchanges.isNotEmpty()) {
                appendLine("Key Exchange History:")
                processedKeyExchanges.take(10).forEach { exchange ->
                    appendLine("  - $exchange")
                }
                if (processedKeyExchanges.size > 10) {
                    appendLine("  ... and ${processedKeyExchanges.size - 10} more")
                }
            }
        }
    }
    
    /**
     * Start periodic cleanup
     */
    private fun startPeriodicCleanup() {
        managerScope.launch {
            while (isActive) {
                delay(CLEANUP_INTERVAL)
                cleanupOldData()
            }
        }
    }
    
    /**
     * Clean up old processed messages and timestamps
     */
    internal fun cleanupOldData(nowMs: Long = System.currentTimeMillis()) {
        val cutoffTime = nowMs - MESSAGE_TIMEOUT

        // Clean up old message timestamps and corresponding processed messages
        val messagesToRemove = messageTimestamps.entries.filter { (_, timestamp) ->
            timestamp < cutoffTime
        }.map { it.key }

        messagesToRemove.forEach { messageId ->
            messageTimestamps.remove(messageId)
            processedMessages.remove(messageId)
        }

        // Limit the size of processed messages set
        if (processedMessages.size > MAX_PROCESSED_MESSAGES) {
            val excess = processedMessages.size - MAX_PROCESSED_MESSAGES
            val toRemove = processedMessages.take(excess)
            processedMessages.removeAll(toRemove.toSet())
            removeFromMessageTimestamps(toRemove)
        }

        // Expire handshake dedup entries by time so a delayed same-ephemeral delivery
        // (e.g. a re-handshake retry after a failed attempt) is not blocked forever.
        val keyExchangeCutoff = nowMs - KEY_EXCHANGE_DEDUP_TIMEOUT
        val keyExchangesToRemove = keyExchangeTimestamps.entries.filter { (_, timestamp) ->
            timestamp < keyExchangeCutoff
        }.map { it.key }

        keyExchangesToRemove.forEach { exchangeKey ->
            keyExchangeTimestamps.remove(exchangeKey)
            processedKeyExchanges.remove(exchangeKey)
        }

        // Limit the size of processed key exchanges set
        if (processedKeyExchanges.size > MAX_PROCESSED_KEY_EXCHANGES) {
            val excess = processedKeyExchanges.size - MAX_PROCESSED_KEY_EXCHANGES
            val toRemove = processedKeyExchanges.take(excess)
            processedKeyExchanges.removeAll(toRemove.toSet())
            toRemove.forEach { keyExchangeTimestamps.remove(it) }
        }
    }
    
    /**
     * Helper to remove entries from messageTimestamps
     */
    private fun removeFromMessageTimestamps(messageIds: List<String>) {
        messageIds.forEach { messageId ->
            messageTimestamps.remove(messageId)
        }
    }
    
    /**
     * Clear all security data
     */
    fun clearAllData() {
        processedMessages.clear()
        processedKeyExchanges.clear()
        messageTimestamps.clear()
        keyExchangeTimestamps.clear()
    }
    
    /**
     * Shutdown the manager
     */
    fun shutdown() {
        managerScope.cancel()
        clearAllData()
    }
}

/**
 * Delegate interface for security manager callbacks
 */
interface SecurityManagerDelegate {
    fun onKeyExchangeCompleted(
        peerID: String,
        authenticatedRemoteStaticKey: ByteArray,
        authenticatedSessionToken: ByteArray,
        directRelayAddress: String?,
        ingressLinkID: String?
    )
    fun sendHandshakeResponse(peerID: String, response: ByteArray)
    fun getPeerInfo(peerID: String): PeerInfo? // NEW: For signature verification
    fun getAuthenticatedSigningKey(noisePublicKey: ByteArray): ByteArray? = null
}
