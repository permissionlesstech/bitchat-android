package com.bitchat.android.features.voice

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.mesh.MessageHandler
import com.bitchat.android.mesh.MessageHandlerDelegate
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.noise.AuthenticatedNoiseSession
import com.bitchat.android.noise.NoiseDecryptionResult
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.kotlin.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class LiveVoicePanicLifecycleRobolectricTest {
    private lateinit var app: Application
    private lateinit var manager: LiveVoiceManager
    private lateinit var timer: TestScope
    private val handlers = mutableListOf<MessageHandler>()
    private var previousManager: Any? = null
    private val singleton = LiveVoiceManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
    private val localPeer = "1111222233334444"
    private val remotePeer = "5555666677778888"
    private val burstID = ByteArray(8) { (it + 1).toByte() }

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        timer = TestScope()
        manager = LiveVoiceManager(app, timer.backgroundScope)
        previousManager = singleton.get(null)
        singleton.set(null, manager)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        LiveVoicePreferences.setEnabled(app, true)
    }

    @After fun tearDown() {
        handlers.forEach { it.shutdown() }
        manager.reset()
        timer.backgroundScope.cancel()
        singleton.set(null, previousManager)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        FileUtils.clearAllMedia(app)
    }

    private fun payload(sequence: Int = 1, kind: VoiceBurstPacket.Kind = VoiceBurstPacket.Kind.Frames(listOf(byteArrayOf(1, 2, 3)))) =
        VoiceBurstPacket.create(burstID, sequence, kind)!!.encode()

    private fun count(dm: Boolean) = if (dm) AppStateStore.privateMessages.value.values.sumOf { it.size } else AppStateStore.publicMessages.value.size

    private fun receiver(): MessageHandler {
        val delegate = mock<MessageHandlerDelegate>()
        whenever(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(delegate.getPeerNickname(remotePeer)).thenReturn("synthetic-sender")
        whenever(delegate.getPeerInfo(remotePeer)).thenReturn(PeerInfo(remotePeer, "synthetic-sender", true, true, null, null, true, System.currentTimeMillis()))
        whenever(delegate.decryptFromPeer(any(), eq(remotePeer))).thenAnswer { call ->
            NoiseDecryptionResult(call.getArgument(0), AuthenticatedNoiseSession(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        }
        return MessageHandler(localPeer, app).also { it.delegate = delegate; handlers += it }
    }

    private suspend fun deliver(handler: MessageHandler, dm: Boolean, bytes: ByteArray = payload()): Boolean {
        val packet = BitchatPacket(
            type = if (dm) MessageType.NOISE_ENCRYPTED.value else MessageType.VOICE_FRAME.value,
            senderID = ByteArray(8) { 0x55 },
            recipientID = if (dm) byteArrayOf(0x11, 0x11, 0x22, 0x22, 0x33, 0x33, 0x44, 0x44) else SpecialRecipients.BROADCAST,
            timestamp = System.currentTimeMillis().toULong(),
            ttl = 3u,
            payload = if (dm) NoisePayload(NoisePayloadType.VOICE_FRAME, bytes).encode() else bytes
        )
        val routed = RoutedPacket(packet, remotePeer, "synthetic-link")
        return if (dm) handler.handleNoiseEncrypted(routed) else handler.handlePublicVoiceFrame(routed)
    }

    private fun wipe() = runBlocking {
        manager.suspendForPanic()
        assertTrue(AppStateStore.panicClearPrivateConversations())
        AppStateStore.clear()
        assertTrue(FileUtils.clearAllMedia(app))
    }

    private fun pendingAssemblyDoesNotReturn(dm: Boolean, gap: Boolean = false) = runBlocking {
        val handler = receiver()
        assertTrue(deliver(handler, dm, payload(if (gap) 2 else 1)))
        assertEquals(1, count(dm))
        if (gap) assertTrue(deliver(handler, dm, payload(3, VoiceBurstPacket.Kind.End(2, 100))))
        timer.runCurrent()
        wipe()
        AppStateStore.resumePrivateConversationsAfterPanic()
        manager.resumeAfterPanic()
        timer.advanceTimeBy(if (gap) 551 else 10000)
        timer.runCurrent()
        assertEquals(0, count(dm))
        assertTrue(manager.liveMessageIDs.value.isEmpty())
    }

    @Test fun pendingPublicIdleCallbackDoesNotRestoreRow() = pendingAssemblyDoesNotReturn(false)
    @Test fun pendingPrivateIdleCallbackDoesNotRestoreRow() = pendingAssemblyDoesNotReturn(true)
    @Test fun pendingPublicGapCallbackDoesNotRestoreRow() = pendingAssemblyDoesNotReturn(false, true)
    @Test fun pendingPrivateGapCallbackDoesNotRestoreRow() = pendingAssemblyDoesNotReturn(true, true)

    private fun oldReceiverRejectedAndNewReceiverAccepted(dm: Boolean) = runBlocking {
        val old = receiver()
        assertTrue(deliver(old, dm))
        assertEquals(1, count(dm))
        wipe()
        // Reception remains suspended when a fresh handler is created.
        val fresh = receiver()
        assertTrue(deliver(fresh, dm))
        assertEquals(0, count(dm))
        assertTrue(manager.liveMessageIDs.value.isEmpty())
        AppStateStore.resumePrivateConversationsAfterPanic()
        manager.resumeAfterPanic()
        assertTrue(deliver(old, dm, payload(2)))
        assertEquals(0, count(dm))
        assertTrue(deliver(fresh, dm))
        assertEquals(1, count(dm))
    }

    @Test fun oldPublicReceiverIsRejectedAfterResumeWhileFreshReceiverWorks() = oldReceiverRejectedAndNewReceiverAccepted(false)
    @Test fun oldPrivateReceiverIsRejectedAfterResumeWhileFreshReceiverWorks() = oldReceiverRejectedAndNewReceiverAccepted(true)

    @Test fun privateCallbackBlockedInDecryptDelegateIsRejectedAfterResume() = runBlocking {
        val old = receiver()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delegate = old.delegate!!
        whenever(delegate.decryptFromPeer(any(), eq(remotePeer))).thenAnswer { call ->
            entered.complete(Unit)
            runBlocking { withTimeout(10000) { release.await() } }
            NoiseDecryptionResult(call.getArgument(0), AuthenticatedNoiseSession(ByteArray(32) { 1 }, ByteArray(32) { 2 }))
        }
        val pending = launch(kotlinx.coroutines.Dispatchers.Default) { assertTrue(deliver(old, true)) }
        withTimeout(10000) { entered.await() }
        wipe()
        AppStateStore.resumePrivateConversationsAfterPanic()
        manager.resumeAfterPanic()
        release.complete(Unit)
        withTimeout(10000) { pending.join() }
        assertEquals(0, count(true))
    }

    @Test fun twoPanicWipesRejectBothPreviousReceivers() = runBlocking {
        val first = receiver()
        wipe()
        AppStateStore.resumePrivateConversationsAfterPanic(); manager.resumeAfterPanic()
        val second = receiver()
        wipe()
        AppStateStore.resumePrivateConversationsAfterPanic(); manager.resumeAfterPanic()
        assertTrue(deliver(first, false)); assertTrue(deliver(second, false))
        assertEquals(0, count(false))
        assertTrue(deliver(receiver(), false)); assertEquals(1, count(false))
    }

    @Test fun publicBurstFinalizesWithoutPanic() = runBlocking {
        val handler = receiver()
        assertTrue(deliver(handler, false))
        assertTrue(deliver(handler, false, payload(2, VoiceBurstPacket.Kind.End(1, 100))))
        assertEquals(1, count(false))
        assertTrue(manager.liveMessageIDs.value.isEmpty())
        assertTrue(java.io.File(AppStateStore.publicMessages.value.single().content).exists())
    }
}
