package com.bitchat.android.services

import android.content.Context
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.nostr.NostrTransport
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID

/** Routing contracts exercise the persisted worker, rather than the removed in-memory queue. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class MessageRouterTest {
    private lateinit var context: Context
    private lateinit var repository: ConversationRepository
    private lateinit var favorites: FavoritesPersistenceService
    private lateinit var databaseName: String
    private val cipher = InMemoryConversationStorageCipher()
    private val conversation = "contact_" + "11".repeat(32)
    private val peer = "1122334455667788"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        databaseName = "delivery-test-${UUID.randomUUID()}.db"
        repository = ConversationRepository(context, Dispatchers.Unconfined, databaseName, cipher)
        favorites = FavoritesPersistenceService(context, SecureIdentityStateManager(context.getSharedPreferences("test-${UUID.randomUUID()}", 0), true))
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(repository)
    }
    @After fun cleanup() {
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repository.closeForTest()
        context.deleteDatabase(databaseName)
    }

    private suspend fun queue() {
        assertTrue(AppStateStore.addPrivateMessageDurably(conversation, BitchatMessage(
            id = "message", sender = "self", content = "fixture", timestamp = Date(), isPrivate = true,
            senderPeerID = "9988776655443322", deliveryStatus = DeliveryStatus.Sending
        ), queueForDelivery = true))
    }

    @Test fun `unacknowledged mesh message falls back with the original ID`() = runTest {
        queue()
        val mesh = mock<MeshService>()
        whenever(mesh.myPeerID).thenReturn("9988776655443322")
        val peerInfo = mock<PeerInfo>()
        whenever(peerInfo.isConnected).thenReturn(true)
        whenever(mesh.getPeerInfo(peer)).thenReturn(peerInfo)
        whenever(mesh.hasEstablishedSession(peer)).thenReturn(true)
        val transport = mock<NostrTransport>()
        whenever(transport.prepare(any())).thenAnswer { it.arguments[0] }
        whenever(transport.publish(any())).thenReturn(true)
        val base = System.currentTimeMillis()
        val worker = PrivateDeliveryCoordinator(context, backgroundScope, { repository }, { transport }, { favorites },
            { ContactDirectory.ContactResolution(conversation, peer, null, "ab".repeat(32), "peer", true) }, { base + testScheduler.currentTime })
        worker.bindMesh(mesh)
        runCurrent()
        verify(mesh).sendPrivateMessage("fixture", peer, "", "message")
        verify(transport, never()).publish(any())
        advanceTimeBy(32_000)
        runCurrent()
        verify(transport).publish(check { assertEquals("message", it.messageID) })
        assertEquals(DeliveryStatus.Sent, repository.storedMessage(conversation, "message")!!.deliveryStatus)
        assertEquals(1, repository.deliveryJobs().size) // Relay acceptance is not recipient delivery.
        worker.stop()
    }

    @Test fun `recipient receipt removes durable work and never downgrades read`() = runTest {
        queue()
        assertTrue(AppStateStore.acknowledgePrivateReceipt(conversation, "message", true))
        assertTrue(repository.deliveryJobs().isEmpty())
        assertTrue(AppStateStore.acknowledgePrivateReceipt(conversation, "message", false))
        assertTrue(repository.storedMessage(conversation, "message")!!.deliveryStatus is DeliveryStatus.Read)
        assertFalse(AppStateStore.acknowledgePrivateReceipt("unrelated", "message", true))
    }

    @Test fun `outgoing echo and work survive reopening the database`() = runTest {
        queue()
        repository.closeForTest()
        repository = ConversationRepository(context, Dispatchers.Unconfined, databaseName, cipher)
        AppStateStore.setConversationRepositoryForTest(repository)
        val jobs = repository.deliveryJobs()
        assertEquals("message", jobs.single().messageID)
        assertNotNull(repository.storedMessage(conversation, "message"))
        // Deleting a conversation removes the unsent message and its work in the same database.
        repository.deleteConversationAndWait(conversation, setOf(conversation))
        assertTrue(repository.deliveryJobs().isEmpty())
    }
    @Test fun `full outbox rolls back outgoing echo instead of losing delivery intent`() = runTest {
        repeat(PrivateDeliveryJob.MAX_PER_CONTACT) { index ->
            repository.saveDelivery(PrivateDeliveryJob("fixture:$index", conversation, "$index", PrivateDeliveryJob.Kind.READ))
        }
        assertFalse(AppStateStore.addPrivateMessageDurably(conversation, BitchatMessage(
            id = "overflow", sender = "self", content = "fixture", timestamp = Date(), isPrivate = true,
            senderPeerID = "9988776655443322", deliveryStatus = DeliveryStatus.Sending
        ), queueForDelivery = true))
        assertNull(repository.storedMessage(conversation, "overflow"))
        assertEquals(PrivateDeliveryJob.MAX_PER_CONTACT, repository.deliveryJobs().size)
    }

    @Test fun `completed delivery cannot be resurrected by a stale publish retry`() = runTest {
        queue()
        val stale = repository.deliveryJobs().single()
        assertTrue(AppStateStore.acknowledgePrivateReceipt(conversation, "message", false))
        assertFalse(repository.updateDelivery(stale.copy(attempts = 1)))
        assertTrue(repository.deliveryJobs().isEmpty())
    }

}
