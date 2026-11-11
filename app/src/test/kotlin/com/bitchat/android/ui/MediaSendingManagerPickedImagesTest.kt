package com.bitchat.android.ui

import android.net.Uri
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PreparedPrivateMediaTransfer
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.mesh.PrivateMediaWireMode
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.util.AppConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
class MediaSendingManagerPickedImagesTest {
    private val peerID = "8877665544332211"
    private lateinit var state: ChatState
    private lateinit var mesh: MeshService
    private lateinit var manager: MediaSendingManager
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationDatabaseName: String
    private val preparedFiles = mutableMapOf<Uri, File>()
    private val unreadable = mutableSetOf<Uri>()
    private var onPrepare: (Uri) -> Unit = {}

    @Before
    fun setup() {
        state = ChatState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
        state.setNickname("me")
        conversationDatabaseName = "picked-images-${UUID.randomUUID()}.db"
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
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(peerID to "peer"))
        manager = MediaSendingManager(
            state,
            MessageManager(state),
            mock(),
            CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mediaWorkDispatcher = Dispatchers.Unconfined,
            prepareImage = { uri ->
                onPrepare(uri)
                if (uri in unreadable) null else preparedFiles[uri]?.absolutePath
            },
            getMeshService = { mesh }
        )
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        conversationRepository.closeForTest()
        org.robolectric.RuntimeEnvironment.getApplication()
            .deleteDatabase(conversationDatabaseName)
        preparedFiles.values.forEach { it.delete() }
    }

    /** Synthetic picked photos, each backed by a distinct small prepared file. */
    private fun pickPhotos(count: Int): List<Uri> = (1..count).map { index ->
        val uri = Uri.parse("content://test.media/picked/$index")
        preparedFiles[uri] = kotlin.io.path.createTempFile("picked-$index-", ".jpg").toFile().apply {
            writeBytes(ByteArray(64) { (it + index).toByte() })
        }
        uri
    }

    private fun readyAnswer(commits: AtomicInteger): (org.mockito.invocation.InvocationOnMock) -> PrivateMediaPreparation =
        { invocation ->
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

    private fun privateImageEchoes() =
        state.privateChats.value[peerID].orEmpty().filter { it.type == BitchatMessageType.Image }

    @Test
    fun `picked images are broadcast in pick order and an unreadable one does not stop the rest`() {
        val uris = pickPhotos(3)
        unreadable += uris[1]

        manager.sendPickedImages(null, null, uris)

        val packets = argumentCaptor<BitchatFilePacket>()
        verify(mesh, times(2)).sendFileBroadcast(packets.capture())
        assertEquals(
            listOf(preparedFiles.getValue(uris[0]).name, preparedFiles.getValue(uris[2]).name),
            packets.allValues.map { it.fileName }
        )
        val messages = state.getMessagesValue()
        assertEquals(
            listOf(preparedFiles.getValue(uris[0]).absolutePath, preparedFiles.getValue(uris[2]).absolutePath),
            messages.filter { it.type == BitchatMessageType.Image }.map { it.content }
        )
        assertTrue(messages.any { it.sender == "system" && it.content.contains("photo 2 of 3") })
    }

    @Test
    fun `a pick beyond the limit sends only the first photos and says so`() {
        val uris = pickPhotos(AppConstants.Media.MAX_IMAGES_PER_PICK + 2)

        manager.sendPickedImages(null, null, uris)

        verify(mesh, times(AppConstants.Media.MAX_IMAGES_PER_PICK)).sendFileBroadcast(any())
        val notice = state.getMessagesValue().single { it.sender == "system" }
        assertTrue(
            notice.content.contains(
                "only the first ${AppConstants.Media.MAX_IMAGES_PER_PICK} of ${uris.size} photos"
            )
        )
    }

    @Test
    fun `private picked images wait for the previous secure send instead of failing`() {
        val commits = AtomicInteger(0)
        val ready = readyAnswer(commits)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.NeedsHandshake)
            .thenAnswer { ready(it) }
        val uris = pickPhotos(2)

        manager.sendPickedImages(peerID, null, uris)

        // The second photo is queued behind the handshake, not refused.
        verify(mesh, times(1)).prepareFilePrivate(eq(peerID), any(), any(), eq(false))
        assertTrue(state.privateChats.value[peerID].isNullOrEmpty())

        manager.retryPendingPrivateMedia(peerID)

        assertEquals(2, commits.get())
        assertEquals(
            uris.map { preparedFiles.getValue(it).absolutePath },
            privateImageEchoes().map { it.content }
        )
        assertTrue(state.privateChats.value[peerID].orEmpty().none { it.sender == "system" })
    }

    @Test
    fun `private picked images wait while legacy consent for an earlier photo is open`() {
        val commits = AtomicInteger(0)
        val ready = readyAnswer(commits)
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.RequiresLegacyConsent("relay-visible warning"))
            .thenAnswer { ready(it) }
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(true)))
            .thenAnswer { ready(it) }
        val uris = pickPhotos(2)

        manager.sendPickedImages(peerID, null, uris)

        val request = manager.legacyPrivateMediaConsent.value
        assertNotNull(request)
        verify(mesh, times(1)).prepareFilePrivate(eq(peerID), any(), any(), eq(false))

        manager.approveLegacyPrivateMedia(request!!.requestId)

        assertEquals(2, commits.get())
        assertEquals(
            uris.map { preparedFiles.getValue(it).absolutePath },
            privateImageEchoes().map { it.content }
        )
    }

    @Test
    fun `panic clear drops picked images still waiting to be sent`() {
        whenever(mesh.prepareFilePrivate(eq(peerID), any(), any(), eq(false)))
            .thenReturn(PrivateMediaPreparation.NeedsHandshake)
        val uris = pickPhotos(3)

        manager.sendPickedImages(peerID, null, uris)
        manager.clearPendingPrivateMediaConsent()
        manager.retryPendingPrivateMedia(peerID)

        verify(mesh, times(1)).prepareFilePrivate(eq(peerID), any(), any(), eq(false))
        verify(mesh, never()).sendFileBroadcast(any())
        assertTrue(privateImageEchoes().isEmpty())
    }

    @Test
    fun `panic clear during image preparation removes the prepared file and sends nothing`() {
        val uris = pickPhotos(2)
        onPrepare = { manager.clearPendingPrivateMediaConsent() }

        manager.sendPickedImages(null, null, uris)

        assertTrue(!preparedFiles.getValue(uris[0]).exists())
        verify(mesh, never()).sendFileBroadcast(any())
        assertTrue(state.getMessagesValue().none { it.type == BitchatMessageType.Image })
    }
}
