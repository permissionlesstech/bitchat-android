package com.bitchat.android.groups

import com.bitchat.android.model.BitchatMessage
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
        val payload = sealedMessage(group, key, creatorKey, "message-1", "hello")

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
            sealedMessage(group, key, creatorKey, "message-2", "blocked"),
            System.currentTimeMillis()
        )
        assertEquals(1, context.messages[group.peerID].orEmpty().size)
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
}

private class FakeGroupContext(
    private val localKey: Ed25519PrivateKeyParameters,
    private val localFingerprint: String
) : GroupCoordinatorContext {
    override val groupStore = GroupStore(TestGroupKeys(), testOnly = true)
    override val nickname = "local"
    override val myPeerID = localFingerprint.take(16)
    override val selectedConversationID: String?
        get() = selected

    var selected: String? = null
    val peerIDs = mutableMapOf<String, String>()
    val connected = mutableSetOf<String>()
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

    override fun peerIDForNickname(nickname: String) = peerIDs[nickname]
    override fun isPeerConnected(peerID: String) = peerID in connected
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
}
