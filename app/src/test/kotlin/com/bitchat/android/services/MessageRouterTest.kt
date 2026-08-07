package com.bitchat.android.services

import android.content.Context
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.nostr.NostrSendAdmission
import com.bitchat.android.nostr.NostrTransport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
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
    private lateinit var nostr: NostrTransport
    private lateinit var router: MessageRouter
    private lateinit var identityManager: SecureIdentityStateManager
    private var fakeTime = 1_000_000L
    private val expired = mutableListOf<String>()
    private val admitted = mutableListOf<String>()
    private val failed = mutableListOf<String>()
    private val pendingNostrSends = mutableListOf<PendingNostrSend>()
    private var nostrAvailable = false

    private data class PendingNostrSend(
        val content: String,
        val peerID: String,
        val messageID: String,
        val completion: (NostrSendAdmission) -> Unit
    )

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
        nostr = mock()
        whenever(mesh.myPeerID).thenReturn(myPeerID)
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "peer"))

        ContactDirectory.initialize(context) { mesh }

        MessageRouter.disableSchedulerForTesting = true
        MessageRouter.resetForTesting()
        fakeTime = 1_000_000L
        expired.clear()
        admitted.clear()
        failed.clear()
        pendingNostrSends.clear()
        nostrAvailable = false

        router = MessageRouter(
            context = context,
            mesh = mesh,
            nostr = nostr,
            privateNostrSender = NostrPrivateMessageSender {
                    content,
                    target,
                    _,
                    messageID,
                    completion ->
                pendingNostrSends += PendingNostrSend(
                    content = content,
                    peerID = target,
                    messageID = messageID,
                    completion = completion
                )
            },
            canSendViaNostrOverride = { nostrAvailable }
        )
        router.clock = { fakeTime }
        router.onMessageExpired = { expired.add(it) }
        router.onMessageAdmitted = { admitted.add(it) }
        router.onMessageFailed = { messageID, _ -> failed.add(messageID) }
    }

    @After
    fun tearDown() {
        MessageRouter.resetForTesting()
        MessageRouter.disableSchedulerForTesting = false
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
    fun `retryable Nostr refusal remains queued until exactly one durable admission`() {
        peerOffline()
        nostrAvailable = true

        assertEquals(
            MessageRouter.RouteResult.NOSTR_PENDING,
            router.sendPrivate("hello", peerID, "peer", "msg-ndr")
        )
        assertEquals(listOf("msg-ndr"), pendingNostrSends.map { it.messageID })
        assertTrue(admitted.isEmpty())

        pendingNostrSends.single().completion(NostrSendAdmission.RETRYABLE)
        router.tickOutbox()
        assertEquals(listOf("msg-ndr", "msg-ndr"), pendingNostrSends.map { it.messageID })
        assertTrue(admitted.isEmpty())

        val admittedAttempt = pendingNostrSends.last()
        admittedAttempt.completion(NostrSendAdmission.ADMITTED)
        admittedAttempt.completion(NostrSendAdmission.ADMITTED)
        router.tickOutbox()

        assertEquals(listOf("msg-ndr"), admitted)
        assertEquals(2, pendingNostrSends.size)
        assertTrue(failed.isEmpty())
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `one Nostr message per conversation is in flight and queued order is preserved`() {
        peerOffline()
        nostrAvailable = true

        router.sendPrivate("first", peerID, "peer", "msg-1")
        router.sendPrivate("second", peerID, "peer", "msg-2")
        router.tickOutbox()

        assertEquals(listOf("msg-1"), pendingNostrSends.map { it.messageID })

        pendingNostrSends[0].completion(NostrSendAdmission.ADMITTED)
        assertEquals(listOf("msg-1", "msg-2"), pendingNostrSends.map { it.messageID })

        pendingNostrSends[1].completion(NostrSendAdmission.ADMITTED)
        assertEquals(listOf("msg-1", "msg-2"), admitted)
    }

    @Test
    fun `mesh and duplicate session callbacks cannot race an in-flight Nostr copy`() {
        peerOffline()
        nostrAvailable = true
        router.sendPrivate("hello", peerID, "peer", "msg-race")

        peerReady()
        router.onSessionEstablished(peerID)
        router.onSessionEstablished(peerID)

        assertEquals(1, pendingNostrSends.size)
        verify(mesh, never()).sendPrivateMessage(any(), any(), any(), anyOrNull())

        pendingNostrSends.single().completion(NostrSendAdmission.RETRYABLE)
        router.onSessionEstablished(peerID)
        router.onSessionEstablished(peerID)

        verify(mesh, times(1)).sendPrivateMessage("hello", peerID, "peer", "msg-race")
        assertEquals(1, pendingNostrSends.size)
    }

    @Test
    fun `terminal Nostr failure removes the entry and reports failure once`() {
        peerOffline()
        nostrAvailable = true
        router.sendPrivate("invalid", peerID, "peer", "msg-failed")

        val attempt = pendingNostrSends.single()
        attempt.completion(NostrSendAdmission.TERMINAL_FAILED)
        attempt.completion(NostrSendAdmission.TERMINAL_FAILED)
        router.tickOutbox()

        assertEquals(listOf("msg-failed"), failed)
        assertTrue(admitted.isEmpty())
        assertEquals(1, pendingNostrSends.size)
    }

    @Test
    fun `duplicate session established callbacks flush a queued mesh message once`() {
        peerOffline()
        router.sendPrivate("hello", peerID, "peer", "msg-once")

        peerReady()
        router.onSessionEstablished(peerID)
        router.onSessionEstablished(peerID)

        verify(mesh, times(1)).sendPrivateMessage("hello", peerID, "peer", "msg-once")
    }

    @Test
    fun `late result from an earlier Nostr attempt cannot complete its replacement`() {
        peerOffline()
        nostrAvailable = true
        router.sendPrivate("hello", peerID, "peer", "msg-stale")

        val first = pendingNostrSends.single()
        first.completion(NostrSendAdmission.RETRYABLE)
        router.tickOutbox()
        val second = pendingNostrSends.last()

        first.completion(NostrSendAdmission.ADMITTED)
        assertTrue(admitted.isEmpty())
        assertEquals(1, router.queuedMessageCount)
        assertEquals(1, router.inFlightNostrAttemptCount)

        second.completion(NostrSendAdmission.ADMITTED)
        assertEquals(listOf("msg-stale"), admitted)
        assertEquals(0, router.queuedMessageCount)
        assertEquals(0, router.inFlightNostrAttemptCount)
    }

    @Test
    fun `alias convergence migrates in-flight ownership without duplicate Nostr send`() {
        whenever(mesh.getPeerInfo(peerID)).thenReturn(null)
        nostrAvailable = true
        router.sendPrivate("hello", peerID, "peer", "msg-alias")
        assertEquals(1, pendingNostrSends.size)

        identityManager.cachePeerNoiseKey(
            peerID,
            ContactIdentityResolver.noiseKeyHex(noiseKey)
        )
        router.flushOutboxFor(peerID)
        router.tickOutbox()

        assertEquals(1, pendingNostrSends.size)
        pendingNostrSends.single().completion(NostrSendAdmission.ADMITTED)
        assertEquals(listOf("msg-alias"), admitted)
        assertEquals(0, router.queuedMessageCount)
        assertEquals(0, router.inFlightNostrAttemptCount)
    }

    @Test
    fun `synchronous Nostr admission is safe and leaves no in-flight token`() {
        peerOffline()
        val synchronous = MessageRouter(
            context = RuntimeEnvironment.getApplication(),
            mesh = mesh,
            nostr = nostr,
            privateNostrSender = NostrPrivateMessageSender {
                    _,
                    _,
                    _,
                    _,
                    completion ->
                completion(NostrSendAdmission.ADMITTED)
            },
            canSendViaNostrOverride = { true }
        )
        val synchronousAdmissions = mutableListOf<String>()
        synchronous.onMessageAdmitted = synchronousAdmissions::add

        synchronous.sendPrivate("first", peerID, "peer", "sync-1")
        synchronous.sendPrivate("second", peerID, "peer", "sync-2")

        assertEquals(listOf("sync-1", "sync-2"), synchronousAdmissions)
        assertEquals(0, synchronous.queuedMessageCount)
        assertEquals(0, synchronous.inFlightNostrAttemptCount)
    }

    @Test
    fun `TTL and cap never evict an in-flight Nostr message`() {
        peerOffline()
        nostrAvailable = true
        router.sendPrivate("first", peerID, "peer", "msg-0")
        repeat(100) { index ->
            router.sendPrivate("queued-$index", peerID, "peer", "msg-${index + 1}")
        }

        assertEquals(listOf("msg-1"), expired)
        assertEquals("msg-0", pendingNostrSends.single().messageID)
        assertEquals(100, router.queuedMessageCount)

        fakeTime += 86_400_001L
        router.tickOutbox()

        assertFalse("msg-0" in expired)
        assertEquals(1, router.queuedMessageCount)
        assertEquals(1, router.inFlightNostrAttemptCount)

        pendingNostrSends.single().completion(NostrSendAdmission.ADMITTED)
        assertEquals(listOf("msg-0"), admitted)
        assertEquals(0, router.queuedMessageCount)
    }

    @Test
    fun `account reset discards plaintext and ignores every late callback`() {
        peerOffline()
        nostrAvailable = true
        router.sendPrivate("old identity", peerID, "peer", "msg-old-account")
        val oldAttempt = pendingNostrSends.single()

        router.discardForAccountReset()

        assertEquals(0, router.queuedMessageCount)
        assertEquals(0, router.inFlightNostrAttemptCount)
        assertEquals(
            MessageRouter.RouteResult.DROPPED,
            router.sendPrivate(
                "must not survive reset",
                peerID,
                "peer",
                "msg-reset-window"
            )
        )
        assertEquals(0, router.queuedMessageCount)
        oldAttempt.completion(NostrSendAdmission.ADMITTED)
        oldAttempt.completion(NostrSendAdmission.RETRYABLE)
        oldAttempt.completion(NostrSendAdmission.TERMINAL_FAILED)

        assertTrue(admitted.isEmpty())
        assertEquals(listOf("msg-reset-window"), failed)
        router.tickOutbox()
        assertEquals(1, pendingNostrSends.size)
    }

    @Test
    fun `stale router reset cannot reopen a newer reset`() {
        peerOffline()
        val firstReset = router.discardForAccountReset()
        val secondReset = router.discardForAccountReset()

        assertFalse(router.completeAccountReset(firstReset))
        assertEquals(
            MessageRouter.RouteResult.DROPPED,
            router.sendPrivate(
                "blocked",
                peerID,
                "peer",
                "msg-stale-reset"
            )
        )

        assertTrue(router.completeAccountReset(secondReset))
        assertEquals(
            MessageRouter.RouteResult.QUEUED,
            router.sendPrivate(
                "fresh",
                peerID,
                "peer",
                "msg-current-reset"
            )
        )
    }

    @Test
    fun `normal scheduler stop preserves queued plaintext and in-flight ownership`() {
        peerOffline()
        nostrAvailable = true
        router.sendPrivate("keep across service stop", peerID, "peer", "msg-pause")
        val attempt = pendingNostrSends.single()

        router.stopOutboxScheduler()

        assertEquals(1, router.queuedMessageCount)
        assertEquals(1, router.inFlightNostrAttemptCount)
        attempt.completion(NostrSendAdmission.ADMITTED)
        assertEquals(listOf("msg-pause"), admitted)
        assertEquals(0, router.queuedMessageCount)
        assertEquals(0, router.inFlightNostrAttemptCount)
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
