package com.bitchat.android.navigation

import android.os.Process
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.di.ChatIdentityEntryPoint
import com.bitchat.android.di.ChatSessionLifetimeEntryPoint
import com.bitchat.android.identity.scope.IdentityLifetimeEntryPoint
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.testhook.MeshTestAccess
import com.bitchat.android.testhook.TestHookDependencies
import com.bitchat.android.ui.entryPoint
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.isActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A panic ends the identity and the session and builds new ones, with the app running throughout.
 *
 * Wipes the app's data, which the clean emulator is for.
 */
@RunWith(AndroidJUnit4::class)
class PanicClearSessionTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    companion object {
        private const val PEER = "0123456789abcdef"

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private fun graph() = MeshTestAccess.of(rule.activity.applicationContext)

    private fun session() = ChatSessionTestAccess.of(rule.activity)

    private fun panicAndAwaitTheNewIdentity() {
        val holder = graph().identityHolder()
        val before = holder.generation.value
        rule.runOnUiThread { session().panicClear().run() }
        rule.waitUntil(timeoutMillis = 15_000) {
            holder.generation.value == before + 1 && !session().panicClear().inProgress
        }
        rule.awaitChat()
    }

    @Test
    fun aPanicClearMovesTheSessionOntoTheNewMesh() {
        rule.awaitChat()
        val before = sessionMesh()

        panicAndAwaitTheNewIdentity()

        val after = sessionMesh()
        val context = rule.activity.applicationContext
        assertNotSame(before, after)
        assertSame(meshServiceHolder().getUnifiedOrCreate(context), after)
        assertNotEquals(before.myPeerID, after.myPeerID)
        // The new mesh reports to the new session, and the old one to nobody.
        rule.waitUntil(timeoutMillis = 5_000) { after.delegate === rule.activity.chatMeshDelegate.get() }
        assertNull(before.delegate)
    }

    @Test
    fun aPanicClearReplacesTheIdentity() {
        rule.awaitChat()
        val graph = graph()
        val identity = graph.identityHolder().current
        val store = graph.appStateStore()
        val directory = graph.contactDirectory()
        val favorites = graph.favorites()
        val spamFilter = graph.spamFilter()
        val identityScope = graph.identityHolder().entryPoint<IdentityLifetimeEntryPoint>().scope()
        val sessionHolder = sessionHolder()
        val sessionScope = sessionHolder.entryPoint<ChatSessionLifetimeEntryPoint>().scope()
        val sessionObject = sessionHolder.current

        panicAndAwaitTheNewIdentity()

        assertNotSame(identity, graph.identityHolder().current)
        assertNotSame(store, graph.appStateStore())
        assertNotSame(directory, graph.contactDirectory())
        assertNotSame(favorites, graph.favorites())
        assertNotSame(spamFilter, graph.spamFilter())
        assertNotSame(sessionObject, sessionHolder.current)
        assertFalse(identityScope.isActive)
        assertFalse(sessionScope.isActive)
        assertTrue(graph.identityHolder().entryPoint<IdentityLifetimeEntryPoint>().scope().isActive)
        assertTrue(sessionHolder.entryPoint<ChatSessionLifetimeEntryPoint>().scope().isActive)
        // The old store's gate stays closed; only the new one admits messages.
        val message = com.bitchat.android.model.BitchatMessage(
            id = java.util.UUID.randomUUID().toString(),
            sender = "synthetic",
            content = "synthetic",
            timestamp = java.util.Date(),
            isPrivate = true,
        )
        assertFalse(store.addPrivateMessage("synthetic-peer", message))
        assertTrue(graph.appStateStore().privateMessages.value.isEmpty())
        // The outcome reached the new session's store, and from there the new session's state.
        val completed = rule.activity.getString(R.string.system_panic_completed)
        assertTrue(graph.appStateStore().publicMessages.value.any { it.content == completed })
        rule.waitUntil(timeoutMillis = 5_000) {
            var shown = false
            rule.runOnUiThread { shown = session().chatState().messages.value.any { it.content == completed } }
            shown
        }
        assertTrue(store.publicMessages.value.none { it.content == completed })
    }

