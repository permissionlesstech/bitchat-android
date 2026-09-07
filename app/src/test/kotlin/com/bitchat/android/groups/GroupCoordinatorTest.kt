package com.bitchat.android.groups

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.PeerCapabilities
import java.security.MessageDigest
import java.util.Date
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupCoordinatorTest {
    @Test
    fun `peer group capability distinguishes unknown unsupported and supported state`() {
        assertEquals(
            PeerGroupCapability.UNKNOWN,
            PeerGroupCapability.fromPeerState(null, hasVerifiedAnnouncement = false)
        )
        assertEquals(
            PeerGroupCapability.UNSUPPORTED,
            PeerGroupCapability.fromPeerState(null, hasVerifiedAnnouncement = true)
        )
        assertEquals(
            PeerGroupCapability.UNSUPPORTED,
            PeerGroupCapability.fromPeerState(
                PeerCapabilities.PRIVATE_MEDIA,
                hasVerifiedAnnouncement = false
            )
        )
        assertEquals(
            PeerGroupCapability.SUPPORTED,
            PeerGroupCapability.fromPeerState(
                PeerCapabilities.GROUPS,
                hasVerifiedAnnouncement = false
            )
        )
    }

    @Test
    fun `creator invite rotates epoch and sends creator-signed state`() {
        val localKey = privateKey(0x11)
        val inviteeKey = privateKey(0x22)
        val context = FakeGroupContext(localKey, "11".repeat(32))
        context.peerIDs["alice"] = "22".repeat(8)
        context.connected += "22".repeat(8)
        context.peerNames["22".repeat(8)] = "alice"
        context.identities["22".repeat(8)] = GroupPeerIdentity(
            "22".repeat(32),
            inviteeKey.generatePublicKey().encoded
        )

        val created = GroupCoordinator(context).createGroup("trail crew")
        assertTrue(created.success)
        val original = context.groupStore.groups.value.single()
        val originalKey = context.groupStore.key(original.groupID)!!

        val result = GroupCoordinator(context).inviteMember("@alice")
        assertTrue(result.success)
        val updated = context.groupStore.group(original.groupID)!!
        val updatedKey = context.groupStore.key(updated.groupID)!!
        assertEquals(2, updated.epoch)
        assertEquals(2, updated.members.size)
        assertFalse(originalKey.contentEquals(updatedKey))
        assertEquals(1, context.invites.size)

        val state = GroupStatePayload.decode(context.invites.single().second)!!
        assertTrue(state.verifyCreatorSignature())
        assertEquals(updated, state.asGroup())
        assertArrayEquals(updatedKey, state.key)
    }

    @Test
    fun `duplicate nicknames require an identity suffix`() {
        val localKey = privateKey(0x23)
        val firstKey = privateKey(0x24)
        val secondKey = privateKey(0x25)
        val firstPeerID = "24".repeat(8)
        val secondPeerID = "25".repeat(8)
        val context = FakeGroupContext(localKey, "23".repeat(32))
        context.peerIDs["alice"] = firstPeerID
        context.additionalPeerIDs.getOrPut("alice") { mutableListOf() } += secondPeerID
        context.connected += firstPeerID
        context.connected += secondPeerID
        context.peerNames[firstPeerID] = "alice"
        context.peerNames[secondPeerID] = "alice"
        context.identities[firstPeerID] = GroupPeerIdentity(
            "24".repeat(32),
            firstKey.generatePublicKey().encoded
        )
        context.identities[secondPeerID] = GroupPeerIdentity(
            "25".repeat(32),
            secondKey.generatePublicKey().encoded
        )
        val coordinator = GroupCoordinator(context)
        assertTrue(coordinator.createGroup("trail crew").success)

        val ambiguous = coordinator.inviteMember("@alice")

        assertFalse(ambiguous.success)
        assertTrue(ambiguous.message.contains("multiple users"))
        assertTrue(context.invites.isEmpty())
        assertEquals(1, context.groupStore.groups.value.single().epoch)

        val resolved = coordinator.inviteMember("@alice#${secondPeerID.takeLast(8)}")

        assertTrue(resolved.success)
        assertEquals(secondPeerID, context.invites.single().first)
        assertEquals("25".repeat(32), context.groupStore.groups.value.single().members.last().fingerprint)

        assertTrue(coordinator.inviteMember("@alice#${firstPeerID.takeLast(8)}").success)
        val epochBeforeAmbiguousRemoval = context.groupStore.groups.value.single().epoch

        val ambiguousRemoval = coordinator.removeMember("@alice")

        assertFalse(ambiguousRemoval.success)
        assertTrue(ambiguousRemoval.message.contains("multiple members"))
        assertEquals(epochBeforeAmbiguousRemoval, context.groupStore.groups.value.single().epoch)

        val resolvedRemoval =
            coordinator.removeMember("@alice#${firstPeerID.takeLast(8)}")

        assertTrue(resolvedRemoval.success)
        assertFalse(
            context.groupStore.groups.value.single().members.any {
                it.fingerprint == "24".repeat(32)
            }
        )
    }

    @Test
    fun `invite requires confirmed group capability`() {
        val localKey = privateKey(0x12)
        val inviteeKey = privateKey(0x13)
        val peerID = "13".repeat(8)
        val context = FakeGroupContext(localKey, "12".repeat(32))
        context.peerIDs["alice"] = peerID
        context.connected += peerID
        context.peerNames[peerID] = "alice"
        context.identities[peerID] = GroupPeerIdentity(
            "13".repeat(32),
            inviteeKey.generatePublicKey().encoded
        )
        val coordinator = GroupCoordinator(context)
        assertTrue(coordinator.createGroup("trail crew").success)
        val original = context.groupStore.groups.value.single()

        context.groupCapabilities[peerID] = PeerGroupCapability.UNSUPPORTED
        val unsupported = coordinator.inviteMember("@alice")
        assertFalse(unsupported.success)
        assertTrue(unsupported.message.contains("does not support"))
        assertEquals(original, context.groupStore.groups.value.single())
        assertTrue(context.invites.isEmpty())

        context.groupCapabilities[peerID] = PeerGroupCapability.UNKNOWN
        val unknown = coordinator.inviteMember("@alice")
        assertFalse(unknown.success)
        assertTrue(unknown.message.contains("not confirmed"))
        assertEquals(original, context.groupStore.groups.value.single())
        assertTrue(context.invites.isEmpty())
    }

    @Test
    fun `authenticated reconnect replays current signed state to retained member`() {
        val localKey = privateKey(0x16)
        val memberKey = privateKey(0x17)
        val peerID = "17".repeat(8)
        val memberFingerprint = "17".repeat(32)
        val context = FakeGroupContext(localKey, "16".repeat(32))
        context.peerIDs["alice"] = peerID
        context.connected += peerID
        context.peerNames[peerID] = "alice"
        context.identities[peerID] = GroupPeerIdentity(
            memberFingerprint,
            memberKey.generatePublicKey().encoded
        )
        val coordinator = GroupCoordinator(context)
        assertTrue(coordinator.createGroup("trail crew").success)
        assertTrue(coordinator.inviteMember("@alice").success)
        val current = context.groupStore.groups.value.single()
        val currentKey = context.groupStore.key(current.groupID)!!
        context.updates.clear()

        coordinator.handlePeerAuthenticated(peerID)

        assertEquals(1, context.updates.size)
        val (recipient, bytes) = context.updates.single()
        assertEquals(peerID, recipient)
        val state = GroupStatePayload.decode(bytes)!!
        assertTrue(state.verifyCreatorSignature())
        assertEquals(current, state.asGroup())
        assertArrayEquals(currentKey, state.key)

        context.updates.clear()
        context.groupCapabilities[peerID] = PeerGroupCapability.UNSUPPORTED
        coordinator.handlePeerAuthenticated(peerID)
        assertTrue(context.updates.isEmpty())
    }

    @Test
    fun `invite is accepted only from authenticated creator`() {
        val creatorKey = privateKey(0x31)
        val localKey = privateKey(0x32)
        val creatorStatic = ByteArray(32) { 0x41 }
        val creatorFingerprint = fingerprint(creatorStatic)
        val localFingerprint = "52".repeat(32)
        val context = FakeGroupContext(localKey, localFingerprint)
        val group = incomingGroup(
            creatorKey,
            creatorFingerprint,
            localKey,
            localFingerprint
        )
        val payload = signedState(group, creatorKey, ByteArray(32) { 0x61 })
        val coordinator = GroupCoordinator(context)

        coordinator.handleInvite("creator", ByteArray(32) { 0x7f }, payload)
        assertTrue(context.groupStore.groups.value.isEmpty())

        coordinator.handleInvite("creator", creatorStatic, payload)
        assertEquals(group, context.groupStore.groups.value.single())
        assertTrue(group.peerID in context.unread)
        assertEquals(1, context.notifications.size)
    }

    @Test
    fun `creator removal state drops key conversation and membership`() {
        val creatorKey = privateKey(0x71)
        val localKey = privateKey(0x72)
        val creatorStatic = ByteArray(32) { 0x73 }
        val creatorFingerprint = fingerprint(creatorStatic)
        val localFingerprint = "74".repeat(32)
        val context = FakeGroupContext(localKey, localFingerprint)
        val original = incomingGroup(
            creatorKey,
            creatorFingerprint,
            localKey,
            localFingerprint
        )
        assertTrue(context.groupStore.upsert(original, ByteArray(32) { 0x75 }))
        context.selected = original.peerID

        val removedState = original.copy(
            epoch = original.epoch + 1,
            members = listOf(original.members.first())
        )
        val payload = signedState(removedState, creatorKey, ByteArray(32))
        GroupCoordinator(context).handleKeyUpdate("creator", creatorStatic, payload)

        assertNull(context.groupStore.group(original.groupID))
        assertNull(context.groupStore.key(original.groupID))
        assertTrue(original.peerID in context.removedConversations)
        assertNull(context.selected)
        assertTrue(context.systemMessages.single().contains("removed"))
    }

    @Test
    fun `stale removal state cannot delete a newer membership`() {
        val creatorKey = privateKey(0x76)
        val localKey = privateKey(0x77)
        val creatorStatic = ByteArray(32) { 0x78 }
        val creatorFingerprint = fingerprint(creatorStatic)
        val localFingerprint = "79".repeat(32)
        val context = FakeGroupContext(localKey, localFingerprint)
        val current = incomingGroup(
            creatorKey,
            creatorFingerprint,
            localKey,
            localFingerprint
        ).copy(epoch = 3)
        assertTrue(context.groupStore.upsert(current, ByteArray(32) { 0x7a }))
        context.selected = current.peerID

        val staleRemoval = current.copy(
            epoch = 2,
            members = listOf(current.members.first())
        )
        val payload = signedState(staleRemoval, creatorKey, ByteArray(32))
        GroupCoordinator(context).handleKeyUpdate("creator", creatorStatic, payload)

        assertEquals(current, context.groupStore.group(current.groupID))
        assertNotNull(context.groupStore.key(current.groupID))
        assertEquals(current.peerID, context.selected)
        assertTrue(context.removedConversations.isEmpty())
        assertTrue(context.systemMessages.isEmpty())
    }

    @Test
    fun `creator cannot leave while other members remain`() {
        val localKey = privateKey(0x14)
        val memberKey = privateKey(0x15)
        val context = FakeGroupContext(localKey, "14".repeat(32))
        val coordinator = GroupCoordinator(context)
        assertTrue(coordinator.createGroup("trail crew").success)
        val created = context.groupStore.groups.value.single()
        val withMember = created.copy(
            members = created.members + GroupMember(
                "15".repeat(32),
                memberKey.generatePublicKey().encoded,
                "alice"
            )
        )
        assertTrue(
            context.groupStore.upsert(
                withMember,
                context.groupStore.key(created.groupID)!!
            )
        )

        val result = coordinator.leaveGroup()

        assertFalse(result.success)
        assertTrue(result.message.contains("remove all other members"))
        assertEquals(withMember, context.groupStore.group(created.groupID))
        assertEquals(withMember.peerID, context.selected)
        assertTrue(context.removedConversations.isEmpty())
    }

    @Test
    fun `voluntary leave ignores key updates until a newer explicit invite`() {
        val creatorKey = privateKey(0x18)
        val localKey = privateKey(0x19)
        val creatorStatic = ByteArray(32) { 0x1a }
        val creatorFingerprint = fingerprint(creatorStatic)
        val localFingerprint = "19".repeat(32)
        val context = FakeGroupContext(localKey, localFingerprint)
        val original = incomingGroup(
            creatorKey,
            creatorFingerprint,
            localKey,
            localFingerprint
        )
        assertTrue(context.groupStore.upsert(original, ByteArray(32) { 0x1b }))
        context.selected = original.peerID
        val coordinator = GroupCoordinator(context)

        assertTrue(coordinator.leaveGroup().success)
        assertNull(context.groupStore.group(original.groupID))
        assertEquals(original.epoch, context.groupStore.departureEpoch(original.groupID))

        val nextState = original.copy(epoch = original.epoch + 1)
        coordinator.handleKeyUpdate(
            "creator",
            creatorStatic,
            signedState(nextState, creatorKey, ByteArray(32) { 0x1c })
        )
        assertNull(context.groupStore.group(original.groupID))

        coordinator.handleInvite(
            "creator",
            creatorStatic,
            signedState(original, creatorKey, ByteArray(32) { 0x1d })
        )
        assertNull(context.groupStore.group(original.groupID))

        coordinator.handleInvite(
            "creator",
            creatorStatic,
            signedState(nextState, creatorKey, ByteArray(32) { 0x1e })
        )
        assertEquals(nextState, context.groupStore.group(original.groupID))
        assertNull(context.groupStore.departureEpoch(original.groupID))
    }

    @Test
    fun `packets wait for asynchronous group store initialization`() {
        val creatorKey = privateKey(0x1f)
        val localKey = privateKey(0x20)
        val creatorStatic = ByteArray(32) { 0x21 }
        val creatorFingerprint = fingerprint(creatorStatic)
        val localFingerprint = "20".repeat(32)
        val store = GroupStore(
            TestGroupKeys(),
            testOnly = true,
            autoInitialize = false
        )
        val context = FakeGroupContext(localKey, localFingerprint, store)
        val group = incomingGroup(
            creatorKey,
            creatorFingerprint,
            localKey,
            localFingerprint
        )
        val coordinator = GroupCoordinator(context)

        val command = coordinator.createGroup("too early")
        assertFalse(command.success)
        assertTrue(command.message.contains("still loading"))
        coordinator.handleInvite(
            "creator",
            creatorStatic,
            signedState(group, creatorKey, ByteArray(32) { 0x22 })
        )
        assertTrue(store.groups.value.isEmpty())

        assertTrue(store.initialize())
        coordinator.onStoreReady()

        assertEquals(group, store.group(group.groupID))
    }

    @Test
    fun `panic discards packets queued during store initialization`() {
        val creatorKey = privateKey(0x26)
        val localKey = privateKey(0x27)
        val creatorStatic = ByteArray(32) { 0x28 }
        val creatorFingerprint = fingerprint(creatorStatic)
        val localFingerprint = "27".repeat(32)
        val store = GroupStore(
            TestGroupKeys(),
            testOnly = true,
            autoInitialize = false
        )
        val context = FakeGroupContext(localKey, localFingerprint, store)
        val group = incomingGroup(
            creatorKey,
            creatorFingerprint,
            localKey,
            localFingerprint
        )
        val coordinator = GroupCoordinator(context)
        coordinator.handleInvite(
            "creator",
            creatorStatic,
            signedState(group, creatorKey, ByteArray(32) { 0x29 })
        )

        coordinator.suspendForPanic()
        assertTrue(store.initialize())
        assertTrue(store.wipe())
        coordinator.onStoreReady()
        coordinator.resumeAfterPanic()
        coordinator.onStoreReady()

        assertTrue(store.groups.value.isEmpty())
        assertNull(store.key(group.groupID))
        assertTrue(context.notifications.isEmpty())
    }

    @Test
    fun `group message requires a roster sender and deduplicates`() {
        val creatorKey = privateKey(0x21)
        val localKey = privateKey(0x22)
        val creatorFingerprint = "81".repeat(32)
        val localFingerprint = "82".repeat(32)
        val context = FakeGroupContext(localKey, localFingerprint)
        val group = incomingGroup(
            creatorKey,
            creatorFingerprint,
            localKey,
            localFingerprint
        )
        val key = ByteArray(32) { 0x33 }
        assertTrue(context.groupStore.upsert(group, key))
        val coordinator = GroupCoordinator(context)
        val payload = sealedMessage(group, key, creatorKey, MESSAGE_ID_1, "hello")

        coordinator.handleMessage(payload, System.currentTimeMillis())
        coordinator.handleMessage(payload, System.currentTimeMillis())

        val messages = context.messages[group.peerID].orEmpty()
        assertEquals(1, messages.size)
        assertEquals("hello", messages.single().content)
        assertEquals("creator", messages.single().sender)
        assertTrue(group.peerID in context.unread)
        assertEquals(1, context.notifications.size)

        context.blocked += creatorFingerprint
        coordinator.handleMessage(
            sealedMessage(group, key, creatorKey, MESSAGE_ID_2, "blocked"),
            System.currentTimeMillis()
        )
        assertEquals(1, context.messages[group.peerID].orEmpty().size)
    }

    @Test
    fun `future epoch message is retried after matching state arrives`() {
        val creatorKey = privateKey(0x2a)
        val localKey = privateKey(0x2b)
        val creatorStatic = ByteArray(32) { 0x2c }
        val creatorFingerprint = fingerprint(creatorStatic)
        val localFingerprint = "2b".repeat(32)
        val context = FakeGroupContext(localKey, localFingerprint)
        val current = incomingGroup(
            creatorKey,
            creatorFingerprint,
            localKey,
            localFingerprint
        )
        val currentKey = ByteArray(32) { 0x2d }
        val nextKey = ByteArray(32) { 0x2e }
        val next = current.copy(epoch = current.epoch + 1)
        assertTrue(context.groupStore.upsert(current, currentKey))
        val coordinator = GroupCoordinator(context)
        val futureMessage = sealedMessage(
            next,
            nextKey,
            creatorKey,
            MESSAGE_ID_3,
            "after rotation"
        )

        coordinator.handleMessage(futureMessage, System.currentTimeMillis())
        assertTrue(context.messages[current.peerID].orEmpty().isEmpty())

        coordinator.handleKeyUpdate(
            "creator",
            creatorStatic,
            signedState(next, creatorKey, nextKey)
        )

        val delivered = context.messages[current.peerID].orEmpty().single()
        assertEquals(MESSAGE_ID_3, delivered.id)
        assertEquals("after rotation", delivered.content)
    }

    private fun incomingGroup(
        creatorKey: Ed25519PrivateKeyParameters,
        creatorFingerprint: String,
        localKey: Ed25519PrivateKeyParameters,
        localFingerprint: String
    ) = BitchatGroup(
        groupID = ByteArray(16) { it.toByte() },
        name = "ops",
        epoch = 1,
        members = listOf(
            GroupMember(
                creatorFingerprint,
                creatorKey.generatePublicKey().encoded,
                "creator"
            ),
            GroupMember(
                localFingerprint,
                localKey.generatePublicKey().encoded,
                "local"
            )
        ),
        creatorFingerprint = creatorFingerprint
    )

    private fun signedState(
        group: BitchatGroup,
        creatorKey: Ed25519PrivateKeyParameters,
        key: ByteArray
    ): ByteArray =
        GroupStatePayload.makeSigned(group, key) { sign(creatorKey, it) }
            ?.encode()
            ?: error("state should encode")

    private fun sealedMessage(
        group: BitchatGroup,
        key: ByteArray,
        senderKey: Ed25519PrivateKeyParameters,
        messageID: String,
        content: String
    ): ByteArray = GroupCrypto.sealMessage(
        content = content,
        messageID = messageID,
        senderNickname = "untrusted nickname",
        senderSigningKey = senderKey.generatePublicKey().encoded,
        timestampMs = System.currentTimeMillis(),
        groupID = group.groupID,
        epoch = group.epoch,
        key = key
    ) { sign(senderKey, it) }

    private fun privateKey(seed: Int) =
        Ed25519PrivateKeyParameters(ByteArray(32) { seed.toByte() }, 0)

    private fun sign(
        privateKey: Ed25519PrivateKeyParameters,
        data: ByteArray
    ): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun fingerprint(noiseKey: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(noiseKey)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MESSAGE_ID_1 = "123e4567-e89b-12d3-a456-426614174010"
        private const val MESSAGE_ID_2 = "123e4567-e89b-12d3-a456-426614174011"
        private const val MESSAGE_ID_3 = "123e4567-e89b-12d3-a456-426614174012"
    }
}

