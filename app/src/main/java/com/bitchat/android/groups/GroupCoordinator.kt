package com.bitchat.android.groups

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.model.PeerCapabilities
import java.util.ArrayDeque
import java.util.Date
import java.util.UUID

data class GroupPeerIdentity(
    val fingerprint: String,
    val signingKey: ByteArray
)

data class GroupCommandResult(
    val success: Boolean,
    val message: String
)

enum class PeerGroupCapability {
    SUPPORTED,
    UNSUPPORTED,
    UNKNOWN;

    companion object {
        fun fromPeerState(
            capabilities: PeerCapabilities?,
            hasVerifiedAnnouncement: Boolean
        ): PeerGroupCapability = when {
            capabilities?.contains(PeerCapabilities.GROUPS) == true -> SUPPORTED
            capabilities != null || hasVerifiedAnnouncement -> UNSUPPORTED
            else -> UNKNOWN
        }
    }
}

interface GroupCoordinatorContext {
    val groupStore: GroupStore
    val nickname: String
    val myPeerID: String
    val selectedConversationID: String?

    fun myNoiseFingerprint(): String
    fun mySigningPublicKey(): ByteArray?
    fun sign(data: ByteArray): ByteArray?

    fun peerIDsForNickname(nickname: String): List<String>
    fun isPeerConnected(peerID: String): Boolean
    fun peerGroupCapability(peerID: String): PeerGroupCapability
    fun peerNickname(peerID: String): String?
    fun peerIdentity(peerID: String): GroupPeerIdentity?
    fun connectedPeerID(fingerprint: String): String?
    fun isFingerprintBlocked(fingerprint: String): Boolean

    fun sendGroupInvite(payload: ByteArray, peerID: String)
    fun sendGroupKeyUpdate(payload: ByteArray, peerID: String)
    fun broadcastGroupMessage(payload: ByteArray)

    fun appendGroupMessage(groupPeerID: String, message: BitchatMessage): Boolean
    fun markGroupUnread(groupPeerID: String)
    fun removeGroupConversation(groupPeerID: String)
    fun openGroupConversation(groupPeerID: String)
    fun closeGroupConversation()
    fun addSystemMessage(message: String)
    fun addGroupSystemMessage(groupPeerID: String, message: String)
    fun notifyGroupMessage(groupPeerID: String, sender: String, message: String)
}

/**
 * Creator-managed private-group state machine matching iOS v1.
 */
class GroupCoordinator(private val context: GroupCoordinatorContext) {
    private data class MemberSelector(
        val nickname: String,
        val identitySuffix: String?
    )

    private sealed class PendingEvent {
        data class Invite(
            val peerID: String,
            val authenticatedRemoteStaticKey: ByteArray,
            val payload: ByteArray
        ) : PendingEvent()

        data class KeyUpdate(
            val peerID: String,
            val authenticatedRemoteStaticKey: ByteArray,
            val payload: ByteArray
        ) : PendingEvent()

        data class Message(
            val payload: ByteArray,
            val receivedAtMs: Long
        ) : PendingEvent()

        data class PeerAuthenticated(val peerID: String) : PendingEvent()
    }

    private data class FutureMessage(
        val groupID: ByteArray,
        val epoch: Long,
        val payload: ByteArray,
        val queuedAtMs: Long
    )

    private val lifecycleLock = Any()
    private val pendingLock = Any()
    private val pendingEvents = ArrayDeque<PendingEvent>()
    private val futureMessages = ArrayDeque<FutureMessage>()
    @Volatile
    private var acceptsInboundEvents = true
    @Volatile
    private var inboundGeneration = 0L

    fun createGroup(rawName: String): GroupCommandResult {
        if (!context.groupStore.isReady) return loadingError()
        val name = rawName.trim()
        if (name.isEmpty()) return error("usage: /group create <name>")
        if (name.codePointCount(0, name.length) > MAX_GROUP_NAME_LENGTH) {
            return error("group name must be $MAX_GROUP_NAME_LENGTH characters or fewer")
        }
        val fingerprint = context.myNoiseFingerprint()
        val signingKey = context.mySigningPublicKey()
        if (!FINGERPRINT.matches(fingerprint) || signingKey?.size != 32) {
            return error("your cryptographic identity is not ready")
        }
        val creator = GroupMember(fingerprint, signingKey, context.nickname)
        val group = context.groupStore.createGroup(name, creator)
            ?: return error("could not create group")
        context.openGroupConversation(group.peerID)
        return success("created private group #${group.name}")
    }

