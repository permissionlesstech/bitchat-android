package com.bitchat.android.mesh

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.features.file.FileUtils
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.VoiceBurstPacket
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.MessageType
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = Application::class)
class PublicVoicePanicValidationRobolectricTest {
    private fun probe(mode: String, valid: Boolean) = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val timers = TestScope()
        val manager = LiveVoiceManager(app, timers.backgroundScope)
        val singleton = LiveVoiceManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val priorManager = singleton.get(null)
        singleton.set(null, manager)
        LiveVoicePreferences.setEnabled(app, true)
        AppStateStore.clear()
        val handler = MessageHandler(LOCAL, app)
        val ingress = mock<MessageHandlerDelegate>()
        whenever(ingress.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
        whenever(ingress.getPeerNickname(REMOTE)).thenReturn("synthetic-sender")
        whenever(ingress.getPeerInfo(REMOTE)).thenReturn(
            PeerInfo(REMOTE, "synthetic-sender", true, true, null, null, true, System.currentTimeMillis()))
        handler.delegate = ingress
        val observer = RecordingDelegate(handler)
        val processor = PacketProcessor(LOCAL).also { it.delegate = observer }
        try {
            if (mode != "active") manager.suspendForPanic()
            if (mode == "stale") manager.resumeAfterPanic()
            val bytes = if (valid) VoiceBurstPacket.create(ByteArray(8) { 1 }, 1,
                VoiceBurstPacket.Kind.Frames(listOf(byteArrayOf(1, 2, 3))))!!.encode()
                else byteArrayOf(1) // Too short for the 11-byte voice header.
            assertEquals("Fixture framing prerequisite", valid, VoiceBurstPacket.decode(bytes) != null)
            processor.processPacket(packet(MessageType.VOICE_FRAME, bytes))
            withTimeout(5_000) { observer.voiceHandled.await() }
            // The same peer actor cannot handle this marker until voice relay processing returns.
            processor.processPacket(packet(MessageType.ANNOUNCE, byteArrayOf(1)))
            withTimeout(5_000) { observer.drained.await() }
            println("PUBLIC_VOICE_VALIDATION mode=$mode valid=$valid accepted=${observer.accepted} " +
                "lastSeen=${observer.lastSeen} relays=${observer.relays} " +
                "assemblies=${manager.liveMessageIDs.value.size} rows=${AppStateStore.publicMessages.value.size}")
            assertEquals("Malformed public voice must be rejected before liveness or relay: $mode", valid, observer.accepted)
            assertEquals("Rejected public voice must not refresh last seen: $mode", if (valid) 1 else 0, observer.lastSeen)
            assertEquals("Rejected public voice must not reach relay delegate: $mode", if (valid) 1 else 0, observer.relays)
            val localAssembly = if (valid && mode == "active") 1 else 0
            assertEquals("Suspended or stale reception must not create local assembly", localAssembly, manager.liveMessageIDs.value.size)
            assertEquals("Suspended or stale reception must not create local row", localAssembly, AppStateStore.publicMessages.value.size)
        } finally {
            processor.shutdown()
            handler.shutdown()
            manager.reset()
            timers.backgroundScope.cancel()
            singleton.set(null, priorManager)
            AppStateStore.clear()
            FileUtils.clearAllMedia(app)
        }
    }

    @Test fun activeMalformedPublicFrameIsRejected() = probe("active", false)
    @Test fun suspendedMalformedPublicFrameIsRejected() = probe("suspended", false)
    @Test fun staleMalformedPublicFrameIsRejected() = probe("stale", false)
    @Test fun activeValidPublicFramePreservesAdmissionAndRelay() = probe("active", true)
    @Test fun suspendedValidPublicFramePreservesDropAndRelay() = probe("suspended", true)
    @Test fun staleValidPublicFramePreservesDropAndRelay() = probe("stale", true)

    private fun packet(type: MessageType, payload: ByteArray) = RoutedPacket(
        BitchatPacket(version = 1u, type = type.value, senderID = ByteArray(8) { 0x55 },
            recipientID = SpecialRecipients.BROADCAST, timestamp = System.currentTimeMillis().toULong(),
            payload = payload, ttl = 7u), REMOTE, "synthetic-link")

    private class RecordingDelegate(private val handler: MessageHandler) : PacketProcessorDelegate {
        val voiceHandled = CompletableDeferred<Unit>()
        val drained = CompletableDeferred<Unit>()
        @Volatile var accepted = false
        @Volatile var lastSeen = 0
        @Volatile var relays = 0
        // Signature/security and peer discovery are controlled boundaries in this ingress fixture.
        override fun validatePacketSecurity(packet: BitchatPacket, peerID: String) = true
        override fun updatePeerLastSeen(peerID: String) { lastSeen++ }
        override fun getPeerNickname(peerID: String) = "synthetic-sender"
        override fun getNetworkSize() = 1
        override fun getBroadcastRecipient(): ByteArray = SpecialRecipients.BROADCAST
        override fun handleVoiceFrame(routed: RoutedPacket): Boolean {
            accepted = handler.handlePublicVoiceFrame(routed)
            voiceHandled.complete(Unit)
            return accepted
        }
        override suspend fun handleAnnounce(routed: RoutedPacket): Boolean { drained.complete(Unit); return false }
        override fun handleNoiseHandshake(routed: RoutedPacket) = false
        override fun handleNoiseEncrypted(routed: RoutedPacket) = false
        override fun handleMessage(routed: RoutedPacket) = Unit
        override fun handleLeave(routed: RoutedPacket) = Unit
        override fun handleFragment(packet: BitchatPacket): BitchatPacket? = null
        override fun handleRequestSync(routed: RoutedPacket) = Unit
        override fun sendAnnouncementToPeer(peerID: String) = Unit
        override fun sendCachedMessages(peerID: String) = Unit
        override fun relayPacket(routed: RoutedPacket) { relays++ }
        override fun sendToPeer(peerID: String, routed: RoutedPacket) = false
    }

    private companion object {
        const val LOCAL = "1111222233334444"
        const val REMOTE = "5555666677778888"
    }
}
