package com.bitchat.android.ui

import android.content.Context
import com.bitchat.android.di.ChatSessionComponent
import com.bitchat.android.di.ChatSessionLifetimeEntryPoint
import com.bitchat.android.di.ChatSessionTeardown
import com.bitchat.android.di.DefaultChatSessionEnder
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.TransferProgressManager
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.TestMeshStores
import com.bitchat.android.ui.debug.DebugSettingsManager
import dagger.hilt.internal.GeneratedComponent
import java.util.Date
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
class ChatSessionStartupTest {

    private val scheduler = TestCoroutineScheduler()
    private val context = mock<Context>().also { whenever(it.applicationContext).thenReturn(it) }

    // The identity's objects: one set, shared by each session built from it.
    private val stores = TestMeshStores(context, conversationRepository = mock<ConversationRepository>())
    private val mesh = mock<MeshService> {
        on { myPeerID } doReturn "self"
        on { getPeerNicknames() } doReturn emptyMap()
        on { getPeerRSSI() } doReturn emptyMap()
    }
    private val debugSettings = mock<DebugSettingsManager> {
        on { debugMessages } doReturn MutableStateFlow(emptyList())
        on { verboseLoggingEnabled } doReturn MutableStateFlow(false)
    }
    private val transferProgress = mock<TransferProgressManager> {
        on { events } doReturn MutableSharedFlow()
    }
    private val builtSessions = ArrayList<Session>()

    /** What Hilt generates: a session component that is its own entry point. */
    private class FakeSessionComponent(
        private val teardown: ChatSessionTeardown,
        private val scope: CoroutineScope,
    ) : ChatSessionComponent, GeneratedComponent, ChatSessionLifetimeEntryPoint {
        override fun teardown() = teardown
        override fun scope() = scope
    }

    /** One chat session's objects, around a real [ChatSessionStartup] and the real ender. */
    private inner class Session {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val teardown = ChatSessionTeardown()
        val state = ChatState(scope, stores.appStateStore)
        val conversationDirectory = mock<ConversationDirectory>()
        val geohashSession = mock<GeohashSession>()
        val startup = ChatSessionStartup(
            context = context,
            scope = scope,
            teardown = teardown,
            sessionMesh = ChatSessionMesh(mesh),
            seenMessageStoreProvider = { mock() },
            nostrTransportProvider = { mock() },
            messageRouterProvider = { mock() },
            debugSettingsManager = debugSettings,
            state = state,
            dataManager = mock { on { loadNickname() } doReturn "anon0000" },
            messageManager = mock(),
            channelManager = mock { on { loadChannelData() } doReturn (emptySet<String>() to emptySet()) },
            privateChatManager = mock { on { getAllPeerFingerprints() } doReturn emptyMap() },
            identityManagerProvider = Provider { mock() },
            verificationHandler = mock(),
            mediaSendingManager = mock(),
            geohashSession = geohashSession,
            contactFavorites = mock(),
            conversationDirectory = conversationDirectory,
            appStateStore = stores.appStateStore,
            contactDirectory = stores.contactDirectory,
            transferProgress = transferProgress,
            favorites = mock<FavoritesPersistenceService>(),
        )

        fun end() = DefaultChatSessionEnder().end(FakeSessionComponent(teardown, scope))
    }

    private fun session() = Session().also { builtSessions += it }

    private fun sessionStateIs(value: NoiseSession.NoiseSessionState) {
        whenever(mesh.getSessionState("peer")).thenReturn(value)
    }

    @After
    fun cancelSessions() = builtSessions.forEach { it.scope.cancel() }

    @Test
    fun `stop runs when the session ends`() {
        val session = session()
        session.startup.start()
        scheduler.runCurrent()

        session.end()

        inOrder(session.conversationDirectory, session.geohashSession) {
            verify(session.conversationDirectory).stopFavoriteTracking()
            verify(session.geohashSession).shutdownUiSubscriptions()
        }
        assertFalse(session.scope.isActive)
    }