    fun inviteMember(rawNickname: String): GroupCommandResult {
        if (!context.groupStore.isReady) return loadingError()
        val selector = parseMemberSelector(rawNickname)
            ?: return error("usage: /group invite <nickname>[#identity-suffix]")
        val nickname = selector.nickname
        val group = selectedGroup() ?: return error("open a private group first")
        if (!isCreator(group)) return error("only the group creator can change members")
        val peerID = resolvePeer(selector) ?: return ambiguousPeerError(selector)
        if (!context.isPeerConnected(peerID)) return error("$nickname is not connected")
        when (context.peerGroupCapability(peerID)) {
            PeerGroupCapability.SUPPORTED -> Unit
            PeerGroupCapability.UNSUPPORTED ->
                return error("$nickname does not support private groups")
            PeerGroupCapability.UNKNOWN ->
                return error("private-group support for $nickname is not confirmed yet; try again")
        }
        val identity = context.peerIdentity(peerID)
            ?: return error("$nickname does not have a verified mesh identity")
        if (group.isMember(identity.fingerprint)) return error("$nickname is already a member")
        if (group.members.size >= BitchatGroup.MAX_MEMBERS) {
            return error("groups are limited to ${BitchatGroup.MAX_MEMBERS} members")
        }

        val member = GroupMember(
            identity.fingerprint,
            identity.signingKey,
            context.peerNickname(peerID) ?: nickname
        )
        val (updated, key) = context.groupStore.rotateKey(
            group.groupID,
            group.members + member
        ) ?: return error("could not rotate the group key")
        val payload = signedStatePayload(updated, key)
            ?: return error("could not sign the group invite")

        context.sendGroupInvite(payload, peerID)
        distributeState(payload, updated, setOf(identity.fingerprint))
        return success("invited $nickname to #${updated.name}")
    }

    fun removeMember(rawNickname: String): GroupCommandResult {
        if (!context.groupStore.isReady) return loadingError()
        val selector = parseMemberSelector(rawNickname)
            ?: return error("usage: /group remove <nickname>[#identity-suffix]")
        val nickname = selector.nickname
        val group = selectedGroup() ?: return error("open a private group first")
        if (!isCreator(group)) return error("only the group creator can change members")
        val matchingMembers = group.members.filter {
            it.nickname.equals(nickname, ignoreCase = true)
        }.let { members ->
            selector.identitySuffix?.let { suffix ->
                members.filter { it.fingerprint.endsWith(suffix, ignoreCase = true) }
            } ?: members
        }
        val member = matchingMembers.singleOrNull() ?: return when {
            matchingMembers.isEmpty() -> error("$nickname is not in this group")
            else -> error(
                "multiple members are named '$nickname'; use ${memberChoices(matchingMembers)}"
            )
        }
        if (member.fingerprint == group.creatorFingerprint) {
            return error("the creator cannot remove themselves")
        }

        val remaining = group.members.filterNot { it.fingerprint == member.fingerprint }
        val (rotated, key) = context.groupStore.rotateKey(group.groupID, remaining)
            ?: return error("could not rotate the group key")
        val payload = signedStatePayload(rotated, key)
            ?: return error("could not sign the group update")
        distributeState(payload, rotated, emptySet())
        notifyRemovedMember(member, rotated)
        return success("removed ${member.nickname} and rotated the group key")
    }

    fun leaveGroup(): GroupCommandResult {
        if (!context.groupStore.isReady) return loadingError()
        val group = selectedGroup() ?: return error("open a private group first")
        if (isCreator(group) && group.members.size > 1) {
            return error("remove all other members before leaving this group")
        }
        val removed = if (isCreator(group)) {
            context.groupStore.removeGroup(group.groupID)
        } else {
            context.groupStore.departGroup(group.groupID, group.epoch)
        }
        if (!removed) return error("could not leave group")
        synchronized(lifecycleLock) {
            dropFutureMessages(group.groupID)
        }
        context.closeGroupConversation()
        context.removeGroupConversation(group.peerID)
        return success("left #${group.name}")
    }

