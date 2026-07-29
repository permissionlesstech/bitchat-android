package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide in-memory state store that survives Activity recreation.
 * The foreground Mesh service updates this store; UI subscribes/hydrates from it.
 */
object AppStateStore {
    // Global de-dup set by message id to avoid duplicate keys in Compose lists
    private val seenMessageIds = mutableSetOf<String>()
    private val seenPublicMessageKeys = mutableSetOf<String>()
    private val peerIdsByTransport = mutableMapOf<String, Set<String>>()
    private var privateWritesSinceGlobalPrune = 0
    // Direct (single-hop) peer IDs per transport, used to gossip a unified neighbor set.
    private val directPeerIdsByTransport = mutableMapOf<String, Set<String>>()
    private val _directPeers = MutableStateFlow<Set<String>>(emptySet())
    val directPeers: StateFlow<Set<String>> = _directPeers.asStateFlow()
    // Connected peer IDs (mesh ephemeral IDs)
    private val _peers = MutableStateFlow<List<String>>(emptyList())
    val peers: StateFlow<List<String>> = _peers.asStateFlow()

    // Public mesh timeline messages (non-channel)
    private val _publicMessages = MutableStateFlow<List<BitchatMessage>>(emptyList())
    val publicMessages: StateFlow<List<BitchatMessage>> = _publicMessages.asStateFlow()

    // Private messages by peerID
    private val _privateMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val privateMessages: StateFlow<Map<String, List<BitchatMessage>>> = _privateMessages.asStateFlow()
    private val _readPrivateMessageIDs = MutableStateFlow<Set<String>>(emptySet())
    val readPrivateMessageIDs: StateFlow<Set<String>> = _readPrivateMessageIDs.asStateFlow()

    @Volatile
    private var conversationRepository: ConversationRepository? = null

    private val _nickname = MutableStateFlow("")
    val nickname: StateFlow<String> = _nickname.asStateFlow()

    private val _selectedPrivateChatPeer = MutableStateFlow<String?>(null)
    val selectedPrivateChatPeer: StateFlow<String?> = _selectedPrivateChatPeer.asStateFlow()

    // Channel messages by channel name
    private val _channelMessages = MutableStateFlow<Map<String, List<BitchatMessage>>>(emptyMap())
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = _channelMessages.asStateFlow()

    fun setPeers(ids: List<String>) {
        synchronized(this) {
            _peers.value = ids.distinct()
        }
    }

    fun setNickname(nickname: String) {
        _nickname.value = nickname
    }

    fun setSelectedPrivateChatPeer(peerID: String?) {
        _selectedPrivateChatPeer.value = peerID
    }

    fun initializeConversationPersistence(context: Context) {
        val repository = ConversationRepository.getInstance(context.applicationContext)
        conversationRepository = repository
        repository.initialize(::restorePrivateConversations)
    }

    /**
     * Restores database state again for a newly created UI, even if Android reused this process
     * after a controlled shutdown cleared the process-wide state.
     */
    fun reloadConversationPersistence(context: Context) {
        val repository = ConversationRepository.getInstance(context.applicationContext)
        conversationRepository = repository
        repository.reload(::restorePrivateConversations)
    }

    suspend fun awaitConversationPersistence() {
        conversationRepository?.awaitPendingWrites()
    }

    fun setTransportPeers(transportId: String, ids: List<String>) {
        synchronized(this) {
            peerIdsByTransport[transportId] = ids.toSet()
            publishTransportPeersLocked()
        }
    }

    fun clearTransportPeers(transportId: String) {
        synchronized(this) {
            peerIdsByTransport.remove(transportId)
            publishTransportPeersLocked()
        }
    }

    private fun publishTransportPeersLocked() {
        _peers.value = peerIdsByTransport.values
            .asSequence()
            .flatten()
            .distinct()
            .toList()
    }

