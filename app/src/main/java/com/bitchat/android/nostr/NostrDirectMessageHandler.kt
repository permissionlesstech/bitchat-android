package com.bitchat.android.nostr

import android.app.Application
import android.util.Log
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.PrivateMessagePacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.ui.PrivateChatManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    // Simple event deduplication
    private val processedIds = ArrayDeque<String>()
    private val seen = HashSet<String>()
    private val max = 2000

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

    fun configureDoubleRatchet(identity: NostrIdentity) {
        ndrService.configureIfNeeded(identity)
        ndrService.onDecryptedMessage = { message ->
            onDoubleRatchetMessage(message, identity)
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

                val (content, senderPubkey, rumorTimestamp) = decryptResult

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

            } catch (e: Exception) {
                Log.e(TAG, "onGiftWrap error: ${e.message}")
            }
        }
    }

    private fun onDoubleRatchetMessage(message: NdrDecryptedMessage, identity: NostrIdentity) {
        scope.launch(Dispatchers.Default) {
            try {
                val innerEvent = message.innerEventJson?.let(NostrEvent::fromJsonString)
                val dedupeId = innerEvent?.id
                    ?: message.eventId
                    ?: "${message.senderPubkeyHex}:${message.content.hashCode()}"
                if (dedupe(dedupeId)) return@launch
                val senderPubkeyHex = innerEvent?.pubkey ?: message.senderPubkeyHex
                if (dataManager.isGeohashUserBlocked(senderPubkeyHex)) return@launch

                Log.d(
                    TAG,
                    "Received NDR message event=${message.eventId ?: "unknown"} sender=${senderPubkeyHex.take(8)}..."
                )

                processEmbeddedBitChatContent(
                    content = innerEvent?.content ?: message.content,
                    senderPubkey = senderPubkeyHex,
                    timestamp = innerEvent?.let { Date(it.createdAt * 1000L) } ?: Date(),
                    geohash = "",
                    recipientIdentity = identity
                )
            } catch (e: Exception) {
                Log.e(TAG, "onDoubleRatchetMessage error: ${e.message}")
            }
        }
    }

    private suspend fun processEmbeddedBitChatContent(
        content: String,
        senderPubkey: String,
        timestamp: Date,
        geohash: String,
        recipientIdentity: NostrIdentity
    ) {
        if (!content.startsWith("bitchat1:")) {
            Log.d(TAG, "Ignoring non-embedded Nostr DM content")
            return
        }

        val base64Content = content.removePrefix("bitchat1:")
        val packetData = base64URLDecode(base64Content) ?: run {
            Log.w(TAG, "Failed to base64url-decode embedded BitChat packet")
            return
        }
        val packet = BitchatPacket.fromBinaryData(packetData) ?: run {
            Log.w(TAG, "Failed to decode embedded BitChat packet bytes=${packetData.size}")
            return
        }
        if (packet.type != com.bitchat.android.protocol.MessageType.NOISE_ENCRYPTED.value) {
            Log.d(TAG, "Ignoring embedded BitChat packet type=${packet.type}")
            return
        }

        val noisePayload = NoisePayload.decode(packet.payload) ?: run {
            Log.w(TAG, "Failed to decode embedded Noise payload bytes=${packet.payload.size}")
            return
        }
        val convKey = "nostr_${senderPubkey.take(16)}"
        repo.putNostrKeyMapping(convKey, senderPubkey)
        GeohashAliasRegistry.put(convKey, senderPubkey)

        if (geohash.isNotEmpty()) {
            repo.setConversationGeohash(convKey, geohash)
            GeohashConversationRegistry.set(convKey, geohash)
            val cached = repo.getCachedNickname(senderPubkey)
            if (cached == null) {
                val base = repo.displayNameForNostrPubkeyUI(senderPubkey).substringBefore("#")
                repo.cacheNickname(senderPubkey, base)
            }
            repo.updateParticipant(geohash, senderPubkey, timestamp)
        }

        val senderNickname = repo.displayNameForNostrPubkeyUI(senderPubkey)
        processNoisePayload(noisePayload, convKey, senderNickname, timestamp, senderPubkey, recipientIdentity)
    }

    private suspend fun processNoisePayload(
        payload: NoisePayload,
        convKey: String,
        senderNickname: String,
        timestamp: Date,
        senderPubkey: String,
        recipientIdentity: NostrIdentity
    ) {
        when (payload.type) {
            NoisePayloadType.PRIVATE_MESSAGE -> {
                val pm = PrivateMessagePacket.decode(payload.data) ?: run {
                    Log.w(TAG, "Failed to decode Nostr private message TLV bytes=${payload.data.size}")
                    return
                }
                val existingMessages = state.getPrivateChatsValue()[convKey] ?: emptyList()
                if (existingMessages.any { it.id == pm.messageID }) return
                Log.d(TAG, "Processing embedded Nostr private message")

                val message = BitchatMessage(
                    id = pm.messageID,
                    sender = senderNickname,
                    content = pm.content,
                    timestamp = timestamp,
                    isRelay = false,
                    isPrivate = true,
                    recipientNickname = state.getNicknameValue(),
                    senderPeerID = convKey,
                    deliveryStatus = DeliveryStatus.Delivered(to = state.getNicknameValue() ?: "Unknown", at = Date())
                )

                val isViewing = state.getSelectedPrivateChatPeerValue() == convKey
                val suppressUnread = seenStore.hasRead(pm.messageID)

                withContext(Dispatchers.Main) {
                    privateChatManager.handleIncomingPrivateMessage(message, suppressUnread)
                }

                if (!seenStore.hasDelivered(pm.messageID)) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    val targetPeerID = resolvePeerIDForNostr(senderPubkey)
                    if (targetPeerID != null) {
                        nostrTransport.sendDeliveryAck(pm.messageID, targetPeerID)
                    } else {
                        nostrTransport.sendDeliveryAckGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    }
                    seenStore.markDelivered(pm.messageID)
                }

                if (isViewing && !suppressUnread) {
                    val nostrTransport = NostrTransport.getInstance(application)
                    val targetPeerID = resolvePeerIDForNostr(senderPubkey)
                    if (targetPeerID != null) {
                        nostrTransport.sendReadReceipt(
                            com.bitchat.android.model.ReadReceipt(pm.messageID),
                            targetPeerID
                        )
                    } else {
                        nostrTransport.sendReadReceiptGeohash(pm.messageID, senderPubkey, recipientIdentity)
                    }
                    seenStore.markRead(pm.messageID)
                }
            }
            NoisePayloadType.DELIVERED -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    meshDelegateHandler.didReceiveDeliveryAck(messageId, convKey)
                }
            }
            NoisePayloadType.READ_RECEIPT -> {
                val messageId = String(payload.data, Charsets.UTF_8)
                withContext(Dispatchers.Main) {
                    meshDelegateHandler.didReceiveReadReceipt(messageId, convKey)
                }
            }
            NoisePayloadType.FILE_TRANSFER -> {
                // Properly handle encrypted file transfer
                val file = BitchatFilePacket.decode(payload.data)
                if (file != null) {
                    val uniqueMsgId = java.util.UUID.randomUUID().toString().uppercase()
                    val savedPath = com.bitchat.android.features.file.FileUtils.saveIncomingFile(application, file)
                    val message = BitchatMessage(
                        id = uniqueMsgId,
                        sender = senderNickname,
                        content = savedPath,
                        type = com.bitchat.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                        timestamp = timestamp,
                        isRelay = false,
                        isPrivate = true,
                        recipientNickname = state.getNicknameValue(),
                        senderPeerID = convKey
                    )
                    Log.d(TAG, "📄 Saved Nostr encrypted incoming file to $savedPath (msgId=$uniqueMsgId)")
                    withContext(Dispatchers.Main) {
                        privateChatManager.handleIncomingPrivateMessage(message, suppressUnread = false)
                    }
                } else {
                    Log.w(TAG, "⚠️ Failed to decode Nostr file transfer from $convKey")
                }
            }
            NoisePayloadType.VERIFY_CHALLENGE,
            NoisePayloadType.VERIFY_RESPONSE,
            NoisePayloadType.NDR_EVENT -> Unit // Ignore transport-control payloads in Nostr direct messages
        }
    }

    private fun resolvePeerIDForNostr(senderPubkey: String): String? {
        return try {
            val favorites = com.bitchat.android.favorites.FavoritesPersistenceService.shared
            favorites.findPeerIDForNostrPubkey(senderPubkey)
                ?: favorites.findNoiseKey(senderPubkey)?.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            null
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