    @Test
    fun aPanicClearKeepsTheAppRunning() {
        rule.awaitChat()
        val activity = rule.activity
        val process = Process.myPid()
        val leftResumed = java.util.Collections.synchronizedList(ArrayList<Lifecycle.Event>())
        rule.runOnUiThread {
            activity.lifecycle.addObserver(
                LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                        leftResumed += event
                    }
                },
            )
        }

        panicAndAwaitTheNewIdentity()

        assertSame(activity, rule.activity)
        assertEquals(Lifecycle.State.RESUMED, rule.activityRule.scenario.state)
        assertEquals(emptyList<Lifecycle.Event>(), leftResumed)
        assertEquals(process, Process.myPid())
        assertFalse(activity.isFinishing)
        assertEquals(listOf<Any>(ChatRoute), rule.backStack())
    }

    @Test
    fun aSecondPanicAfterTheFirstWorks() {
        rule.awaitChat()
        panicAndAwaitTheNewIdentity()
        val firstNewStore = graph().appStateStore()
        val firstNewMesh = sessionMesh()

        panicAndAwaitTheNewIdentity()

        assertNotSame(firstNewStore, graph().appStateStore())
        assertNotSame(firstNewMesh, sessionMesh())
        assertNotEquals(firstNewMesh.myPeerID, sessionMesh().myPeerID)
        assertNull(firstNewMesh.delegate)
    }

    /** The draft is saved as it is typed, so it has to go from storage as well as from the screen. */
    @Test
    fun aDraftHoldingPrivateTextDoesNotSurviveIntoTheNewSession() {
        rule.awaitChat()
        val draft = "synthetic private draft"
        rule.runOnUiThread { rule.activity.navigator.openPrivateChat(PEER) }
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER) }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction() and hasText("")).performTextInput(draft)
        rule.waitUntil(timeoutMillis = 5_000) { drafts().isNotEmpty() }

        panicAndAwaitTheNewIdentity()

        assertEquals(emptyMap<String, String>(), drafts())
        assertTrue(rule.onAllNodes(hasText(draft)).fetchSemanticsNodes().isEmpty())
        rule.onNode(hasSetTextAction() and hasText("")).assertExists()

        // Opening that conversation again finds nothing either.
        rule.runOnUiThread { rule.activity.navigator.openPrivateChat(PEER) }
        rule.waitUntil(timeoutMillis = 5_000) { rule.topOfStack() == PrivateChatRoute(PEER) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasText(draft)).fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun theMeshReportsToTheSessionOnceTheAppHasStarted() {
        rule.awaitChat()

        val mesh = meshServiceHolder().getUnifiedOrCreate(rule.activity.applicationContext)
        rule.waitUntil(timeoutMillis = 5_000) { mesh.delegate === rule.activity.chatMeshDelegate.get() }
    }

    private fun drafts(): Map<String, String> =
        graph().identityHolder().entryPoint<ChatIdentityEntryPoint>().conversationListPreferences().drafts.value

    private fun sessionHolder(): com.bitchat.android.ui.ChatSessionHolder {
        lateinit var holder: com.bitchat.android.ui.ChatSessionHolder
        rule.runOnUiThread { holder = session().chatSessionHolder() }
        return holder
    }

    /** The graph's holder, the one the session and the foreground service use. */
    private fun meshServiceHolder() = EntryPointAccessors.fromApplication(
        rule.activity.applicationContext,
        TestHookDependencies::class.java,
    ).meshServiceHolder()

    private fun sessionMesh(): MeshService {
        lateinit var mesh: MeshService
        rule.runOnUiThread { mesh = session().sessionMesh().unified }
        return mesh
    }
}
