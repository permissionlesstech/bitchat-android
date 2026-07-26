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

    fun peerIDForNickname(nickname: String): String?
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

    private val pendingLock = Any()
    private val pendingEvents = ArrayDeque<PendingEvent>()

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
        val nickname = normalizeNickname(rawNickname)
        if (nickname.isEmpty()) return error("usage: /group invite <nickname>")
        val group = selectedGroup() ?: return error("open a private group first")
        if (!isCreator(group)) return error("only the group creator can change members")
        val peerID = context.peerIDForNickname(nickname)
            ?: return error("user '$nickname' was not found")
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
        val nickname = normalizeNickname(rawNickname)
        if (nickname.isEmpty()) return error("usage: /group remove <nickname>")
        val group = selectedGroup() ?: return error("open a private group first")
        if (!isCreator(group)) return error("only the group creator can change members")
        val member = group.members.firstOrNull {
            it.nickname.equals(nickname, ignoreCase = true)
        } ?: return error("$nickname is not in this group")
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
        if (deferIfLoading(PendingEvent.Message(payload.copyOf(), receivedAtMs))) return
        processMessage(payload)
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processMessage(payload: ByteArray) {
        val envelope = GroupMessageEnvelope.decode(payload) ?: return
        val group = context.groupStore.group(envelope.groupID) ?: return
        if (envelope.epoch != group.epoch) return
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

    fun handleKeyUpdate(
        peerID: String,
        authenticatedRemoteStaticKey: ByteArray,
        payload: ByteArray
    ) {
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

    /**
     * Replays the current creator-signed state after an authenticated peer
     * reconnects. This uses the existing iOS GROUP_KEY_UPDATE payload and
     * repairs updates that could not be delivered while the member was offline.
     */
    fun handlePeerAuthenticated(peerID: String) {
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

    /**
     * Drains packets received during asynchronous store initialization.
     */
    fun onStoreReady() {
        if (!context.groupStore.isReady) return
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
            if (existing != null) {
                if (!context.groupStore.removeGroup(existing.groupID)) return
                if (context.selectedConversationID == existing.peerID) {
                    context.closeGroupConversation()
                }
                context.removeGroupConversation(existing.peerID)
                context.addSystemMessage("you were removed from #${existing.name}")
            }
            return
        }
        val stored = if (isInvite && departureEpoch != null) {
            context.groupStore.acceptInvite(state.asGroup(), state.key)
        } else {
            context.groupStore.upsert(state.asGroup(), state.key)
        }
        if (!stored) return

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

    private fun normalizeNickname(raw: String): String =
        raw.trim().removePrefix("@")

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
        private val FINGERPRINT = Regex("^[0-9a-fA-F]{64}$")
    }
}
