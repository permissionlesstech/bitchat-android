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
    private val reservedPrivateMessageIds = mutableSetOf<String>()
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
    private val _unreadPrivateMessageCounts = MutableStateFlow<Map<String, Int>>(emptyMap())
    val unreadPrivateMessageCounts: StateFlow<Map<String, Int>> =
        _unreadPrivateMessageCounts.asStateFlow()
    private val _privateConversationDisplayNames =
        MutableStateFlow<Map<String, String>>(emptyMap())
    val privateConversationDisplayNames: StateFlow<Map<String, String>> =
        _privateConversationDisplayNames.asStateFlow()

    @Volatile
    private var conversationRepository: ConversationRepository? = null
    private var privateConversationWritesSuspended = false
    private var privateConversationGeneration = 0L

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

    internal fun setConversationRepositoryForTest(repository: ConversationRepository?) {
        conversationRepository = repository
    }

    suspend fun awaitConversationPersistence() {
        conversationRepository?.awaitPendingWrites()
    }

    suspend fun loadPrivateConversationHistory(conversationID: String): Boolean {
        val repository = conversationRepository ?: return false
        val snapshot = repository.loadConversationAndWait(
            ContactDirectory.canonicalConversationId(conversationID)
        ) ?: return false
        restorePrivateConversations(snapshot)
        return true
    }

    /**
     * Drops an opened conversation's full payloads from memory while retaining its summary row.
     * The complete bounded history remains encrypted in SQLite and is loaded again on demand.
     */
    fun releasePrivateConversationHistory(conversationID: String) {
        synchronized(this) {
            val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
            val matching = _privateMessages.value.entries.filter { (id, _) ->
                ContactDirectory.canonicalConversationId(id)
                    .equals(canonicalID, ignoreCase = true)
            }
            val latest = matching
                .flatMap { it.value }
                .distinctBy { it.id }
                .maxWithOrNull(
                    compareBy<BitchatMessage> {
                        PrivateMessageArrivalOrder.sequenceOf(it.id) ?: Long.MIN_VALUE
                    }.thenBy { it.timestamp.time }
                )
                ?: return
            val compacted = _privateMessages.value.toMutableMap()
            matching.forEach { compacted.remove(it.key) }
            compacted[canonicalID] = listOf(latest)
            _privateMessages.value = compacted
        }
    }

    val conversationStoreState: StateFlow<ConversationStoreState>
        get() = conversationRepository?.storeState ?: EMPTY_CONVERSATION_STORE_STATE

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

    /** Replace a live media row by ID, or append it if the row was not admitted yet. */
    fun upsertPublicMessage(msg: BitchatMessage) {
        synchronized(this) {
            val index = _publicMessages.value.indexOfFirst { it.id == msg.id }
            if (index >= 0) {
                _publicMessages.value = _publicMessages.value.toMutableList().also { it[index] = msg }
            } else {
                seenMessageIds.add(msg.id)
                seenPublicMessageKeys.add(publicMessageKey(msg))
                _publicMessages.value = _publicMessages.value + msg
            }
        }
    }

    fun removePublicMessage(messageID: String) {
        synchronized(this) {
            val existing = _publicMessages.value.firstOrNull { it.id == messageID } ?: return
            _publicMessages.value = _publicMessages.value.filterNot { it.id == messageID }
            seenMessageIds.remove(messageID)
            seenPublicMessageKeys.remove(publicMessageKey(existing))
        }
    }

    fun addPrivateMessage(
        peerID: String,
        msg: BitchatMessage,
        forceRead: Boolean = false
    ): Boolean = synchronized(this) {
        addPrivateMessageLocked(peerID, msg, forceRead, persistAsynchronously = true)
    }

    /** Replace-or-append used by a live voice row as its partial file becomes final media. */
    fun upsertPrivateMessage(peerID: String, msg: BitchatMessage, forceRead: Boolean = false) {
        synchronized(this) {
            if (privateConversationWritesSuspended) return
            val canonicalID = ContactDirectory.canonicalConversationId(peerID)
            val map = _privateMessages.value.toMutableMap()
            val matchingKey = map.keys.firstOrNull {
                ContactDirectory.canonicalConversationId(it).equals(canonicalID, ignoreCase = true)
            } ?: canonicalID
            val messages = map[matchingKey].orEmpty().toMutableList()
            val index = messages.indexOfFirst { it.id == msg.id }
            if (index >= 0) {
                messages[index] = msg
            } else {
                messages += msg
                seenMessageIds.add(msg.id)
            }
            map[matchingKey] = messages
            _privateMessages.value = ContactDirectory.canonicalizePrivateChats(map)
            if (forceRead) {
                _readPrivateMessageIDs.value = _readPrivateMessageIDs.value + msg.id
            }
            conversationRepository?.upsertMessage(
                conversationID = canonicalID,
                aliases = privateConversationAliases(peerID, canonicalID),
                displayName = ContactDirectory.resolve(canonicalID).displayName,
                message = msg,
                isRead = forceRead
            )
        }
    }

    /**
     * Persists an incoming private message before it is admitted to UI, unread, haptic, or
     * notification state. Transport callbacks invoke this from their background worker.
     */
    suspend fun addPrivateMessageDurably(
        peerID: String,
        msg: BitchatMessage,
        forceRead: Boolean = false
    ): Boolean {
        val persistence = synchronized(this) {
            if (privateConversationWritesSuspended) return false
            if (seenMessageIds.contains(msg.id) || !reservedPrivateMessageIds.add(msg.id)) {
                return false
            }
            privateMessagePersistence(peerID, msg, forceRead)
        }
        val repository = persistence.repository
        if (repository == null) {
            synchronized(this) { reservedPrivateMessageIds.remove(msg.id) }
            return false
        }
        val persisted = repository.upsertMessageAndWait(
            conversationID = persistence.conversationID,
            aliases = persistence.aliases,
            displayName = persistence.displayName,
            message = msg,
            isRead = persistence.isRead
        )
        return synchronized(this) {
            reservedPrivateMessageIds.remove(msg.id)
            if (
                !persisted ||
                privateConversationWritesSuspended ||
                persistence.generation != privateConversationGeneration ||
                seenMessageIds.contains(msg.id)
            ) {
                return@synchronized false
            }
            addPrivateMessageLocked(
                peerID = peerID,
                msg = msg,
                forceRead = forceRead,
                persistAsynchronously = false
            )
        }
    }

    private fun addPrivateMessageLocked(
        peerID: String,
        msg: BitchatMessage,
        forceRead: Boolean,
        persistAsynchronously: Boolean
    ): Boolean {
        if (privateConversationWritesSuspended) return false
        if (seenMessageIds.contains(msg.id)) return false
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
        } else {
            val counts = _unreadPrivateMessageCounts.value.toMutableMap()
            counts[conversationID] = (counts[conversationID] ?: 0) + 1
            _unreadPrivateMessageCounts.value = counts
        }
        val aliases = privateConversationAliases(peerID, conversationID)
        val displayName = ContactDirectory.resolve(conversationID).displayName
            ?: msg.sender.takeUnless {
                it.isBlank() || it == "system" || it == _nickname.value
            }
        displayName
            ?.takeUnless {
                it.isBlank() || it.equals("Unknown", ignoreCase = true)
            }
            ?.let { updateConversationDisplayNameLocked(conversationID, it) }
        if (persistAsynchronously) {
            conversationRepository?.upsertMessage(
                conversationID = conversationID,
                aliases = aliases,
                displayName = displayName,
                message = msg,
                isRead = isRead
            )
        }
        prunePrivateMessagesLocked(conversationID)
        return true
    }

    private fun privateMessagePersistence(
        peerID: String,
        msg: BitchatMessage,
        forceRead: Boolean
    ): PendingPrivateMessagePersistence {
        val conversationID = ContactDirectory.canonicalConversationId(peerID)
        val existingMessages = _privateMessages.value[conversationID].orEmpty()
        val isRead = forceRead ||
            msg.sender == "system" ||
            msg.sender == _nickname.value ||
            _selectedPrivateChatPeer.value
                ?.let(ContactDirectory::canonicalConversationId)
                ?.equals(conversationID, ignoreCase = true) == true
        val aliases = privateConversationAliases(peerID, conversationID)
        val displayName = ContactDirectory.resolve(conversationID).displayName
            ?: (existingMessages + msg)
                .lastOrNull { candidate ->
                    candidate.sender.isNotBlank() &&
                        candidate.sender != "system" &&
                        candidate.sender != _nickname.value
                }
                ?.sender
        return PendingPrivateMessagePersistence(
            repository = conversationRepository,
            conversationID = conversationID,
            aliases = aliases,
            displayName = displayName,
            isRead = isRead,
            generation = privateConversationGeneration
        )
    }

    /**
     * Derive aliases from the routed conversation, never from the message author.
     *
     * For outgoing messages senderPeerID is our own ID, which is shared by every private chat.
     * Persisting it as an alias would merge otherwise unrelated conversation histories.
     */
    private fun privateConversationAliases(
        peerID: String,
        conversationID: String
    ): Set<String> = runCatching {
        ContactDirectory.aliasesForConversation(peerID) +
            ContactDirectory.aliasesForConversation(conversationID)
    }.getOrDefault(setOf(peerID, conversationID))

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
            if (privateConversationWritesSuspended) return
            val map = _privateMessages.value.toMutableMap()
            var changed = false
            map.keys.toList().forEach { peer ->
                val list = map[peer]?.toMutableList() ?: mutableListOf()
                val idx = list.indexOfFirst { it.id == messageID }
                if (idx >= 0) {
                    val current = list[idx].deliveryStatus
                    // Do not downgrade (e.g., Read -> Delivered)
                    val mayReplace = when {
                        status is DeliveryStatus.Failed ->
                            current !is DeliveryStatus.Delivered &&
                                current !is DeliveryStatus.Read
                        current is DeliveryStatus.Failed -> true
                        else -> statusPriority(status) >= statusPriority(current)
                    }
                    if (mayReplace) {
                        list[idx] = list[idx].copy(deliveryStatus = status)
                        map[peer] = list
                        changed = true
                    }
                }
            }
            if (changed) {
                _privateMessages.value = map
            }
            // Full histories are unloaded after a chat closes, so the message may only exist in
            // SQLite. Always offer the update to the repository; it safely ignores unknown IDs
            // and enforces the same monotonic status rules as the in-memory path.
            conversationRepository?.updateDeliveryStatus(messageID, status)
        }
    }

    fun unifyPrivateChatsIntoPeer(targetPeerID: String, keysToMerge: List<String>) {
        if (keysToMerge.isEmpty()) return
        synchronized(this) {
            if (privateConversationWritesSuspended) return
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
                _privateMessages.value = map
            }
            canonicalizePrivateConversationStateLocked()
        }
    }

    fun canonicalizePrivateChats() {
        synchronized(this) {
            canonicalizePrivateConversationStateLocked()
        }
    }

    /**
     * Applies current peer announcements to retained conversations and persists the latest name
     * independently of message history. A nickname change must not require another message to
     * survive process death.
     */
    fun updatePrivateConversationDisplayNames(peerNicknames: Map<String, String>) {
        if (peerNicknames.isEmpty()) return
        synchronized(this) {
            if (privateConversationWritesSuspended || _privateMessages.value.isEmpty()) return
            canonicalizePrivateConversationStateLocked()
            val conversationIDs = _privateMessages.value.keys
                .mapTo(mutableSetOf()) { it.lowercase() }
            peerNicknames.forEach { (peerID, nickname) ->
                val usableName = nickname.takeUnless {
                    it.isBlank() || it.equals("Unknown", ignoreCase = true)
                } ?: return@forEach
                val canonicalID = ContactDirectory.canonicalConversationId(peerID)
                if (canonicalID.lowercase() !in conversationIDs) {
                    return@forEach
                }
                updateConversationDisplayNameLocked(canonicalID, usableName)
                val aliases = runCatching {
                    ContactDirectory.aliasesForConversation(peerID) +
                        ContactDirectory.aliasesForConversation(canonicalID)
                }.getOrDefault(setOf(peerID, canonicalID))
                conversationRepository?.updateConversationIdentity(
                    conversationID = canonicalID,
                    aliases = aliases,
                    displayName = usableName
                )
            }
        }
    }

    fun markPrivateMessageRead(messageID: String) {
        synchronized(this) {
            if (privateConversationWritesSuspended) return
            if (messageID in _readPrivateMessageIDs.value) return
            canonicalizePrivateConversationStateLocked()
            _readPrivateMessageIDs.value = _readPrivateMessageIDs.value + messageID
            val conversationID = _privateMessages.value.entries
                .firstOrNull { (_, messages) -> messages.any { it.id == messageID } }
                ?.key
            if (conversationID != null) {
                val counts = _unreadPrivateMessageCounts.value.toMutableMap()
                val remaining = ((counts[conversationID] ?: 0) - 1).coerceAtLeast(0)
                if (remaining == 0) counts.remove(conversationID) else {
                    counts[conversationID] = remaining
                }
                _unreadPrivateMessageCounts.value = counts
            }
            conversationRepository?.markRead(messageID)
        }
    }

    fun isPrivateMessageRead(messageID: String): Boolean =
        messageID in _readPrivateMessageIDs.value

    suspend fun setPrivateConversationRead(
        conversationID: String,
        isRead: Boolean
    ): Boolean {
        val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
        val repository = conversationRepository ?: return false
        val result = repository.setConversationReadAndWait(canonicalID, isRead)
        if (!result.success) return false
        synchronized(this) {
            val messageIDs = _privateMessages.value
                .filterKeys { key ->
                    ContactDirectory.canonicalConversationId(key)
                        .equals(canonicalID, ignoreCase = true)
                }
                .values
                .flatten()
                .mapTo(linkedSetOf(), BitchatMessage::id)
            if (isRead) {
                _readPrivateMessageIDs.value = _readPrivateMessageIDs.value + messageIDs
                _unreadPrivateMessageCounts.value =
                    _unreadPrivateMessageCounts.value.filterKeys { key ->
                        !ContactDirectory.canonicalConversationId(key)
                            .equals(canonicalID, ignoreCase = true)
                    }
            } else {
                result.affectedMessageID?.let { latestMessageID ->
                    _readPrivateMessageIDs.value =
                        _readPrivateMessageIDs.value - latestMessageID
                    _unreadPrivateMessageCounts.value =
                        _unreadPrivateMessageCounts.value + (canonicalID to 1)
                }
            }
        }
        return true
    }

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
            removeConversationDisplayNamesLocked(canonicalID)
            _readPrivateMessageIDs.value = _readPrivateMessageIDs.value - messageIDs
            _unreadPrivateMessageCounts.value =
                _unreadPrivateMessageCounts.value - matchingKeys - canonicalID
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

    internal suspend fun deletePrivateConversationAndWait(
        peerOrConversationID: String
    ): DeletedPrivateConversation? {
        loadPrivateConversationHistory(peerOrConversationID)
        val deletion = synchronized(this) {
            if (privateConversationWritesSuspended) return null
            buildDeletedConversationLocked(peerOrConversationID)
        }
        val repository = conversationRepository ?: return null
        if (!repository.deleteConversationAndWait(deletion.conversationID, deletion.aliases)) {
            return null
        }
        synchronized(this) {
            val updated = _privateMessages.value.toMutableMap()
            updated.keys.toList().forEach { key ->
                if (
                    ContactDirectory.canonicalConversationId(key)
                        .equals(deletion.conversationID, ignoreCase = true)
                ) {
                    val remaining = updated[key].orEmpty().filterNot {
                        it.id in deletion.messageIDs
                    }
                    if (remaining.isEmpty()) updated.remove(key) else updated[key] = remaining
                }
            }
            _privateMessages.value = updated
            removeConversationDisplayNamesLocked(deletion.conversationID)
            _readPrivateMessageIDs.value =
                _readPrivateMessageIDs.value - deletion.messageIDs
            canonicalizePrivateConversationStateLocked()
            val counts = _unreadPrivateMessageCounts.value.toMutableMap()
            val currentCount = counts[deletion.conversationID] ?: 0
            val remainingUnread = (currentCount - deletion.unreadMessageCount).coerceAtLeast(0)
            if (remainingUnread == 0) counts.remove(deletion.conversationID) else {
                counts[deletion.conversationID] = remainingUnread
            }
            _unreadPrivateMessageCounts.value = counts
            if (
                _selectedPrivateChatPeer.value
                    ?.let(ContactDirectory::canonicalConversationId)
                    ?.equals(deletion.conversationID, ignoreCase = true) == true
            ) {
                _selectedPrivateChatPeer.value = null
            }
        }
        return deletion
    }

    internal suspend fun restoreDeletedConversation(
        deletion: DeletedPrivateConversation
    ): Boolean {
        val repository = conversationRepository ?: return false
        val restoredDisplayName =
            ContactDirectory.resolve(deletion.conversationID).displayName
                ?: deletion.displayName
        if (
            !repository.restoreConversationAndWait(
                conversationID = deletion.conversationID,
                aliases = deletion.aliases,
                displayName = restoredDisplayName,
                messages = deletion.messages,
                readMessageIDs = deletion.readMessageIDs
            )
        ) {
            return false
        }
        synchronized(this) {
            seenMessageIds.removeAll(deletion.messageIDs)
            deletion.messages.forEach { message ->
                addPrivateMessageLocked(
                    peerID = deletion.conversationID,
                    msg = message,
                    forceRead = message.id in deletion.readMessageIDs,
                    persistAsynchronously = false
                )
            }
            restoredDisplayName?.let {
                updateConversationDisplayNameLocked(deletion.conversationID, it)
            }
        }
        return true
    }

    private fun buildDeletedConversationLocked(
        peerOrConversationID: String
    ): DeletedPrivateConversation {
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
        val messages = matchingKeys
            .flatMap { _privateMessages.value[it].orEmpty() }
            .distinctBy(BitchatMessage::id)
        val messageIDs = messages.mapTo(linkedSetOf(), BitchatMessage::id)
        val readIDs = _readPrivateMessageIDs.value.intersect(messageIDs)
        return DeletedPrivateConversation(
            conversationID = canonicalID,
            aliases = aliases,
            displayName = _privateConversationDisplayNames.value.entries
                .firstOrNull { (key, _) ->
                    ContactDirectory.canonicalConversationId(key)
                        .equals(canonicalID, ignoreCase = true)
                }
                ?.value
                ?: ContactDirectory.resolve(canonicalID).displayName,
            messages = messages,
            readMessageIDs = readIDs,
            unreadMessageCount = messages.count { message ->
                message.id !in readIDs &&
                    message.sender != "system" &&
                    message.sender != _nickname.value
            }
        )
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
            val retainedConversationIDs = updated.keys
                .mapTo(mutableSetOf()) {
                    ContactDirectory.canonicalConversationId(it).lowercase()
                }
            _privateConversationDisplayNames.value =
                _privateConversationDisplayNames.value.filterKeys {
                    ContactDirectory.canonicalConversationId(it).lowercase() in
                        retainedConversationIDs
                }
            _readPrivateMessageIDs.value = _readPrivateMessageIDs.value - messageID
        }
    }

    /**
     * Atomically hides all conversations, rejects in-flight transport deliveries, then waits for
     * every earlier database write and the panic wipe itself to finish.
     */
    suspend fun panicClearPrivateConversations(): Boolean {
        val repository = synchronized(this) {
            privateConversationWritesSuspended = true
            privateConversationGeneration += 1
            _privateMessages.value = emptyMap()
            _readPrivateMessageIDs.value = emptySet()
            _unreadPrivateMessageCounts.value = emptyMap()
            _privateConversationDisplayNames.value = emptyMap()
            _selectedPrivateChatPeer.value = null
            conversationRepository
        }
        return repository?.clearAllAndWait() ?: true
    }

    fun resumePrivateConversationsAfterPanic() {
        synchronized(this) {
            privateConversationWritesSuspended = false
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
            reservedPrivateMessageIds.clear()
            privateConversationGeneration += 1
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
            _unreadPrivateMessageCounts.value = emptyMap()
            _privateConversationDisplayNames.value = emptyMap()
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

    internal fun restorePrivateConversations(snapshot: PersistedConversationSnapshot) {
        synchronized(this) {
            if (privateConversationWritesSuspended) return
            val liveChats = _privateMessages.value
            val liveMessageIDs = liveChats.values.flatten().map { it.id }
            PrivateMessageArrivalOrder.restore(
                snapshot.arrivalOrder,
                liveMessageIDs,
                snapshot.receivedAtByMessageID,
                snapshot.arrivalSequenceByMessageID
            )

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
            val unreadCounts = _unreadPrivateMessageCounts.value.toMutableMap()
            snapshot.unreadCounts.forEach { (conversationID, count) ->
                if (count > 0) unreadCounts[conversationID] = count
                else unreadCounts.remove(conversationID)
            }
            _unreadPrivateMessageCounts.value = unreadCounts
            _privateConversationDisplayNames.value =
                snapshot.displayNames + _privateConversationDisplayNames.value
            _privateMessages.value = ContactDirectory.canonicalizePrivateChats(
                merged.mapValues { (_, messages) ->
                    PrivateMessageArrivalOrder.order(messages.distinctBy { it.id })
                }
            )
            canonicalizePrivateConversationStateLocked()
        }
    }

    private fun updateConversationDisplayNameLocked(
        conversationID: String,
        displayName: String
    ) {
        val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
        val updated = _privateConversationDisplayNames.value
            .filterKeys { key ->
                !ContactDirectory.canonicalConversationId(key)
                    .equals(canonicalID, ignoreCase = true)
            }
            .toMutableMap()
        updated[canonicalID] = displayName
        _privateConversationDisplayNames.value = updated
    }

    private fun removeConversationDisplayNamesLocked(conversationID: String) {
        val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
        _privateConversationDisplayNames.value =
            _privateConversationDisplayNames.value.filterKeys { key ->
                !ContactDirectory.canonicalConversationId(key)
                    .equals(canonicalID, ignoreCase = true)
            }
    }

    /**
     * Identity mappings can become richer after a Noise handshake or favorite/Nostr update.
     * Keep every process-wide projection on the same canonical key so unread/read/delete updates
     * cannot leave a stale alias behind.
     */
    private fun canonicalizePrivateConversationStateLocked() {
        val canonicalChats = ContactDirectory.canonicalizePrivateChats(_privateMessages.value)
        if (canonicalChats != _privateMessages.value) {
            _privateMessages.value = canonicalChats
        }

        val canonicalUnreadCounts = linkedMapOf<String, Int>()
        _unreadPrivateMessageCounts.value.forEach { (conversationID, count) ->
            if (count <= 0) return@forEach
            val canonicalID = ContactDirectory.canonicalConversationId(conversationID)
            canonicalUnreadCounts[canonicalID] =
                (canonicalUnreadCounts[canonicalID] ?: 0) + count
        }
        if (canonicalUnreadCounts != _unreadPrivateMessageCounts.value) {
            _unreadPrivateMessageCounts.value = canonicalUnreadCounts
        }

        val canonicalDisplayNames = linkedMapOf<String, String>()
        _privateConversationDisplayNames.value.forEach { (conversationID, displayName) ->
            if (displayName.isBlank()) return@forEach
            canonicalDisplayNames[
                ContactDirectory.canonicalConversationId(conversationID)
            ] = displayName
        }
        if (canonicalDisplayNames != _privateConversationDisplayNames.value) {
            _privateConversationDisplayNames.value = canonicalDisplayNames
        }

        _selectedPrivateChatPeer.value?.let { selected ->
            val canonicalSelected = ContactDirectory.canonicalConversationId(selected)
            if (canonicalSelected != selected) {
                _selectedPrivateChatPeer.value = canonicalSelected
            }
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

private data class PendingPrivateMessagePersistence(
    val repository: ConversationRepository?,
    val conversationID: String,
    val aliases: Set<String>,
    val displayName: String?,
    val isRead: Boolean,
    val generation: Long
)

internal data class DeletedPrivateConversation(
    val conversationID: String,
    val aliases: Set<String>,
    val displayName: String?,
    val messages: List<BitchatMessage>,
    val readMessageIDs: Set<String>,
    val unreadMessageCount: Int,
    val wasPinned: Boolean = false,
    val wasMuted: Boolean = false,
    val draft: String? = null
) {
    val messageIDs: Set<String> = messages.mapTo(linkedSetOf(), BitchatMessage::id)
}

private val EMPTY_CONVERSATION_STORE_STATE =
    MutableStateFlow<ConversationStoreState>(ConversationStoreState.Ready).asStateFlow()
