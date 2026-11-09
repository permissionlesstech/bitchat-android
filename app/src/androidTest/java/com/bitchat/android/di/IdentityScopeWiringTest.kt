package com.bitchat.android.di

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.identity.scope.IdentityLifetimeEntryPoint
import com.bitchat.android.identity.scope.MeshIdentityEntryPoint
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.navigation.OnboardedApp
import com.bitchat.android.navigation.awaitChat
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.testhook.ConversationTestAccess
import com.bitchat.android.testhook.MeshTestAccess
import com.bitchat.android.ui.ChatSessionHolder
import com.bitchat.android.ui.ChatSessionMesh
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.GeohashSession
import com.bitchat.android.ui.NotificationManager
import com.bitchat.android.ui.entryPoint as sessionEntryPoint
import java.util.Date
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/**
 * An identity-scoped type reached through the process graph's bridge and straight from the
 * identity component is one instance, so a consumer on either path sees the same state.
 *
 * Reads the real application graph; it sends nothing and wipes nothing.
 */
@RunWith(AndroidJUnit4::class)
class IdentityScopeWiringTest {

    /**
     * What a receiver or service that starts with no Activity sees. Runs before [rule] launches
     * the Activity, on a background thread, because the notification reply reads there.
     * `Application.onCreate` has already built the component, so this pins one instance across
     * entry paths, not a first read.
     */
    private lateinit var holderBeforeAnyActivity: MeshServiceHolder

    @get:Rule(order = 0)
    val readBeforeAnyActivity = object : ExternalResource() {
        override fun before() {
            val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
            val background = Executors.newSingleThreadExecutor()
            try {
                holderBeforeAnyActivity =
                    background.submit(Callable { MeshTestAccess.of(context).meshServiceHolder() }).get()
            } finally {
                background.shutdown()
            }
        }
    }

    @get:Rule(order = 1)
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private fun graph() = MeshTestAccess.of(rule.activity.applicationContext)

    private fun identityEntry(): MeshIdentityEntryPoint =
        graph().identityHolder().entryPoint()

    private fun appIdentityEntry(): AppIdentityEntryPoint =
        graph().identityHolder().entryPoint()

    @Test
    fun theBridgeAndTheScopeGiveTheSameInstance() {
        rule.awaitChat()
        assertSame(graph().voiceWaveformCache(), identityEntry().voiceWaveformCache())
    }

    private fun sessionHolder(): ChatSessionHolder {
        lateinit var holder: ChatSessionHolder
        rule.runOnUiThread { holder = ChatSessionTestAccess.of(rule.activity).chatSessionHolder() }
        return holder
    }

    @Test
    fun theSessionBridgeAndTheSessionScopeGiveTheSameInstance() {
        rule.awaitChat()
        rule.runOnUiThread {
            val access = ChatSessionTestAccess.of(rule.activity)
            assertSame(rule.activity.chatSessionHolder, access.chatSessionHolder())
            val scoped = access.chatSessionHolder().sessionEntryPoint<ChatSessionEntryPoint>().secureIdentityStateManager()
            assertSame(scoped, access.secureIdentityStateManager())
            assertSame(scoped, access.secureIdentityStateManager())
        }
    }

    @Test
    fun aConfigurationChangeKeepsTheSession() {
        rule.awaitChat()
        val holder = sessionHolder()
        val session = holder.current

        rule.activityRule.scenario.recreate()
        rule.awaitChat()

        assertSame(holder, sessionHolder())
        assertSame(session, holder.current)
    }

