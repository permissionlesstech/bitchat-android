package com.bitchat.watch.ui

import android.Manifest
import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.lifecycleScope
import com.bitchat.android.features.voice.VoiceRecorder
import java.io.File
import java.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Push-to-talk must only capture and send audio while a finger is deliberately holding it, and
 * the 10 s cap must honor the same send-or-cancel choice the overlay is showing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class VoiceNotePushToTalkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var audioFile: File
    private val sent = mutableListOf<String>()
    private var recorderStarts = 0

    private lateinit var recorder: VoiceRecorder
    private val frameClock = BroadcastFrameClock()
    private val composeJob = Job()

    @Before
    fun setUp() {
        audioFile = tempFolder.newFile("synthetic.m4a")
        recorder = mock {
            on { start() } doAnswer {
                recorderStarts++
                audioFile
            }
            on { stop(false) } doReturn audioFile
            on { stop(true) } doReturn null
        }
    }

    @After
    fun tearDown() {
        composeJob.cancel()
    }

    @Test
    fun `granting the microphone permission does not record until a new press`() {
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val registry = GrantOnDemandRegistry()
        val host = launchHost()
        val voice = controllerOn(host)
        host.render {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                    override val activityResultRegistry: ActivityResultRegistry = registry
                }
            ) {
                ChatActionBar(onKeyboard = {}, voice = voice)
            }
        }
        pump(LAYOUT_SETTLE_MS)

        host.touch(MotionEvent.ACTION_DOWN, MIC_X, MIC_Y)
        pump()
        host.touch(MotionEvent.ACTION_UP, MIC_X, MIC_Y)
        pump()
        assertEquals("the press asks for the permission", 1, registry.launches)

        registry.grant()
        pump()

        assertEquals("no recorder may start without a finger on the mic", 0, recorderStarts)
        assertFalse(voice.recording)

        pump(MAX_VOICE_NOTE_MS + 1_000)
        assertTrue("nothing may be sent", sent.isEmpty())
    }

    @Test
    fun `ten second cap sends when the finger is away from the cancel target`() = runTest {
        val voice = virtualTimeController(this)

        voice.start()
        advanceTimeBy(MAX_VOICE_NOTE_MS + POLL_SLACK_MS)
        runCurrent()

        assertFalse(voice.recording)
        verify(recorder).stop(false)
        assertEquals(listOf(audioFile.absolutePath), sent)
    }

    @Test
    fun `ten second cap discards the note while the finger hovers the cancel target`() = runTest {
        val voice = virtualTimeController(this)

        voice.start()
        voice.hoveringCancel = true
        advanceTimeBy(MAX_VOICE_NOTE_MS + POLL_SLACK_MS)
        runCurrent()

        assertFalse(voice.recording)
        verify(recorder).stop(true)
        verify(recorder, never()).stop(false)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `cancel hover from a previous note does not carry into the next one`() = runTest {
        val voice = virtualTimeController(this)

        voice.start()
        voice.hoveringCancel = true
        advanceTimeBy(MAX_VOICE_NOTE_MS + POLL_SLACK_MS)
        runCurrent()

        voice.start()
        advanceTimeBy(MAX_VOICE_NOTE_MS + POLL_SLACK_MS)
        runCurrent()

        assertEquals(listOf(audioFile.absolutePath), sent)
    }

    @Test
    fun `finger resting on the cancel target at the cap cancels through the screen watcher`() {
        val (host, voice) = renderChat()

        voice.start()
        pump(LAYOUT_SETTLE_MS)
        host.touch(MotionEvent.ACTION_DOWN, CANCEL_X, CANCEL_Y)
        pump()

        pump(MAX_VOICE_NOTE_MS + POLL_SLACK_MS)

        assertFalse(voice.recording)
        verify(recorder).stop(true)
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `lifting after a cap stop leaves no stale finger for the next recording`() {
        val (host, voice) = renderChat()

        voice.start()
        pump(LAYOUT_SETTLE_MS)
        host.touch(MotionEvent.ACTION_DOWN, CANCEL_X, CANCEL_Y)
        pump()
        pump(MAX_VOICE_NOTE_MS + POLL_SLACK_MS)
        assertFalse(voice.recording)
        host.touch(MotionEvent.ACTION_UP, CANCEL_X, CANCEL_Y)
        pump()

        // A recording whose finger never moves produces no pointer events for the watcher, so
        // only the previous gesture could make it look like the cancel target is hovered.
        voice.start()
        pump(MAX_VOICE_NOTE_MS + POLL_SLACK_MS)

        assertFalse(voice.recording)
        assertEquals(listOf(audioFile.absolutePath), sent)
    }

    @Test
    fun `lifting away from the cancel target during a recording sends it`() {
        val (host, voice) = renderChat()

        voice.start()
        pump(LAYOUT_SETTLE_MS)
        pump(MIN_RECORDING_FOR_TEST_MS)
        host.touch(MotionEvent.ACTION_DOWN, AWAY_X, AWAY_Y)
        pump()
        host.touch(MotionEvent.ACTION_UP, AWAY_X, AWAY_Y)
        pump()

        assertFalse(voice.recording)
        verify(recorder).stop(false)
        assertEquals(listOf(audioFile.absolutePath), sent)
    }

    private fun renderChat(): Pair<ComponentActivity, VoiceNoteController> {
        val host = launchHost()
        val voice = controllerOn(host)
        host.render {
            ChatScaffold(
                messages = emptyList(),
                myPeerID = "0000000000000000",
                emptyText = "",
                voice = voice,
                onOpenImage = {},
                header = {},
                actionBar = {}
            )
        }
        pump(LAYOUT_SETTLE_MS)
        return host to voice
    }

    private fun controllerOn(host: ComponentActivity) = VoiceNoteController(
        context = host,
        scope = host.lifecycleScope,
        recorderFactory = { recorder },
        onSendVoice = { sent += it },
        clock = SystemClock::uptimeMillis
    )

    /**
     * Composes on a recomposer owned by this test. The default one runs on a process-wide
     * dispatcher bound to the first test's Choreographer, so later tests would get no frames.
     */
    private fun ComponentActivity.render(content: @Composable () -> Unit) {
        val context = Dispatchers.Main + frameClock + composeJob
        val recomposer = Recomposer(context)
        CoroutineScope(context).launch { recomposer.runRecomposeAndApplyChanges() }
        setContentView(
            ComposeView(this).apply {
                setParentCompositionContext(recomposer)
                setContent(content)
            },
            // Same layout params Activity.setContent uses.
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )
    }

    /** Advances the fake clock frame by frame, delivering recompositions and timers. */
    private fun pump(millis: Long = FRAME_MS) {
        var remaining = millis
        while (remaining > 0) {
            Snapshot.sendApplyNotifications()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
            frameClock.sendFrame(SystemClock.uptimeMillis() * NANOS_PER_MILLI)
            shadowOf(Looper.getMainLooper()).idle()
            remaining -= FRAME_MS
        }
    }

    private fun virtualTimeController(scope: TestScope): VoiceNoteController {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        return VoiceNoteController(
            context = RuntimeEnvironment.getApplication(),
            scope = CoroutineScope(dispatcher),
            recorderFactory = { recorder },
            onSendVoice = { sent += it },
            clock = { scope.testScheduler.currentTime }
        )
    }

    private fun launchHost(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun ComponentActivity.touch(action: Int, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, x, y, 0)
        // Straight to the Compose root: decor insets must not shift the synthetic coordinates.
        findViewById<ViewGroup>(android.R.id.content).getChildAt(0).dispatchTouchEvent(event)
        event.recycle()
    }

    /** Stands in for the system permission dialog: records the launch, grants on request. */
    private class GrantOnDemandRegistry : ActivityResultRegistry() {
        var launches = 0
            private set
        private var pendingRequest: Int? = null

        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?
        ) {
            launches++
            pendingRequest = requestCode
        }

        fun grant() {
            dispatchResult(checkNotNull(pendingRequest), true)
        }
    }

    private companion object {
        const val FRAME_MS = 16L
        const val NANOS_PER_MILLI = 1_000_000L
        const val POLL_SLACK_MS = 200L
        const val LAYOUT_SETTLE_MS = 500L
        const val MIN_RECORDING_FOR_TEST_MS = 700L

        // Robolectric's default display is 320x470 at density 1, so these are also dp.
        // The action bar starts at the top-left: 48dp keyboard, 10dp gap, 48dp mic.
        const val MIC_X = 82f
        const val MIC_Y = 24f

        // The overlay's cancel target sits centered in its upper half.
        const val CANCEL_X = 160f
        const val CANCEL_Y = 180f

        // Mid-height at the far left: inside the window, outside the inflated cancel target.
        const val AWAY_X = 10f
        const val AWAY_Y = 240f
    }
}
