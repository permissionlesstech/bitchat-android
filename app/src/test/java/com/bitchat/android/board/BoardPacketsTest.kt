package com.bitchat.android.board

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardPacketsTest {
    private val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    private val publicKey = privateKey.generatePublicKey().encoded

    @Test
    fun `post round trips and verifies`() {
        val post = signedPost(geohash = "u33dc1", content = "water at gate 2", urgent = true)
        val encoded = BoardWireCodec.encode(BoardWire.Post(post))
        val decoded = BoardWireCodec.decode(encoded) as BoardWire.Post

        assertEquals(post, decoded.packet)
        assertTrue(decoded.verifySignature())
        assertTrue(BoardWireCodec.urgentFlag(encoded))
    }

    @Test
    fun `mesh-local post and tombstone round trip`() {
        val post = signedPost(geohash = "", content = "mesh notice", urgent = false)
        val deletedAt = 1_700_000_100_000uL
        val tombstoneBytes = BoardTombstonePacket.signingBytes(post.postID, deletedAt)
        val tombstone = BoardTombstonePacket(
            postID = post.postID,
            authorSigningKey = publicKey,
            deletedAt = deletedAt,
            signature = sign(tombstoneBytes)
        )

        val decoded = BoardWireCodec.decode(
            BoardWireCodec.encode(BoardWire.Tombstone(tombstone))
        ) as BoardWire.Tombstone

        assertEquals(tombstone, decoded.packet)
        assertTrue(decoded.verifySignature())
        assertFalse(BoardWireCodec.urgentFlag(BoardWireCodec.encode(BoardWire.Post(post))))
    }

    @Test
    fun `tampered content fails signature verification`() {
        val encoded = BoardWireCodec.encode(
            BoardWire.Post(signedPost(content = "original"))
        )
        val decoded = BoardWireCodec.decode(encoded) as BoardWire.Post
        val tampered = BoardPostPacket(
            postID = decoded.packet.postID,
            geohash = decoded.packet.geohash,
            content = "changed",
            authorSigningKey = decoded.packet.authorSigningKey,
            authorNickname = decoded.packet.authorNickname,
            createdAt = decoded.packet.createdAt,
            expiresAt = decoded.packet.expiresAt,
            flags = decoded.packet.flags,
            signature = decoded.packet.signature
        )

        assertFalse(tampered.verifySignature())
    }

    @Test
    fun `decoder rejects invalid field sizes lifetime and geohash`() {
        assertNull(BoardWireCodec.decode(replaceTlv(
            BoardWireCodec.encode(BoardWire.Post(signedPost())),
            type = 0x02,
            value = ByteArray(15)
        )))
        assertNull(BoardWireCodec.decode(replaceTlv(
            BoardWireCodec.encode(BoardWire.Post(signedPost())),
            type = 0x03,
            value = "u33dio".toByteArray()
        )))

        val tooLong = signedPost(
            createdAt = 1_700_000_000_000uL,
            expiresAt = 1_700_000_000_000uL + BoardWireConstants.MAX_LIFETIME_MS + 1uL
        )
        assertNull(BoardWireCodec.decode(BoardWireCodec.encode(BoardWire.Post(tooLong))))
    }

    @Test
    fun `unknown TLVs are ignored`() {
        val post = signedPost()
        val encoded = BoardWireCodec.encode(BoardWire.Post(post))
        val unknown = byteArrayOf(0x7F, 0x00, 0x03, 0x01, 0x02, 0x03)

        val decoded = BoardWireCodec.decode(encoded + unknown) as BoardWire.Post

        assertEquals(post, decoded.packet)
        assertTrue(decoded.verifySignature())
    }

    @Test
    fun `canonical signing bytes use context and big-endian length prefixes`() {
        val post = signedPost(
            postID = ByteArray(16) { (it + 1).toByte() },
            geohash = "u4",
            content = "hi",
            nickname = "n",
            createdAt = 0x0102030405060708uL,
            expiresAt = 0x1112131415161718uL,
            urgent = true
        )

        val bytes = post.signingBytes
        assertEquals(BoardWireConstants.POST_SIGNING_CONTEXT.length, bytes[0].toInt())
        assertArrayEquals(
            BoardWireConstants.POST_SIGNING_CONTEXT.toByteArray(),
            bytes.copyOfRange(1, 1 + BoardWireConstants.POST_SIGNING_CONTEXT.length)
        )
        assertTrue(post.verifySignature())
    }

    @Test
    fun `transport sender is scoped to embedded author identity`() {
        val wire = BoardWire.Post(signedPost())
        val sameAuthor = BoardWire.Post(signedPost(content = "another notice"))
        val otherIdentity = BoardSigningIdentity.fromEd25519Seed(ByteArray(32) { (it + 7).toByte() })
        val otherPost = BoardPostPacket(
            postID = ByteArray(16) { 9 },
            geohash = "u33dc1",
            content = "other author",
            authorSigningKey = otherIdentity.publicKey,
            authorNickname = "bob",
            createdAt = 1_700_000_000_000uL,
            expiresAt = 1_700_086_400_000uL,
            flags = 0u,
            signature = ByteArray(64)
        )

        assertEquals(BoardWireConstants.TRANSPORT_SENDER_ID_LENGTH, wire.transportSenderID().size)
        assertArrayEquals(wire.transportSenderID(), sameAuthor.transportSenderID())
        assertFalse(wire.transportSenderID().contentEquals(BoardWire.Post(otherPost).transportSenderID()))
    }

    @Test
    fun `geo board identity is deterministic and domain separated from Nostr key`() {
        val nostrSecret = ByteArray(32) { (it + 11).toByte() }
        val secretHex = nostrSecret.joinToString("") { "%02x".format(it) }
        val first = BoardSigningIdentity.fromNostrPrivateKeyHex(secretHex)!!
        val second = BoardSigningIdentity.fromNostrPrivateKeyHex(secretHex)!!

        assertArrayEquals(first.publicKey, second.publicKey)
        assertFalse(first.publicKey.contentEquals(nostrSecret))
        val message = "ios-compatible-board-payload".toByteArray()
        assertTrue(BoardWireCodec.verify(first.sign(message)!!, message, first.publicKey))
    }

    private fun signedPost(
        postID: ByteArray = ByteArray(16) { (it + 1).toByte() },
        geohash: String = "u33dc1",
        content: String = "notice",
        nickname: String = "alice",
        createdAt: ULong = 1_700_000_000_000uL,
        expiresAt: ULong = createdAt + 86_400_000uL,
        urgent: Boolean = false
    ): BoardPostPacket {
        val flags = if (urgent) BoardPostPacket.URGENT_FLAG else 0u.toUByte()
        val signingBytes = BoardPostPacket.signingBytes(
            postID = postID,
            geohash = geohash,
            content = content,
            authorSigningKey = publicKey,
            authorNickname = nickname,
            createdAt = createdAt,
            expiresAt = expiresAt,
            flags = flags
        )
        return BoardPostPacket(
            postID = postID,
            geohash = geohash,
            content = content,
            authorSigningKey = publicKey,
            authorNickname = nickname,
            createdAt = createdAt,
            expiresAt = expiresAt,
            flags = flags,
            signature = sign(signingBytes)
        )
    }

    private fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, privateKey)
        update(message, 0, message.size)
        generateSignature()
    }

    private fun replaceTlv(encoded: ByteArray, type: Int, value: ByteArray): ByteArray {
        val output = ArrayList<Byte>()
        var offset = 0
        while (offset + 3 <= encoded.size) {
            val currentType = encoded[offset].toInt() and 0xFF
            val length =
                ((encoded[offset + 1].toInt() and 0xFF) shl 8) or
                    (encoded[offset + 2].toInt() and 0xFF)
            val end = offset + 3 + length
            if (currentType == type) {
                output += type.toByte()
                output += ((value.size ushr 8) and 0xFF).toByte()
                output += (value.size and 0xFF).toByte()
                output += value.toList()
            } else {
                output += encoded.copyOfRange(offset, end).toList()
            }
            offset = end
        }
        return output.toByteArray()
    }
}