    /**
     * Record the set of direct (single-hop) peers reachable over a given transport. Each transport
     * (BLE, Wi-Fi Aware, ...) only knows its own direct peers; [getDirectPeers] unions them so every
     * transport can gossip the same complete neighbor list under our shared node identity.
     */
    fun setTransportDirectPeers(transportId: String, ids: Collection<String>) {
        synchronized(this) {
            directPeerIdsByTransport[transportId] = ids.toSet()
            publishDirectPeersLocked()
        }
    }

    fun clearTransportDirectPeers(transportId: String) {
        synchronized(this) {
            directPeerIdsByTransport.remove(transportId)
            publishDirectPeersLocked()
        }
    }

    /** Union of direct peers across all transports. */
    fun getDirectPeers(): Set<String> = _directPeers.value

    private fun publishDirectPeersLocked() {
        _directPeers.value = directPeerIdsByTransport.values
            .asSequence()
            .flatten()
            .toSet()
    }

    fun addPublicMessage(msg: BitchatMessage) {
        synchronized(this) {
            val publicKey = publicMessageKey(msg)
            if (seenMessageIds.contains(msg.id) || seenPublicMessageKeys.contains(publicKey)) return
            seenMessageIds.add(msg.id)
            seenPublicMessageKeys.add(publicKey)
            _publicMessages.value = _publicMessages.value + msg
        }
    }

    fun addPrivateMessage(
        peerID: String,
        msg: BitchatMessage,
        forceRead: Boolean = false
    ): Boolean = synchronized(this) {
        if (seenMessageIds.contains(msg.id)) return@synchronized false
        seenMessageIds.add(msg.id)
        PrivateMessageArrivalOrder.record(msg.id)
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val map = _privateMessages.value.toMutableMap()
        val list = (map[conversationID] ?: emptyList()) + msg
        map[conversationID] = list
        _privateMessages.value = ContactDirectory.canonicalizePrivateChats(map)

        val isRead = forceRead ||
            msg.sender == "system" ||
            msg.sender == _nickname.value ||
            _selectedPrivateChatPeer.value
                ?.let(ContactDirectory::canonicalConversationId)
                ?.equals(conversationID, ignoreCase = true) == true
        if (isRead) {
            _readPrivateMessageIDs.value = _readPrivateMessageIDs.value + msg.id
        }
        val aliases = runCatching {
            ContactDirectory.aliasesForConversation(peerID) +
                ContactDirectory.aliasesForConversation(conversationID) +
                listOfNotNull(msg.senderPeerID)
        }.getOrDefault(setOf(peerID, conversationID))
        val displayName = ContactDirectory.resolve(conversationID).displayName
            ?: msg.sender.takeUnless {
                it.isBlank() || it == "system" || it == _nickname.value
            }
        conversationRepository?.upsertMessage(
            conversationID = conversationID,
            aliases = aliases,
            displayName = displayName,
            message = msg,
            isRead = isRead
        )
        prunePrivateMessagesLocked(conversationID)
        true
    }

    fun hasSeenMessage(messageID: String): Boolean = synchronized(this) {
        messageID in seenMessageIds
    }

    private fun statusPriority(status: DeliveryStatus?): Int = when (status) {
        null -> 0
        is DeliveryStatus.Sending -> 1
        is DeliveryStatus.Sent -> 2
        is DeliveryStatus.PartiallyDelivered -> 3
        is DeliveryStatus.Delivered -> 4
        is DeliveryStatus.Read -> 5
        is DeliveryStatus.Failed -> 0
    }

