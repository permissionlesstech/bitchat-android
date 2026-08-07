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
import com.bitchat.android.ui.PrivateChatManager
import com.bitchat.android.ui.PrivateMessageOrigin
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

class NostrDirectMessageHandler(
    private val application: Application,
    private val state: ChatState,
    private val privateChatManager: PrivateChatManager,
    private val updateDeliveryStatus: (String, DeliveryStatus) -> Unit,
    private val scope: CoroutineScope,
    private val repo: GeohashRepository,
    private val dataManager: com.bitchat.android.ui.DataManager,
    private val seenStoreProvider: () -> SeenMessageStore = {
        SeenMessageStore.getInstance(application)
    },
    private val legacyNostrInboundAllowed: (String) -> Boolean = { senderPubkey ->
        FavoritesPersistenceService.shared
            .isLegacyNostrInboundAllowed(senderPubkey)
    }
) {
    companion object { private const val TAG = "NostrDirectMessageHandler" }

    private val seenStore by lazy(seenStoreProvider)
    private val ndrService by lazy { NdrNostrService.getInstance(application) }
    private val accountLock = Any()

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

    /**
     * Begin a fresh account-wide receive epoch before installing any account or
     * derived-geohash subscription. The returned epoch must be captured by the
     * subscription handler so a delayed old subscription cannot join a newer
     * account merely because its event arrives later.
     */
    internal fun configureAccount(identity: NostrIdentity): NostrAccountEpoch =
        synchronized(accountLock) {
            val accountContext = NostrInboundAccountLifecycle.begin(
                accountPubkeyHex = identity.publicKeyHex,
                parentJob = scope.coroutineContext[Job]
            )
            // A prior account's callback must not consume and discard pending
            // deliveries while the replacement runtime is initialized.
            ndrService.onDecryptedMessage = null
            val configured = ndrService.configureIfNeeded(identity) {
                NostrInboundAccountLifecycle.isCurrent(accountContext.epoch)
            }
            if (!configured ||
                !NdrFeatureGate.isEnabled() ||
                !NostrInboundAccountLifecycle.isCurrent(accountContext.epoch)
            ) {
                return@synchronized accountContext.epoch
            }

            val callback = callback@{
                    message: NdrDecryptedMessage,
                    completion: (NdrDeliveryResult) -> Unit ->
                if (!NdrFeatureGate.isEnabled() ||
                    !NostrInboundAccountLifecycle.isCurrent(accountContext.epoch)
                ) {
                    completion(NdrDeliveryResult.REJECTED)
                    return@callback
                }
                val currentIdentity =
                    NostrIdentityBridge.getCurrentNostrIdentity(application)
                if (currentIdentity == null) {
                    completion(NdrDeliveryResult.RETRY)
                    return@callback
                }
                if (!currentIdentity.publicKeyHex.equals(
                        accountContext.epoch.accountPubkeyHex,
                        ignoreCase = true
                    )
                ) {
                    completion(NdrDeliveryResult.REJECTED)
                    return@callback
                }
                onDoubleRatchetMessage(
                    message,
                    currentIdentity,
                    accountContext.epoch,
                    accountContext.receiveScope,
                    completion
                )
            }
            ndrService.onDecryptedMessage = callback
            if (!NostrInboundAccountLifecycle.isCurrent(accountContext.epoch)) {
                ndrService.onDecryptedMessage = null
                return@synchronized accountContext.epoch
            }
            accountContext.epoch
        }

    internal fun currentAccountEpoch(): NostrAccountEpoch? =
        NostrInboundAccountLifecycle.currentEpoch()

    fun invalidateAccount() {
        synchronized(accountLock) {
            NostrInboundAccountLifecycle.invalidate()
            ndrService.onDecryptedMessage = null
            synchronized(this) {
                processedIds.clear()
                seen.clear()
            }
        }
    }

    internal fun onGiftWrap(
        giftWrap: NostrEvent,
        geohash: String,
        identity: NostrIdentity,
        accountEpoch: NostrAccountEpoch
    ): Job? {
        val accountContext =
            NostrInboundAccountLifecycle.contextFor(accountEpoch)
                ?: return null
        return accountContext.receiveScope.launch {
            try {
                if (!isAccountEpochCurrent(accountEpoch)) return@launch
                var duplicate = false
                if (!runIfAccountMutationCurrent(accountEpoch, null) {
                        duplicate = dedupe(giftWrap.id)
                    }
                ) return@launch
                if (duplicate) return@launch

                val messageAge = System.currentTimeMillis() / 1000 - giftWrap.createdAt
                if (messageAge > 173700) return@launch // 48 hours + 15 mins

                val decryptResult = NostrProtocol.decryptPrivateMessage(giftWrap, identity)
                if (decryptResult == null) {
                    Log.w(TAG, "Failed to decrypt Nostr message")
                    return@launch
                }

                val (content, rawSenderPubkey, rumorTimestamp) = decryptResult
                val senderPubkey = rawSenderPubkey.lowercase()
                if (!isAccountEpochCurrent(accountEpoch)) return@launch
                val legacyAllowed = runCatching {
                    legacyNostrInboundAllowed(senderPubkey)
                }.getOrDefault(false)
                if (!legacyAllowed) {
                    Log.w(TAG, "Rejecting legacy DM for an NDR-pinned contact")
                    return@launch
                }
                if (!isAccountEpochCurrent(accountEpoch)) return@launch

                // If sender is blocked for geohash contexts, drop any events from this pubkey
                // Applies to both geohash DMs (geohash != "") and account DMs (geohash == "")
                if (dataManager.isGeohashUserBlocked(senderPubkey)) return@launch
                processEmbeddedBitChatContent(
                    content = content,
                    senderPubkey = senderPubkey,
                    timestamp = Date(rumorTimestamp * 1000L),
                    geohash = geohash,
                    recipientIdentity = identity,
                    accountEpoch = accountEpoch
                )

            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Log.e(TAG, "Failed to process gift wrap")
            }
        }
    }

    private fun onDoubleRatchetMessage(
        message: NdrDecryptedMessage,
        identity: NostrIdentity,
        accountEpoch: NostrAccountEpoch,
        receiveScope: CoroutineScope,
        completion: (NdrDeliveryResult) -> Unit
    ) {
        receiveScope.launch {
            var result = NdrDeliveryResult.RETRY
            try {
                if (!NdrFeatureGate.isEnabled() ||
                    !isAccountEpochCurrent(accountEpoch)
                ) {
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
                if (!NdrFeatureGate.isEnabled() ||
                    !isAccountEpochCurrent(accountEpoch)
                ) {
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
                    accountEpoch = accountEpoch,
                    ndrEventId = dedupeId,
                    expiresAtSeconds = applicationMessage.expiresAtSeconds
                )
            } catch (_: CancellationException) {
                result = NdrDeliveryResult.REJECTED
            } catch (_: Exception) {
                Log.e(TAG, "Failed to process double-ratchet message")
                result = NdrDeliveryResult.RETRY
            } finally {
                if (!isAccountEpochCurrent(accountEpoch)) {
                    result = NdrDeliveryResult.REJECTED
                } else if (result.shouldAcknowledge) {
                    var marked = false
                    val mutationApplied =
                        runIfAccountMutationCurrent(accountEpoch, null) {
                            marked = seenStore.markProcessedNdr(message.eventId)
                            if (marked) {
                                markProcessed(message.eventId)
                            }
                        }
                    if (!mutationApplied) {
                        result = NdrDeliveryResult.REJECTED
                    } else if (!marked) {
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
        accountEpoch: NostrAccountEpoch,
        ndrEventId: String? = null,
        expiresAtSeconds: Long? = null
    ): NdrDeliveryResult {
        if (!isAccountEpochCurrent(accountEpoch)) return NdrDeliveryResult.REJECTED
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
        if (!runIfAccountMutationCurrent(accountEpoch, expiresAtSeconds) {
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
            accountEpoch = accountEpoch,
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
        accountEpoch: NostrAccountEpoch,
        ndrEventId: String? = null,
        expiresAtSeconds: Long? = null
    ): NdrDeliveryResult {
        if (!isAccountEpochCurrent(accountEpoch) || isExpired(expiresAtSeconds)) {
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
                    if (!isAccountEpochCurrent(accountEpoch) ||
                        isExpired(expiresAtSeconds)
                    ) {
                        return NdrDeliveryResult.REJECTED
                    }
                    val favoriteResult = handleFavoriteControl(
                        favoriteControl,
                        conversationID,
                        senderNickname,
                        timestamp,
                        senderPubkey,
                        accountEpoch,
                        ndrEventId,
                        expiresAtSeconds
                    )
                    if (favoriteResult != NdrDeliveryResult.CONSUMED &&
                        favoriteResult != NdrDeliveryResult.DUPLICATE
                    ) return favoriteResult
                    if (!runIfAccountMutationCurrent(accountEpoch, expiresAtSeconds) {
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
                val suppressUnread = seenStore.hasBeenReadLocally(pm.messageID)

                val messageAccepted = withContext(Dispatchers.Main) {
                    if (!isAccountEpochCurrent(accountEpoch) || isExpired(expiresAtSeconds)) {
                        false
                    } else {
                        privateChatManager.handleIncomingPrivateMessageDurably(
                            message = message,
                            suppressUnread = suppressUnread,
                            origin = PrivateMessageOrigin.NOSTR
                        )
                    }
                }
                if (!messageAccepted) {
                    return if (com.bitchat.android.services.AppStateStore
                            .hasSeenMessage(pm.messageID)
                    ) {
                        NdrDeliveryResult.DUPLICATE
                    } else {
                        NdrDeliveryResult.RETRY
                    }
                }

                runCatching {
                    runIfAccountMutationCurrent(accountEpoch, expiresAtSeconds) {
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
                            seenStore.markReadLocally(pm.messageID)
                            seenStore.markReadReceiptSent(pm.messageID)
                        }
                    }
                }
                NdrDeliveryResult.CONSUMED
            }
            NoisePayloadType.DELIVERED -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                var consumed = false
                withContext(Dispatchers.Main) {
                    runIfAccountMutationCurrent(accountEpoch, expiresAtSeconds) {
                        updateDeliveryStatus(
                            messageId,
                            DeliveryStatus.Delivered(conversationID, Date())
                        )
                        consumed = true
                    }
                }
                if (consumed) NdrDeliveryResult.CONSUMED else NdrDeliveryResult.REJECTED
            }
            NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                var consumed = false
                withContext(Dispatchers.Main) {
                    runIfAccountMutationCurrent(accountEpoch, expiresAtSeconds) {
                        updateDeliveryStatus(
                            messageId,
                            DeliveryStatus.Read(conversationID, Date())
                        )
                        consumed = true
                    }
                }
                if (consumed) NdrDeliveryResult.CONSUMED else NdrDeliveryResult.REJECTED
            }
            NoisePayloadType.FILE_TRANSFER -> {
                val file = BitchatFilePacket.decode(payload.data)
                if (file != null) {
                    var savedPath: String? = null
                    var retained = false
                    try {
                        if (ndrEventId != null &&
                            com.bitchat.android.services.AppStateStore
                                .hasSeenMessage(ndrEventId)
                        ) {
                            return NdrDeliveryResult.DUPLICATE
                        }
                        if (!isAccountEpochCurrent(accountEpoch) || isExpired(expiresAtSeconds)) {
                            return NdrDeliveryResult.REJECTED
                        }

                        val path = com.bitchat.android.features.file.FileUtils.saveIncomingFile(
                            context = application,
                            file = file,
                            stableId = ndrEventId
                        )
                        savedPath = path
                        val message = BitchatMessage(
                            id = ndrEventId
                                ?: java.util.UUID.randomUUID().toString().uppercase(),
                            sender = senderNickname,
                            content = path,
                            type = com.bitchat.android.features.file.FileUtils
                                .messageTypeForMime(file.mimeType),
                            timestamp = timestamp,
                            isRelay = false,
                            isPrivate = true,
                            recipientNickname = state.getNicknameValue(),
                            senderPeerID = conversationID,
                            senderNostrPubkey = senderPubkey
                        )
                        val admitted = withContext(Dispatchers.Main) {
                            if (!isAccountEpochCurrent(accountEpoch) ||
                                isExpired(expiresAtSeconds)
                            ) {
                                false
                            } else {
                                privateChatManager.handleIncomingPrivateMessageDurably(
                                    message = message,
                                    suppressUnread = false,
                                    origin = PrivateMessageOrigin.NOSTR
                                )
                            }
                        }
                        if (!admitted) {
                            return if (com.bitchat.android.services.AppStateStore
                                    .hasSeenMessage(message.id)
                            ) {
                                NdrDeliveryResult.DUPLICATE
                            } else {
                                NdrDeliveryResult.RETRY
                            }
                        }
                        retained = true
                        NdrDeliveryResult.CONSUMED
                    } finally {
                        if (!retained) {
                            savedPath?.let {
                                com.bitchat.android.features.file.FileUtils
                                    .deleteStoredMediaPaths(application, listOf(it))
                            }
                        }
                    }
                } else {
                    Log.w(TAG, "Failed to decode Nostr file transfer from $conversationID")
                    NdrDeliveryResult.REJECTED
                }
            }
            NoisePayloadType.VERIFY_CHALLENGE,
            NoisePayloadType.VERIFY_RESPONSE,
            NoisePayloadType.VOICE_FRAME,
            NoisePayloadType.PEER_STATE,
            NoisePayloadType.NDR_EVENT ->
                NdrDeliveryResult.REJECTED // Transport controls never arrive inside relay DMs.
        }
    }

    private fun isAccountEpochCurrent(epoch: NostrAccountEpoch): Boolean =
        NostrInboundAccountLifecycle.isCurrent(epoch)

    private fun isExpired(expiresAtSeconds: Long?): Boolean =
        expiresAtSeconds?.let { it <= System.currentTimeMillis() / 1_000L } == true

    private fun runIfAccountMutationCurrent(
        epoch: NostrAccountEpoch,
        expiresAtSeconds: Long?,
        mutation: () -> Unit
    ): Boolean {
        if (isExpired(expiresAtSeconds)) return false
        var applied = false
        val epochCurrent = NostrInboundAccountLifecycle.runIfCurrent(epoch) {
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
        accountEpoch: NostrAccountEpoch,
        ndrEventId: String? = null,
        expiresAtSeconds: Long? = null
    ): NdrDeliveryResult {
        return try {
            if (isExpired(expiresAtSeconds)) return NdrDeliveryResult.REJECTED
            val targetConversationID = ContactDirectory.canonicalConversationId(conversationID)
            if (ndrEventId != null &&
                com.bitchat.android.services.AppStateStore.hasSeenMessage(ndrEventId)
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
            if (!runIfAccountMutationCurrent(accountEpoch, expiresAtSeconds) {
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
            val pendingMessage = systemMessage ?: return NdrDeliveryResult.REJECTED
            withContext(Dispatchers.Main) {
                if (isAccountEpochCurrent(accountEpoch) && !isExpired(expiresAtSeconds)) {
                    consumed = privateChatManager.handleIncomingPrivateMessageDurably(
                        message = pendingMessage,
                        suppressUnread = true,
                        origin = PrivateMessageOrigin.NOSTR
                    )
                }
            }
            when {
                consumed -> NdrDeliveryResult.CONSUMED
                com.bitchat.android.services.AppStateStore
                    .hasSeenMessage(pendingMessage.id) -> NdrDeliveryResult.DUPLICATE
                else -> NdrDeliveryResult.RETRY
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