    fun listGroups(): GroupCommandResult {
        if (!context.groupStore.isReady) return loadingError()
        val groups = context.groupStore.groups.value
        if (groups.isEmpty()) return success("you are not in any private groups")
        val fingerprint = context.myNoiseFingerprint()
        val lines = groups.joinToString("\n") { group ->
            val role = if (group.creatorFingerprint == fingerprint) " (creator)" else ""
            "#${group.name}$role — ${group.members.size}/${BitchatGroup.MAX_MEMBERS}"
        }
        return success("private groups:\n$lines")
    }

    fun sendMessage(content: String, groupPeerID: String) {
        if (!context.groupStore.isReady) {
            context.addGroupSystemMessage(groupPeerID, "private groups are still loading")
            return
        }
        if (content.isEmpty() ||
            content.codePointCount(0, content.length) > MAX_MESSAGE_LENGTH
        ) {
            return
        }
        val group = context.groupStore.group(groupPeerID)
        val key = group?.let { context.groupStore.key(it.groupID) }
        if (group == null || key == null) {
            context.addGroupSystemMessage(groupPeerID, "this private group is unavailable")
            return
        }
        val signingKey = context.mySigningPublicKey()
        if (signingKey?.size != 32) {
            context.addGroupSystemMessage(groupPeerID, "your signing identity is unavailable")
            return
        }

        val messageID = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val payload = try {
            GroupCrypto.sealMessage(
                content = content,
                messageID = messageID,
                senderNickname = context.nickname,
                senderSigningKey = signingKey,
                timestampMs = timestamp,
                groupID = group.groupID,
                epoch = group.epoch,
                key = key,
                sign = context::sign
            )
        } catch (_: Exception) {
            context.addGroupSystemMessage(groupPeerID, "could not encrypt group message")
            return
        }

        context.appendGroupMessage(
            groupPeerID,
            BitchatMessage(
                id = messageID,
                sender = context.nickname,
                content = content,
                timestamp = Date(timestamp),
                isPrivate = true,
                recipientNickname = group.name,
                senderPeerID = context.myPeerID,
                deliveryStatus = DeliveryStatus.Sent
            )
        )
        context.broadcastGroupMessage(payload)
    }

    fun handleMessage(payload: ByteArray, receivedAtMs: Long) {
        val generation = inboundGeneration
        if (!acceptsInboundEvents) return
        synchronized(lifecycleLock) {
            if (!acceptsInboundEvents || generation != inboundGeneration) return
            if (deferIfLoading(PendingEvent.Message(payload.copyOf(), receivedAtMs))) return
            processMessage(payload)
        }
    }

    private fun processMessage(payload: ByteArray, queueFutureEpoch: Boolean = true) {
        val envelope = GroupMessageEnvelope.decode(payload) ?: return
        val group = context.groupStore.group(envelope.groupID) ?: return
        if (envelope.epoch != group.epoch) {
            if (queueFutureEpoch && envelope.epoch > group.epoch) {
                queueFutureMessage(envelope, payload)
            }
            return
        }
        val key = context.groupStore.key(group.groupID) ?: return
        val plaintext = try {
            GroupCrypto.openMessage(envelope, key)
        } catch (_: Exception) {
            return
        }
        val member = group.memberWithSigningKey(plaintext.senderSigningKey) ?: return
        val ownSigningKey = context.mySigningPublicKey()
        if (ownSigningKey != null && plaintext.senderSigningKey.contentEquals(ownSigningKey)) return
        if (context.isFingerprintBlocked(member.fingerprint)) return

        val now = System.currentTimeMillis()
        val timestamp = plaintext.timestampMs.coerceIn(0, now)
        val sender = member.nickname.ifBlank { plaintext.senderNickname }
        val message = BitchatMessage(
            id = plaintext.messageID,
            sender = sender,
            content = plaintext.content,
            timestamp = Date(timestamp),
            isPrivate = true,
            recipientNickname = group.name,
            senderPeerID = member.fingerprint.take(16)
        )
        if (!context.appendGroupMessage(group.peerID, message)) return

        if (context.selectedConversationID != group.peerID) {
            context.markGroupUnread(group.peerID)
            if (now - timestamp < RECENT_NOTIFICATION_WINDOW_MS) {
                context.notifyGroupMessage(group.peerID, "$sender @ ${group.name}", plaintext.content)
            }
        }
    }

