package com.bitchat.android.navigation

import android.content.Context
import android.os.SystemClock
import android.view.ViewTreeObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bitchat.android.MainActivity
import com.bitchat.android.R
import com.bitchat.android.di.ChatSessionLifetimeEntryPoint
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.testhook.ChatSessionTestAccess
import com.bitchat.android.testhook.MeshTestAccess
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.PanicClear
import com.bitchat.android.ui.entryPoint
import java.util.Collections
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.isActive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A panic against what the screen is doing: drawing, stopped, or reopened during the wipe.
 *
 * No Compose test rule here. Its clock replaces the real frames, and a drawn frame is what the first
 * tests are about. Wipes the app's data, which the clean emulator is for.
 */
@RunWith(AndroidJUnit4::class)
class PanicAroundTheScreenTest {

    companion object {
        private const val PEER = "0123456789abcdef"

        @JvmStatic
        @BeforeClass
        fun completeOnboarding() = OnboardedApp.prepare()
    }

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get() = MeshTestAccess.of(context.applicationContext)
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        await("no panic left running") { !graph.identityHolder().ending.value }
        scenario?.close()
    }

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun await(what: String, timeoutMs: Long = 15_000, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for: $what" }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(20)
        }
    }

    private fun <T> ActivityScenario<MainActivity>.read(block: (MainActivity) -> T): T {
        var result: Result<T>? = null
        onActivity { result = runCatching { block(it) } }
        return result!!.getOrThrow()
    }

    private fun awaitChat(activity: ActivityScenario<MainActivity>) = await("the chat") {
        activity.read {
            it.startupGate.phase.value == com.bitchat.android.AppPhase.READY && it.navigator.backStack.lastOrNull() == ChatRoute
        }
    }

    private fun access(activity: MainActivity) = ChatSessionTestAccess.of(activity)

    private fun message(private: Boolean = false) = BitchatMessage(
        id = UUID.randomUUID().toString(),
        sender = "synthetic",
        content = "synthetic visible text",
        timestamp = Date(),
        isPrivate = private,
    )

    /** One drawn frame: whether the old identity's store was blank, and which session it belonged to. */
    private data class Frame(val blank: Boolean, val sessionGeneration: Int)

    private fun recordFrames(activity: MainActivity, store: AppStateStore): MutableList<Frame> {
        val frames = Collections.synchronizedList(ArrayList<Frame>())
        val holder = access(activity).chatSessionHolder()
        val listener = ViewTreeObserver.OnDrawListener {
            frames += Frame(
                blank = store.publicMessages.value.isEmpty() && store.privateMessages.value.isEmpty(),
                sessionGeneration = holder.generation.value,
            )
        }
        activity.window.decorView.viewTreeObserver.addOnDrawListener(listener)
        return frames
    }

    private fun panicAndAwaitBlankFrame(setUp: (MainActivity) -> Unit) {
        val activity = launch()
        awaitChat(activity)
        val store = graph.appStateStore()
        val before = activity.read { access(it).chatSessionHolder().generation.value }
        activity.onActivity { setUp(it) }
        // The screen has settled on what was seeded before the panic.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(200)

        val frames = activity.read { recordFrames(it, store) }
        activity.onActivity { access(it).panicClear().run() }
        await("the new session") { activity.read { access(it).chatSessionHolder().generation.value } == before + 1 }
        awaitChat(activity)

        val drawn = synchronized(frames) { frames.toList() }
        assertTrue(
            "a frame of the blanked old session is drawn before the switch: $drawn",
            drawn.any { it.blank && it.sessionGeneration == before },
        )
    }

    @Test
    fun aBlankFrameIsDrawnBeforeTheSwitch_fromTheMainChat() {
        panicAndAwaitBlankFrame {
            graph.appStateStore().addPublicMessage(message())
        }
    }

    @Test
    fun aBlankFrameIsDrawnBeforeTheSwitch_withAPrivateChatAndASheetOpen() {
        panicAndAwaitBlankFrame { activity ->
            val store = graph.appStateStore()
            store.addPublicMessage(message())
            assertTrue(store.addPrivateMessage(PEER, message(private = true)))
            activity.navigator.openPrivateChat(PEER)
            activity.navigator.goTo(SecurityVerificationRoute(PEER))
        }
    }

    /**
     * Composition pauses at ON_STOP, so the screens change only when the Activity restarts. Whatever
     * the stopped screen still holds can do nothing meanwhile: its session and identity are over.
     */
    @Test
    fun aPanicFinishingWhileStoppedShowsTheEmptyMainChatOnRestart() {
        val activity = launch()
        awaitChat(activity)
        lateinit var oldViewModel: ChatViewModel
        await("the chat ViewModel") {
            val found = activity.read { chatViewModel(it) }
            if (found != null) oldViewModel = found
            found != null
        }
        val oldScope = activity.read { access(it).chatSessionHolder().entryPoint<ChatSessionLifetimeEntryPoint>().scope() }
        val oldStore = graph.appStateStore()
        oldStore.addPublicMessage(message())
        activity.onActivity { it.navigator.openPrivateChat(PEER) }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val generation = graph.identityHolder().generation.value
        val panicClear = activity.read { access(it).panicClear() }
        val original = activity.read { it }

        activity.moveToState(Lifecycle.State.CREATED)
        activity.onActivity { panicClear.run() }
        await("the new identity") { graph.identityHolder().generation.value == generation + 1 && !panicClear.inProgress }

        assertFalse(oldScope.isActive)

        activity.moveToState(Lifecycle.State.RESUMED)
        awaitChat(activity)
        await("a new chat ViewModel") { activity.read { chatViewModel(it) }.let { it != null && it !== oldViewModel } }
        await("the old ViewModel cleared") { !oldViewModel.viewModelScope.isActive }

        assertSame(original, activity.read { it })
        assertEquals(listOf<Any>(ChatRoute), activity.read { it.navigator.snapshot() })
        val completed = context.getString(R.string.system_panic_completed)
        assertEquals(
            "only the outcome is on the new chat",
            listOf(completed),
            activity.read { access(it).chatState().messages.value.map { message -> message.content } },
        )
    }

    /**
     * Reopening during the wipe: the person leaves, the wipe goes on, and the new Activity comes up
     * with a new retained lifetime. It must not start a session on the identity being wiped, and
     * cannot start a second wipe. When the wipe ends it moves to the new identity by itself.
     */
    @Test
    fun reopeningDuringTheWipeStartsNoSessionOnTheEndingIdentityAndNoSecondPanic() {
        val first = launch()
        awaitChat(first)
        val store = graph.appStateStore()
        val repositoryField = AppStateStore::class.java.getDeclaredField("conversationRepository").apply { isAccessible = true }
        val original = repositoryField.get(store) as ConversationRepository?

        val executor = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val queued = AtomicInteger()
        val dispatcher = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                queued.incrementAndGet()
                executor.execute(block)
            }
        }
        val database = "synthetic-reopen-held-store.db"
        val held = ConversationRepository(context, dispatcher, database)
        executor.execute {
            entered.countDown()
            check(release.await(60, TimeUnit.SECONDS))
        }
        assertTrue(entered.await(5, TimeUnit.SECONDS))

        var oldPanic: PanicClear? = null
        try {
            repositoryField.set(store, held)
            first.onActivity {
                oldPanic = access(it).panicClear()
                oldPanic!!.run()
            }
            await("the wipe to be held") { queued.get() == 1 && oldPanic!!.inProgress }
            val generation = graph.identityHolder().generation.value
            assertTrue(graph.identityHolder().ending.value)

            first.close()
            val second = launch()
            await("the new Activity") { second.read { it.chatSessionHolder.generation.value } == 0 }

            val heldBackScope = second.read { it.chatSessionHolder.entryPoint<ChatSessionLifetimeEntryPoint>().scope() }
            assertFalse("the new session is over before it began", heldBackScope.isActive)
            second.onActivity { activity ->
                val panic = access(activity).panicClear()
                assertNotSame(oldPanic, panic)
                panic.run()
            }
            assertEquals("no second wipe", 1, queued.get())
            assertEquals(generation, graph.identityHolder().generation.value)

            release.countDown()
            await("the new identity") {
                graph.identityHolder().generation.value == generation + 1 && !oldPanic!!.inProgress
            }
            awaitChat(second)
            await("the new session") { second.read { it.chatSessionHolder.generation.value } >= 1 }
            await("a live scope") {
                second.read { it.chatSessionHolder.entryPoint<ChatSessionLifetimeEntryPoint>().scope() }.isActive
            }
            assertNotSame(store, graph.appStateStore())
            assertTrue(second.read { it.chatSessionHolder.current } !== null)
            val completed = context.getString(R.string.system_panic_completed)
            assertTrue(
                "nobody was left to show the outcome to",
                graph.appStateStore().publicMessages.value.none { it.content == completed },
            )
            assertNotNull(graph.meshServiceHolder().unifiedMeshService)
        } finally {
            release.countDown()
            await("the wipe to end") { oldPanic?.inProgress != true }
            repositoryField.set(store, original)
            held.closeForTest()
            executor.shutdown()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
            context.deleteDatabase(database)
        }
    }

    private fun chatViewModel(activity: MainActivity): ChatViewModel? =
        entryViewModels(activity.screenViewModels.viewModelStore).filterIsInstance<ChatViewModel>().singleOrNull()
}
