package com.bitchat.android.di

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.identity.scope.MeshIdentityEntryPoint
import com.bitchat.android.identity.scope.entryPoint
import com.bitchat.android.navigation.OnboardedApp
import com.bitchat.android.navigation.awaitChat
import com.bitchat.android.service.MeshForegroundService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.testhook.MeshTestAccess
import com.bitchat.android.ui.ChatSessionMesh
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
}
