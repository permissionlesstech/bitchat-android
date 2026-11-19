package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.core.chat.R
import com.bitchat.android.di.ChatSessionBuilderEntryPoint
import com.bitchat.android.di.ChatSessionComponent
import com.bitchat.android.di.ChatSessionComponentBuilder
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.GeohashBookmarksStore
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityComponentBuilder
import com.bitchat.android.identity.scope.IdentityEnder
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.IdentityStartup
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.MessageRouterHolder
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.TestMeshStores
import com.bitchat.android.spam.SpamFilter
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import dagger.hilt.internal.GeneratedComponent
import java.util.Date
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The order and the outcomes of a panic, on recording fakes behind the real holders and the real
 * runner. No key, relay or file of a device is touched; every value is synthetic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class PanicClearTest {

    /** A library's resources do not reach a JVM test, so the two outcome messages are stood in for. */
    private val application: Application = Mockito.spy(RuntimeEnvironment.getApplication()).also {
        doReturn("synthetic completed").whenever(it).getString(R.string.system_panic_completed)
        doReturn("synthetic incomplete").whenever(it).getString(R.string.system_panic_incomplete)
    }
    private val log = ArrayList<String>()

    /** Records each call as `name.method`. A wipe stage reports success unless a test says not. */
    private inline fun <reified T : Any> recorded(name: String): T =
        Mockito.mock(T::class.java) { invocation: InvocationOnMock ->
            log += "$name.${invocation.method.name}"
            if (invocation.method.returnType == Boolean::class.javaPrimitiveType) true
            else Mockito.RETURNS_DEFAULTS.answer(invocation)
        }

    private fun recordedMesh(name: String): MeshService {
        var held: MeshDelegate? = null
        val mesh = recorded<MeshService>(name)
        doAnswer { held }.whenever(mesh).delegate
        doAnswer {
            log += "$name.setDelegate(${if (it.getArgument<MeshDelegate?>(0) == null) "none" else "session"})"
            held = it.getArgument(0)
            Unit
        }.whenever(mesh).delegate = org.mockito.kotlin.anyOrNull()
        return mesh
    }

    /** What the wipe of the conversation database does; a test can hold it or fail it. */
    private var databaseWipe: suspend () -> Boolean = { true }

    /** One identity's objects. Built only when the holder builds that identity. */
    private inner class Identity(name: String) : IdentityComponent, GeneratedComponent, ChatSessionBuilderEntryPoint {
        val router = recorded<MessageRouter>("$name.router")
        val routerHolder = Mockito.mock(MessageRouterHolder::class.java).also {
            doReturn(router).whenever(it).peek()
        }
        val preferences = recorded<ConversationListPreferences>("$name.preferences")
        val seen = recorded<SeenMessageStore>("$name.seen")
        val location = recorded<LocationChannelManager>("$name.location")
        val bookmarks = recorded<GeohashBookmarksStore>("$name.bookmarks")
        val favorites = recorded<FavoritesPersistenceService>("$name.favorites")
        val spam = recorded<SpamFilter>("$name.spam")
        val mesh = recordedMesh("$name.mesh")
        var store: AppStateStore = recorded<AppStateStore>("$name.store").also { store ->
            runBlocking {
                doSuspendableAnswer {
                    log += "$name.store.panicClearPrivateConversations"
                    databaseWipe()
                }.whenever(store).panicClearPrivateConversations()
            }
        }

        override fun chatSessionBuilder() = object : ChatSessionComponentBuilder {
            override fun build(): ChatSessionComponent = Session(this@Identity, "session${sessions.size}").also { sessions += it }
        }
    }

    private inner class Session(val identity: Identity, name: String) : ChatSessionComponent, GeneratedComponent {
        val state = recorded<ChatState>("$name.state")
        val data = recorded<DataManager>("$name.data")
        val messages = recorded<MessageManager>("$name.messages")
        val channels = recorded<ChannelManager>("$name.channels")
        val privateChats = recorded<PrivateChatManager>("$name.privateChats")
        val media = recorded<MediaSendingManager>("$name.media")
        val notifications = recorded<NotificationManager>("$name.notifications")
        val geohash = recorded<GeohashSession>("$name.geohash")
        val secure = recorded<SecureIdentityStateManager>("$name.secure")
        val startup = recorded<ChatSessionStartup>("$name.startup")
        val delegate = recorded<ChatMeshDelegate>("$name.delegate")
        val mesh = Mockito.mock(ChatSessionMesh::class.java).also {
            doReturn(identity.mesh).whenever(it).unified
        }
    }

    private val identities = ArrayList<Identity>()
    private val sessions = ArrayList<Session>()
    private var identityBuildFailures = 0
    private var failedCleanups: List<String> = emptyList()
    private var startupFails = false
    private var readFails = false
    private var frameFails = false

    private val identityHolder = IdentityHolder(
        Provider {
            object : IdentityComponentBuilder {
                override fun build(): IdentityComponent {
                    if (identityBuildFailures > 0) {
                        identityBuildFailures--
                        error("synthetic build failure")
                    }
                    return Identity("identity${identities.size}").also { identities += it }
                }
            }
        },
        IdentityEnder {
            log += "identity ended"
            failedCleanups
        },
    )

    private class FakeLifecycle : ActivityRetainedLifecycle {
        private val listeners = ArrayList<RetainedLifecycle.OnClearedListener>()
        override fun addOnClearedListener(listener: RetainedLifecycle.OnClearedListener) {
            listeners += listener
        }
        override fun removeOnClearedListener(listener: RetainedLifecycle.OnClearedListener) {
            listeners -= listener
        }
        fun clear() = listeners.toList().forEach { it.onCleared() }
    }

    private val lifecycle = FakeLifecycle()
    private val sessionHolder = ChatSessionHolder(identityHolder, lifecycle) { log += "session ended" }
    private val runner = PanicRunner()
    private val blankFrame = Mockito.mock(BlankFrame::class.java)

    private val identity get() = identityHolder.current as Identity
    private val session get() = sessionHolder.current as Session

    private val startup = IdentityStartup {
        log += "identity started"
        check(!startupFails) { "synthetic startup failure" }
    }

    private val panic = PanicClear(
        context = application,
        runner = runner,
        identityHolder = identityHolder,
        identityStartup = startup,
        sessionHolder = sessionHolder,
        blankFrame = blankFrame,
        stateProvider = { session.state },
        sessionMeshProvider = { session.mesh },
        meshDelegateProvider = { session.delegate },
        messageManagerProvider = { session.messages },
        channelManagerProvider = { session.channels },
        privateChatManagerProvider = { session.privateChats },
        dataManagerProvider = { if (readFails) error("synthetic read failure") else session.data },
        mediaSendingManagerProvider = { session.media },
        notificationManagerProvider = { session.notifications },
        geohashSessionProvider = { session.geohash },
        secureIdentityProvider = { session.secure },
        sessionStartupProvider = { session.startup },
        meshProvider = { identity.mesh },
        messageRouterHolder = { identity.routerHolder },
        conversationListPreferencesProvider = { identity.preferences },
        seenMessageStoreProvider = { identity.seen },
        locationChannelManagerProvider = { identity.location },
        geohashBookmarksStoreProvider = { identity.bookmarks },
        appStateStoreProvider = { identity.store },
        spamFilterProvider = { identity.spam },
        favorites = { identity.favorites },
    )

    private val completed get() = application.getString(R.string.system_panic_completed)
    private val incomplete get() = application.getString(R.string.system_panic_incomplete)

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            doSuspendableAnswer {
                log += "frame"
                check(!frameFails) { "synthetic frame failure" }
            }.whenever(blankFrame).await()
        }
        // Starts as the app would be: a session on a first identity.
        session
        log.clear()
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `steps keep the design's order`() {
        panic.run()

        val old = listOf(
            "identity0.location.disableLocationServices",
            "session0.media.clearPendingPrivateMediaConsent",
            "session0.geohash.cancelSendsInFlight",
            "identity0.mesh.clearAllInternalData",
            "identity0.store.panicClearPrivateConversations",
            "identity0.store.clear",
            "session0.messages.clearAllMessages",
            "session0.channels.clearAllChannels",
            "session0.privateChats.clearAllPrivateChats",
            "frame",
            "session0.data.clearAllData",
            "identity0.preferences.clearAll",
            "identity0.seen.clear",
            "identity0.router.clearAll",
            "identity0.mesh.clearAllEncryptionData",
            "session0.secure.clearIdentityData",
            "session0.secure.clearSecureValuesSynchronously",
            "identity0.favorites.clearAllFavorites",
            "session0.notifications.clearAllNotifications",
            "identity0.bookmarks.clearAll",
            "identity0.location.clearPersistedChannel",
            "session0.geohash.panicReset",
            "session0.data.saveNickname",
            "session0.state.setNickname",
            "identity0.mesh.setDelegate(none)",
            "identity0.mesh.stopServices",
            "session ended",
            "identity ended",
        )
        val new = listOf(
            "identity started",
            "session1.state.setNickname",
            "identity1.mesh.setDelegate(session)",
            "identity1.mesh.startServices",
            "identity1.mesh.sendBroadcastAnnounce",
            "session1.startup.start",
            "session1.messages.addSystemMessage",
        )
        assertEquals(old + new, log)
    }

    @Test
    fun `the screen blanks before the first wipe stage`() {
        panic.run()

        val blanked = listOf("identity0.store.clear", "session0.messages.clearAllMessages", "session0.channels.clearAllChannels", "session0.privateChats.clearAllPrivateChats")
        val frame = log.indexOf("frame")
        val firstWipe = log.indexOf("session0.data.clearAllData")
        assertTrue(blanked.all { log.indexOf(it) in 0 until frame })
        assertTrue(frame < firstWipe)
    }

    @Test
    fun `a failed database wipe does not replace the identity and shows incomplete`() {
        databaseWipe = { false }
        val oldIdentity = identity
        val oldSession = session

        panic.run()

        assertSame(oldIdentity, identity)
        assertSame(oldSession, session)
        assertEquals(1, identities.size)
        assertEquals(1, sessions.size)
        assertEquals(0, identityHolder.generation.value)
        assertEquals(0, sessionHolder.generation.value)
        assertFalse(log.contains("identity ended"))
        assertFalse(log.contains("session ended"))
        assertFalse(log.contains("identity started"))
        verify(oldSession.messages).addSystemMessage(incomplete)
        verify(oldSession.messages, never()).addSystemMessage(completed)
        assertFalse(identityHolder.ending.value)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `a throw while reading the old objects shows incomplete and never completed`() {
        readFails = true
        val oldSession = session

        panic.run()

        verify(oldSession.messages).addSystemMessage(incomplete)
        verify(oldSession.messages, never()).addSystemMessage(completed)
        assertFalse(log.contains("identity ended"))
        assertTrue("the stages that can run still do", log.contains("identity0.store.panicClearPrivateConversations"))
        assertFalse(identityHolder.ending.value)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `a throw while blanking the screen does not stop the wipe`() {
        frameFails = true
        val oldSession = session

        panic.run()

        assertTrue(log.contains("session0.data.clearAllData"))
        verify(oldSession.messages, never()).addSystemMessage(completed)
        assertFalse(identityHolder.ending.value)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `the failed path resets the spam filter it no longer rebuilds`() {
        databaseWipe = { false }

        panic.run()

        verify(identity.spam).reset()
    }

    @Test
    fun `the rebuilt path leaves the spam filter to the rebuild`() {
        val old = identity

        panic.run()

        verify(old.spam, never()).reset()
    }

    @Test
    fun `the failed path leaves the gate closed`() {
        val failing = Mockito.mock(ConversationRepository::class.java)
        runBlocking { doReturn(false).whenever(failing).clearAllAndWait() }
        val real = TestMeshStores(application, failing).appStateStore
        identity.store = real

        panic.run()

        assertEquals(1, identities.size)
        verify(session.messages).addSystemMessage(incomplete)
        assertFalse(real.addPrivateMessage("synthetic-peer", message("after-a-failed-wipe")))
    }

    @Test
    fun `a throwing database wipe takes the failed path`() {
        databaseWipe = { error("synthetic") }

        panic.run()

        assertEquals(1, identities.size)
        verify(session.messages).addSystemMessage(incomplete)
    }

    @Test
    fun `a stage not confirming still rebuilds and shows incomplete in the new session`() {
        val oldSession = session
        doReturn(false).whenever(oldSession.secure).clearIdentityData()

        panic.run()

        assertEquals(2, identities.size)
        assertNotSame(oldSession, session)
        verify(session.messages).addSystemMessage(incomplete)
        verify(session.messages, never()).addSystemMessage(completed)
        verify(oldSession.messages, never()).addSystemMessage(any())
    }

    @Test
    fun `a stage that throws counts as not confirming and the rest still run`() {
        val oldSession = session
        doAnswer { error("synthetic") }.whenever(oldSession.data).clearAllData()

        panic.run()

        assertTrue(log.contains("identity0.preferences.clearAll"))
        verify(session.messages).addSystemMessage(incomplete)
    }

    @Test
    fun `success shows completed in the new session`() {
        val oldSession = session

        panic.run()

        assertNotSame(oldSession, session)
        verify(session.messages).addSystemMessage(completed)
        verify(oldSession.messages, never()).addSystemMessage(any())
        assertEquals(1, identityHolder.generation.value)
        assertEquals(1, sessionHolder.generation.value)
    }

    @Test
    fun `the nickname reaches the new session's state before the mesh announces with it`() {
        panic.run()

        assertTrue(log.indexOf("session1.state.setNickname") < log.indexOf("identity1.mesh.sendBroadcastAnnounce"))
    }

    @Test
    fun `the old mesh is stopped and detached before the identity ends`() {
        val oldMesh = identity.mesh
        panic.run()

        assertNull(oldMesh.delegate)
        assertTrue(log.indexOf("identity0.mesh.stopServices") < log.indexOf("identity ended"))
        assertSame(session.delegate, identity.mesh.delegate)
    }

    @Test
    fun `a second call while running is ignored`() {
        val hold = CompletableDeferred<Boolean>()
        databaseWipe = { hold.await() }

        panic.run()
        panic.run()
        assertEquals(1, log.count { it.endsWith(".store.panicClearPrivateConversations") })
        assertTrue(panic.inProgress)

        hold.complete(true)
        assertFalse(panic.inProgress)
        assertEquals(1, log.count { it.endsWith(".store.panicClearPrivateConversations") })
        assertEquals(2, identities.size)
    }

    @Test
    fun `a second panic after the first works`() {
        panic.run()
        val second = session

        panic.run()

        assertEquals(3, identities.size)
        assertNotSame(second, session)
        verify(session.messages).addSystemMessage(completed)
    }

    @Test
    fun `the session lifecycle clearing mid-wipe does not stop the wipe, and no message is shown`() {
        val hold = CompletableDeferred<Boolean>()
        databaseWipe = { hold.await() }
        val oldSession = session

        panic.run()
        lifecycle.clear()
        hold.complete(true)

        assertTrue("the wipe went on", log.contains("session0.geohash.panicReset"))
        assertEquals(2, identities.size)
        assertEquals("no session is built for nobody", 1, sessions.size)
        assertTrue(log.contains("identity1.mesh.startServices"))
        assertTrue(log.contains("identity1.mesh.sendBroadcastAnnounce"))
        assertNull(identities.last().mesh.delegate)
        verify(oldSession.messages, never()).addSystemMessage(any())
    }

    @Test
    fun `a new holder appearing mid-wipe starts nothing on the ending identity and no second panic`() {
        val hold = CompletableDeferred<Boolean>()
        databaseWipe = { hold.await() }
        panic.run()
        val ended = ArrayList<ChatSessionComponent>()
        val reopened = ChatSessionHolder(identityHolder, FakeLifecycle()) { ended += it }

        val heldBack = reopened.current

        assertEquals("what it got is already over", listOf(heldBack), ended)
        assertFalse(runner.runOnce { error("a second panic ran") })

        hold.complete(true)
        val next = reopened.current as Session

        assertNotSame(heldBack, next)
        assertSame(identities.last(), next.identity)
        assertEquals(1, reopened.generation.value)
    }

    @Test
    fun `an in-flight message into the old store does not reach the new one`() {
        val real = realStore()
        identity.store = real
        val fresh = realStore()
        val builtBefore = identities.size

        panic.run()
        assertEquals(builtBefore + 1, identities.size)
        identity.store = fresh

        assertFalse(real.addPrivateMessage("synthetic-peer", message("in-flight")))
        assertTrue(real.privateMessages.value.isEmpty())
        assertTrue(fresh.privateMessages.value.isEmpty())
        // Only the new identity's store admits messages.
        assertTrue(fresh.addPrivateMessage("synthetic-peer", message("arrived-after")))
    }

    @Test
    fun `a cleanup failure marks the outcome incomplete`() {
        failedCleanups = listOf("SyntheticCleanup")

        panic.run()

        verify(session.messages).addSystemMessage(incomplete)
    }

    @Test
    fun `a swap whose first build fails rebuilds and shows incomplete`() {
        val oldSession = session
        identityBuildFailures = 1

        panic.run()

        assertEquals(2, identities.size)
        assertNotSame(oldSession, session)
        assertSame(identities.last(), session.identity)
        assertTrue(log.contains("identity started"))
        verify(session.messages).addSystemMessage(incomplete)
        assertEquals(1, identityHolder.generation.value)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `a swap that cannot build at all announces nothing and does not reach the process`() {
        val oldSession = session
        identityBuildFailures = 2

        panic.run()

        verify(oldSession.messages, never()).addSystemMessage(any())
        assertFalse(log.contains("session1.messages.addSystemMessage"))
        assertFalse(runner.isRunning)
        assertFalse(identityHolder.ending.value)
    }

    @Test
    fun `a start-up failure still announces incomplete in the new session`() {
        startupFails = true

        panic.run()

        verify(session.messages).addSystemMessage(incomplete)
    }

    @Test
    fun `the identity is marked ending for the whole wipe`() {
        val hold = CompletableDeferred<Boolean>()
        databaseWipe = { hold.await() }

        panic.run()
        assertTrue(identityHolder.ending.value)

        hold.complete(true)
        assertFalse(identityHolder.ending.value)
    }

    private fun realStore(): AppStateStore = TestMeshStores(application).appStateStore

    private fun message(id: String) = BitchatMessage(
        id = id,
        sender = "synthetic",
        content = "synthetic",
        timestamp = Date(),
        isPrivate = true,
    )
}
