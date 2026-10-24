package com.bitchat.android.ui

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.PreparedPrivateMediaTransfer
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.mesh.PrivateMediaWireMode
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ContactIdentityResolver
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class MediaSendingManagerMigrationTest {
    private val peerID = "8877665544332211"
    private lateinit var state: ChatState
    private lateinit var mesh: MeshService
    private lateinit var manager: MediaSendingManager
    private lateinit var file: File
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationDatabaseName: String

    @Before
    fun setup() {
        state = ChatState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        state.setNickname("me")
        conversationDatabaseName = "media-send-${UUID.randomUUID()}.db"
        conversationRepository = ConversationRepository(
            context = org.robolectric.RuntimeEnvironment.getApplication(),
            dispatcher = Dispatchers.Unconfined,
            databaseName = conversationDatabaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(conversationRepository)
        mesh = mock()
        whenever(mesh.myPeerID).thenReturn("0011223344556677")
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "old peer"))
        manager = MediaSendingManager(
            state,
            MessageManager(state),
            mock(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mediaWorkDispatcher = Dispatchers.Unconfined,
            getMeshService = { mesh }
        )
        file = kotlin.io.path.createTempFile("private-media", ".jpg").toFile().apply {
            writeBytes(ByteArray(128) { it.toByte() })
        }
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        conversationRepository.closeForTest()
        org.robolectric.RuntimeEnvironment.getApplication()
            .deleteDatabase(conversationDatabaseName)
        file.delete()
    }

    @Test
    fun `preflight rejection creates only a visible failure and no file echo or send`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.Rejected("too many fragments"))

        manager.sendImageNote(peerID, null, file.absolutePath)

        val messages = state.privateChats.value[peerID].orEmpty()
        assertEquals(1, messages.size)
        assertTrue(messages.single().content.contains("too many fragments"))
        assertTrue(messages.none { it.type == com.bitchat.android.model.BitchatMessageType.Image })
        assertEquals(null, manager.legacyPrivateMediaConsent.value)
        verify(mesh, never()).sendFilePrivate(any(), any())
    }

    @Test
    fun `private preparation runs on the configured media worker`() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "private-media-test-worker")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val preparationThread = AtomicReference<String>()
            val prepared = CountDownLatch(1)
            whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
                .thenAnswer {
                    preparationThread.set(Thread.currentThread().name)
                    prepared.countDown()
                    PrivateMediaPreparation.Rejected("test complete")
                }
            val asynchronousManager = MediaSendingManager(
                state,
                MessageManager(state),
                mock(),
                CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                mediaWorkDispatcher = dispatcher,
                getMeshService = { mesh }
            )

            asynchronousManager.sendImageNote(peerID, null, file.absolutePath)

            assertTrue(prepared.await(5, TimeUnit.SECONDS))
            assertTrue(preparationThread.get().contains("private-media-test-worker"))
        } finally {
            dispatcher.close()
            executor.shutdownNow()
        }
    }

    @Test
    fun `pinned downgrade rejection is visible without a file echo or send`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(
                PrivateMediaPreparation.Rejected(
                    "Encrypted private media was previously pinned, but this session proved no support; send blocked"
                )
            )

        manager.sendImageNote(peerID, null, file.absolutePath)

        val messages = state.privateChats.value[peerID].orEmpty()
        assertEquals(1, messages.size)
        assertTrue(messages.single().content.contains("previously pinned"))
        assertTrue(messages.none { it.type == com.bitchat.android.model.BitchatMessageType.Image })
        verify(mesh, never()).sendFilePrivate(any(), any())
    }

    @Test
    fun `missing Noise session retains first send and commits after proof resolution`() {
        val commits = AtomicInteger(0)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.NeedsHandshake)
            .thenAnswer { invocation ->
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = invocation.getArgument<String>(2),
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        commits.incrementAndGet()
                        true
                    }
                )
            }

        manager.sendImageNote(peerID, null, file.absolutePath)

        verify(mesh, times(1)).initiateNoiseHandshake(peerID)
        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
        assertEquals(null, manager.legacyPrivateMediaConsent.value)

        manager.retryPendingPrivateMedia(peerID)

        assertEquals(1, commits.get())
        assertEquals(1, state.privateChats.value[peerID]?.size)
        verify(mesh, times(2)).prepareFilePrivate(eq(peerID), any(), any(), eq(false))
        verify(mesh, never()).sendFilePrivate(any(), any())
    }

    @Test
    fun `contact conversation resolves to live mesh peer for voice image and file sends`() {
        val noisePublicKey = ByteArray(32) { (it + 1).toByte() }
        val conversationID =
            ContactIdentityResolver.contactConversationIdForNoiseKey(noisePublicKey)
        whenever(mesh.getPeerInfo(peerID)).thenReturn(
            PeerInfo(
                id = peerID,
                nickname = "old peer",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = noisePublicKey,
                signingPublicKey = null,
                isVerifiedNickname = true,
                lastSeen = System.currentTimeMillis()
            )
        )
        val commits = AtomicInteger(0)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenAnswer { invocation ->
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = invocation.getArgument(2),
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        commits.incrementAndGet()
                        true
                    }
                )
            }
        val genericFile = kotlin.io.path.createTempFile("private-media", ".txt").toFile().apply {
            writeText("private attachment")
        }
        try {
            manager.sendVoiceNote(conversationID, null, file.absolutePath)
            manager.sendImageNote(conversationID, null, file.absolutePath)
            manager.sendFileNote(conversationID, null, genericFile.absolutePath)

            assertEquals(3, commits.get())
            assertEquals(
                listOf(BitchatMessageType.Audio, BitchatMessageType.Image, BitchatMessageType.File),
                state.privateChats.value[conversationID].orEmpty().map { it.type }
            )
            verify(mesh, times(3))
                .prepareFilePrivate(eq(peerID), any(), any(), eq(false))
            verify(mesh, never())
                .prepareFilePrivate(eq(conversationID), any(), any(), any())
        } finally {
            genericFile.delete()
        }
    }

    @Test
    fun `mesh policy callback retries contact conversation using live peer ID`() {
        val noisePublicKey = ByteArray(32) { (it + 1).toByte() }
        val conversationID =
            ContactIdentityResolver.contactConversationIdForNoiseKey(noisePublicKey)
        whenever(mesh.getPeerInfo(peerID)).thenReturn(
            PeerInfo(
                id = peerID,
                nickname = "old peer",
                isConnected = true,
                isDirectConnection = true,
                noisePublicKey = noisePublicKey,
                signingPublicKey = null,
                isVerifiedNickname = true,
                lastSeen = System.currentTimeMillis()
            )
        )
        val commits = AtomicInteger(0)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.NeedsHandshake)
            .thenAnswer { invocation ->
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = invocation.getArgument(2),
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        commits.incrementAndGet()
                        true
                    }
                )
            }

        manager.sendVoiceNote(conversationID, null, file.absolutePath)
        manager.retryPendingPrivateMedia(peerID)

        assertEquals(1, commits.get())
        assertEquals(BitchatMessageType.Audio, state.privateChats.value[conversationID]?.single()?.type)
        verify(mesh).initiateNoiseHandshake(peerID)
        verify(mesh, never()).initiateNoiseHandshake(conversationID)
    }

    @Test
    fun `awaiting peer state retains send and watchdog resolution offers legacy consent`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.AwaitingPeerState)
            .thenReturn(PrivateMediaPreparation.RequiresLegacyConsent("relay-visible warning"))

        manager.sendImageNote(peerID, null, file.absolutePath)

        verify(mesh, never()).initiateNoiseHandshake(peerID)
        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
        assertEquals(null, manager.legacyPrivateMediaConsent.value)

        manager.retryPendingPrivateMedia(peerID)

        assertNotNull(manager.legacyPrivateMediaConsent.value)
        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
    }

    @Test
    fun `reentrant proof resolution during preparation cannot lose first send intent`() {
        val commits = AtomicInteger(0)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenAnswer {
                manager.retryPendingPrivateMedia(peerID)
                PrivateMediaPreparation.AwaitingPeerState
            }
            .thenAnswer { invocation ->
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = invocation.getArgument<String>(2),
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        commits.incrementAndGet()
                        true
                    }
                )
            }

        manager.sendImageNote(peerID, null, file.absolutePath)

        assertEquals(1, commits.get())
        assertEquals(1, state.privateChats.value[peerID]?.size)
        verify(mesh, times(2)).prepareFilePrivate(eq(peerID), any(), any(), eq(false))
    }

    @Test
    fun `legacy consent is one shot rechecks policy and echoes only after approval`() {
        val commits = AtomicInteger(0)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.RequiresLegacyConsent("relay-visible warning"))
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(true)))
            .thenAnswer { invocation ->
                val transferId = invocation.getArgument<String>(2)
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = transferId,
                        // Simulate the capability becoming authenticated while
                        // the consent dialog was open: recheck must upgrade.
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        commits.incrementAndGet()
                        true
                    }
                )
            }

        manager.sendImageNote(peerID, null, file.absolutePath)

        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
        val request = manager.legacyPrivateMediaConsent.value
        assertNotNull(request)

        manager.approveLegacyPrivateMedia(request!!.requestId)
        manager.approveLegacyPrivateMedia(request.requestId)

        assertEquals(1, commits.get())
        assertEquals(1, state.privateChats.value[peerID]?.size)
        assertEquals(null, manager.legacyPrivateMediaConsent.value)
        verify(mesh, times(1)).prepareFilePrivate(eq(peerID), any(), any(), eq(false))
        verify(mesh, times(1)).prepareFilePrivate(eq(peerID), any(), any(), eq(true))
    }

    @Test
    fun `prepared transfer ID mismatch aborts before local echo or commit`() {
        val commits = AtomicInteger(0)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = "wrong-transfer-id",
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        commits.incrementAndGet()
                        true
                    }
                )
            )

        manager.sendImageNote(peerID, null, file.absolutePath)

        assertEquals(0, commits.get())
        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
    }

    @Test
    fun `failed prepared commit keeps a retryable failed local file echo`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenAnswer { invocation ->
                PrivateMediaPreparation.Ready(
                    PreparedPrivateMediaTransfer(
                        transferId = invocation.getArgument(2),
                        wireMode = PrivateMediaWireMode.ENCRYPTED_NOISE_0X20
                    ) {
                        false
                    }
                )
            }

        manager.sendImageNote(peerID, null, file.absolutePath)

        val messages = state.privateChats.value[peerID].orEmpty()
        assertEquals(2, messages.size)
        val fileEcho = messages.single { it.type == BitchatMessageType.Image }
        assertTrue(fileEcho.deliveryStatus is DeliveryStatus.Failed)
        assertTrue(
            messages.single { it.sender == "system" }
                .content.contains("could not be committed")
        )
    }

    @Test
    fun `oversized file failure is posted to the private conversation and nothing is sent`() {
        val bigFile = kotlin.io.path.createTempFile("oversized-private", ".jpg").toFile()
        try {
            bigFile.writeBytes(ByteArray(11 * 1024 * 1024) { 0x42 })

            manager.sendImageNote(peerID, null, bigFile.absolutePath)

            val messages = state.privateChats.value[peerID].orEmpty()
            assertEquals(1, messages.size)
            assertTrue(messages.single().content.contains("too large"))
            assertTrue(messages.none { it.type == com.bitchat.android.model.BitchatMessageType.Image })
            assertTrue(state.getMessagesValue().none { it.content.contains("too large") })
            verify(mesh, never()).prepareFilePrivate(any(), any(), any(), any())
        } finally {
            bigFile.delete()
        }
    }

    @Test
    fun `oversized file failure is posted to the channel and nothing is sent`() {
        val bigFile = kotlin.io.path.createTempFile("oversized-channel", ".jpg").toFile()
        try {
            bigFile.writeBytes(ByteArray(11 * 1024 * 1024) { 0x42 })

            manager.sendImageNote(null, "#test", bigFile.absolutePath)

            val channelMessages = state.getChannelMessagesValue()["#test"].orEmpty()
            assertEquals(1, channelMessages.size)
            assertTrue(channelMessages.single().content.contains("too large"))
            assertTrue(state.getMessagesValue().none { it.content.contains("too large") })
            verify(mesh, never()).prepareFilePrivate(any(), any(), any(), any())
        } finally {
            bigFile.delete()
        }
    }

    @Test
    fun `cancelled consent cannot later send or echo`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.RequiresLegacyConsent("relay-visible warning"))

        manager.sendImageNote(peerID, null, file.absolutePath)
        val request = manager.legacyPrivateMediaConsent.value!!
        manager.cancelLegacyPrivateMedia(request.requestId)
        manager.approveLegacyPrivateMedia(request.requestId)

        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())
        verify(mesh, never()).prepareFilePrivate(eq(peerID), any(), any(), eq(true))
    }
}