    fun handleInvite(
        peerID: String,
        authenticatedRemoteStaticKey: ByteArray,
        payload: ByteArray
    ) {
        val generation = inboundGeneration
        if (!acceptsInboundEvents) return
        synchronized(lifecycleLock) {
            if (!acceptsInboundEvents || generation != inboundGeneration) return
            if (
                deferIfLoading(
                    PendingEvent.Invite(
                        peerID,
                        authenticatedRemoteStaticKey.copyOf(),
                        payload.copyOf()
                    )
                )
            ) {
                return
            }
            applyState(peerID, authenticatedRemoteStaticKey, payload, isInvite = true)
        }
    }

    fun handleKeyUpdate(
        peerID: String,
        authenticatedRemoteStaticKey: ByteArray,
        payload: ByteArray
    ) {
        val generation = inboundGeneration
        if (!acceptsInboundEvents) return
        synchronized(lifecycleLock) {
            if (!acceptsInboundEvents || generation != inboundGeneration) return
            if (
                deferIfLoading(
                    PendingEvent.KeyUpdate(
                        peerID,
                        authenticatedRemoteStaticKey.copyOf(),
                        payload.copyOf()
                    )
                )
            ) {
                return
            }
            applyState(peerID, authenticatedRemoteStaticKey, payload, isInvite = false)
        }
    }

    /**
     * Replays the current creator-signed state after an authenticated peer
     * reconnects. This uses the existing iOS GROUP_KEY_UPDATE payload and
     * repairs updates that could not be delivered while the member was offline.
     */
    fun handlePeerAuthenticated(peerID: String) {
        val generation = inboundGeneration
        if (!acceptsInboundEvents) return
        synchronized(lifecycleLock) {
            if (!acceptsInboundEvents || generation != inboundGeneration) return
            if (deferIfLoading(PendingEvent.PeerAuthenticated(peerID))) return
            if (!context.isPeerConnected(peerID)) return
            if (context.peerGroupCapability(peerID) != PeerGroupCapability.SUPPORTED) return
            val identity = context.peerIdentity(peerID) ?: return
            val ownFingerprint = context.myNoiseFingerprint()
            context.groupStore.groups.value.forEach { group ->
                if (group.creatorFingerprint != ownFingerprint ||
                    !group.isMember(identity.fingerprint)
                ) {
                    return@forEach
                }
                val key = context.groupStore.key(group.groupID) ?: return@forEach
                val payload = signedStatePayload(group, key) ?: return@forEach
                context.sendGroupKeyUpdate(payload, peerID)
            }
        }
    }

    /**
     * Drains packets received during asynchronous store initialization.
     */
    fun onStoreReady() {
        val generation = inboundGeneration
        if (!acceptsInboundEvents) return
        synchronized(lifecycleLock) {
            if (!acceptsInboundEvents ||
                generation != inboundGeneration ||
                !context.groupStore.isReady
            ) {
                return
            }
            while (true) {
                val event = synchronized(pendingLock) {
                    pendingEvents.pollFirst()
                } ?: return
                when (event) {
                    is PendingEvent.Invite -> applyState(
                        event.peerID,
                        event.authenticatedRemoteStaticKey,
                        event.payload,
                        isInvite = true
                    )
                    is PendingEvent.KeyUpdate -> applyState(
                        event.peerID,
                        event.authenticatedRemoteStaticKey,
                        event.payload,
                        isInvite = false
                    )
                    is PendingEvent.Message -> processMessage(event.payload)
                    is PendingEvent.PeerAuthenticated ->
                        handlePeerAuthenticated(event.peerID)
                }
            }
        }
    }

    fun suspendForPanic() {
        synchronized(lifecycleLock) {
            acceptsInboundEvents = false
            inboundGeneration += 1
            synchronized(pendingLock) {
                pendingEvents.clear()
            }
            futureMessages.clear()
        }
    }