private class FakeGroupContext(
    private val localKey: Ed25519PrivateKeyParameters,
    private val localFingerprint: String,
    override val groupStore: GroupStore = GroupStore(TestGroupKeys(), testOnly = true)
) : GroupCoordinatorContext {
    override val nickname = "local"
    override val myPeerID = localFingerprint.take(16)
    override val selectedConversationID: String?
        get() = selected

    var selected: String? = null
    val peerIDs = mutableMapOf<String, String>()
    val additionalPeerIDs = mutableMapOf<String, MutableList<String>>()
    val connected = mutableSetOf<String>()
    val groupCapabilities = mutableMapOf<String, PeerGroupCapability>()
    val peerNames = mutableMapOf<String, String>()
    val identities = mutableMapOf<String, GroupPeerIdentity>()
    val connectedFingerprints = mutableMapOf<String, String>()
    val blocked = mutableSetOf<String>()
    val invites = mutableListOf<Pair<String, ByteArray>>()
    val updates = mutableListOf<Pair<String, ByteArray>>()
    val broadcasts = mutableListOf<ByteArray>()
    val messages = mutableMapOf<String, MutableList<BitchatMessage>>()
    val unread = mutableSetOf<String>()
    val removedConversations = mutableSetOf<String>()
    val systemMessages = mutableListOf<String>()
    val notifications = mutableListOf<Triple<String, String, String>>()

    override fun myNoiseFingerprint() = localFingerprint
    override fun mySigningPublicKey(): ByteArray = localKey.generatePublicKey().encoded
    override fun sign(data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, localKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    override fun peerIDsForNickname(nickname: String): List<String> =
        listOfNotNull(peerIDs[nickname]) + additionalPeerIDs[nickname].orEmpty()
    override fun isPeerConnected(peerID: String) = peerID in connected
    override fun peerGroupCapability(peerID: String) =
        groupCapabilities[peerID] ?: PeerGroupCapability.SUPPORTED
    override fun peerNickname(peerID: String) = peerNames[peerID]
    override fun peerIdentity(peerID: String) = identities[peerID]
    override fun connectedPeerID(fingerprint: String) = connectedFingerprints[fingerprint]
    override fun isFingerprintBlocked(fingerprint: String) = fingerprint in blocked

    override fun sendGroupInvite(payload: ByteArray, peerID: String) {
        invites += peerID to payload
    }

    override fun sendGroupKeyUpdate(payload: ByteArray, peerID: String) {
        updates += peerID to payload
    }

    override fun broadcastGroupMessage(payload: ByteArray) {
        broadcasts += payload
    }

    override fun appendGroupMessage(
        groupPeerID: String,
        message: BitchatMessage
    ): Boolean {
        val conversation = messages.getOrPut(groupPeerID) { mutableListOf() }
        if (conversation.any { it.id == message.id }) return false
        conversation += message
        return true
    }

    override fun markGroupUnread(groupPeerID: String) {
        unread += groupPeerID
    }

    override fun removeGroupConversation(groupPeerID: String) {
        removedConversations += groupPeerID
        messages.remove(groupPeerID)
        unread.remove(groupPeerID)
    }

    override fun openGroupConversation(groupPeerID: String) {
        selected = groupPeerID
    }

    override fun closeGroupConversation() {
        selected = null
    }

    override fun addSystemMessage(message: String) {
        systemMessages += message
    }

    override fun addGroupSystemMessage(groupPeerID: String, message: String) {
        appendGroupMessage(
            groupPeerID,
            BitchatMessage(
                sender = "system",
                content = message,
                timestamp = Date(),
                isPrivate = true
            )
        )
    }

    override fun notifyGroupMessage(
        groupPeerID: String,
        sender: String,
        message: String
    ) {
        notifications += Triple(groupPeerID, sender, message)
    }
}

private class TestGroupKeys : GroupKeyStorage {
    private val values = mutableMapOf<String, ByteArray>()

    override fun get(key: String): ByteArray? = values[key]?.copyOf()

    override fun put(key: String, value: ByteArray): Boolean {
        values[key] = value.copyOf()
        return true
    }

    override fun remove(key: String): Boolean = values.remove(key) != null

    override fun clear(): Boolean {
        values.clear()
        return true
    }
}
