package com.bitchat.android.ui

import android.app.Application
import android.os.Build
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.services.SeenMessageStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockedConstruction
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Text typed into a private-chat sheet is addressed to that sheet's peer. It must reach that
 * peer or be refused, whatever the selected private chat happens to be at that moment.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class PrivateChatSheetSendRobolectricTest {
    private val dispatcher = StandardTestDispatcher()
    private val application: Application = RuntimeEnvironment.getApplication()
    private val blockedPeerID = "1111111111111111"
    private val otherPeerID = "2222222222222222"
    private val previousPeerID = "3333333333333333"
    private val blockedFingerprint = "synthetic-blocked-fingerprint"
    private lateinit var mesh: MeshService
    private lateinit var viewModel: ChatViewModel
    private lateinit var conversationRepository: ConversationRepository
    private val conversationDatabaseName = "private-sheet-send-${UUID.randomUUID()}.db"
    // ChatViewModel's preferences need an identity store, which needs the AndroidKeyStore.
    private lateinit var identityConstruction: MockedConstruction<SecureIdentityStateManager>
    // Its notification channels need string resources, which JVM tests here do not package.
    private lateinit var notificationConstruction: MockedConstruction<NotificationManager>

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        AppStateStore.clear()
        mesh = mock()
        whenever(mesh.myPeerID).thenReturn("aaaaaaaaaaaaaaaa")
        whenever(mesh.getPeerNicknames()).thenReturn(emptyMap())
        // ChatViewModel reads the persisted blocklist once, during construction.
        DataManager(application).addBlockedUser(blockedFingerprint)
        identityConstruction = Mockito.mockConstruction(SecureIdentityStateManager::class.java)
        notificationConstruction = Mockito.mockConstruction(NotificationManager::class.java)
        // Built lazily on an IO thread otherwise, where the thread-scoped stub above does not apply.
        SeenMessageStore.getInstance(application)
        viewModel = ChatViewModel(application, mock<BluetoothMeshService>(), mesh)
        // Set after construction, which installs the keystore-backed repository.
        conversationRepository = ConversationRepository(
            context = application,
            dispatcher = Dispatchers.Unconfined,
            databaseName = conversationDatabaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        AppStateStore.setConversationRepositoryForTest(conversationRepository)
        PeerFingerprintManager.getInstance().updatePeerIDMapping(
            oldPeerID = null,
            newPeerID = blockedPeerID,
            fingerprint = blockedFingerprint
        )
    }

    @After
    fun tearDown() {
        AppStateStore.setConversationRepositoryForTest(null)
        conversationRepository.closeForTest()
        application.deleteDatabase(conversationDatabaseName)
        notificationConstruction.close()
        identityConstruction.close()
        PeerFingerprintManager.getInstance().clearAllFingerprints()
        DataManager(application).removeBlockedUser(blockedFingerprint)
        AppStateStore.clear()
        Dispatchers.resetMain()
    }

    @Test
    fun `text sent from a blocked peer's sheet never reaches the public mesh`() = runTest(dispatcher) {
        viewModel.showPrivateChatSheet(blockedPeerID)
        var accepted: Boolean? = null

        viewModel.sendPrivateChatSheetMessage(blockedPeerID, "for the sheet only") { accepted = it }
        runCurrent()

        verify(mesh, never()).sendMessage(any(), any(), anyOrNull())
        assertEquals(false, accepted)
    }

    @Test
    fun `slash action from a sheet is refused while another conversation is selected`() = runTest(dispatcher) {
        viewModel.showPrivateChatSheet(blockedPeerID)
        var accepted: Boolean? = null

        viewModel.sendPrivateChatSheetMessage(blockedPeerID, "/hug someone") { accepted = it }
        runCurrent()

        verify(mesh, never()).sendMessage(any(), any(), anyOrNull())
        assertEquals(false, accepted)
    }

    @Test
    fun `sheet text goes to its own peer before the sheet's chat has started`() = runTest(dispatcher) {
        viewModel.showPrivateChatSheet(otherPeerID)

        viewModel.sendPrivateChatSheetMessage(otherPeerID, "draft sent early")
        runCurrent()

        verify(mesh, never()).sendMessage(any(), any(), anyOrNull())
        assertEquals(listOf("draft sent early"), sentTextIn(otherPeerID))
    }

    @Test
    fun `sheet text goes to its own peer while another chat is still selected`() = runTest(dispatcher) {
        viewModel.startPrivateChat(previousPeerID)
        viewModel.showPrivateChatSheet(otherPeerID)

        viewModel.sendPrivateChatSheetMessage(otherPeerID, "meant for the new sheet")
        runCurrent()

        verify(mesh, never()).sendMessage(any(), any(), anyOrNull())
        assertEquals(listOf("meant for the new sheet"), sentTextIn(otherPeerID))
        assertEquals(emptyList<String>(), sentTextIn(previousPeerID))
    }

    @Test
    fun `refused send closes a sheet whose peer was blocked after it opened`() = runTest(dispatcher) {
        viewModel.showPrivateChatSheet(blockedPeerID)

        viewModel.sendPrivateChatSheetMessage(blockedPeerID, "too late")
        runCurrent()

        assertNull(viewModel.privateChatSheetPeer.value)
    }

    @Test
    fun `a sheet that cannot start its chat closes and leaves nothing selected`() = runTest(dispatcher) {
        viewModel.startPrivateChat(previousPeerID)
        viewModel.showPrivateChatSheet(blockedPeerID)

        viewModel.startPrivateChat(blockedPeerID)
        runCurrent()

        assertNull(viewModel.privateChatSheetPeer.value)
        assertNull(viewModel.selectedPrivateChatPeer.value)
    }

    @Test
    fun `a stale refused start does not close a sheet opened for someone else`() = runTest(dispatcher) {
        viewModel.showPrivateChatSheet(otherPeerID)

        viewModel.startPrivateChat(blockedPeerID)
        runCurrent()

        assertEquals(otherPeerID, viewModel.privateChatSheetPeer.value)
    }

    private fun sentTextIn(conversationID: String): List<String> =
        AppStateStore.privateMessages.value[conversationID].orEmpty()
            .filter { it.sender != "system" }
            .map { it.content }
}