    @Test
    fun `stop does not run for a session that never started`() {
        val session = session()

        session.end()

        verify(session.conversationDirectory, never()).stopFavoriteTracking()
        verify(session.geohashSession, never()).shutdownUiSubscriptions()
        assertFalse(session.scope.isActive)
    }

    @Test
    fun `one failing step of stop does not stop the rest`() {
        val session = session()
        whenever(session.conversationDirectory.stopFavoriteTracking()).doThrow(IllegalStateException("synthetic failure"))
        stores.appStateStore.setSelectedPrivateChatPeer("synthetic-peer")
        session.startup.start()
        scheduler.runCurrent()

        session.end()

        verify(session.geohashSession).shutdownUiSubscriptions()
        assertEquals(null, stores.appStateStore.selectedPrivateChatPeer.value)
        assertFalse(session.scope.isActive)
    }

    @Test
    fun `the peer poll stops when the session ends`() {
        stores.appStateStore.setPeers(listOf("peer"))
        sessionStateIs(NoiseSession.NoiseSessionState.Uninitialized)
        val session = session()
        session.startup.start()
        scheduler.runCurrent()
        scheduler.advanceTimeBy(1_001)
        val polled = session.state.getPeerSessionStatesValue()
        assertEquals(setOf("peer"), polled.keys)

        session.end()
        sessionStateIs(NoiseSession.NoiseSessionState.Handshaking)
        scheduler.advanceTimeBy(10_000)

        assertEquals(polled, session.state.getPeerSessionStatesValue())
    }

    @Test
    fun `a second session starts its own poll and hydrates from the store`() {
        val message = BitchatMessage(id = "synthetic-1", sender = "synthetic", content = "hello", timestamp = Date())
        stores.appStateStore.addPublicMessage(message)
        stores.appStateStore.setPeers(listOf("peer"))
        sessionStateIs(NoiseSession.NoiseSessionState.Uninitialized)
        val first = session()
        first.startup.start()
        scheduler.runCurrent()
        first.end()

        val second = session()
        assertTrue(second.state.getMessagesValue().isEmpty())
        second.startup.start()
        scheduler.runCurrent()

        assertEquals(listOf(message), second.state.getMessagesValue())
        assertEquals(listOf("peer"), second.state.getConnectedPeersValue())
        scheduler.advanceTimeBy(1_001)
        assertEquals(setOf("peer"), second.state.getPeerSessionStatesValue().keys)
    }

    @Test
    fun `start runs once per session`() {
        val session = session()
        session.startup.start()
        session.startup.start()
        scheduler.runCurrent()

        verify(session.geohashSession, times(1)).initialize()
    }

    @Test
    fun `a start after the session ended starts nothing`() {
        val session = session()
        session.end()

        session.startup.start()
        scheduler.runCurrent()

        verify(session.conversationDirectory, never()).startFavoriteTracking()
        verify(session.conversationDirectory, never()).observePresenceWithDisconnectGrace()
        verify(session.geohashSession, never()).initialize()
    }

    @Test
    fun `the ender runs every cleanup then cancels the scope even when one throws`() {
        val session = session()
        val ran = ArrayList<String>()
        session.teardown.register("first") { ran += "first" }
        session.teardown.register("broken") { error("synthetic failure") }
        session.teardown.register("last") {
            assertTrue("the scope outlives the cleanups", session.scope.isActive)
            ran += "last"
        }

        session.end()

        assertEquals(listOf("last", "first"), ran)
        assertFalse(session.scope.isActive)
    }

    @Test
    fun `the ender does not throw when it cannot reach the cleanups and still cancels the scope`() {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val component = object : ChatSessionComponent, GeneratedComponent, ChatSessionLifetimeEntryPoint {
            override fun teardown(): ChatSessionTeardown = error("synthetic failure")
            override fun scope() = scope
        }

        DefaultChatSessionEnder().end(component)

        assertFalse(scope.isActive)
    }

    @Test
    fun `a cleanup registered after the session ended runs at once`() {
        val teardown = ChatSessionTeardown()
        teardown.runAll()
        var ran = false

        teardown.register("late") { ran = true }

        assertTrue(ran)
    }
}
