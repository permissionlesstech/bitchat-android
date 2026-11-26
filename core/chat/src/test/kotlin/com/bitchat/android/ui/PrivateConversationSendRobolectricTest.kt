package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.TestMeshStores
import java.util.UUID
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Text typed into a private chat is addressed to that chat's peer. It must reach that peer or be
 * refused, whatever the selected private chat happens to be at that moment (upstream's
 * PrivateChatSheetSendRobolectricTest, on this branch's MessageSender and PrivateChatSession).
 */
@RunWith(RobolectricTestRunner::class)
class PrivateConversationSendRobolectricTest {
    private val application: Application = RuntimeEnvironment.getApplication()
    private val blockedPeerID = "1111111111111111"
    private val otherPeerID = "2222222222222222"
    private val previousPeerID = "3333333333333333"
    private val blockedFingerprint = "synthetic-blocked-fingerprint"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val conversationDatabaseName = "private-conversation-send-${UUID.randomUUID()}.db"
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var stores: TestMeshStores
    private lateinit var state: ChatState
    private lateinit var mesh: MeshService
    private lateinit var privateChatManager: PrivateChatManager
    private lateinit var privateChatSession: PrivateChatSession
    private lateinit var sender: MessageSender

    @Before
    fun setUp() {
        conversationRepository = ConversationRepository(
            context = application,
            dispatcher = Dispatchers.Unconfined,
            databaseName = conversationDatabaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        stores = TestMeshStores(application, conversationRepository)
        state = ChatState(scope, stores.appStateStore)
        state.setNickname("synthetic-self")
        mesh = mock()
        whenever(mesh.myPeerID).thenReturn("aaaaaaaaaaaaaaaa")
        whenever(mesh.getPeerNicknames()).thenReturn(emptyMap())
        val dataManager = DataManager(application)
        dataManager.addBlockedUser(blockedFingerprint)
        val fingerprints = PeerFingerprintManager()
        fingerprints.updatePeerIDMapping(oldPeerID = null, newPeerID = blockedPeerID, fingerprint = blockedFingerprint)
        val messageManager = MessageManager(state, stores.appStateStore, stores.contactDirectory)
        val channelManager = ChannelManager(state, messageManager, dataManager, scope)
        privateChatManager = PrivateChatManager(
            state = state,
            messageManager = messageManager,
            dataManager = dataManager,
            noiseSessionDelegate = mock<NoiseSessionDelegate>(),
            contactDirectory = stores.contactDirectory,
            appStateStore = stores.appStateStore,
            favorites = Provider { stores.favorites },
            peerFingerprints = fingerprints,
        )
        val geohashSession = mock<GeohashSession>()
        val sessionMesh = ChatSessionMesh(mesh)
        val router = mock<MessageRouter>()
        sender = MessageSender(
            state = state,
            messageManager = messageManager,
            channelManager = channelManager,
            privateChatManager = privateChatManager,
            commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager),
            geohashSession = geohashSession,
            sessionMesh = sessionMesh,
            messageRouterProvider = Provider { router },
            contactDirectory = stores.contactDirectory,
            favorites = Provider { stores.favorites },
            geohashAliases = stores.geohashAliases,
            scope = scope,
        )
        privateChatSession = PrivateChatSession(
            state = state,
            privateChatManager = privateChatManager,
            notificationManager = mock(),
            geohashSession = geohashSession,
            sessionMesh = sessionMesh,
            appStateStore = stores.appStateStore,
            contactDirectory = stores.contactDirectory,
        )
    }

    @After
    fun tearDown() {
        scope.cancel()
        conversationRepository.closeForTest()
        application.deleteDatabase(conversationDatabaseName)
    }

    @Test
    fun `text sent from a blocked peer's chat never reaches the public mesh`() {
        var accepted: Boolean? = null

        sender.sendToConversation(blockedPeerID, "for this chat only") { accepted = it }

        verify(mesh, never()).sendMessage(any(), any(), anyOrNull())
        assertEquals(false, accepted)
    }

    @Test
    fun `slash action from a chat is refused while another conversation is selected`() {
        var accepted: Boolean? = null

        sender.sendToConversation(blockedPeerID, "/hug someone") { accepted = it }

        verify(mesh, never()).sendMessage(any(), any(), anyOrNull())
        assertEquals(false, accepted)
    }

    @Test
    fun `msg typed in an open chat reports the conversation it selected`() {
        whenever(mesh.getPeerNicknames()).thenReturn(mapOf(otherPeerID to "bob"))
        assertTrue(privateChatManager.startPrivateChat(previousPeerID, mesh))
        val selectedOther = mutableListOf<String>()

        sender.sendToConversation(
            previousPeerID,
            "/msg bob",
            onSelectedOther = { selectedOther += it },
        )

        assertEquals(otherPeerID, state.getSelectedPrivateChatPeerValue())
        assertEquals(listOf(otherPeerID), selectedOther)
    }

    @Test
    fun `a command that keeps this chat selected reports no other conversation`() {
        assertTrue(privateChatManager.startPrivateChat(previousPeerID, mesh))
        val selectedOther = mutableListOf<String>()

        sender.sendToConversation(
            previousPeerID,
            "/clear",
            onSelectedOther = { selectedOther += it },
        )

        assertEquals(emptyList<String>(), selectedOther)
    }

    @Test
    fun `chat text goes to its own peer before the chat has started`() {
        sender.sendToConversation(otherPeerID, "draft sent early")

        verify(mesh, never()).sendMessage(any(), any(), anyOrNull())
        assertEquals(listOf("draft sent early"), sentTextIn(otherPeerID))
    }

    @Test
    fun `chat text goes to its own peer while another chat is still selected`() {
        assertTrue(privateChatManager.startPrivateChat(previousPeerID, mesh))

        sender.sendToConversation(otherPeerID, "meant for the new chat")

        verify(mesh, never()).sendMessage(any(), any(), anyOrNull())
        assertEquals(listOf("meant for the new chat"), sentTextIn(otherPeerID))
        assertEquals(emptyList<String>(), sentTextIn(previousPeerID))
    }

    @Test
    fun `refused send reports a chat whose peer was blocked after it opened`() {
        var refusedBlocked = false

        sender.sendToConversation(blockedPeerID, "too late", onRefusedBlocked = { refusedBlocked = true })

        assertTrue(refusedBlocked)
    }

    @Test
    fun `a refused send for a peer that is not blocked does not close the chat`() {
        var refusedBlocked = false

        sender.sendToConversation(otherPeerID, "", onRefusedBlocked = { refusedBlocked = true })

        assertFalse(refusedBlocked)
    }

    @Test
    fun `a chat that cannot start reports it and selects nothing`() = runBlocking {
        val started = privateChatSession.start(blockedPeerID)

        assertFalse(started)
        assertNull(state.getSelectedPrivateChatPeerValue())
    }

    private fun sentTextIn(conversationID: String): List<String> =
        stores.appStateStore.privateMessages.value[conversationID].orEmpty()
            .filter { it.sender != "system" }
            .map { it.content }
}