    fun updatePrivateMessageStatus(messageID: String, status: DeliveryStatus) {
        synchronized(this) {
            val map = _privateMessages.value.toMutableMap()
            var changed = false
            map.keys.toList().forEach { peer ->
                val list = map[peer]?.toMutableList() ?: mutableListOf()
                val idx = list.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    val current = list[idx].deliveryStatus
                    // Do not downgrade (e.g., Read -> Delivered)
                    if (statusPriority(status) >= statusPriority(current)) {
                        list[idx] = list[idx].copy(deliveryStatus = status)
                        map[peer] = list
                        changed = true
                    }
                }
            }
            if (changed) {
                _privateMessages.value = map
                conversationRepository?.updateDeliveryStatus(messageID, status)
            }
        }
    }

    fun unifyPrivateChatsIntoPeer(targetPeerID: String, keysToMerge: List<String>) {
        if (keysToMerge.isEmpty()) return
        synchronized(this) {
            val targetConversationID = ContactDirectory.canonicalConversationId(targetPeerID)
            val persistenceAliases = (keysToMerge + targetPeerID + targetConversationID)
                .flatMap { key ->
                    runCatching {
                        ContactDirectory.aliasesForConversation(key).toList()
                    }.getOrDefault(listOf(key))
                }
                .toSet()
            conversationRepository?.mergeAliases(targetConversationID, persistenceAliases)
            val map = _privateMessages.value.toMutableMap()
            val targetList = (map[targetConversationID] ?: emptyList()).toMutableList()
            val targetIds = targetList.map { it.id }.toMutableSet()
            var changed = false

            keysToMerge.distinct().forEach { key ->
                val canonicalKey = ContactDirectory.canonicalConversationId(key)
                if (canonicalKey == targetConversationID) {
                    val messages = map.remove(key)
                    if (messages != null) {
                        changed = true
                        messages.forEach { message ->
                            if (targetIds.add(message.id)) targetList.add(message)
                        }
                    }
                    return@forEach
                }
                if (key == targetConversationID) return@forEach
                val messages = map.remove(key) ?: return@forEach
                changed = true
                messages.forEach { message ->
                    if (targetIds.add(message.id)) {
                        targetList.add(message)
                    }
                }
            }

            if (changed) {
                if (targetList.isEmpty()) {
                    map.remove(targetConversationID)
                } else {
                    map[targetConversationID] = targetList
                }
                _privateMessages.value = ContactDirectory.canonicalizePrivateChats(map)
            }
        }
    }

    fun canonicalizePrivateChats() {
        synchronized(this) {
            val canonical = ContactDirectory.canonicalizePrivateChats(_privateMessages.value)
            if (canonical != _privateMessages.value) {
                _privateMessages.value = canonical
            }
        }
    }

    fun markPrivateMessageRead(messageID: String) {
        synchronized(this) {
            if (messageID in _readPrivateMessageIDs.value) return
            _readPrivateMessageIDs.value = _readPrivateMessageIDs.value + messageID
            conversationRepository?.markRead(messageID)
        }
    }

    fun isPrivateMessageRead(messageID: String): Boolean =
        messageID in _readPrivateMessageIDs.value

    fun deletePrivateConversation(peerOrConversationID: String): Set<String> {
        synchronized(this) {
            val canonicalID = ContactDirectory.canonicalConversationId(peerOrConversationID)
            val matchingKeys = _privateMessages.value.keys.filterTo(linkedSetOf()) { key ->
                ContactDirectory.canonicalConversationId(key)
                    .equals(canonicalID, ignoreCase = true)
            }
            val aliases = (matchingKeys + peerOrConversationID + canonicalID)
                .flatMap { key ->
                    runCatching {
                        ContactDirectory.aliasesForConversation(key).toList()
                    }.getOrDefault(listOf(key))
                }
                .toSet()
            val messageIDs = matchingKeys
                .flatMapTo(linkedSetOf()) { _privateMessages.value[it].orEmpty().map { it.id } }

            // Queue the database deletion while holding the same lock used by addPrivateMessage.
            // A genuinely new arrival is therefore queued after the delete and starts a fresh chat.
            conversationRepository?.deleteConversation(canonicalID, aliases)

            val updated = _privateMessages.value.toMutableMap()
            matchingKeys.forEach(updated::remove)
            _privateMessages.value = updated
            _readPrivateMessageIDs.value = _readPrivateMessageIDs.value - messageIDs
            if (
                _selectedPrivateChatPeer.value
                    ?.let(ContactDirectory::canonicalConversationId)
                    ?.equals(canonicalID, ignoreCase = true) == true
            ) {
                _selectedPrivateChatPeer.value = null
            }
            return messageIDs
        }
    }

    fun removePrivateMessage(messageID: String) {
        synchronized(this) {
            val updated = _privateMessages.value.toMutableMap()
            var changed = false
            updated.keys.toList().forEach { conversationID ->
                val messages = updated[conversationID].orEmpty()
                if (messages.any { it.id == messageID }) {
                    val remaining = messages.filterNot { it.id == messageID }
                    if (remaining.isEmpty()) {
                        updated.remove(conversationID)
                    } else {
                        updated[conversationID] = remaining
                    }
                    changed = true
                }
            }
            if (!changed) return
            conversationRepository?.deleteMessage(messageID)
            _privateMessages.value = updated
            _readPrivateMessageIDs.value = _readPrivateMessageIDs.value - messageID
        }
    }

    fun clearPersistedPrivateConversations() {
        synchronized(this) {
            conversationRepository?.clearAll()
            _privateMessages.value = emptyMap()
            _readPrivateMessageIDs.value = emptySet()
            _selectedPrivateChatPeer.value = null
        }
    }

    fun addChannelMessage(channel: String, msg: BitchatMessage) {
        synchronized(this) {
            if (seenMessageIds.contains(msg.id)) return
            seenMessageIds.add(msg.id)
            val map = _channelMessages.value.toMutableMap()
            val list = (map[channel] ?: emptyList()) + msg
            map[channel] = list
            _channelMessages.value = map
        }
    }

    // Clear all in-memory state (used for full app shutdown)
    fun clear() {
        synchronized(this) {
            seenMessageIds.clear()
            seenPublicMessageKeys.clear()
            PrivateMessageArrivalOrder.clear()
            privateWritesSinceGlobalPrune = 0
            peerIdsByTransport.clear()
            directPeerIdsByTransport.clear()
            _peers.value = emptyList()
            _directPeers.value = emptySet()
            _publicMessages.value = emptyList()
            _privateMessages.value = emptyMap()
            _readPrivateMessageIDs.value = emptySet()
            _channelMessages.value = emptyMap()
            _nickname.value = ""
            _selectedPrivateChatPeer.value = null
        }
    }

    private fun publicMessageKey(msg: BitchatMessage): String {
        val sender = msg.senderPeerID ?: msg.sender
        return listOf(
            sender,
            msg.timestamp.time.toString(),
            msg.type.name,
            msg.channel ?: "",
            msg.content
        ).joinToString("\u001F")
    }

    private fun restorePrivateConversations(snapshot: PersistedConversationSnapshot) {
        synchronized(this) {
            val liveChats = _privateMessages.value
            val liveMessageIDs = liveChats.values.flatten().map { it.id }
            PrivateMessageArrivalOrder.restore(snapshot.arrivalOrder, liveMessageIDs)

            val merged = linkedMapOf<String, MutableList<BitchatMessage>>()
            snapshot.chats.forEach { (conversationID, messages) ->
                merged.getOrPut(conversationID) { mutableListOf() }.addAll(messages)
            }
            liveChats.forEach { (conversationID, messages) ->
                val target = merged.getOrPut(conversationID) { mutableListOf() }
                messages
                    .filterNot { it.id in snapshot.deletedMessageIDs }
                    .forEach { live ->
                        val existingIndex = target.indexOfFirst { it.id == live.id }
                        if (existingIndex >= 0) {
                            target[existingIndex] = live
                        } else {
                            target.add(live)
                        }
                    }
            }

            merged.values.flatten().forEach { seenMessageIds.add(it.id) }
            seenMessageIds.addAll(snapshot.deletedMessageIDs)
            _readPrivateMessageIDs.value =
                (snapshot.readMessageIDs + _readPrivateMessageIDs.value) -
                    snapshot.deletedMessageIDs
            _privateMessages.value = ContactDirectory.canonicalizePrivateChats(
                merged.mapValues { (_, messages) ->
                    PrivateMessageArrivalOrder.order(messages.distinctBy { it.id })
                }
            )
        }
    }

    private fun prunePrivateMessagesLocked(recentConversationID: String) {
        val chats = _privateMessages.value.toMutableMap()
        val removedIDs = linkedSetOf<String>()
        val recentKey = chats.keys.firstOrNull { key ->
            ContactDirectory.canonicalConversationId(key)
                .equals(recentConversationID, ignoreCase = true)
        }
        if (recentKey != null) {
            val messages = chats[recentKey].orEmpty()
            val excess =
                messages.size - ConversationDatabase.MAX_MESSAGES_PER_CONVERSATION
            if (excess > 0) {
                val removable = messages
                    .dropLast(1)
                    .sortedWith(privateMessagePruneComparator())
                    .take(excess)
                    .mapTo(linkedSetOf()) { it.id }
                removedIDs.addAll(removable)
                chats[recentKey] = messages.filterNot { it.id in removable }
            }
        }

        privateWritesSinceGlobalPrune += 1
        if (privateWritesSinceGlobalPrune >= 64) {
            privateWritesSinceGlobalPrune = 0
            var totalMessages = chats.values.sumOf { it.size }
            var totalPayloadBytes = chats.values
                .asSequence()
                .flatten()
                .sumOf(::privateMessagePayloadBytes)
            if (
                totalMessages > ConversationDatabase.MAX_MESSAGES_TOTAL ||
                totalPayloadBytes > ConversationDatabase.MAX_PAYLOAD_BYTES
            ) {
                val candidates = chats.values
                    .asSequence()
                    .flatMap { messages -> messages.dropLast(1).asSequence() }
                    .sortedWith(privateMessagePruneComparator())
                    .iterator()
                while (
                    candidates.hasNext() &&
                    (
                        totalMessages > ConversationDatabase.MAX_MESSAGES_TOTAL ||
                            totalPayloadBytes > ConversationDatabase.MAX_PAYLOAD_BYTES
                        )
                ) {
                    val candidate = candidates.next()
                    if (!removedIDs.add(candidate.id)) continue
                    totalMessages -= 1
                    totalPayloadBytes -= privateMessagePayloadBytes(candidate)
                }
                if (
                    totalMessages > ConversationDatabase.MAX_MESSAGES_TOTAL ||
                    totalPayloadBytes > ConversationDatabase.MAX_PAYLOAD_BYTES
                ) {
                    // Enforce the hard bound even when every conversation contains only its
                    // newest message. Read conversations still sort ahead of unread ones.
                    val latestCandidates = chats.values
                        .asSequence()
                        .flatten()
                        .filterNot { it.id in removedIDs }
                        .sortedWith(privateMessagePruneComparator())
                        .iterator()
                    while (
                        latestCandidates.hasNext() &&
                        (
                            totalMessages > ConversationDatabase.MAX_MESSAGES_TOTAL ||
                                totalPayloadBytes > ConversationDatabase.MAX_PAYLOAD_BYTES
                            )
                    ) {
                        val candidate = latestCandidates.next()
                        if (!removedIDs.add(candidate.id)) continue
                        totalMessages -= 1
                        totalPayloadBytes -= privateMessagePayloadBytes(candidate)
                    }
                }
                if (removedIDs.isNotEmpty()) {
                    chats.keys.toList().forEach { key ->
                        chats[key] = chats[key].orEmpty().filterNot {
                            it.id in removedIDs
                        }
                    }
                }
            }
        }

        if (removedIDs.isNotEmpty()) {
            _privateMessages.value = chats.filterValues { it.isNotEmpty() }
            _readPrivateMessageIDs.value = _readPrivateMessageIDs.value - removedIDs
        }
    }

    private fun privateMessagePruneComparator(): Comparator<BitchatMessage> =
        compareByDescending<BitchatMessage> {
            it.id in _readPrivateMessageIDs.value
        }.thenBy {
            PrivateMessageArrivalOrder.sequenceOf(it.id) ?: Long.MAX_VALUE
        }

    private fun privateMessagePayloadBytes(message: BitchatMessage): Long =
        message.content.toByteArray(Charsets.UTF_8).size.toLong() +
            (message.encryptedContent?.size ?: 0) +
            message.mentions.orEmpty().sumOf {
                it.toByteArray(Charsets.UTF_8).size
            }
}
