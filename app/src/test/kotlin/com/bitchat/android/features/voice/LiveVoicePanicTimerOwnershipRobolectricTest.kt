package com.bitchat.android.features.voice

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.features.file.FileUtils
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveVoicePanicTimerOwnershipRobolectricTest {
    private fun probe(dm: Boolean, gap: Boolean) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val timer = TestScope()
        val manager = LiveVoiceManager(app, timer.backgroundScope)
        LiveVoicePreferences.setEnabled(app, true)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        val payload = VoiceBurstPacket.create(ByteArray(8) { 1 }, if (gap) 2 else 1,
            VoiceBurstPacket.Kind.Frames(listOf(byteArrayOf(1, 2, 3))))!!.encode()
        fun receive(): Boolean {
            val accepted = manager.handleFrame("synthetic-peer", "synthetic-sender",
            if (dm) LiveVoiceScope.DIRECT_MESSAGE else LiveVoiceScope.PUBLIC_MESH, payload, 1700000000000L, manager.currentReceiverGeneration())
            if (gap) assertTrue(manager.handleFrame("synthetic-peer", "synthetic-sender",
                if (dm) LiveVoiceScope.DIRECT_MESSAGE else LiveVoiceScope.PUBLIC_MESH,
                VoiceBurstPacket.create(ByteArray(8) { 1 }, 3, VoiceBurstPacket.Kind.End(2, 100))!!.encode(),
                1700000000000L, manager.currentReceiverGeneration()))
            return accepted
        }
        val error = AtomicReference<Throwable?>()
        val callback = Thread({
            try { timer.runCurrent() } catch (failure: Throwable) { error.set(failure) }
        }, "synthetic-timer")
        try {
            assertTrue(receive())
            timer.runCurrent()
            timer.advanceTimeBy(if (gap) 550 else 3000)
            synchronized(manager) {
                callback.start()
                val deadline = System.nanoTime() + 5_000_000_000L
                while (callback.state != Thread.State.BLOCKED && callback.isAlive && System.nanoTime() < deadline) Thread.yield()
                assertEquals("Timer must reach manager monitor before cancellation", Thread.State.BLOCKED, callback.state)
                assertTrue("Blocked callback must be inside LiveVoiceManager", callback.stackTrace.any { it.fileName == "LiveVoiceManager.kt" })
                manager.suspendForPanic()
                AppStateStore.clear()
                manager.resumeAfterPanic()
                assertTrue(receive())
                assertEquals(1, manager.liveMessageIDs.value.size)
            }
            callback.join(5000)
            assertFalse("Timer must complete after monitor release", callback.isAlive)
            error.get()?.let { throw it }
            assertEquals("Canceled old timer must leave fresh same-key assembly live", 1, manager.liveMessageIDs.value.size)
            timer.runCurrent()
            timer.advanceTimeBy(if (gap) 551 else 3001)
            timer.runCurrent()
            assertTrue("Fresh assembly must finalize on its own timer", manager.liveMessageIDs.value.isEmpty())
            assertEquals(1, if (dm) AppStateStore.privateMessages.value.values.sumOf { it.size } else AppStateStore.publicMessages.value.size)
        } finally {
            callback.join(5000)
            manager.reset()
            timer.backgroundScope.cancel()
            AppStateStore.clear()
            FileUtils.clearAllMedia(app)
        }
    }
    @Test fun canceledPublicIdleTimerLeavesReplacementLive() = probe(false, false)
    @Test fun canceledPrivateIdleTimerLeavesReplacementLive() = probe(true, false)
    @Test fun canceledPublicGapTimerLeavesReplacementLive() = probe(false, true)
    @Test fun canceledPrivateGapTimerLeavesReplacementLive() = probe(true, true)
}
