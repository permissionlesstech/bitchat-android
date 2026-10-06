package com.bitchat.android.nostr

import android.app.Application
import android.util.Base64
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.model.*
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.services.*
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.NotificationManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Date

/** Authenticated, durable DM admission shared by live delivery and historical catch-up. */
class NostrDirectMessageHandler(
    private val application: Application,
    private val scope: CoroutineScope,
    private val displayName: (String) -> String = { "Contact" },
    private val favoritesProvider: () -> FavoritesPersistenceService = { FavoritesPersistenceService.shared },
    private val repositoryProvider: () -> ConversationRepository = { ConversationRepository.getInstance(application) },
    private val wakeDeliveries: () -> Unit = { PrivateDeliveryCoordinator.getInstance(application).wake() },
    private val isViewing: (String) -> Boolean = { conversation ->
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) &&
            AppStateStore.selectedPrivateChatPeer.value == conversation
    }
) {
    private val mutex = Mutex()
    private val repository get() = repositoryProvider()
    private val notifications by lazy { NotificationManager(application, NotificationManagerCompat.from(application)) }

    fun onGiftWrap(event: NostrEvent, geohash: String, identity: NostrIdentity) {
        val token = AppStateStore.privateConversationToken() ?: return
        scope.launch { process(event, geohash, identity, token) }
    }

    /** False means processing must be retried; malformed or unauthorized events are consumed. */
    suspend fun process(event: NostrEvent, geohash: String, identity: NostrIdentity, token: Long? = AppStateStore.privateConversationToken()): Boolean = mutex.withLock {
        if (token == null || AppStateStore.privateConversationToken() != token) return@withLock false
        try {
            val decoded = NostrProtocol.decryptPrivateMessage(event, identity) ?: return@withLock true
            val (content, sender, timestamp) = decoded
            val now = System.currentTimeMillis()
            if (timestamp.toLong() * 1000 < now - PrivateDeliveryJob.RETENTION_MS || timestamp.toLong() * 1000 > now + 900_000) return@withLock true
            if (!content.startsWith("bitchat1:") || content.length > 20_000_000) return@withLock true
            val packet = runCatching { BitchatPacket.fromBinaryData(Base64.decode(content.removePrefix("bitchat1:"), Base64.URL_SAFE or Base64.NO_WRAP)) }.getOrNull() ?: return@withLock true
            if (packet.type != MessageType.NOISE_ENCRYPTED.value) return@withLock true
            val payload = runCatching { NoisePayload.decode(packet.payload) }.getOrNull() ?: return@withLock true
            val favorites = favoritesProvider()
            val noiseKey = favorites.findNoiseKey(sender)
            val relationship = noiseKey?.let(favorites::getFavoriteStatus)
            val data = DataManager(application).apply { loadBlockedUsers(); loadGeohashBlockedUsers() }
            if (data.isGeohashUserBlocked(sender) || noiseKey?.let { data.isUserBlocked(ContactIdentityResolver.fingerprintHex(it)) } == true) return@withLock true
            val alias = requireNotNull(ContactIdentityResolver.nostrAliasForPubkey(sender))
            val conversation = noiseKey?.let(ContactIdentityResolver::contactConversationIdForNoiseKey) ?: alias
            val favorite = if (payload.type == NoisePayloadType.PRIVATE_MESSAGE) {
                PrivateMessagePacket.decode(payload.data)?.let { FavoriteControlMessage.parse(it.content) }
            } else null
            // Controls can establish mutuality, but never establish a new Noise-key binding over Nostr.
            if (favorite != null) {
                if (noiseKey == null || favorite.npub?.let(ContactIdentityResolver::nostrPubkeyHex)?.let { it != sender } == true) return@withLock true
                val pm = PrivateMessagePacket.decode(payload.data) ?: return@withLock true
                val updated = favorites.applyRemoteFavorite(noiseKey, favorite.isFavorite, packet.timestamp.toLong(), pm.messageID)
                if (updated) {
                    val notice = BitchatMessage(id = "favorite:${pm.messageID}", sender = "system",
                        content = if (favorite.isFavorite) "Contact favorited you" else "Contact unfavorited you",
                        timestamp = Date(timestamp.toLong() * 1000), isPrivate = true, senderPeerID = conversation)
                    AppStateStore.admitIncomingPrivate(notice, forceRead = true)
                }
                if (AppStateStore.privateConversationToken() != token) return@withLock false
                repository.saveDelivery(receiptJob(conversation, pm.messageID, sender, geohash, false), replace = false)
                wakeDeliveries()
                return@withLock true
            }
            val isReceipt = payload.type == NoisePayloadType.DELIVERED || payload.type == NoisePayloadType.READ_RECEIPT
            if (geohash.isEmpty() && (relationship == null || (!isReceipt && !relationship.isMutual))) return@withLock true
            GeohashAliasRegistry.put(alias, sender)
            if (geohash.isNotEmpty()) GeohashConversationRegistry.set(alias, geohash)
            when (payload.type) {
                NoisePayloadType.PRIVATE_MESSAGE -> {
                    val pm = PrivateMessagePacket.decode(payload.data) ?: return@withLock true
                    if (pm.messageID.isBlank() || pm.messageID.length > 256) return@withLock true
                    val nickname = relationship?.peerNickname?.takeUnless { it == "Unknown" } ?: displayName(sender)
                    val message = BitchatMessage(id = pm.messageID, sender = nickname, content = pm.content,
                        timestamp = Date(timestamp.toLong() * 1000), isPrivate = true, senderPeerID = conversation,
                        senderNostrPubkey = sender, deliveryStatus = DeliveryStatus.Delivered(conversation, Date()))
                    val viewing = isViewing(conversation)
                    val receipt = receiptJob(conversation, pm.messageID, sender, geohash, viewing)
                    val admitted = AppStateStore.admitIncomingPrivate(message, forceRead = viewing, receiptJob = receipt)
                    if (admitted == AppStateStore.PrivateAdmission.RETRYABLE_FAILURE) return@withLock false
                    if (admitted == AppStateStore.PrivateAdmission.REJECTED) return@withLock true
                    wakeDeliveries()
                    if (admitted == AppStateStore.PrivateAdmission.INSERTED && !viewing) {
                        // Notification permissions and channels must not affect durable delivery.
                        runCatching {
                            notifications.setAppBackgroundState(true)
                            notifications.showPrivateMessageNotification(conversation, nickname, pm.content)
                        }
                    }
                }
                NoisePayloadType.DELIVERED, NoisePayloadType.READ_RECEIPT -> {
                    val id = payload.data.toString(Charsets.UTF_8)
                    if (id.length in 1..256) AppStateStore.acknowledgePrivateReceipt(conversation, id, payload.type == NoisePayloadType.READ_RECEIPT)
                }
                NoisePayloadType.FILE_TRANSFER -> {
                    // Existing receive compatibility only. Deduplicate before creating a file.
                    val wireID = "file:" + java.security.MessageDigest.getInstance("SHA-256").digest(payload.data)
                        .joinToString("") { "%02x".format(it) }
                    val file = BitchatFilePacket.decode(payload.data) ?: return@withLock true
                    val localID = AppStateStore.incomingLocalID(conversation, wireID)
                    if (repository.isDeletedMessage(localID)) return@withLock true
                    val marker = repository.storedMessage(conversation, localID)
                    if (marker != null) return@withLock true
                    val path = com.bitchat.android.features.file.FileUtils.saveIncomingFile(application, file)
                    val message = BitchatMessage(id = localID, wireMessageID = wireID, sender = displayName(sender), content = path,
                        type = com.bitchat.android.features.file.FileUtils.messageTypeForMime(file.mimeType),
                        timestamp = Date(timestamp.toLong() * 1000), isPrivate = true, senderPeerID = conversation, senderNostrPubkey = sender)
                    if (!AppStateStore.addPrivateMessageDurably(conversation, message, forceRead = isViewing(conversation))) {
                        com.bitchat.android.features.file.FileUtils.deleteStoredMediaPaths(application, listOf(path))
                        return@withLock false
                    }
                }
                else -> Unit
            }
            true
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { false }
    }

    private fun receiptJob(conversation: String, messageID: String, sender: String, geohash: String, read: Boolean): PrivateDeliveryJob {
        val kind = if (read) PrivateDeliveryJob.Kind.READ else PrivateDeliveryJob.Kind.DELIVERED
        return PrivateDeliveryJob("${kind.name}:$conversation:$messageID", conversation, messageID, kind,
            recipientPubkey = sender, sourceGeohash = geohash.takeIf(String::isNotEmpty),
            localMessageID = AppStateStore.incomingLocalID(conversation, messageID))
    }
}
