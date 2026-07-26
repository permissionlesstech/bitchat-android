package com.bitchat.android.board

import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BoardStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private var now = 1_700_000_000_000uL
    private val author = Key(ByteArray(32) { it.toByte() })
    private val attacker = Key(ByteArray(32) { (it + 32).toByte() })

    @Test
    fun `expired and future-dated posts are rejected`() {
        val store = BoardStore(nowMs = { now })

        assertEquals(
            BoardIngestResult.REJECTED,
            store.ingestPost(signedPost(createdAt = now - 100uL, expiresAt = now))
        )
        assertEquals(
            BoardIngestResult.REJECTED,
            store.ingestPost(
                signedPost(
                    createdAt = now + BoardStore.Limits.CLOCK_SKEW_MS + 1uL,
                    expiresAt = now + BoardStore.Limits.CLOCK_SKEW_MS + 2uL
                )
            )
        )
    }

    @Test
    fun `per-author cap evicts oldest posts`() {
        val store = BoardStore(nowMs = { now })
        repeat(BoardStore.Limits.MAX_POSTS_PER_AUTHOR + 1) { index ->
            val post = signedPost(
                idByte = index.toByte(),
                createdAt = now + index.toULong(),
                expiresAt = now + 86_400_000uL
            )
            assertEquals(BoardIngestResult.ACCEPTED, store.ingestPost(post))
        }

        val posts = store.posts("")
        assertEquals(BoardStore.Limits.MAX_POSTS_PER_AUTHOR, posts.size)
        assertTrue(posts.none { it.postID[0] == 0.toByte() })
    }

    @Test
    fun `only author tombstone deletes known post`() {
        val store = BoardStore(nowMs = { now })
        val post = signedPost()
        assertEquals(BoardIngestResult.ACCEPTED, store.ingestPost(post))

        val forged = signedTombstone(post, attacker)
        assertEquals(BoardIngestResult.REJECTED, store.ingestTombstone(forged))
        assertEquals(1, store.posts("").size)

        val valid = signedTombstone(post, author, deletedAt = now + 1uL)
        assertEquals(BoardIngestResult.ACCEPTED, store.ingestTombstone(valid))
        assertTrue(store.posts("").isEmpty())
        assertEquals(1, store.syncCandidates().size)
    }

    @Test
    fun `tombstone suppresses a stale copy until original expiry`() {
        val store = BoardStore(nowMs = { now })
        val post = signedPost(expiresAt = now + 10_000uL)
        store.ingestPost(post)
        store.ingestTombstone(signedTombstone(post, author))

        assertEquals(BoardIngestResult.REJECTED, store.ingestPost(post))
        now += 10_001uL
        store.pruneExpired()
        assertTrue(store.syncCandidates().isEmpty())
    }

    @Test
    fun `signed packets persist and are reverified on load`() {
        val file = temporaryFolder.newFile("posts.json")
        file.delete()
        val store = BoardStore(file = file, nowMs = { now })
        val post = signedPost(geohash = "u33dc1")
        assertEquals(BoardIngestResult.ACCEPTED, store.ingestPost(post))

        val restored = BoardStore(file = file, nowMs = { now })

        assertEquals(listOf(post), restored.posts("u33dc1"))
        assertEquals(1, restored.syncCandidates().size)
    }

    private fun BoardStore.ingestPost(post: BoardPostPacket): BoardIngestResult {
        val wire = BoardWire.Post(post)
        return ingest(wire, packet(wire), BoardIngestSource.REMOTE)
    }

    private fun BoardStore.ingestTombstone(
        tombstone: BoardTombstonePacket
    ): BoardIngestResult {
        val wire = BoardWire.Tombstone(tombstone)
        return ingest(wire, packet(wire), BoardIngestSource.REMOTE)
    }

    private fun packet(wire: BoardWire) = BitchatPacket(
        type = MessageType.BOARD_POST.value,
        senderID = ByteArray(8) { 1 },
        timestamp = now,
        payload = BoardWireCodec.encode(wire),
        ttl = 7u
    )

    private fun signedPost(
        idByte: Byte = 1,
        geohash: String = "",
        createdAt: ULong = now,
        expiresAt: ULong = now + 86_400_000uL
    ): BoardPostPacket {
        val postID = ByteArray(16).also { it[0] = idByte }
        val content = "notice-$idByte"
        val signingBytes = BoardPostPacket.signingBytes(
            postID,
            geohash,
            content,
            author.publicKey,
            "alice",
            createdAt,
            expiresAt,
            0u
        )
        return BoardPostPacket(
            postID,
            geohash,
            content,
            author.publicKey,
            "alice",
            createdAt,
            expiresAt,
            0u,
            author.sign(signingBytes)
        )
    }

    private fun signedTombstone(
        post: BoardPostPacket,
        key: Key,
        deletedAt: ULong = now
    ): BoardTombstonePacket {
        val signingBytes = BoardTombstonePacket.signingBytes(post.postID, deletedAt)
        return BoardTombstonePacket(
            post.postID,
            key.publicKey,
            deletedAt,
            key.sign(signingBytes)
        )
    }

    private class Key(seed: ByteArray) {
        private val privateKey = Ed25519PrivateKeyParameters(seed, 0)
        val publicKey: ByteArray = privateKey.generatePublicKey().encoded

        fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
            init(true, privateKey)
            update(message, 0, message.size)
            generateSignature()
        }
    }
}
