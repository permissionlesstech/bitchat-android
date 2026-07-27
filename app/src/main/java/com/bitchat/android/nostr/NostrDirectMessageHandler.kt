package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NdrFeatureGate
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.ContactDirectory
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.ui.PrivateChatManager
import com.bitchat.android.ui.PrivateMessageOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class NostrDirectMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
    private val meshDelegateHandler: MeshDelegateHandler,
    private val scope: CoroutineScope,
    private val repo: GeohashRepository,
    private val dataManager: com.bitchat.android.ui.DataManager
) {
    companion object { private const val TAG = "NostrDirectMessageHandler" }

    private val seenStore by lazy { SeenMessageStore.getInstance(application) }
    private val ndrService by lazy { NdrNostrService.getInstance(application) }
    private val ndrAccountEpochs = NdrAccountEpochGuard()
    private val ndrReceiveJobLock = Any()
    private var ndrReceiveJob: Job = SupervisorJob(scope.coroutineContext[Job])

    // Simple event deduplication
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

    @Synchronized
    private fun dedupe(id: String): Boolean {
        if (seen.contains(id)) return true
        seen.add(id)
        processedIds.addLast(id)
        if (processedIds.size > max) {
            val old = processedIds.removeFirst()
            seen.remove(old)
        }
        return false
    }

    @Synchronized
    private fun hasProcessed(id: String): Boolean = id in seen

    private fun markProcessed(id: String) {
        dedupe(id)
    }

    fun configureDoubleRatchet(identity: NostrIdentity) {
        if (!NdrFeatureGate.isEnabled()) {
            invalidateDoubleRatchetAccount()
            ndrService.onDecryptedMessage = null
            return
        }
        val epoch = ndrAccountEpochs.begin(identity.publicKeyHex)
        val receiveJob = synchronized(ndrReceiveJobLock) {
            ndrReceiveJob.cancel()
            SupervisorJob(scope.coroutineContext[Job]).also { ndrReceiveJob = it }
        }
        // A prior account's callback must not consume and discard pending
        // deliveries while the replacement runtime is initialized.
        ndrService.onDecryptedMessage = null
        ndrService.configureIfNeeded(identity)
        ndrService.onDecryptedMessage = callback@{ message, completion ->
            if (!NdrFeatureGate.isEnabled() || !ndrAccountEpochs.isCurrent(epoch)) {
                completion(NdrDeliveryResult.REJECTED)
                return@callback
            }
            val currentIdentity =
                NostrIdentityBridge.getCurrentNostrIdentity(application)
            if (currentIdentity == null) {
                completion(NdrDeliveryResult.RETRY)
                return@callback
            }
            if (!currentIdentity.publicKeyHex.equals(epoch.accountPubkeyHex, ignoreCase = true)) {
                completion(NdrDeliveryResult.REJECTED)
                return@callback
            }
            onDoubleRatchetMessage(message, currentIdentity, epoch, receiveJob, completion)
        }
    }

    fun invalidateDoubleRatchetAccount() {
        ndrAccountEpochs.invalidate()
        synchronized(ndrReceiveJobLock) {
            ndrReceiveJob.cancel()
        }
    }

    fun onGiftWrap(giftWrap: NostrEvent, geohash: String, identity: NostrIdentity) {
        scope.launch(Dispatchers.Default) {
            try {
                if (dedupe(giftWrap.id)) return@launch

                val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
                if (messageAge > 173700) return@launch // 48 hours + 15 mins

                val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                if (decryptResult == null) {
                    Log.w(TAG, "Failed to decrypt Nostr message")
                    return@launch
                }

                val (content, rawSenderPubkey, rumorTimestamp) = decryptResult
                val senderPubkey = rawSenderPubkey.lowercase()
                val legacyAllowed = runCatching {
                    FavoritesPersistenceService.shared
                        .isLegacyNostrInboundAllowed(senderPubkey)
                }.getOrDefault(false)
                if (!legacyAllowed) {
                    Log.w(TAG, "Rejecting legacy DM for an NDR-pinned contact")
                    return@launch
                }

                // If sender is blocked for geohash contexts, drop any events from this pubkey
                // Applies to both geohash DMs (geohash != "") and account DMs (geohash == "")
                if (dataManager.isGeohashUserBlocked(senderPubkey)) return@launch
                processEmbeddedBitChatContent(
                    content = content,
                    senderPubkey = senderPubkey,
                    timestamp = Date(giftWrap.createdAt * 1000L),
                    geohash = geohash,
                    recipientIdentity = identity
                )

            } catch (_: Exception) {
                Log.e(TAG, "Failed to process gift wrap")
            }
        }
    }

    private fun onDoubleRatchetMessage(
        message: NdrDecryptedMessage,
        identity: NostrIdentity,
        epoch: NdrAccountEpoch,
        receiveJob: Job,
        completion: (NdrDeliveryResult) -> Unit
    ) {
        scope.launch(Dispatchers.Default + receiveJob) {
            var result = NdrDeliveryResult.RETRY
            try {
                if (!NdrFeatureGate.isEnabled() || !ndrAccountEpochs.isCurrent(epoch)) {
                    result = NdrDeliveryResult.REJECTED
                    return@launch
                }
                val dedupeId = message.eventId
                if (seenStore.hasProcessedNdr(dedupeId) || hasProcessed(dedupeId)) {
                    result = NdrDeliveryResult.DUPLICATE
                    return@launch
                }

                // The pairwise FFI returns a v1 unsigned kind-14 rumor.
                // Bind that rumor to the ratchet-authenticated peer before
                // allowing any inner fields into the application.
                val applicationMessage = NdrApplicationMessageDecoder.decode(message)
                if (applicationMessage == null) {
                    result = NdrDeliveryResult.REJECTED
                    return@launch
                }
                val senderPubkey = message.senderPubkeyHex.lowercase()
                val senderIsCurrent = runCatching {
                    FavoritesPersistenceService.shared
                        .isCurrentNdrPeerAuthorized(senderPubkey)
                }.getOrDefault(false)
                if (!senderIsCurrent) {
                    result = NdrDeliveryResult.REJECTED
                    return@launch
                }
                if (dataManager.isGeohashUserBlocked(senderPubkey)) {
                    result = NdrDeliveryResult.REJECTED
                    return@launch
                }
                if (!NdrFeatureGate.isEnabled() || !ndrAccountEpochs.isCurrent(epoch)) {
                    result = NdrDeliveryResult.REJECTED
                    return@launch
                }
                if (applicationMessage.isExpiredAt(System.currentTimeMillis() / 1_000L)) {
                    result = NdrDeliveryResult.REJECTED
                    return@launch
                }

                result = processEmbeddedBitChatContent(
                    content = applicationMessage.content,
                    senderPubkey = senderPubkey,
                    timestamp = Date(applicationMessage.timestampMs),
                    geohash = "",
                    recipientIdentity = identity,
                    ndrEpoch = epoch,
                    ndrEventId = dedupeId,
                    expiresAtSeconds = applicationMessage.expiresAtSeconds
                )
            } catch (_: Exception) {
                Log.e(TAG, "Failed to process double-ratchet message")
                result = NdrDeliveryResult.RETRY
            } finally {
                if (result.shouldAcknowledge) {
                    if (seenStore.markProcessedNdr(message.eventId)) {
                        markProcessed(message.eventId)
                    } else {
                        result = NdrDeliveryResult.RETRY
                    }
                }
                completion(result)
            }
        }
    }

    private suspend fun processEmbeddedBitChatContent(
        content: String,
        senderPubkey: String,
        timestamp: Date,
        geohash: String,
        recipientIdentity: NostrIdentity,
        ndrEpoch: NdrAccountEpoch? = null,
        ndrEventId: String? = null,
        expiresAtSeconds: Long? = null
    ): NdrDeliveryResult {
        if (isExpired(expiresAtSeconds)) return NdrDeliveryResult.REJECTED
        if (!content.startsWith("bitchat1:")) return NdrDeliveryResult.REJECTED

        val packetData = base64URLDecode(content.removePrefix("bitchat1:"))
            ?: return NdrDeliveryResult.REJECTED
        val packet = BitchatPacket.fromBinaryData(packetData)
            ?: return NdrDeliveryResult.REJECTED
        if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) {
            return NdrDeliveryResult.REJECTED
        }

        val noisePayload = NoisePayload.decode(packet.payload)
            ?: return NdrDeliveryResult.REJECTED
        val convKey = "nostr_${senderPubkey.take(16)}"
        if (!runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                repo.putNostrKeyMapping(convKey, senderPubkey)
                GeohashAliasRegistry.put(convKey, senderPubkey)

                if (geohash.isNotEmpty()) {
                    repo.setConversationGeohash(convKey, geohash)
                    GeohashConversationRegistry.set(convKey, geohash)
                    if (repo.getCachedNickname(senderPubkey) == null) {
                        val base =
                            repo.displayNameForNostrPubkeyUI(senderPubkey).substringBefore("#")
                        repo.cacheNickname(senderPubkey, base)
                    }
                    repo.updateParticipant(geohash, senderPubkey, timestamp)
                }
            }
        ) return NdrDeliveryResult.REJECTED

        return processNoisePayload(
            payload = noisePayload,
            conversationID = ContactDirectory.canonicalConversationId(convKey),
            senderNickname = repo.displayNameForNostrPubkeyUI(senderPubkey),
            timestamp = timestamp,
            senderPubkey = senderPubkey,
            recipientIdentity = recipientIdentity,
            allowAccountNdr = geohash.isEmpty(),
            ndrEpoch = ndrEpoch,
            ndrEventId = ndrEventId,
            expiresAtSeconds = expiresAtSeconds
        )
    }

    private suspend fun processNoisePayload(
        payload: NoisePayload,
        conversationID: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String,
        recipientIdentity: NostrIdentity,
        allowAccountNdr: Boolean,
        ndrEpoch: NdrAccountEpoch? = null,
        ndrEventId: String? = null,
        expiresAtSeconds: Long? = null
    ): NdrDeliveryResult {
        if (!isNdrEpochCurrent(ndrEpoch) || isExpired(expiresAtSeconds)) {
            return NdrDeliveryResult.REJECTED
        }
        return when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessagePacket.decode(payload.data)
                    ?: return NdrDeliveryResult.REJECTED
                val existingMessages = state.getPrivateChatsValue()[conversationID] ?: emptyList()
                if (existingMessages.any { it.id == pm.messageID }) {
                    return NdrDeliveryResult.DUPLICATE
                }

                val favoriteControl = FavoriteControlMessage.parse(pm.content)
                if (favoriteControl != null) {
                    if (!isNdrEpochCurrent(ndrEpoch) || isExpired(expiresAtSeconds)) {
                        return NdrDeliveryResult.REJECTED
                    }
                    val favoriteResult = handleFavoriteControl(
                        favoriteControl,
                        conversationID,
                        senderNickname,
                        timestamp,
                        senderPubkey,
                        ndrEpoch,
                        ndrEventId,
                        expiresAtSeconds
                    )
                    if (favoriteResult != NdrDeliveryResult.CONSUMED &&
                        favoriteResult != NdrDeliveryResult.DUPLICATE
                    ) return favoriteResult
                    if (!runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                            if (!seenStore.hasDelivered(pm.messageID)) {
                                sendDeliveryAck(
                                    pm.messageID,
                                    senderPubkey,
                                    recipientIdentity,
                                    allowAccountNdr
                                )
                                seenStore.markDelivered(pm.messageID)
                            }
                        }
                    ) return NdrDeliveryResult.REJECTED
                    return favoriteResult
                }

                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = senderNickname,
                    content = pm.content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = conversationID,
                    senderNostrPubkey = senderPubkey,
                    deliveryStatus =
                        DeliveryStatus.Delivered(
                            to = state.getNicknameValue(),
                            at = Date()
                        )
                )

                val isViewing = state.getSelectedPrivateChatPeerValue() == conversationID
                val suppressUnread = seenStore.hasRead(pm.messageID)

                var messageAccepted = false
                withContext(Dispatchers.Main) {
                    runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                        privateChatManager.handleIncomingPrivateMessage(
                            message = message,
                            suppressUnread = suppressUnread,
                            origin = PrivateMessageOrigin.NOSTR
                        )
                        messageAccepted = true
                    }
                }
                if (!messageAccepted) return NdrDeliveryResult.REJECTED

                runCatching {
                    runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                        if (!seenStore.hasDelivered(pm.messageID)) {
                            sendDeliveryAck(
                                pm.messageID,
                                senderPubkey,
                                recipientIdentity,
                                allowAccountNdr
                            )
                            seenStore.markDelivered(pm.messageID)
                        }

                        if (isViewing && !suppressUnread) {
                            val nostrTransport = NostrTransport.getInstance(application)
                            val targetPeerID = resolvePeerIDForNostr(senderPubkey)
                                .takeIf { allowAccountNdr }
                            if (targetPeerID != null) {
                                nostrTransport.sendReadReceipt(
                                    com.bitchat.android.model.ReadReceipt(pm.messageID),
                                    targetPeerID
                                )
                            } else {
                                nostrTransport.sendReadReceiptGeohash(
                                    pm.messageID,
                                    senderPubkey,
                                    recipientIdentity
                                )
                            }
                            seenStore.markRead(pm.messageID)
                        }
                    }
                }
                NdrDeliveryResult.CONSUMED
            }
            NoisePayloadType.DELIVERED -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                var consumed = false
                withContext(Dispatchers.Main) {
                    runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                        meshDelegateHandler.didReceiveDeliveryAck(messageId, conversationID)
                        consumed = true
                    }
                }
                if (consumed) NdrDeliveryResult.CONSUMED else NdrDeliveryResult.REJECTED
            }
            NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                var consumed = false
                withContext(Dispatchers.Main) {
                    runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                        meshDelegateHandler.didReceiveReadReceipt(messageId, conversationID)
                        consumed = true
                    }
                }
                if (consumed) NdrDeliveryResult.CONSUMED else NdrDeliveryResult.REJECTED
            }
            NoisePayloadType.FILE_TRANSFER -> {
                val file = BitchatFilePacket.decode(payload.data)
                if (file != null) {
                    if (ndrEventId != null &&
                        state.getPrivateChatsValue()[conversationID]
                            .orEmpty()
                            .any { it.id.equals(ndrEventId, ignoreCase = true) }
                    ) {
                        return NdrDeliveryResult.DUPLICATE
                    }
                    var message: BitchatMessage? = null
                    if (!runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                            val savedPath =
                                com.bitchat.android.features.file.FileUtils.saveIncomingFile(
                                    context = application,
                                    file = file,
                                    stableId = ndrEventId
                                )
                            message = BitchatMessage(
                                id = ndrEventId ?: java.util.UUID.randomUUID().toString().uppercase(),
                                sender = senderNickname,
                                content = savedPath,
                                type = com.bitchat.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                                timestamp = timestamp,
                                isRelay = false,
                                isPrivate = true,
                                recipientNickname = state.getNicknameValue(),
                                senderPeerID = conversationID,
                                senderNostrPubkey = senderPubkey
                            )
                        }
                    ) return NdrDeliveryResult.REJECTED
                    val savedPath = message?.content
                    if (isExpired(expiresAtSeconds)) {
                        savedPath?.let { java.io.File(it).delete() }
                        return NdrDeliveryResult.REJECTED
                    }
                    var consumed = false
                    withContext(Dispatchers.Main) {
                        runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                            message?.let {
                                privateChatManager.handleIncomingPrivateMessage(
                                    message = it,
                                    suppressUnread = false,
                                    origin = PrivateMessageOrigin.NOSTR
                                )
                                consumed = true
                            }
                        }
                    }
                    if (consumed) {
                        NdrDeliveryResult.CONSUMED
                    } else {
                        if (isExpired(expiresAtSeconds)) {
                            savedPath?.let { java.io.File(it).delete() }
                        }
                        NdrDeliveryResult.REJECTED
                    }
                } else {
                    Log.w(TAG, "Failed to decode Nostr file transfer from $conversationID")
                    NdrDeliveryResult.REJECTED
                }
            }
            NoisePayloadType.VERIFY_CHALLENGE,
            NoisePayloadType.VERIFY_RESPONSE,
            NoisePayloadType.PEER_STATE,
            NoisePayloadType.NDR_EVENT ->
                NdrDeliveryResult.REJECTED // Transport controls never arrive inside relay DMs.
        }
    }

    private fun isNdrEpochCurrent(epoch: NdrAccountEpoch?): Boolean =
        epoch == null ||
            (NdrFeatureGate.isEnabled() && ndrAccountEpochs.isCurrent(epoch))

    private fun runIfNdrEpochCurrent(
        epoch: NdrAccountEpoch?,
        mutation: () -> Unit
    ): Boolean {
        if (epoch == null) {
            mutation()
            return true
        }
        if (!NdrFeatureGate.isEnabled()) return false
        return ndrAccountEpochs.runIfCurrent(epoch, mutation)
    }

    private fun isExpired(expiresAtSeconds: Long?): Boolean =
        expiresAtSeconds?.let { it <= System.currentTimeMillis() / 1_000L } == true

    private fun runIfNdrMutationCurrent(
        epoch: NdrAccountEpoch?,
        expiresAtSeconds: Long?,
        mutation: () -> Unit
    ): Boolean {
        if (isExpired(expiresAtSeconds)) return false
        var applied = false
        val epochCurrent = runIfNdrEpochCurrent(epoch) {
            if (!isExpired(expiresAtSeconds)) {
                mutation()
                applied = true
            }
        }
        return epochCurrent && applied
    }

    private fun sendDeliveryAck(
        messageId: String,
        senderPubkey: String,
        recipientIdentity: NostrIdentity,
        allowAccountNdr: Boolean
    ) {
        val nostrTransport = NostrTransport.getInstance(application)
        val targetPeerID = resolvePeerIDForNostr(senderPubkey)
            .takeIf { allowAccountNdr }
        if (targetPeerID != null) {
            nostrTransport.sendDeliveryAck(messageId, targetPeerID)
        } else {
            nostrTransport.sendDeliveryAckGeohash(messageId, senderPubkey, recipientIdentity)
        }
    }

    private fun resolvePeerIDForNostr(senderPubkey: String): String? {
        return try {
            FavoritesPersistenceService.shared.findPeerIDForNostrPubkey(senderPubkey)
                ?: FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)
                    ?.let(ContactIdentityResolver::noiseKeyHex)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun handleFavoriteControl(
        control: FavoriteControlMessage,
        conversationID: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String,
        ndrEpoch: NdrAccountEpoch? = null,
        ndrEventId: String? = null,
        expiresAtSeconds: Long? = null
    ): NdrDeliveryResult {
        return try {
            if (isExpired(expiresAtSeconds)) return NdrDeliveryResult.REJECTED
            val targetConversationID = ContactDirectory.canonicalConversationId(conversationID)
            if (ndrEventId != null &&
                state.getPrivateChatsValue()[targetConversationID]
                    .orEmpty()
                    .any { it.id.equals(ndrEventId, ignoreCase = true) }
            ) {
                return NdrDeliveryResult.DUPLICATE
            }
            val senderNpub = control.npub ?: ContactIdentityResolver.npubFromHex(senderPubkey)
            val noiseKey = senderNpub?.let { FavoritesPersistenceService.shared.findNoiseKey(it) }
                ?: FavoritesPersistenceService.shared.findNoiseKey(senderPubkey)

            if (noiseKey == null) {
                Log.w(TAG, "Favorite notification from Nostr sender without known Noise key: ${senderPubkey.take(16)}...")
                return NdrDeliveryResult.REJECTED
            }

            var systemMessage: BitchatMessage? = null
            if (!runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                    FavoritesPersistenceService.shared.updatePeerFavoritedUs(
                        noiseKey,
                        control.isFavorite
                    )
                    senderNpub?.let {
                        FavoritesPersistenceService.shared.updateNostrPublicKey(noiseKey, it)
                    }
                    val relationship = FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey)
                    val displayName = relationship
                        ?.peerNickname
                        ?.takeUnless { it.equals("Unknown", ignoreCase = true) }
                        ?: senderNickname
                    val guidance = if (control.isFavorite) {
                        if (relationship?.isFavorite == true) {
                            " - mutual! You can continue DMs via Nostr when out of mesh."
                        } else {
                            " - favorite back to continue DMs later."
                        }
                    } else {
                        ". DMs over Nostr will pause unless you both favorite again."
                    }
                    val action = if (control.isFavorite) "favorited" else "unfavorited"
                    systemMessage = BitchatMessage(
                        id = ndrEventId ?: java.util.UUID.randomUUID().toString().uppercase(),
                        sender = "system",
                        content = "$displayName $action you$guidance",
                        timestamp = timestamp,
                        isRelay = false,
                        isPrivate = true,
                        senderPeerID = targetConversationID
                    )
                }
            ) return NdrDeliveryResult.REJECTED

            var consumed = false
            var duplicate = false
            withContext(Dispatchers.Main) {
                runIfNdrMutationCurrent(ndrEpoch, expiresAtSeconds) {
                    systemMessage?.let {
                        if (state.getPrivateChatsValue()[targetConversationID]
                                .orEmpty()
                                .any { existing -> existing.id.equals(it.id, ignoreCase = true) }
                        ) {
                            duplicate = true
                        } else {
                            privateChatManager.handleIncomingPrivateMessage(
                                message = it,
                                suppressUnread = true,
                                origin = PrivateMessageOrigin.NOSTR
                            )
                            consumed = true
                        }
                    }
                }
            }
            when {
                duplicate -> NdrDeliveryResult.DUPLICATE
                consumed -> NdrDeliveryResult.CONSUMED
                else -> NdrDeliveryResult.REJECTED
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to handle Nostr favorite notification: ${e.message}")
            NdrDeliveryResult.RETRY
        }
    }

    private fun base64URLDecode(input: String): ByteArray? {
        return try {
            val padded = input.replace("-", "+")
                .replace("_", "/")
                .let { str ->
                    val padding = (4 - str.length % 4) % 4
                    str + "=".repeat(padding)
                }
            android.util.Base64.decode(padded, android.util.Base64.DEFAULT)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode base64url: ${e.message}")
            null
        }
    }
}
