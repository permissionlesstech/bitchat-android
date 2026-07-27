package com.bitchat.android.nostr

import android.content.Context
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.model.ReadReceipt
import com.bitchat.android.model.NdrFeatureGate
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import kotlinx.coroutines.*
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

enum class NostrSendAdmission {
    /** The legacy relay handoff or durable pairwise NDR state now owns delivery. */
    ADMITTED,

    /** No transport accepted the message yet, but unchanged input may succeed later. */
    RETRYABLE,

    /** The payload or recipient is invalid and cannot succeed unchanged. */
    TERMINAL_FAILED
}

internal enum class NdrSendDisposition {
    ADMITTED,
    LEGACY_FALLBACK,
    RETRYABLE
}

internal fun ndrSendDisposition(
    result: NdrSendResult,
    ndrRequired: Boolean = false,
    rebindBlocked: Boolean = false,
    pairwiseOnly: Boolean = false
): NdrSendDisposition = when {
    result == NdrSendResult.SENT -> NdrSendDisposition.ADMITTED
    rebindBlocked -> NdrSendDisposition.RETRYABLE
    result == NdrSendResult.NO_SESSION && !ndrRequired && !pairwiseOnly ->
        NdrSendDisposition.LEGACY_FALLBACK
    else -> NdrSendDisposition.RETRYABLE
}

internal fun shouldUseLegacyNostrFallback(
    result: NdrSendResult,
    ndrRequired: Boolean = false,
    rebindBlocked: Boolean = false
): Boolean =
    ndrSendDisposition(
        result = result,
        ndrRequired = ndrRequired,
        rebindBlocked = rebindBlocked
    ) == NdrSendDisposition.LEGACY_FALLBACK

internal fun isLegacyNostrAllowedWhenNdrDisabled(
    ndrRequired: Boolean,
    rebindBlocked: Boolean
): Boolean = !ndrRequired && !rebindBlocked

private data class NdrRecipientResolution(
    val peerPubkeyHex: String,
    val ndrRequired: Boolean,
    val rebindBlocked: Boolean
)

@JvmInline
internal value class NostrTransportResetToken(val epoch: Long)

/**
 * Nostr transport for offline private messages and receipts.
 */
