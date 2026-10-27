package com.bitchat.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.board.BoardManager
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.features.voice.*
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.groups.GroupCoordinator
import com.bitchat.android.groups.GroupStore
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.UnifiedMeshService
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
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.MessageOutboxStore
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.services.ConversationListPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class LiveVoicePanicEntryRobolectricTest {
    private fun field(owner: Any, name: String, value: Any?) {
        owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
    }

    // UI and transport collaborators are mocked to keep radio and public-relay
    // startup outside this panic-entry fixture.
    private fun probe(dm: Boolean, successfulWipe: Boolean = true, retry: Boolean = false): Int = runBlocking {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val wifiHoldFields = listOf("panicSuspended", "panicGeneration").map { name ->
            com.bitchat.android.wifiaware.WifiAwareController::class.java.getDeclaredField(name).apply { isAccessible = true }
        }
        val previousWifiHold = wifiHoldFields.map { it.get(null) }
        val timer = TestScope()
        val uiScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(timer.testScheduler))
        val vm = mock(ChatViewModel::class.java, CALLS_REAL_METHODS)
        AndroidViewModel::class.java.getDeclaredField("application").apply { isAccessible = true }.set(vm, app)
        val initializedParent = object : ViewModel(uiScope) {}
        ViewModel::class.java.getDeclaredField("impl").apply { isAccessible = true }.let { it.set(vm, it.get(initializedParent)) }
        val wipeState = MutableStateFlow(PanicWipeState.IDLE)
        field(vm, "_panicWipeState", wipeState)
        field(vm, "panicWipeState", wipeState)
        field(vm, "unifiedMeshService", mock(MeshService::class.java))
        field(vm, "groupCoordinator", mock(GroupCoordinator::class.java))
        val groups = mock(GroupStore::class.java)
        `when`(groups.wipe()).thenReturn(successfulWipe)
        field(vm, "groupStore", groups)
        field(vm, "mediaSendingManager", mock(MediaSendingManager::class.java))
        field(vm, "messageManager", mock(MessageManager::class.java))
        field(vm, "channelManager", mock(ChannelManager::class.java))
        field(vm, "privateChatManager", mock(PrivateChatManager::class.java))
        field(vm, "dataManager", mock(DataManager::class.java))
        field(vm, "conversationListPreferences", mock(ConversationListPreferences::class.java))
        field(vm, "boardManager", mock(BoardManager::class.java))
        field(vm, "notificationManager", mock(NotificationManager::class.java))
        field(vm, "geohashViewModel", mock(GeohashViewModel::class.java))
        field(vm, "state", mock(ChatState::class.java))
        val locationField = LocationChannelManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val priorLocation = locationField.get(null)
        locationField.set(null, mock(LocationChannelManager::class.java))
        val favoritesField = FavoritesPersistenceService::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val priorFavorites = favoritesField.get(null)
        favoritesField.set(null, mock(FavoritesPersistenceService::class.java))
        val seenField = SeenMessageStore::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val priorSeen = seenField.get(null)
        seenField.set(null, mock(SeenMessageStore::class.java))
        val priorOutboxFactory = MessageRouter.outboxStoreFactory
        MessageRouter.outboxStoreFactory = { MessageOutboxStore(it, cipher = InMemoryConversationStorageCipher()) }
        val voiceField = LiveVoiceManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val priorVoice = voiceField.get(null)
        val manager = LiveVoiceManager(app, timer.backgroundScope)
        voiceField.set(null, manager)
        LiveVoicePreferences.setEnabled(app, true)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        fun count() = if (dm) AppStateStore.privateMessages.value.values.sumOf { it.size } else AppStateStore.publicMessages.value.size
        try {
            mockConstruction(SecureIdentityStateManager::class.java) { identity, _ ->
                `when`(identity.clearIdentityData()).thenReturn(true)
            }.use {
                mockConstruction(BluetoothMeshService::class.java) { mesh, _ ->
                    `when`(mesh.isReusable()).thenReturn(true)
                }.use {
                    mockConstruction(UnifiedMeshService::class.java).use {
                        val target = if (dm) LiveVoiceScope.DIRECT_MESSAGE else LiveVoiceScope.PUBLIC_MESH
                        assertTrue(manager.handleFrame("synthetic-peer", "synthetic-sender", target,
                            VoiceBurstPacket.create(ByteArray(8) { (it + 1).toByte() }, 1,
                                VoiceBurstPacket.Kind.Frames(listOf(byteArrayOf(1, 2, 3))))!!.encode(), 1700000000000, manager.currentReceiverGeneration()))
                        assertEquals(1, count())
                        timer.runCurrent()
                        vm.panicClearAllData()
                        val terminal = withTimeout(10000) {
                            while (wipeState.value != PanicWipeState.COMPLETE && wipeState.value != PanicWipeState.FAILED) {
                                timer.runCurrent()
                                yield()
                            }
                            wipeState.value
                        }
                        assertEquals(if (successfulWipe) PanicWipeState.COMPLETE else PanicWipeState.FAILED, terminal)
                        assertEquals(0, count())
                        timer.advanceTimeBy(3001)
                        timer.runCurrent()
                        val restoredRows = count()
                        suspend fun admitFresh() {
                            val peer = "5555666677778888"
                            val delegate = mock(MessageHandlerDelegate::class.java)
                            `when`(delegate.getBroadcastRecipient()).thenReturn(SpecialRecipients.BROADCAST)
                            `when`(delegate.getPeerNickname(peer)).thenReturn("synthetic-sender")
                            `when`(delegate.getPeerInfo(peer)).thenReturn(PeerInfo(peer, "synthetic-sender", true, true, null, null, true, System.currentTimeMillis()))
                            val voice = VoiceBurstPacket.create(ByteArray(8) { (it + 10).toByte() }, 1,
                                VoiceBurstPacket.Kind.Frames(listOf(byteArrayOf(1, 2, 3))))!!.encode()
                            val bytes = if (dm) NoisePayload(NoisePayloadType.VOICE_FRAME, voice).encode() else voice
                            `when`(delegate.decryptFromPeer(bytes, peer)).thenReturn(
                                NoiseDecryptionResult(bytes, AuthenticatedNoiseSession(ByteArray(32) { 1 }, ByteArray(32) { 2 })))
                            val handler = MessageHandler("1111222233334444", app).also { it.delegate = delegate }
                            try {
                                val packet = BitchatPacket(
                                    type = if (dm) MessageType.NOISE_ENCRYPTED.value else MessageType.VOICE_FRAME.value,
                                    senderID = ByteArray(8) { 0x55 },
                                    recipientID = if (dm) byteArrayOf(0x11, 0x11, 0x22, 0x22, 0x33, 0x33, 0x44, 0x44) else SpecialRecipients.BROADCAST,
                                    timestamp = System.currentTimeMillis().toULong(), ttl = 3u, payload = bytes)
                                val routed = RoutedPacket(packet, peer, "synthetic-link")
                                assertTrue(if (dm) handler.handleNoiseEncrypted(routed) else handler.handlePublicVoiceFrame(routed))
                            } finally { handler.shutdown() }
                        }
                        admitFresh()
                        assertEquals(if (successfulWipe) 1 else 0, count())
                        assertEquals(successfulWipe, manager.liveMessageIDs.value.isNotEmpty())
                        if (retry) {
                            `when`(groups.wipe()).thenReturn(true)
                            vm.panicClearAllData()
                            withTimeout(10000) {
                                while (wipeState.value == PanicWipeState.ERASING) {
                                    timer.runCurrent()
                                    yield()
                                }
                            }
                            assertEquals(PanicWipeState.COMPLETE, wipeState.value)
                            assertEquals(0, count())
                            admitFresh()
                            assertEquals(1, count())
                        }
                        restoredRows
                    }
                }
            }
        } finally {
            manager.reset()
            uiScope.cancel()
            timer.backgroundScope.cancel()
            AppStateStore.resumePrivateConversationsAfterPanic()
            AppStateStore.clear()
            MessageRouter.outboxStoreFactory = priorOutboxFactory
            seenField.set(null, priorSeen)
            voiceField.set(null, priorVoice)
            locationField.set(null, priorLocation)
            favoritesField.set(null, priorFavorites)
            MeshServiceHolder.clear()
            wifiHoldFields.zip(previousWifiHold).forEach { (field, value) -> field.set(null, value) }
        }
    }

    @Test fun publicPendingVoiceDoesNotReturnAfterSuccessfulPanic() { assertEquals(0, probe(false)) }
    @Test fun privatePendingVoiceDoesNotReturnAfterSuccessfulPanic() { assertEquals(0, probe(true)) }
    @Test fun publicPendingVoiceDoesNotReturnAfterGroupStoreWipeFailure() { assertEquals(0, probe(false, false)) }
    @Test fun privatePendingVoiceDoesNotReturnAfterGroupStoreWipeFailure() { assertEquals(0, probe(true, false)) }
    @Test fun freshHandlerAdmitsPublicFrameAfterGroupStoreWipeRetry() { assertEquals(0, probe(false, false, true)) }
    @Test fun freshHandlerAdmitsPrivateFrameAfterGroupStoreWipeRetry() { assertEquals(0, probe(true, false, true)) }
}