    fun resumeAfterPanic() {
        synchronized(lifecycleLock) {
            acceptsInboundEvents = true
        }
    }

    private fun applyState(
        peerID: String,
        authenticatedRemoteStaticKey: ByteArray,
        payload: ByteArray,
        isInvite: Boolean
    ) {
        val state = GroupStatePayload.decode(payload) ?: return
        val senderFingerprint = sha256(authenticatedRemoteStaticKey).toHex()
        if (senderFingerprint != state.creatorFingerprint) return
        if (!state.verifyCreatorSignature()) return

        val ownFingerprint = context.myNoiseFingerprint()
        val existing = context.groupStore.group(state.groupID)
        // Reject stale state before interpreting a missing-self roster as a
        // removal. Otherwise an old, valid removal notice could delete a
        // membership restored by a later creator-signed re-invite.
        if (existing != null && state.epoch < existing.epoch) return
        val departureEpoch = context.groupStore.departureEpoch(state.groupID)
        if (departureEpoch != null &&
            (!isInvite || state.epoch <= departureEpoch)
        ) {
            return
        }
        if (state.members.none { it.fingerprint == ownFingerprint }) {
            val removed = context.groupStore.removeGroupForState(state.groupID, state.epoch)
                ?: return
            dropFutureMessages(removed.groupID)
            if (context.selectedConversationID == removed.peerID) {
                context.closeGroupConversation()
            }
            context.removeGroupConversation(removed.peerID)
            context.addSystemMessage("you were removed from #${removed.name}")
            return
        }
        val stored = if (isInvite && departureEpoch != null) {
            context.groupStore.acceptInvite(state.asGroup(), state.key)
        } else {
            context.groupStore.upsert(state.asGroup(), state.key)
        }
        if (!stored) return
        retryFutureMessages(state.groupID)

        if (existing == null) {
            val inviter = state.members.firstOrNull {
                it.fingerprint == state.creatorFingerprint
            }?.nickname ?: context.peerNickname(peerID) ?: "unknown"
            val notice = "joined #${state.name}, invited by $inviter"
            context.addSystemMessage(notice)
            context.markGroupUnread(state.asGroup().peerID)
            context.notifyGroupMessage(state.asGroup().peerID, inviter, notice)
        }
    }

    private fun signedStatePayload(group: BitchatGroup, key: ByteArray): ByteArray? =
        GroupStatePayload.makeSigned(group, key, context::sign)?.encode()

    private fun distributeState(
        payload: ByteArray,
        group: BitchatGroup,
        excludedFingerprints: Set<String>
    ) {
        val ownFingerprint = context.myNoiseFingerprint()
        group.members.forEach { member ->
            if (member.fingerprint == ownFingerprint ||
                member.fingerprint in excludedFingerprints
            ) {
                return@forEach
            }
            context.connectedPeerID(member.fingerprint)?.let { peerID ->
                context.sendGroupKeyUpdate(payload, peerID)
            }
        }
    }

    private fun notifyRemovedMember(member: GroupMember, rotated: BitchatGroup) {
        val peerID = context.connectedPeerID(member.fingerprint) ?: return
        val payload = signedStatePayload(rotated, ByteArray(BitchatGroup.KEY_LENGTH)) ?: return
        context.sendGroupKeyUpdate(payload, peerID)
    }

    private fun selectedGroup(): BitchatGroup? =
        context.selectedConversationID?.let(context.groupStore::group)

    private fun isCreator(group: BitchatGroup): Boolean =
        group.creatorFingerprint == context.myNoiseFingerprint()

    private fun parseMemberSelector(raw: String): MemberSelector? {
        val normalized = raw.trim().removePrefix("@")
        if (normalized.isEmpty()) return null
        val separator = normalized.lastIndexOf('#')
        if (separator < 0) return MemberSelector(normalized, null)
        if (separator == 0 || separator == normalized.lastIndex) return null
        val suffix = normalized.substring(separator + 1)
        if (!IDENTITY_SUFFIX.matches(suffix)) return null
        return MemberSelector(
            nickname = normalized.substring(0, separator),
            identitySuffix = suffix.lowercase()
        )
    }