    @Test
    fun leavingTheAppEndsTheSession() {
        rule.awaitChat()
        val holder = sessionHolder()
        val session = holder.current
        lateinit var geohashSession: GeohashSession
        lateinit var notifications: NotificationManager
        rule.runOnUiThread {
            val access = ChatSessionTestAccess.of(rule.activity)
            geohashSession = access.geohashSession()
            notifications = access.notificationManager()
        }
        val scope = holder.sessionEntryPoint<ChatSessionLifetimeEntryPoint>().scope()
        assertTrue(scope.isActive)
        assertTrue(geohashScope(geohashSession).isActive)
        assertTrue(liveNotificationManagers().contains(notifications))

        rule.activityRule.scenario.close()

        assertFalse("the session's scope ends with the session", scope.isActive)
        assertFalse("a class owning its own scope ends with the session", geohashScope(geohashSession).isActive)
        assertFalse("a session's manager leaves the process-wide registry", liveNotificationManagers().contains(notifications))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            assertFalse("a cleared session holder must refuse to build another", holder.replace())
            assertSame(session, holder.current)
        }
    }

    @Test
    fun leavingAndReopeningBuildsANewSessionOverTheSameStore() {
        rule.awaitChat()
        val graph = graph()
        val store = graph.appStateStore()
        lateinit var firstState: ChatState
        rule.runOnUiThread { firstState = ChatSessionTestAccess.of(rule.activity).chatState() }

        rule.activityRule.scenario.close()

        ActivityScenario.launch(MainActivity::class.java).use { reopened ->
            reopened.onActivity { activity ->
                assertNotSame(firstState, ChatSessionTestAccess.of(activity).chatState())
            }
            assertSame(store, graph.appStateStore())
        }
    }

    private fun geohashScope(session: GeohashSession): CoroutineScope =
        GeohashSession::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(session) as CoroutineScope

    @Suppress("UNCHECKED_CAST")
    private fun liveNotificationManagers(): Set<NotificationManager> {
        val field = NotificationManager::class.java.getDeclaredField("liveManagers").apply { isAccessible = true }
        return synchronized(field.get(null)!!) { (field.get(null) as Set<NotificationManager>).toSet() }
    }

    @Test
    fun aServiceThatStartsFirstAndTheUiShareTheOneMeshHolder() {
        rule.awaitChat()

        assertSame(holderBeforeAnyActivity, rule.activity.meshServiceHolder.get())
        assertSame(holderBeforeAnyActivity, appIdentityEntry().meshServiceHolder())

        lateinit var sessionMesh: ChatSessionMesh
        rule.runOnUiThread { sessionMesh = ChatSessionTestAccess.of(rule.activity).sessionMesh() }
        assertNotNull(holderBeforeAnyActivity.unifiedMeshService)
        assertSame(holderBeforeAnyActivity.unifiedMeshService, sessionMesh.unified)
    }

    @Test
    fun theTransportTogglesRegisteredAtStartUpAreTheCurrentIdentitys() {
        // Nothing re-registers the toggles for a later identity yet, so this holds only until
        // another test in the process has replaced the identity.
        assumeTrue(graph().identityHolder().generation.value == 0)
        assertSame(appIdentityEntry().meshTransportToggles(), graph().debugSettings().transportToggles)
    }

    @Test
    fun stoppingTheServiceAndReopeningKeepsTheStoresAndRouterAndBuildsANewMesh() {
        rule.awaitChat()
        val context = rule.activity.applicationContext
        val routerHolder = graph().messageRouterHolder()
        val meshHolder = graph().meshServiceHolder()
        val store = graph().appStateStore()

        // Chat start-up resolves the router.
        rule.waitUntil(timeoutMillis = 10_000) { routerHolder.peek()?.isSchedulerRunning == true }
        val router = routerHolder.peek()!!
        val meshBefore = meshHolder.unifiedMeshService
        assertNotNull(meshBefore)
        lateinit var sessionMesh: ChatSessionMesh
        rule.runOnUiThread { sessionMesh = ChatSessionTestAccess.of(rule.activity).sessionMesh() }
        assertSame(meshBefore, sessionMesh.unified)

        MeshForegroundService.stop(context)
        rule.waitUntil(timeoutMillis = 10_000) {
            !router.isSchedulerRunning && meshHolder.unifiedMeshService !== meshBefore
        }

        assertSame(router, routerHolder.peek())
        assertFalse(router.isSchedulerRunning)
        assertSame(store, graph().appStateStore())

        // What the next reader of the router does: it attaches, and that asks for a mesh.
        assertSame(router, graph().messageRouter())
        assertTrue(router.isSchedulerRunning)
        assertNotNull(meshHolder.unifiedMeshService)
        assertNotSame(meshBefore, meshHolder.unifiedMeshService)
        // The session keeps the mesh it was using; only a panic clear replaces it.
        assertSame(meshBefore, sessionMesh.unified)
    }

    @Test
    fun replacingTheIdentityCancelsItsScopeAndBuildsNewInstances() {
        rule.awaitChat()
        val holder = graph().identityHolder()
        val oldLifetime = holder.entryPoint<IdentityLifetimeEntryPoint>()
        val oldScope = oldLifetime.scope()
        val oldTeardown = oldLifetime.teardown()
        val oldMesh = identityEntry()
        assertTrue(oldScope.isActive)
        val generation = holder.generation.value

        val failed = holder.replace()

        assertTrue(failed.isEmpty())
        assertFalse(oldScope.isActive)
        assertEquals(generation + 1, holder.generation.value)
        val newLifetime = holder.entryPoint<IdentityLifetimeEntryPoint>()
        assertTrue(newLifetime.scope().isActive)
        assertNotSame(oldScope, newLifetime.scope())
        assertNotSame(oldTeardown, newLifetime.teardown())
        val newMesh = identityEntry()
        assertNotSame(oldMesh.appStateStore(), newMesh.appStateStore())
        assertNotSame(oldMesh.seenMessageStore(), newMesh.seenMessageStore())
        assertNotSame(oldMesh.spamFilter(), newMesh.spamFilter())
    }

    @Test
    fun theNewIdentitysConversationStoreOpensTheFileTheOldOneClosed() {
        rule.awaitChat()
        val holder = graph().identityHolder()
        // Fresh ids each run: the store keeps a tombstone for a deleted message and refuses its id.
        val run = java.util.UUID.randomUUID().toString()
        val message = { id: String ->
            BitchatMessage(id = "$run-$id", sender = "synthetic", content = id, timestamp = Date(), isPrivate = true)
        }
        val old = ConversationTestAccess.of(holder)
        assertTrue(
            runBlocking { old.upsertMessageAndWait("synthetic-peer", setOf("synthetic-peer"), "synthetic", message("before"), isRead = true) },
        )

        assertTrue(holder.replace().isEmpty())

        val new = ConversationTestAccess.of(holder)
        assertNotSame(old, new)
        // The old helper is closed, so it refuses; the new one opens the same file without a lock error.
        assertFalse(
            runBlocking { old.upsertMessageAndWait("synthetic-peer", setOf("synthetic-peer"), "synthetic", message("late"), isRead = true) },
        )
        assertTrue(
            runBlocking { new.upsertMessageAndWait("synthetic-peer", setOf("synthetic-peer"), "synthetic", message("after"), isRead = true) },
        )
        assertTrue(runBlocking { new.deleteConversationAndWait("synthetic-peer", setOf("synthetic-peer")) })
    }
}
