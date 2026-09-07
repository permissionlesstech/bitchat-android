package com.bitchat.android.board

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.nostr.LocationNotesManager
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.security.SecureRandom

class BoardManagerTest {
    private val privateKey = Ed25519PrivateKeyParameters(ByteArray(32) { it.toByte() }, 0)
    private val publicKey = privateKey.generatePublicKey().encoded
    private val geoIdentity = BoardSigningIdentity.fromEd25519Seed(ByteArray(32) { (it + 64).toByte() })

    @Test
    fun `create and delete emit iOS-compatible signed wire payloads`() = runTest {
        val mesh = mock<MeshService>()
        val notes = mock<LocationNotesManager>()
        whenever(mesh.getSigningPublicKey()).thenReturn(publicKey)
        whenever(mesh.signData(any())).thenAnswer { invocation ->
            sign(invocation.getArgument(0))
        }
        val manager = BoardManager(
            store = BoardStore(nowMs = { NOW }),
            scope = backgroundScope,
            meshProvider = { mesh },
            geoIdentityProvider = { geoIdentity },
            notesManager = notes,
            nowMs = { NOW },
            random = SecureRandom(byteArrayOf(7))
        )

        assertTrue(
            manager.createPost(
                content = "  water at gate two  ",
                geohash = "U33DC",
                nickname = "alice",
                urgent = false,
                expiryDays = 3
            )
        )
        val payloads = argumentCaptor<ByteArray>()
        verify(mesh).sendBoardPayload(payloads.capture())
        val post = (BoardWireCodec.decode(payloads.firstValue) as BoardWire.Post).packet
        assertEquals("water at gate two", post.content)
        assertEquals("u33dc", post.geohash)
        assertEquals(NOW + 3uL * DAY_MS, post.expiresAt)
        assertTrue(geoIdentity.publicKey.contentEquals(post.authorSigningKey))
        assertTrue(post.verifySignature())
        verify(mesh, never()).signData(any())

        val reloadedManager = BoardManager(
            store = BoardStore(nowMs = { NOW }),
            scope = backgroundScope,
            meshProvider = { mesh },
            geoIdentityProvider = { geoIdentity },
            notesManager = notes,
            nowMs = { NOW }
        )
        assertTrue(reloadedManager.deletePost(post))
        verify(mesh, times(2)).sendBoardPayload(payloads.capture())
        val tombstone =
            (BoardWireCodec.decode(payloads.allValues.last()) as BoardWire.Tombstone).packet
        assertTrue(tombstone.postID.contentEquals(post.postID))
        assertTrue(tombstone.verifySignature())
    }

    @Test
    fun `mesh board keeps the established mesh signing identity`() = runTest {
        val mesh = mock<MeshService>()
        whenever(mesh.getSigningPublicKey()).thenReturn(publicKey)
        whenever(mesh.signData(any())).thenAnswer { invocation ->
            sign(invocation.getArgument(0))
        }
        val manager = BoardManager(
            store = BoardStore(nowMs = { NOW }),
            scope = backgroundScope,
            meshProvider = { mesh },
            geoIdentityProvider = { geoIdentity },
            notesManager = mock(),
            nowMs = { NOW }
        )

        assertTrue(manager.createPost("mesh notice", "", "alice", false, 1))

        val payload = argumentCaptor<ByteArray>()
        verify(mesh).sendBoardPayload(payload.capture())
        val post = (BoardWireCodec.decode(payload.firstValue) as BoardWire.Post).packet
        assertTrue(publicKey.contentEquals(post.authorSigningKey))
        assertTrue(post.verifySignature())
        verify(mesh).signData(any())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `remote urgent arrivals badge their scope and collapse into an alert`() = runTest {
        val mesh = mock<MeshService>()
        whenever(mesh.getSigningPublicKey()).thenReturn(ByteArray(32) { 9 })
        val store = BoardStore(nowMs = { NOW })
        val alerts = mutableListOf<Pair<String, List<BoardPostPacket>>>()
        val manager = BoardManager(
            store = store,
            scope = backgroundScope,
            meshProvider = { mesh },
            notesManager = mock(),
            nowMs = { NOW },
            onUrgentPosts = { geohash, posts -> alerts += geohash to posts }
        )
        runCurrent()
        val post = signedPost(urgent = true)
        val wire = BoardWire.Post(post)

        assertEquals(
            BoardIngestResult.ACCEPTED,
            store.ingest(
                wire = wire,
                packet = BitchatPacket(
                    type = MessageType.BOARD_POST.value,
                    senderID = ByteArray(8) { 1 },
                    timestamp = NOW,
                    payload = BoardWireCodec.encode(wire),
                    ttl = 7u
                ),
                source = BoardIngestSource.REMOTE
            )
        )
        runCurrent()
        assertTrue("u33dc" in manager.unseenScopes.value)
        advanceTimeBy(4_000)
        runCurrent()
        assertEquals(listOf("u33dc"), alerts.map { it.first })
        assertEquals(listOf(post), alerts.single().second)

        manager.markSeen(setOf("u33dc"))
        assertFalse("u33dc" in manager.unseenScopes.value)
    }

    private fun signedPost(urgent: Boolean): BoardPostPacket {
        val postID = ByteArray(16) { 4 }
        val flags: UByte = if (urgent) BoardPostPacket.URGENT_FLAG else 0u
        val signingBytes = BoardPostPacket.signingBytes(
            postID,
            "u33dc",
            "road closed",
            publicKey,
            "alice",
            NOW,
            NOW + DAY_MS,
            flags
        )
        return BoardPostPacket(
            postID,
            "u33dc",
            "road closed",
            publicKey,
            "alice",
            NOW,
            NOW + DAY_MS,
            flags,
            sign(signingBytes)
        )
    }

    private fun sign(message: ByteArray): ByteArray = Ed25519Signer().run {
        init(true, privateKey)
        update(message, 0, message.size)
        generateSignature()
    }

    private companion object {
        const val NOW: ULong = 1_700_000_000_000uL
        const val DAY_MS: ULong = 86_400_000uL
    }
}
