package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argThat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class MessageRouterTest {

    private val myPeerID = "1111222233334444"
    private val peerID = "aaaabbbbccccdddd"
    private val noiseKey = ByteArray(32) { 0x0B }

    private lateinit var mesh: MeshService
    private lateinit var router: MessageRouter
    private lateinit var identityManager: SecureIdentityStateManager
    private var fakeTime = 1_000_000L
    private val expired = mutableListOf<String>()

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences(
            "message-router-test-${UUID.randomUUID()}",
            Context.MODE_PRIVATE
        )
        identityManager = SecureIdentityStateManager(prefs, testOnly = true)
        ContactDirectory.identityManagerProvider = { identityManager }

        mesh = mock()
        whenever(mesh.myPeerID).thenReturn(myPeerID)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "peer"))

        ContactDirectory.initialize(context) { mesh }

        MessageRouter.disableSchedulerForTesting = true
        MessageRouter.outboxStoreFactory = { testContext ->
            MessageOutboxStore(testContext, object : ConversationStorageCipher {
                override fun encrypt(plaintext: ByteArray, associatedData: ByteArray) = plaintext
                override fun decrypt(envelope: ByteArray, associatedData: ByteArray) = envelope
                override fun destroyKey() = Unit
            })
        }
        MessageRouter.resetForTesting()
        fakeTime = 1_000_000L
        expired.clear()

        router = MessageRouter.getInstance(context, mesh)
        router.clearAll()
        router.clock = { fakeTime }
        router.onMessageExpired = { expired.add(it) }
    }

    @After
    fun tearDown() {
        MessageRouter.resetForTesting()
        MessageRouter.disableSchedulerForTesting = false
        MessageRouter.outboxStoreFactory = ::MessageOutboxStore
        ContactDirectory.identityManagerProvider = { SecureIdentityStateManager(it) }
    }

    @Test
    fun `queued message flushes after peer returns and session establishes`() {
        peerOffline()
        val result = router.sendPrivate("hello", peerID, "peer", "msg-1")

        assertEquals(MessageRouter.RouteResult.QUEUED, result)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
        verify(mesh, never()).initiateNoiseHandshake(any())

        // Peer reappears without a session: handshake kicked immediately
        peerConnectedNoSession()
        router.onPeersUpdated(listOf(peerID))
        verify(mesh, times(1)).initiateNoiseHandshake(peerID)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())

        // Session established: queued message is sent
        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, times(1)).sendPrivateMessage("hello", peerID, "peer", "msg-1")
    }

    @Test
    fun `scheduler retries handshake with capped backoff`() {
        peerConnectedNoSession()
        val result = router.sendPrivate("hello", peerID, "peer", "msg-1")
        assertEquals(MessageRouter.RouteResult.QUEUED, result)
        verify(mesh, times(1)).initiateNoiseHandshake(peerID) // immediate kick at enqueue
        clearInvocations(mesh)

        router.tickOutbox() // backoff (5s) not yet elapsed
        verify(mesh, never()).initiateNoiseHandshake(any())

        fakeTime += 6_000
        router.tickOutbox() // attempt 2, next in 15s
        verify(mesh, times(1)).initiateNoiseHandshake(peerID)

        fakeTime += 7_000
        router.tickOutbox() // too early
        verify(mesh, times(1)).initiateNoiseHandshake(peerID)

        fakeTime += 9_000
        router.tickOutbox() // attempt 3, next in 30s
        verify(mesh, times(2)).initiateNoiseHandshake(peerID)

        fakeTime += 31_000
        router.tickOutbox() // attempt 4, next in 60s
        verify(mesh, times(3)).initiateNoiseHandshake(peerID)

        fakeTime += 61_000
        router.tickOutbox() // attempt 5, capped at 60s
        verify(mesh, times(4)).initiateNoiseHandshake(peerID)
    }

    @Test
    fun `expired entries are dropped and reported`() {
        peerOffline()
        router.sendPrivate("old message", peerID, "peer", "msg-old")

        fakeTime += 86_400_001L
        router.tickOutbox()

        assertEquals(listOf("msg-old"), expired)

        // Nothing left to flush even when the peer becomes reachable
        peerReady()
        router.tickOutbox()
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `outbox cap evicts oldest and preserves order`() {
        peerOffline()
        repeat(101) { i ->
            router.sendPrivate("content-$i", peerID, "peer", "msg-$i")
        }

        assertEquals(listOf("msg-0"), expired)

        peerReady()
        router.onSessionEstablished(peerID)
        verify(mesh, times(100)).sendPrivateMessage(any(), eq(peerID), any(), any())
        verify(mesh, times(1)).sendPrivateMessage("content-1", peerID, "peer", "msg-1")
        verify(mesh, times(1)).sendPrivateMessage("content-100", peerID, "peer", "msg-100")
        verify(mesh, never()).sendPrivateMessage(eq("content-0"), any(), any(), anyOrNull())
    }

    @Test
    fun `peer reappearance without pending messages does not kick handshake`() {
        peerConnectedNoSession()
        router.onPeersUpdated(listOf(peerID))
        verify(mesh, never()).initiateNoiseHandshake(any())
    }

    @Test
    fun `established session flushes directly without handshake retry state`() {
        peerReady()
        val result = router.sendPrivate("direct", peerID, "peer", "msg-direct")
        assertEquals(MessageRouter.RouteResult.MESH, result)
        verify(mesh, times(1)).sendPrivateMessage("direct", peerID, "peer", "msg-direct")
        verify(mesh, never()).initiateNoiseHandshake(any())
    }

    @Test
    fun `direct send remains retained until delivery acknowledgement`() {
        peerReady()
        router.sendPrivate("direct", peerID, "peer", "msg-direct")
        clearInvocations(mesh)

        fakeTime += 31_000
        router.tickOutbox()
        verify(mesh, times(1)).sendPrivateMessage("direct", peerID, "peer", "msg-direct")

        router.onMessageAcknowledged("msg-direct", peerID)
        clearInvocations(mesh)
        router.tickOutbox()
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `queued message survives router recreation`() {
        peerOffline()
        router.sendPrivate("durable", peerID, "peer", "msg-durable")

        MessageRouter.resetForTesting()
        router = MessageRouter.getInstance(RuntimeEnvironment.getApplication(), mesh)
        router.clock = { fakeTime }
        peerReady()
        router.onSessionEstablished(peerID)

        verify(mesh).sendPrivateMessage("durable", peerID, "peer", "msg-durable")
    }

    @Test
    fun `offline cached contact deposits once with a verified peer courier`() {
        val recipientNoiseKey = ByteArray(32) { 0x2A }
        val recipientPeerID = ContactIdentityResolver.peerIdForNoiseKey(recipientNoiseKey)
        val courierPeerID = "9999aaaabbbbcccc"
        val courierNoiseKey = ByteArray(32) { 0x3B }
        identityManager.cachePeerNoiseKey(
            recipientPeerID,
            ContactIdentityResolver.noiseKeyHex(recipientNoiseKey)
        )
        whenever(mesh.getPeerInfo(recipientPeerID)).thenReturn(
            PeerInfo(
                id = recipientPeerID,
                nickname = "offline contact",
                isConnected = false,
                isDirectConnection = false,
                noisePublicKey = recipientNoiseKey,
                signingPublicKey = ByteArray(32) { 0x0A },
                isVerifiedNickname = false,
                lastSeen = fakeTime
            )
        )
        whenever(mesh.getPeerInfos()).thenReturn(
            listOf(
                PeerInfo(
                    id = courierPeerID,
                    nickname = "verified courier",
                    isConnected = true,
                    isDirectConnection = true,
                    noisePublicKey = courierNoiseKey,
                    signingPublicKey = ByteArray(32) { 0x0C },
                    isVerifiedNickname = false,
                    lastSeen = fakeTime,
                    hasVerifiedAnnouncement = true
                )
            )
        )
        whenever(mesh.sendCourierMessage(any(), any(), any(), any())).thenReturn(listOf(courierPeerID))

        val result = router.sendPrivate("courier payload", recipientPeerID, "offline contact", "msg-courier")

        assertEquals(MessageRouter.RouteResult.QUEUED, result)
        verify(mesh).sendCourierMessage(
            eq("courier payload"),
            eq("msg-courier"),
            argThat { contentEquals(recipientNoiseKey) },
            eq(listOf(courierPeerID))
        )

        router.tickOutbox()

        verify(mesh, times(1)).sendCourierMessage(
            eq("courier payload"),
            eq("msg-courier"),
            argThat { contentEquals(recipientNoiseKey) },
            eq(listOf(courierPeerID))
        )
    }

    @Test
    fun `courier acknowledgement clears an offline contact outbox entry before direct reconnect`() {
        val recipientNoiseKey = ByteArray(32) { 0x4A }
        val recipientPeerID = ContactIdentityResolver.peerIdForNoiseKey(recipientNoiseKey)
        identityManager.cachePeerNoiseKey(
            recipientPeerID,
            ContactIdentityResolver.noiseKeyHex(recipientNoiseKey)
        )
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(recipientPeerID to "offline contact"))
        whenever(mesh.getPeerInfo(recipientPeerID)).thenReturn(
            PeerInfo(
                id = recipientPeerID,
                nickname = "offline contact",
                isConnected = false,
                isDirectConnection = false,
                noisePublicKey = recipientNoiseKey,
                signingPublicKey = ByteArray(32) { 0x0A },
                isVerifiedNickname = false,
                lastSeen = fakeTime
            )
        )

        assertEquals(
            MessageRouter.RouteResult.QUEUED,
            router.sendPrivate("courier payload", recipientPeerID, "offline contact", "msg-courier-ack")
        )
        router.onMessageAcknowledged("msg-courier-ack", recipientPeerID)

        whenever(mesh.getPeerInfo(recipientPeerID)).thenReturn(
            PeerInfo(
                id = recipientPeerID,
                nickname = "offline contact",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = recipientNoiseKey,
                signingPublicKey = ByteArray(32) { 0x0A },
                isVerifiedNickname = false,
                lastSeen = fakeTime
            )
        )
        whenever(mesh.hasEstablishedSession(recipientPeerID)).thenReturn(true)

        router.tickOutbox()

        verify(mesh, never()).sendPrivateMessage(
            eq("courier payload"),
            eq(recipientPeerID),
            any(),
            eq("msg-courier-ack")
        )
    }

    @Test
    fun `outbox stops transport retries after eight attempts`() {
        peerReady()
        router.sendPrivate("bounded", peerID, "peer", "msg-bounded")
        repeat(10) {
            fakeTime += 10 * 60_000L + 1
            router.tickOutbox()
        }

        verify(mesh, times(8)).sendPrivateMessage("bounded", peerID, "peer", "msg-bounded")
    }

    @Test
    fun `scheduler stops with the mesh service and restarts on rebind`() {
        MessageRouter.disableSchedulerForTesting = false
        MessageRouter.resetForTesting()
        val context = RuntimeEnvironment.getApplication()
        val running = MessageRouter.getInstance(context, mesh)
        assertTrue(running.isSchedulerRunning)

        running.stopOutboxScheduler()
        assertFalse(running.isSchedulerRunning)

        val rebound = MessageRouter.getInstance(context, mesh)
        assertTrue(rebound.isSchedulerRunning)
    }

    private fun peerOffline() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = false))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(false)
    }

    private fun peerConnectedNoSession() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = true))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(false)
    }

    private fun peerReady() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(peerInfo(isConnected = true))
        whenever(mesh.hasEstablishedSession(peerID)).thenReturn(true)
    }

    private fun peerInfo(isConnected: Boolean) = PeerInfo(
        id = peerID,
        nickname = "peer",
        isConnected = isConnected,
        isDirectConnection = true,
        noisePublicKey = noiseKey,
        signingPublicKey = ByteArray(32) { 0x0A },
        isVerifiedNickname = false,
        lastSeen = System.currentTimeMillis()
    )
}
