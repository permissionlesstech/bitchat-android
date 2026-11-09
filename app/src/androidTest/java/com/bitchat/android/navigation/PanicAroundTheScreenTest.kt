package com.bitchat.android.navigation

import android.content.Context
import android.os.SystemClock
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.io.File
import kotlin.math.abs
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
import com.bitchat.android.ui.ChatSessionHolder
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.PanicClear
import com.bitchat.android.ui.entryPoint
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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
        private const val SLOW_WIPE_FILES = 6000
        private const val SEEDED_TEXT = "synthetic visible text"

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
        content = SEEDED_TEXT,
        timestamp = Date(),
        isPrivate = private,
    )

    /** One look at the window's pixels, and the session it was taken under. */
    private data class Sample(val shows: Boolean, val sessionGeneration: Int)

    private fun composeRoots(view: View, into: MutableList<RootForTest> = ArrayList()): List<RootForTest> {
        if (view is RootForTest) into += view
        if (view is ViewGroup) for (i in 0 until view.childCount) composeRoots(view.getChildAt(i), into)
        return into
    }

    private fun SemanticsNode.find(text: String): SemanticsNode? =
        if (config.getOrNull(SemanticsProperties.Text)?.any { it.text.contains(text) } == true) this
        else children.firstNotNullOfOrNull { it.find(text) }

    /** Where the seeded text sits in the window, or null when the Compose tree does not hold it. */
    private fun seededTextBounds(activity: MainActivity): Rect? =
        composeRoots(activity.window.decorView)
            .firstNotNullOfOrNull { it.semanticsOwner.unmergedRootSemanticsNode.find(SEEDED_TEXT) }
            ?.boundsInWindow
            ?.let { Rect(it.left.toInt(), it.top.toInt(), it.right.toInt(), it.bottom.toInt()) }

    /** Copies what the window's surface holds for [region]. Works while the main thread is blocked. */
    private fun copyPixels(activity: MainActivity, region: Rect, handler: Handler): Bitmap? {
        val bitmap = Bitmap.createBitmap(region.width(), region.height(), Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        PixelCopy.request(activity.window, region, bitmap, { result = it; done.countDown() }, handler)
        return if (done.await(2, TimeUnit.SECONDS) && result == PixelCopy.SUCCESS) bitmap else null
    }

    /** Pixels that differ clearly from the region's background colour: the ink of the text. */
    private fun ink(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val background = pixels.groupBy { it }.maxByOrNull { it.value.size }!!.key
        fun channel(color: Int, shift: Int) = (color shr shift) and 0xff
        return pixels.count { color ->
            listOf(0, 8, 16).any { abs(channel(color, it) - channel(background, it)) > 40 }
        }
    }

    /**
     * Looks at the window's pixels about every 10 ms, from before the panic until the new session
     * is up. A pixel copy shows what is on the display, not what the store or the Compose tree
     * holds: a row fading out has left composition and still shows.
     */
    private fun samplePixels(activity: MainActivity, holder: ChatSessionHolder, region: Rect, reference: Int, stop: () -> Boolean): List<Sample> {
        val thread = HandlerThread("pixel-samples").apply { start() }
        val handler = Handler(thread.looper)
        val samples = ArrayList<Sample>()
        try {
            while (!stop()) {
                val generation = holder.generation.value
                val bitmap = copyPixels(activity, region, handler)
                // A copy taken across the switch belongs to neither session.
                if (bitmap != null && holder.generation.value == generation) {
                    samples += Sample(shows = ink(bitmap) >= reference / 20, sessionGeneration = generation)
                }
                SystemClock.sleep(10)
            }
        } finally {
            thread.quitSafely()
        }
        return samples
    }

    private fun panicAndAwaitBlankFrame(setUp: (MainActivity) -> Unit) {
        val activity = launch()
        awaitChat(activity)
        val before = activity.read { access(it).chatSessionHolder().generation.value }
        activity.onActivity { setUp(it) }
        // The screen has settled on what was seeded before the panic.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(500)

        // Without this the test could pass on a screen that never showed the seeded text.
        val region = checkNotNull(activity.read { seededTextBounds(it) }) { "the seeded text is not on the screen" }
        val handler = HandlerThread("reference").apply { start() }
        val reference = try {
            ink(checkNotNull(copyPixels(activity.read { it }, region, Handler(handler.looper))) { "no pixel copy" })
        } finally {
            handler.quitSafely()
        }
        assertTrue("the seeded text is drawn before the panic: $reference", reference > 20)

        // Stages that block the main thread long enough to be seen: they are what the screen must
        // already be blank for.
        val incoming = File(context.filesDir, "files/incoming").apply { mkdirs() }
        repeat(SLOW_WIPE_FILES) { File(incoming, "synthetic-$it").createNewFile() }

        // The main thread is blocked during the wipe, so the sampler never calls into the Activity.
        val window = activity.read { it }
        val holder = activity.read { access(it).chatSessionHolder() }
        val panicStarted = AtomicBoolean(false)
        // Ends the sampler however the test ends: its own condition never holds if an await throws.
        val stopSampling = AtomicBoolean(false)
        val sampling = Executors.newSingleThreadExecutor()
        val seen = try {
            val samples = sampling.submit<List<Sample>> {
                samplePixels(window, holder, region, reference) {
                    stopSampling.get() || (panicStarted.get() && holder.generation.value > before)
                }
            }
            panicStarted.set(true)
            activity.onActivity { access(it).panicClear().run() }
            await("the new session") { activity.read { access(it).chatSessionHolder().generation.value } == before + 1 }
            awaitChat(activity)
            samples.get(30, TimeUnit.SECONDS).filter { it.sessionGeneration == before }
        } finally {
            stopSampling.set(true)
            sampling.shutdown()
            if (!sampling.awaitTermination(10, TimeUnit.SECONDS)) sampling.shutdownNow()
        }

        val blank = seen.count { !it.shows }
        assertTrue("the text was seen on the screen in some sample: $seen", seen.isNotEmpty())
        assertTrue(
            "the screen is blank before the switch: ${seen.count { it.shows }} of ${seen.size} samples of the old session still show the text",
            blank > 0,
        )
        // Once blank, the old session never draws the text again.
        val firstBlank = seen.indexOfFirst { !it.shows }
        assertTrue(
            "the text came back after the screen was blank: ${seen.map { it.shows }}",
            seen.drop(firstBlank).none { it.shows },
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