    private fun resolvePeer(selector: MemberSelector): String? {
        val matches = context.peerIDsForNickname(selector.nickname).distinct()
        val narrowed = selector.identitySuffix?.let { suffix ->
            matches.filter { peerID ->
                peerID.endsWith(suffix, ignoreCase = true) ||
                    context.peerIdentity(peerID)
                        ?.fingerprint
                        ?.endsWith(suffix, ignoreCase = true) == true
            }
        } ?: matches
        return narrowed.singleOrNull()
    }

    private fun ambiguousPeerError(selector: MemberSelector): GroupCommandResult {
        val matches = context.peerIDsForNickname(selector.nickname).distinct()
        if (matches.isEmpty() || selector.identitySuffix != null) {
            return error("user '${selector.nickname}' was not found")
        }
        return error(
            "multiple users are named '${selector.nickname}'; use " +
                matches.joinToString(" or ") { peerID ->
                    val suffix = context.peerIdentity(peerID)
                        ?.fingerprint
                        ?.takeLast(8)
                        ?: peerID.takeLast(8)
                    "@${selector.nickname}#$suffix"
                }
        )
    }

    private fun memberChoices(members: List<GroupMember>): String =
        members.joinToString(" or ") { "@${it.nickname}#${it.fingerprint.takeLast(8)}" }

    private fun queueFutureMessage(envelope: GroupMessageEnvelope, payload: ByteArray) {
        val now = System.currentTimeMillis()
        pruneExpiredFutureMessages(now)
        if (futureMessages.any {
                it.epoch == envelope.epoch &&
                    it.groupID.contentEquals(envelope.groupID) &&
                    it.payload.contentEquals(payload)
            }
        ) {
            return
        }
        if (futureMessages.size >= MAX_FUTURE_MESSAGES) futureMessages.pollFirst()
        futureMessages.addLast(
            FutureMessage(
                envelope.groupID.copyOf(),
                envelope.epoch,
                payload.copyOf(),
                now
            )
        )
    }

    private fun retryFutureMessages(groupID: ByteArray) {
        val current = context.groupStore.group(groupID) ?: return
        val now = System.currentTimeMillis()
        val ready = mutableListOf<ByteArray>()
        val iterator = futureMessages.iterator()
        while (iterator.hasNext()) {
            val message = iterator.next()
            if (now - message.queuedAtMs > FUTURE_MESSAGE_TTL_MS) {
                iterator.remove()
            } else if (message.groupID.contentEquals(groupID) &&
                message.epoch <= current.epoch
            ) {
                iterator.remove()
                if (message.epoch == current.epoch) ready += message.payload
            }
        }
        ready.forEach { processMessage(it, queueFutureEpoch = false) }
    }

    private fun dropFutureMessages(groupID: ByteArray) {
        val iterator = futureMessages.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().groupID.contentEquals(groupID)) iterator.remove()
        }
    }

    private fun pruneExpiredFutureMessages(now: Long) {
        val iterator = futureMessages.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().queuedAtMs > FUTURE_MESSAGE_TTL_MS) iterator.remove()
        }
    }

    private fun deferIfLoading(event: PendingEvent): Boolean =
        synchronized(pendingLock) {
            if (context.groupStore.isReady) return@synchronized false
            if (pendingEvents.size >= MAX_PENDING_EVENTS) pendingEvents.pollFirst()
            pendingEvents.addLast(event)
            true
        }

    private fun loadingError() =
        error("private groups are still loading; try again")

    private fun success(message: String) = GroupCommandResult(true, message)
    private fun error(message: String) = GroupCommandResult(false, message)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_GROUP_NAME_LENGTH = 40
        private const val MAX_MESSAGE_LENGTH = 60_000
        private const val RECENT_NOTIFICATION_WINDOW_MS = 30_000L
        private const val MAX_PENDING_EVENTS = 64
        private const val MAX_FUTURE_MESSAGES = 32
        private const val FUTURE_MESSAGE_TTL_MS = 2 * 60_000L
        private val FINGERPRINT = Regex("^[0-9a-fA-F]{64}$")
        private val IDENTITY_SUFFIX = Regex("^[0-9a-fA-F]{4,64}$")
    }
}