class NostrTransport(
    private val context: Context,
    var senderPeerID: String = "",
    private val transportScope: CoroutineScope =
        CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val relayManager: NostrRelayManager =
        NostrRelayManager.getInstance(context)
) {
    
    companion object {
        private const val TAG = "NostrTransport"
        private const val READ_ACK_INTERVAL = com.bitchat.android.util.AppConstants.Nostr.READ_ACK_INTERVAL_MS // ~3 per second (0.35s interval like iOS)
        
        @Volatile
        private var INSTANCE: NostrTransport? = null

        fun tryGetInstance(): NostrTransport? = INSTANCE
        
        fun getInstance(context: Context): NostrTransport {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NostrTransport(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
    
    // Throttle READ receipts to avoid relay rate limits (like iOS)
    private data class AccountToken(
        val transportEpoch: Long,
        val relayGeneration: Long
    )

    private data class QueuedRead(
        val receipt: ReadReceipt,
        val peerID: String,
        val sequence: Long,
        val accountToken: AccountToken
    )
    
    private val readQueue = ConcurrentLinkedQueue<QueuedRead>()
    private val accountStateLock = Any()
    private var transportAccountEpoch = 0L
    private var accountResetBlocked = false
    private var nextReadSequence = 0L
    private var activeReadSequence: Long? = null
    private val ndrService by lazy { NdrNostrService.getInstance(context) }
    
    // MARK: - Transport Interface Methods
    
    val myPeerID: String get() = senderPeerID

    private fun captureAccountToken(): AccountToken? = synchronized(accountStateLock) {
        if (accountResetBlocked) {
            null
        } else {
            AccountToken(
                transportEpoch = transportAccountEpoch,
                relayGeneration = relayManager.captureAccountGeneration()
            )
        }
    }

    private fun isAccountTokenCurrent(token: AccountToken): Boolean =
        synchronized(accountStateLock) {
            !accountResetBlocked &&
                token.transportEpoch == transportAccountEpoch &&
                relayManager.isAccountGenerationCurrent(token.relayGeneration)
        }

    /**
     * Start an account-lifetime barrier. Already-launched work keeps its captured
     * token and is refused at the final relay handoff; throttled receipts are
     * discarded immediately.
     */
    internal fun discardForAccountReset(): NostrTransportResetToken =
        synchronized(accountStateLock) {
            accountResetBlocked = true
            transportAccountEpoch += 1
            readQueue.clear()
            activeReadSequence = null
            NostrTransportResetToken(transportAccountEpoch)
        }

    /**
     * Allow fresh work only when the caller still owns the latest reset.
     * A later panic/quit must not be reopened by an older reset finishing late.
     */
    internal fun completeAccountReset(
        resetToken: NostrTransportResetToken
    ): Boolean =
        synchronized(accountStateLock) {
            if (resetToken.epoch != transportAccountEpoch) {
                return@synchronized false
            }
            accountResetBlocked = false
            true
        }

    internal fun queuedReadCountForTesting(): Int = readQueue.size

    internal fun activeReadCountForTesting(): Int = synchronized(accountStateLock) {
        if (activeReadSequence == null) 0 else 1
    }
    
    fun sendPrivateMessage(
        content: String,
        to: String,
        recipientNickname: String,
        messageID: String,
        expiresAtSeconds: ULong? = null,
        completion: (NostrSendAdmission) -> Unit = {}
    ) {
        val accountToken = captureAccountToken()
        if (accountToken == null) {
            runCatching { completion(NostrSendAdmission.RETRYABLE) }
                .onFailure { Log.w(TAG, "Nostr private-message admission callback failed") }
            return
        }
        val completed = AtomicBoolean(false)
        fun completeOnce(admission: NostrSendAdmission) {
            if (!completed.compareAndSet(false, true)) return
            runCatching { completion(admission) }
                .onFailure { Log.w(TAG, "Nostr private-message admission callback failed") }
        }

        val job = transportScope.launch {
            val admission = try {
                prepareAndSendPrivateMessage(
                    content = content,
                    to = to,
                    messageID = messageID,
                    expiresAtSeconds = expiresAtSeconds,
                    accountToken = accountToken
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send private message via Nostr: ${e.message}")
                NostrSendAdmission.RETRYABLE
            }
            completeOnce(admission)
        }
        job.invokeOnCompletion { cause ->
            if (cause != null) {
                completeOnce(NostrSendAdmission.RETRYABLE)
            }
        }
    }

    private fun prepareAndSendPrivateMessage(
        content: String,
        to: String,
        messageID: String,
        expiresAtSeconds: ULong?,
        accountToken: AccountToken
    ): NostrSendAdmission {
        if (!isAccountTokenCurrent(accountToken)) {
            return NostrSendAdmission.RETRYABLE
        }
        if (expiresAtSeconds != null &&
            expiresAtSeconds <= (System.currentTimeMillis() / 1_000L).toULong()
        ) {
            Log.e(TAG, "NostrTransport: refusing an already-expired private message")
            return NostrSendAdmission.TERMINAL_FAILED
        }
        val recipientNostrPubkey = resolveNostrPublicKey(to)
        if (recipientNostrPubkey == null) {
            Log.w(TAG, "No Nostr public key found for peerID: $to")
            return NostrSendAdmission.RETRYABLE
        }

        val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
        if (senderIdentity == null) {
            Log.e(TAG, "No Nostr identity available")
            return NostrSendAdmission.RETRYABLE
        }

        val recipientHex = ContactIdentityResolver.nostrPubkeyHex(recipientNostrPubkey)
        if (recipientHex == null) {
            Log.e(TAG, "NostrTransport: recipient key is not a valid Nostr pubkey")
            return NostrSendAdmission.TERMINAL_FAILED
        }
        val ndrRecipient = resolveNdrRecipient(to, recipientHex)

        val recipientPeerIDForEmbed = try {
            com.bitchat.android.favorites.FavoritesPersistenceService.shared
                .findPeerIDForNostrPubkey(recipientNostrPubkey)
        } catch (_: Exception) {
            null
        }
        if (recipientPeerIDForEmbed.isNullOrBlank()) {
            Log.e(TAG, "NostrTransport: no peerID stored for recipient npub; cannot embed PM")
            return NostrSendAdmission.RETRYABLE
        }
        val embedded = NostrEmbeddedBitChat.encodePMForNostr(
            content = content,
            messageID = messageID,
            recipientPeerID = recipientPeerIDForEmbed,
            senderPeerID = senderPeerID
        )
        if (embedded == null) {
            Log.e(TAG, "NostrTransport: failed to embed PM packet")
            return NostrSendAdmission.TERMINAL_FAILED
        }

        return sendWrappedMessage(
            content = embedded,
            fallbackRecipientHex = recipientHex,
            senderIdentity = senderIdentity,
            ndrRecipient = ndrRecipient,
            expiresAtSeconds = expiresAtSeconds,
            accountToken = accountToken
        )
    }
    
    fun sendReadReceipt(receipt: ReadReceipt, to: String) {
        val accountToken = captureAccountToken() ?: return
        // Enqueue and process with throttling to avoid relay rate limits
        val queuedRead = synchronized(accountStateLock) {
            if (!isAccountTokenCurrent(accountToken)) {
                null
            } else {
                QueuedRead(
                    receipt = receipt,
                    peerID = to,
                    sequence = ++nextReadSequence,
                    accountToken = accountToken
                ).also(readQueue::offer)
            }
        } ?: return
        if (!isAccountTokenCurrent(queuedRead.accountToken)) return
        processReadQueueIfNeeded()
    }
    
    private fun processReadQueueIfNeeded() {
        val item = synchronized(accountStateLock) {
            if (accountResetBlocked || activeReadSequence != null) return
            var next = readQueue.poll()
            while (next != null && !isAccountTokenCurrent(next.accountToken)) {
                next = readQueue.poll()
            }
            next?.also { activeReadSequence = it.sequence }
        } ?: return
        sendReadAck(item)
    }
    
    private fun sendReadAck(item: QueuedRead) {
        transportScope.launch {
            try {
                if (!isAccountTokenCurrent(item.accountToken)) {
                    finishReadAck(item)
                    return@launch
                }
                val recipientNostrPubkey = resolveNostrPublicKey(item.peerID)
                
                if (recipientNostrPubkey == null) {
                    Log.w(TAG, "No Nostr public key found for read receipt to: ${item.peerID}")
                    finishReadAck(item)
                    return@launch
                }
                
                val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                if (senderIdentity == null) {
                    Log.e(TAG, "No Nostr identity available for read receipt")
                    finishReadAck(item)
                    return@launch
                }
                
                val recipientHex = ContactIdentityResolver.nostrPubkeyHex(recipientNostrPubkey)
                if (recipientHex == null) {
                    finishReadAck(item)
                    return@launch
                }
                val ndrRecipient = resolveNdrRecipient(item.peerID, recipientHex)
                
                val ack = NostrEmbeddedBitChat.encodeAckForNostr(
                    type = NoisePayloadType.READ_RECEIPT,
                    messageID = item.receipt.originalMessageID,
                    recipientPeerID = item.peerID,
                    senderPeerID = senderPeerID
                )
                
                if (ack == null) {
                    Log.e(TAG, "NostrTransport: failed to embed READ ack")
                    finishReadAck(item)
                    return@launch
                }
                
                sendWrappedMessage(
                    content = ack,
                    fallbackRecipientHex = recipientHex,
                    senderIdentity = senderIdentity,
                    ndrRecipient = ndrRecipient,
                    accountToken = item.accountToken
                )
                
                finishReadAck(item)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send read receipt via Nostr: ${e.message}")
                finishReadAck(item)
            }
        }
    }
    
    private fun finishReadAck(item: QueuedRead) {
        transportScope.launch {
            delay(READ_ACK_INTERVAL)
            synchronized(accountStateLock) {
                if (activeReadSequence != item.sequence) return@launch
                activeReadSequence = null
            }
            processReadQueueIfNeeded()
        }
    }
    
    fun sendFavoriteNotification(to: String, isFavorite: Boolean) {
        val accountToken = captureAccountToken() ?: return
        transportScope.launch {
            try {
                if (!isAccountTokenCurrent(accountToken)) return@launch
                val recipientNostrPubkey = resolveNostrPublicKey(to)
                
                if (recipientNostrPubkey == null) {
                    Log.w(TAG, "No Nostr public key found for favorite notification to: $to")
                    return@launch
                }
                
                val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                if (senderIdentity == null) {
                    Log.e(TAG, "No Nostr identity available for favorite notification")
                    return@launch
                }
                
                val content = FavoriteControlMessage.encode(isFavorite, senderIdentity.npub)

                val recipientHex = ContactIdentityResolver.nostrPubkeyHex(recipientNostrPubkey)
                if (recipientHex == null) {
                    return@launch
                }
                val ndrRecipient = resolveNdrRecipient(to, recipientHex)

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
                
                sendWrappedMessage(
                    content = embedded,
                    fallbackRecipientHex = recipientHex,
                    senderIdentity = senderIdentity,
                    ndrRecipient = ndrRecipient,
                    accountToken = accountToken
                )
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send favorite notification via Nostr: ${e.message}")
            }
        }
    }
    
    fun sendDeliveryAck(messageID: String, to: String) {
        val accountToken = captureAccountToken() ?: return
        transportScope.launch {
            try {
                if (!isAccountTokenCurrent(accountToken)) return@launch
                val recipientNostrPubkey = resolveNostrPublicKey(to)
                
                if (recipientNostrPubkey == null) {
                    Log.w(TAG, "No Nostr public key found for delivery ack to: $to")
                    return@launch
                }
                
                val senderIdentity = NostrIdentityBridge.getCurrentNostrIdentity(context)
                if (senderIdentity == null) {
                    Log.e(TAG, "No Nostr identity available for delivery ack")
                    return@launch
                }
                
                val recipientHex = ContactIdentityResolver.nostrPubkeyHex(recipientNostrPubkey)
                if (recipientHex == null) {
                    return@launch
                }
                val ndrRecipient = resolveNdrRecipient(to, recipientHex)

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
                
                sendWrappedMessage(
                    content = ack,
                    fallbackRecipientHex = recipientHex,
                    senderIdentity = senderIdentity,
                    ndrRecipient = ndrRecipient,
                    accountToken = accountToken
                )
                
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
        val accountToken = captureAccountToken() ?: return
        transportScope.launch {
            try {
                if (!isAccountTokenCurrent(accountToken)) return@launch
                val embedded = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(
                    type = NoisePayloadType.DELIVERED,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) return@launch
                
                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )
                
                sendLegacyGiftWraps(giftWraps, accountToken)
                
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
        val accountToken = captureAccountToken() ?: return
        transportScope.launch {
            try {
                if (!isAccountTokenCurrent(accountToken)) return@launch
                val embedded = NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(
                    type = NoisePayloadType.READ_RECEIPT,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                )
                
                if (embedded == null) return@launch
                
                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )
                
                sendLegacyGiftWraps(giftWraps, accountToken)
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash read receipt: ${e.message}")
            }
        }
    }
    
    // MARK: - Geohash DMs (per-geohash identity)
    
    fun sendPrivateMessageGeohash(
        content: String,
        toRecipientHex: String,
        messageID: String,
        sourceGeohash: String? = null
    ) {
        val accountToken = captureAccountToken() ?: return
        // Use provided geohash or derive from current location
        val geohash = sourceGeohash ?: run {
            val selected = try {
                com.bitchat.android.geohash.LocationChannelManager.getInstance(context).selectedChannel.value
            } catch (_: Exception) { null }
            if (selected !is com.bitchat.android.geohash.ChannelID.Location) {
                Log.w(TAG, "NostrTransport: cannot send geohash PM - not in a location channel and no geohash provided")
                return
            }
            selected.channel.geohash
        }
        
        val fromIdentity = try {
            NostrIdentityBridge.deriveIdentity(geohash, context)
        } catch (e: Exception) {
            Log.e(TAG, "NostrTransport: cannot derive geohash identity for $geohash: ${e.message}")
            return
        }
        
        transportScope.launch {
            try {
                if (!isAccountTokenCurrent(accountToken)) return@launch
                if (toRecipientHex.isEmpty()) return@launch

                // Build embedded BitChat packet without recipient peer ID
                val embedded = NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                    content = content,
                    messageID = messageID,
                    senderPeerID = senderPeerID
                ) ?: run {
                    Log.e(TAG, "NostrTransport: failed to embed geohash PM packet")
                    return@launch
                }

                val giftWraps = NostrProtocol.createPrivateMessage(
                    content = embedded,
                    recipientPubkey = toRecipientHex,
                    senderIdentity = fromIdentity
                )

                sendLegacyGiftWraps(giftWraps, accountToken)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send geohash private message: ${e.message}")
            }
        }
    }
    
    // MARK: - Helper Methods

    private fun sendWrappedMessage(
        content: String,
        fallbackRecipientHex: String,
        senderIdentity: NostrIdentity,
        ndrRecipient: NdrRecipientResolution = NdrRecipientResolution(
            peerPubkeyHex = fallbackRecipientHex,
            ndrRequired = false,
            rebindBlocked = false
        ),
        expiresAtSeconds: ULong? = null,
        accountToken: AccountToken
    ): NostrSendAdmission {
        if (!isAccountTokenCurrent(accountToken)) {
            return NostrSendAdmission.RETRYABLE
        }
        if (ndrRecipient.rebindBlocked) {
            Log.e(TAG, "NostrTransport: recipient rebind is quarantined")
            return NostrSendAdmission.RETRYABLE
        }
        if (NdrFeatureGate.isEnabled()) {
            val configured = ndrService.configureIfNeeded(senderIdentity) {
                isAccountTokenCurrent(accountToken)
            }
            if (!configured) {
                return NostrSendAdmission.RETRYABLE
            }
            val sendResult = ndrService.sendIfPossible(
                text = content,
                peerPubkeyHex = ndrRecipient.peerPubkeyHex,
                expiresAtSeconds = expiresAtSeconds,
                accountGuard = { isAccountTokenCurrent(accountToken) }
            )
            when (
                ndrSendDisposition(
                    result = sendResult,
                    ndrRequired = ndrRecipient.ndrRequired,
                    rebindBlocked = ndrRecipient.rebindBlocked,
                    pairwiseOnly = expiresAtSeconds != null
                )
            ) {
                NdrSendDisposition.ADMITTED -> return NostrSendAdmission.ADMITTED
                NdrSendDisposition.RETRYABLE -> {
                    Log.e(TAG, "NostrTransport: pairwise send not admitted; refusing legacy downgrade")
                    return NostrSendAdmission.RETRYABLE
                }
                NdrSendDisposition.LEGACY_FALLBACK -> Unit
            }
        } else if (expiresAtSeconds != null ||
            !isLegacyNostrAllowedWhenNdrDisabled(
                ndrRequired = ndrRecipient.ndrRequired,
                rebindBlocked = ndrRecipient.rebindBlocked
            )
        ) {
            Log.e(TAG, "NostrTransport: pairwise transport is required")
            return NostrSendAdmission.RETRYABLE
        }

        val events = NostrProtocol.createPrivateMessage(
            content = content,
            recipientPubkey = fallbackRecipientHex,
            senderIdentity = senderIdentity
        )
        if (events.isEmpty()) {
            Log.e(TAG, "NostrTransport: failed to create legacy gift wrap")
            return NostrSendAdmission.RETRYABLE
        }
        return if (sendLegacyGiftWraps(events, accountToken)) {
            NostrSendAdmission.ADMITTED
        } else {
            NostrSendAdmission.RETRYABLE
        }
    }

    private fun sendLegacyGiftWraps(
        events: List<NostrEvent>,
        accountToken: AccountToken
    ): Boolean = synchronized(accountStateLock) {
        if (!isAccountTokenCurrent(accountToken)) return@synchronized false
        events.all { event ->
            relayManager.registerPendingGiftWrap(
                event.id,
                accountToken.relayGeneration
            ) && relayManager.sendEvent(
                event = event,
                expectedAccountGeneration = accountToken.relayGeneration
            )
        }
    }

    private fun resolveNdrRecipient(
        target: String,
        fallbackRecipientHex: String
    ): NdrRecipientResolution {
        return try {
            val favorites = com.bitchat.android.favorites.FavoritesPersistenceService.shared
            if (!favorites.isNdrProtectionStateReadable()) {
                return NdrRecipientResolution(
                    peerPubkeyHex = fallbackRecipientHex,
                    ndrRequired = true,
                    rebindBlocked = true
                )
            }
            val relationship = favorites.getFavoriteStatus(target)
                ?: return NdrRecipientResolution(
                    peerPubkeyHex = fallbackRecipientHex,
                    ndrRequired = false,
                    rebindBlocked = false
                )
            NdrRecipientResolution(
                peerPubkeyHex =
                    favorites.findNdrSessionPubkeyHex(relationship.peerNoisePublicKey)
                        ?: fallbackRecipientHex,
                ndrRequired = favorites.isNdrRequired(relationship.peerNoisePublicKey),
                rebindBlocked =
                    favorites.isNdrRebindBlocked(relationship.peerNoisePublicKey)
            )
        } catch (_: Exception) {
            NdrRecipientResolution(
                peerPubkeyHex = fallbackRecipientHex,
                ndrRequired = true,
                rebindBlocked = true
            )
        }
    }
    
    /**
     * Resolve Nostr public key for a peer ID
     */
    private fun resolveNostrPublicKey(peerID: String): String? {
        try {
            ContactDirectory.resolve(peerID).nostrPubkey?.let { return it }

            com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNostrPubkeyForPeerID(peerID)?.let { return it }

            if (ContactIdentityResolver.isNoiseKeyHex(peerID)) {
                val noiseKey = ContactIdentityResolver.bytesFromHex(peerID) ?: return null
                val favoriteStatus = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                if (favoriteStatus?.peerNostrPublicKey != null) return favoriteStatus.peerNostrPublicKey
            }

            if (ContactIdentityResolver.isMeshPeerId(peerID)) {
                val fallbackStatus = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(peerID)
                return fallbackStatus?.peerNostrPublicKey
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve Nostr public key for $peerID: ${e.message}")
            return null
        }
    }
    
    fun cleanup() {
        transportScope.cancel()
    }
}
