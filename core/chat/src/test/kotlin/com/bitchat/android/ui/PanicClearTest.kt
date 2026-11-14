package com.bitchat.android.ui

import android.app.Application
import com.bitchat.android.core.chat.R
import com.bitchat.android.di.ChatSessionBuilderEntryPoint
import com.bitchat.android.di.ChatSessionComponent
import com.bitchat.android.di.ChatSessionComponentBuilder
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.identity.scope.IdentityComponent
import com.bitchat.android.identity.scope.IdentityComponentBuilder
import com.bitchat.android.identity.scope.IdentityEnder
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.IdentityStartup
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.panic.PanicCleanup
import com.bitchat.android.panic.PanicMarker
import com.bitchat.android.panic.ProcessRestarter
import com.bitchat.android.panic.SandboxWipeRunner
import com.bitchat.android.panic.WipeResult
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.TestMeshStores
import dagger.hilt.android.ActivityRetainedLifecycle
import dagger.hilt.android.lifecycle.RetainedLifecycle
import dagger.hilt.internal.GeneratedComponent
import java.io.File
import java.nio.file.Files
import java.util.Date
import java.util.Optional
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
 * runner. No key, relay or file of a device is touched; every value is synthetic, and the sandbox
 * wipe, the system-side cleanups and the restart are fakes.
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

    /** Records each call as `name.method`. A call that returns a Boolean reports success. */
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
        val location = recorded<LocationChannelManager>("$name.location").also { location ->
            doAnswer {
                log += "$name.location.disableLocationServices(marker ${if (marker.pending) "set" else "unset"})"
                Unit
            }.whenever(location).disableLocationServices()
        }
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
        val geohash = recorded<GeohashSession>("$name.geohash")
        val startup = recorded<ChatSessionStartup>("$name.startup")
        val delegate = recorded<ChatMeshDelegate>("$name.delegate")
        val mesh = Mockito.mock(ChatSessionMesh::class.java).also {
            doReturn(identity.mesh).whenever(it).unified
        }
    }

    private val identities = ArrayList<Identity>()
    private val sessions = ArrayList<Session>()
    private var identityBuildFailures = 0
    private var failedTeardownCleanups: List<String> = emptyList()
    private var startupFails = false
    private var readFails = false
    private var frameFails = false

    private val markerDir: File = Files.createTempDirectory("panic-marker").toFile()

    /** In a directory, so a test can make writing it fail by putting a file where the directory goes. */
    private val markerParent = File(markerDir, "no_backup")
    private val marker = PanicMarker(File(markerParent, PanicMarker.NAME))

    /** Makes [PanicMarker.set] throw until [unblockMarker]. */
    private fun blockMarker() {
        markerParent.deleteRecursively()
        markerParent.writeText("synthetic file in the way")
    }

    private fun unblockMarker() {
        markerParent.delete()
    }

    /** What the sandbox wipe reports; a test can make it leave something. */
    private var wipeResult = WipeResult(leftovers = emptyList(), aliasesLeft = 0)
    private var onWipe: () -> Unit = {}
    private val sandboxWipe = SandboxWipeRunner {
        log += "sandbox wiped (marker ${if (marker.pending) "set" else "unset"})"
        onWipe()
        wipeResult
    }

    private inner class RecordedCleanup(override val name: String) : PanicCleanup {
        var result: () -> Boolean = { true }
        override fun run(): Boolean {
            log += "cleanup $name"
            return result()
        }
    }

    private val notificationsCleanup = RecordedCleanup("notifications")
    private val clipboardCleanup = RecordedCleanup("clipboard")

    private var restarts = 0
    private val restarter = ProcessRestarter {
        log += "restart"
        restarts++
    }

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
            failedTeardownCleanups
        },
        marker,
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
        marker = marker,
        sandboxWipe = sandboxWipe,
        cleanups = linkedSetOf(notificationsCleanup, clipboardCleanup),
        restarter = restarter,
        stateProvider = { session.state },
        sessionMeshProvider = { session.mesh },
        meshDelegateProvider = { session.delegate },
        messageManagerProvider = { session.messages },
        channelManagerProvider = { session.channels },
        privateChatManagerProvider = { session.privateChats },
        dataManagerProvider = { session.data },
        mediaSendingManagerProvider = { if (readFails) error("synthetic read failure") else session.media },
        geohashSessionProvider = { session.geohash },
        sessionStartupProvider = { session.startup },
        meshProvider = { identity.mesh },
        locationChannelManagerProvider = { identity.location },
        appStateStoreProvider = { identity.store },
        stageListener = Optional.empty(),
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
    fun tearDown() {
        Dispatchers.resetMain()
        markerDir.deleteRecursively()
    }

    @Test
    fun `steps keep the design's order`() {
        panic.run()

        val old = listOf(
            "identity0.location.disableLocationServices(marker set)",
            "session0.media.clearPendingPrivateMediaConsent",
            "session0.geohash.cancelSendsInFlight",
            "identity0.mesh.clearAllInternalData",
            "identity0.store.panicClearPrivateConversations",
            "identity0.store.clear",
            "session0.messages.clearAllMessages",
            "session0.channels.clearAllChannels",
            "session0.privateChats.clearAllPrivateChats",
            "frame",
            "session0.geohash.panicReleaseRelays",
            "identity0.mesh.setDelegate(none)",
            "identity0.mesh.stopServices",
            "session ended",
            "identity ended",
            "sandbox wiped (marker set)",
            "cleanup notifications",
            "cleanup clipboard",
        )
        val new = listOf(
            "identity started",
            "session1.data.saveNickname",
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
    fun theMarkerIsSetBeforeAnyStage() {
        panic.run()

        assertEquals("identity0.location.disableLocationServices(marker set)", log.first())
        assertTrue(log.contains("sandbox wiped (marker set)"))
    }

    @Test
    fun admissionStopsAndTheScreenBlanksBeforeTheIdentityEnds() {
        panic.run()

        val before = listOf(
            "session0.geohash.cancelSendsInFlight",
            "identity0.mesh.clearAllInternalData",
            "identity0.store.panicClearPrivateConversations",
            "identity0.store.clear",
            "session0.messages.clearAllMessages",
            "session0.channels.clearAllChannels",
            "session0.privateChats.clearAllPrivateChats",
            "frame",
        ).map(log::indexOf)
        val sessionEnded = log.indexOf("session ended")
        val identityEnded = log.indexOf("identity ended")
        val wiped = log.indexOf("sandbox wiped (marker set)")
        assertTrue(before.all { it >= 0 })
        assertEquals("in that order", before.sorted(), before)
        assertTrue(before.last() < sessionEnded)
        assertTrue(sessionEnded < identityEnded)
        assertTrue("the wipe runs once no identity exists", identityEnded < wiped)
    }

    @Test
    fun aConfirmedWipeBuildsTheNextIdentityAndNeverRestarts() {
        val oldSession = session

        panic.run()

        assertEquals(0, restarts)
        assertFalse(marker.pending)
        assertEquals(2, identities.size)
        assertNotSame(oldSession, session)
        assertSame(identities.last(), session.identity)
        verify(session.messages).addSystemMessage(completed)
        verify(oldSession.messages, never()).addSystemMessage(any())
        assertEquals(1, identityHolder.generation.value)
        assertEquals(1, sessionHolder.generation.value)
        assertFalse(runner.isRunning)
        assertFalse(identityHolder.ending.value)
    }

    @Test
    fun anUnconfirmedWipeRestartsAndLeavesTheMarker() {
        wipeResult = WipeResult(leftovers = listOf("files/synthetic"), aliasesLeft = 0)
        val oldSession = session

        panic.run()

        assertEquals(1, restarts)
        assertTrue(marker.pending)
        assertEquals("no identity is built over storage that may still hold data", 1, identities.size)
        assertFalse(log.contains("identity started"))
        assertTrue("the system-side cleanups still run before the restart", log.indexOf("cleanup clipboard") < log.indexOf("restart"))
        assertEquals("restart", log.last())
        verify(oldSession.messages, never()).addSystemMessage(any())
        assertFalse(runner.isRunning)
    }

    @Test
    fun aKeystoreAliasLeftOverRestarts() {
        wipeResult = WipeResult(leftovers = emptyList(), aliasesLeft = 1)

        panic.run()

        assertEquals(1, restarts)
        assertTrue(marker.pending)
    }

    @Test
    fun aThrowingWipeRestartsAndLeavesTheMarker() {
        onWipe = { error("synthetic wipe failure") }

        panic.run()

        assertEquals(1, restarts)
        assertTrue(marker.pending)
        assertEquals(1, identities.size)
    }

    @Test
    fun aFailedTeardownCleanupRestarts() {
        failedTeardownCleanups = listOf("SyntheticCleanup")

        panic.run()

        assertEquals(1, restarts)
        assertTrue("the next launch wipes again", marker.pending)
        assertTrue("the wipe still ran", log.contains("sandbox wiped (marker set)"))
        assertEquals(1, identities.size)
        assertFalse(log.contains("identity started"))
    }

    @Test
    fun aBuildFailureAfterAConfirmedWipeRestarts() {
        identityBuildFailures = 1

        panic.run()

        assertEquals(1, restarts)
        assertTrue("the next launch wipes again", marker.pending)
        assertFalse(log.contains("identity started"))
        assertFalse(runner.isRunning)
        assertFalse(identityHolder.ending.value)
    }

    @Test
    fun aFailedSystemCleanupReportsIncompleteWithoutRestarting() {
        notificationsCleanup.result = { false }

        panic.run()

        assertEquals(0, restarts)
        assertTrue("the other cleanups still run", log.contains("cleanup clipboard"))
        verify(session.messages).addSystemMessage(incomplete)
        verify(session.messages, never()).addSystemMessage(completed)
    }

    @Test
    fun aThrowingSystemCleanupReportsIncompleteWithoutRestarting() {
        notificationsCleanup.result = { error("synthetic cleanup failure") }

        panic.run()

        assertEquals(0, restarts)
        assertTrue(log.contains("cleanup clipboard"))
        verify(session.messages).addSystemMessage(incomplete)
    }

    @Test
    fun aThrowFromTheMeshClearReportsIncomplete() {
        doAnswer { error("synthetic") }.whenever(identity.mesh).clearAllInternalData()

        panic.run()

        assertEquals(0, restarts)
        assertEquals(2, identities.size)
        verify(session.messages).addSystemMessage(incomplete)
        verify(session.messages, never()).addSystemMessage(completed)
    }

    @Test
    fun aFailedConversationClearStillWipesByLocationAndReportsIncomplete() {
        databaseWipe = { false }

        panic.run()

        assertTrue(log.contains("sandbox wiped (marker set)"))
        assertEquals(2, identities.size)
        verify(session.messages).addSystemMessage(incomplete)
    }

    @Test
    fun aMarkerThatCannotBeWrittenStillWipesAndCannotReportCompleted() {
        blockMarker()

        panic.run()

        assertTrue(log.contains("sandbox wiped (marker unset)"))
        assertEquals(0, restarts)
        assertEquals(2, identities.size)
        verify(session.messages).addSystemMessage(incomplete)
        verify(session.messages, never()).addSystemMessage(completed)
    }

    @Test
    fun anUnconfirmedWipeWritesAMarkerThatFailedAtTheStart() {
        blockMarker()
        onWipe = ::unblockMarker
        wipeResult = WipeResult(leftovers = listOf("files/synthetic"), aliasesLeft = 0)

        panic.run()

        assertTrue("written again before the next read could build", marker.pending)
        assertEquals(1, restarts)
    }

    @Test
    fun aMarkerThatCanNeverBeWrittenStillRestartsAnUnconfirmedWipe() {
        blockMarker()
        wipeResult = WipeResult(leftovers = listOf("files/synthetic"), aliasesLeft = 0)

        panic.run()

        assertFalse(marker.pending)
        assertEquals(1, restarts)
    }

    @Test
    fun withNoSessionTheIdentityIsStillRebuilt() {
        val hold = CompletableDeferred<Boolean>()
        databaseWipe = { hold.await() }
        val oldSession = session

        panic.run()
        lifecycle.clear()
        hold.complete(true)

        assertEquals(0, restarts)
        assertEquals(2, identities.size)
        assertEquals("no session is built for nobody", 1, sessions.size)
        assertTrue(log.contains("identity started"))
        assertTrue(log.contains("identity1.mesh.startServices"))
        assertTrue(log.contains("identity1.mesh.sendBroadcastAnnounce"))
        assertFalse("the next session makes its own nickname", log.any { it.endsWith(".saveNickname") })
        assertNull(identities.last().mesh.delegate)
        verify(oldSession.messages, never()).addSystemMessage(any())
    }

    @Test
    fun `the screen blanks before the identity ends`() {
        panic.run()

        val blanked = listOf("identity0.store.clear", "session0.messages.clearAllMessages", "session0.channels.clearAllChannels", "session0.privateChats.clearAllPrivateChats")
        val frame = log.indexOf("frame")
        assertTrue(blanked.all { log.indexOf(it) in 0 until frame })
        assertTrue(frame < log.indexOf("identity ended"))
    }

    @Test
    fun `a throw while reading the old objects shows incomplete and never completed`() {
        readFails = true

        panic.run()

        verify(session.messages).addSystemMessage(incomplete)
        verify(session.messages, never()).addSystemMessage(completed)
        assertTrue("the stages that can run still do", log.contains("identity0.store.panicClearPrivateConversations"))
        assertFalse(identityHolder.ending.value)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `a throw while blanking the screen does not stop the wipe`() {
        frameFails = true

        panic.run()

        assertTrue(log.contains("sandbox wiped (marker set)"))
        verify(session.messages).addSystemMessage(incomplete)
        verify(session.messages, never()).addSystemMessage(completed)
        assertFalse(identityHolder.ending.value)
        assertFalse(runner.isRunning)
    }

    @Test
    fun `a stage that throws counts as not confirming and the rest still run`() {
        val oldSession = session
        doAnswer { error("synthetic") }.whenever(oldSession.messages).clearAllMessages()

        panic.run()

        assertTrue(log.contains("session0.channels.clearAllChannels"))
        verify(session.messages).addSystemMessage(incomplete)
    }

    @Test
    fun `the process-wide relays are released, and a throw there reports incomplete`() {
        val oldSession = session
        doAnswer { error("synthetic") }.whenever(oldSession.geohash).panicReleaseRelays()

        panic.run()

        assertEquals(0, restarts)
        verify(session.messages).addSystemMessage(incomplete)
    }

    @Test
    fun `the nickname reaches the new session's storage and state before the mesh announces with it`() {
        panic.run()

        val announce = log.indexOf("identity1.mesh.sendBroadcastAnnounce")
        assertTrue(log.indexOf("session1.data.saveNickname") < announce)
        assertTrue(log.indexOf("session1.state.setNickname") < announce)
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
        assertEquals(0, restarts)
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
        val real = TestMeshStores(application).appStateStore
        identity.store = real
        val fresh = TestMeshStores(application).appStateStore
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
    fun `a start-up failure still announces incomplete in the new session`() {
        startupFails = true

        panic.run()

        verify(session.messages).addSystemMessage(incomplete)
        assertEquals(0, restarts)
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

    private fun message(id: String) = BitchatMessage(
        id = id,
        sender = "synthetic",
        content = "synthetic",
        timestamp = Date(),
        isPrivate = true,
    )
}
