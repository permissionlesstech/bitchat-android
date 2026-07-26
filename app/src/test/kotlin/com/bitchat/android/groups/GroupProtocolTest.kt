package com.bitchat.android.groups

import java.security.MessageDigest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GroupProtocolTest {
    private val creatorPrivate = Ed25519PrivateKeyParameters(ByteArray(32) { 0x11 }, 0)
    private val memberPrivate = Ed25519PrivateKeyParameters(ByteArray(32) { 0x22 }, 0)
    private val creator = member(creatorPrivate, "creator", 0x31)
    private val member = member(memberPrivate, "alice", 0x41)
    private val group = BitchatGroup(
        groupID = ByteArray(16) { it.toByte() },
        name = "hike",
        epoch = 7,
        members = listOf(creator, member),
        creatorFingerprint = creator.fingerprint
    )
    private val groupKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `creator-signed state round trips and verifies`() {
        val payload = GroupStatePayload.makeSigned(group, groupKey) { sign(creatorPrivate, it) }!!
        val decoded = GroupStatePayload.decode(payload.encode()!!)!!

        assertEquals(payload, decoded)
        assertTrue(decoded.verifyCreatorSignature())
        assertEquals(group, decoded.asGroup())
    }

    @Test
    fun `creator signature covers name epoch key and roster`() {
        val payload = GroupStatePayload.makeSigned(group, groupKey) { sign(creatorPrivate, it) }!!

        assertFalse(copyState(payload, name = "ops").verifyCreatorSignature())
        assertFalse(copyState(payload, epoch = payload.epoch + 1).verifyCreatorSignature())
        assertFalse(copyState(payload, key = ByteArray(32) { 9 }).verifyCreatorSignature())
        assertFalse(
            copyState(
                payload,
                members = payload.members.map {
                    if (it == member) it.copy(nickname = "mallory") else it
                }
            ).verifyCreatorSignature()
        )
    }

    @Test
    fun `state rejects creator missing from roster and oversized roster`() {
        val noCreator = copyState(
            GroupStatePayload.makeSigned(group, groupKey) { sign(creatorPrivate, it) }!!,
            members = listOf(member)
        )
        assertFalse(noCreator.verifyCreatorSignature())

        val tooMany = List(BitchatGroup.MAX_MEMBERS + 1) { index ->
            member(memberPrivate, "m$index", index)
        }
        assertNull(GroupRosterCoding.encode(tooMany))
    }

    @Test
    fun `roster nickname truncation remains valid UTF-8`() {
        val longNickname = "€".repeat(40)
        val encoded = GroupRosterCoding.encode(listOf(creator.copy(nickname = longNickname)))!!
        val decoded = GroupRosterCoding.decode(encoded)!!

        assertTrue(decoded.single().nickname.toByteArray(Charsets.UTF_8).size <= 64)
        assertTrue(decoded.single().nickname.all { it != '\uFFFD' })
    }

    @Test
    fun `group message seals opens and verifies sender`() {
        val encoded = GroupCrypto.sealMessage(
            content = "meet at the ridge",
            messageID = "message-1",
            senderNickname = member.nickname,
            senderSigningKey = member.signingKey,
            timestampMs = 1_725_000_000_123,
            groupID = group.groupID,
            epoch = group.epoch,
            key = groupKey
        ) { sign(memberPrivate, it) }

        val envelope = GroupMessageEnvelope.decode(encoded)!!
        val opened = GroupCrypto.openMessage(envelope, groupKey)

        assertEquals("message-1", opened.messageID)
        assertEquals("meet at the ridge", opened.content)
        assertEquals(member.nickname, opened.senderNickname)
        assertArrayEquals(member.signingKey, opened.senderSigningKey)
    }

    @Test
    fun `message cannot move between keys groups or epochs`() {
        val encoded = sealedMessage()
        val envelope = GroupMessageEnvelope.decode(encoded)!!

        assertThrows(GroupCryptoException.DecryptionFailed::class.java) {
            GroupCrypto.openMessage(envelope, ByteArray(32) { 0x7f })
        }
        assertThrows(GroupCryptoException.DecryptionFailed::class.java) {
            GroupCrypto.openMessage(
                GroupMessageEnvelope(
                    envelope.groupID,
                    envelope.epoch + 1,
                    envelope.nonce,
                    envelope.ciphertext
                ),
                groupKey
            )
        }
        assertThrows(GroupCryptoException.DecryptionFailed::class.java) {
            GroupCrypto.openMessage(
                GroupMessageEnvelope(
                    ByteArray(16) { 0x55 },
                    envelope.epoch,
                    envelope.nonce,
                    envelope.ciphertext
                ),
                groupKey
            )
        }
    }

    @Test
    fun `bad sender signature is rejected after valid AEAD`() {
        val encoded = GroupCrypto.sealMessage(
            content = "forged",
            messageID = "message-2",
            senderNickname = member.nickname,
            senderSigningKey = member.signingKey,
            timestampMs = 42,
            groupID = group.groupID,
            epoch = group.epoch,
            key = groupKey
        ) { ByteArray(64) }

        assertThrows(GroupCryptoException.BadSenderSignature::class.java) {
            GroupCrypto.openMessage(GroupMessageEnvelope.decode(encoded)!!, groupKey)
        }
    }

    @Test
    fun `malformed envelopes and state are rejected`() {
        assertNull(GroupMessageEnvelope.decode(byteArrayOf(1, 0)))
        assertNull(GroupStatePayload.decode(byteArrayOf(1, 0)))

        val envelope = GroupMessageEnvelope.decode(sealedMessage())!!
        val tampered = envelope.ciphertext.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertThrows(GroupCryptoException.DecryptionFailed::class.java) {
            GroupCrypto.openMessage(
                GroupMessageEnvelope(envelope.groupID, envelope.epoch, envelope.nonce, tampered),
                groupKey
            )
        }
    }

    @Test
    fun `group IDs use iOS virtual conversation form`() {
        val peerID = GroupIds.peerID(group.groupID)
        assertEquals("group_000102030405060708090a0b0c0d0e0f", peerID)
        assertTrue(GroupIds.isGroup(peerID))
        assertArrayEquals(group.groupID, GroupIds.groupID(peerID))
        assertNull(GroupIds.groupID("group_not-hex"))
    }

    @Test
    fun `message signing content covers epoch`() {
        val first = GroupCrypto.messageSigningContent(group.groupID, 1, "id", 9, "hello")
        val second = GroupCrypto.messageSigningContent(group.groupID, 2, "id", 9, "hello")
        assertFalse(MessageDigest.isEqual(first, second))
    }

    @Test
    fun `opens deterministic vectors emitted by iOS CryptoKit`() {
        val state = GroupStatePayload.decode(IOS_STATE_VECTOR.hexBytes())
        assertNotNull(state)
        assertEquals("hike", state!!.name)
        assertEquals(7, state.epoch)
        assertTrue(state.verifyCreatorSignature())

        val plaintext = GroupCrypto.openMessage(
            GroupMessageEnvelope.decode(IOS_MESSAGE_VECTOR.hexBytes())!!,
            ByteArray(32) { (it + 1).toByte() }
        )
        assertEquals("ios-vector", plaintext.messageID)
        assertEquals("alice", plaintext.senderNickname)
        assertEquals("meet at ridge", plaintext.content)
        assertEquals(1_725_000_000_123, plaintext.timestampMs)
        assertArrayEquals(IOS_MEMBER_PUBLIC_KEY.hexBytes(), plaintext.senderSigningKey)
    }

    private fun sealedMessage(): ByteArray = GroupCrypto.sealMessage(
        content = "hello",
        messageID = "message",
        senderNickname = member.nickname,
        senderSigningKey = member.signingKey,
        timestampMs = 1234,
        groupID = group.groupID,
        epoch = group.epoch,
        key = groupKey
    ) { sign(memberPrivate, it) }

    private fun member(
        privateKey: Ed25519PrivateKeyParameters,
        nickname: String,
        fingerprintSeed: Int
    ): GroupMember = GroupMember(
        fingerprint = ByteArray(32) { fingerprintSeed.toByte() }
            .joinToString("") { "%02x".format(it) },
        signingKey = privateKey.generatePublicKey().encoded,
        nickname = nickname
    )

    private fun sign(privateKey: Ed25519PrivateKeyParameters, data: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
        signer.update(data, 0, data.size)
        return signer.generateSignature()
    }

    private fun copyState(
        source: GroupStatePayload,
        groupID: ByteArray = source.groupID,
        name: String = source.name,
        key: ByteArray = source.key,
        epoch: Long = source.epoch,
        members: List<GroupMember> = source.members,
        creatorFingerprint: String = source.creatorFingerprint,
        signature: ByteArray = source.signature
    ): GroupStatePayload = GroupStatePayload(
        groupID,
        name,
        key,
        epoch,
        members,
        creatorFingerprint,
        signature
    )

    private fun String.hexBytes(): ByteArray =
        chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    companion object {
        private const val IOS_MEMBER_PUBLIC_KEY =
            "a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f0"
        private const val IOS_STATE_VECTOR =
            "010010000102030405060708090a0b0c0d0e0f02000468696b650300200102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f200400040000000705008f023131313131313131313131313131313131313131313131313131313131313131d04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c97787370763726561746f724141414141414141414141414141414141414141414141414141414141414141a09aa5f47a6759802ff955f8dc2d2a14a5c99d23be97f864127ff9383455a4f005616c69636506002031313131313131313131313131313131313131313131313131313131313131310700407e7dcb5210e48a2edc346f1364c0e3f3a7939a3006f318bd591e94f11b7c6d5a928042f04278bf2e5d612da02258acd686d11a8f8f310b2cc0f8b886bb13ac05"
        private const val IOS_MESSAGE_VECTOR =
            "010010000102030405060708090a0b0c0d0e0f0200040000000703000c000102030405060708090a0b0400a6e1abbfb19fd03920bbd627b82b5d575bd8ec48fc57c70d8f7f7c3af33375ae8ac1ed189e8e17acbe8f4c77cda97e44b8084234d2d8e7825ddcfdb492ed01a4eba676a9c28fcae940b33a80a756f27af8758e6dfca33c4184cd5fa2b82fe527de32c79c9a52d8ddcd596c7e3dcc29003184adf571489a8a9a9d0b7301cdf85b45b4521e55aebf6238031af50c81d6dd639b6210e82c0ab70e19ba4d1b04c323c70f6e09b54585"
    }
}
