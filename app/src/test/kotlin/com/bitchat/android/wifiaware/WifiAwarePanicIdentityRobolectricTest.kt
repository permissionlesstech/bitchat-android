package com.bitchat.android.wifiaware

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.wifi.aware.WifiAwareManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.AndroidViewModel
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.R
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.features.voice.LiveVoiceManager
import com.bitchat.android.features.voice.LiveVoicePreferences
import com.bitchat.android.features.voice.LiveVoiceScope
import com.bitchat.android.features.voice.VoiceBurstPacket
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.UnifiedMeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.ui.ChatViewModel
import com.bitchat.android.ui.MeshDelegateHandler
import com.bitchat.android.board.BoardManager
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.geohash.LocationChannelManager
import com.bitchat.android.groups.GroupCoordinator
import com.bitchat.android.groups.GroupStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.ConversationListPreferences
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.services.MessageRouter
import com.bitchat.android.services.MessageOutboxStore
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/** Real panic entry, storage suspension, Wi-Fi/core and final recreation; synthetic crypto and radio. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class WifiAwarePanicIdentityRobolectricTest {
    private val oldFingerprint = "11".repeat(32)
    private val newFingerprint = "22".repeat(32)

    private fun get(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(owner)
    private fun set(owner: Any, name: String, value: Any?) = owner.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(owner, value)
    private fun controllerField(name: String) = WifiAwareController::class.java.getDeclaredField(name)
        .apply { isAccessible = true }

    private fun startupDiagnostics(): String = ShadowLog.getLogs().joinToString("\n") { item ->
        "${item.tag}: ${item.msg} ${item.throwable?.stackTraceToString().orEmpty()}"
    }

    private fun probe(failFirst: Boolean = false, ordinary: Boolean = false) = runBlocking {
        ShadowLog.clear()
        ShadowLog.stream = System.out
        val app = ApplicationProvider.getApplicationContext<Application>()
        val ctx = spy(app)
        val pm = spy(app.packageManager)
        val aware = mock(WifiAwareManager::class.java)
        val location = mock(LocationManager::class.java)
        val connectivity = mock(ConnectivityManager::class.java)
        doReturn(ctx).`when`(ctx).applicationContext
        // This JVM fixture has no packaged Android string resources. Preserve real
        // NotificationManager construction while supplying only its channel labels.
        doReturn("Synthetic direct messages").`when`(ctx).getString(R.string.notification_channel_direct_messages)
        doReturn("Synthetic direct message description").`when`(ctx).getString(R.string.notification_channel_direct_messages_description)
        doReturn("Synthetic geohash").`when`(ctx).getString(R.string.notification_channel_geohash)
        doReturn("Synthetic geohash description").`when`(ctx).getString(R.string.notification_channel_geohash_description)
        doReturn(pm).`when`(ctx).packageManager
        doReturn(true).`when`(pm).hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        doReturn(aware).`when`(ctx).getSystemService(WifiAwareManager::class.java)
        doReturn(location).`when`(ctx).getSystemService(Context.LOCATION_SERVICE)
        doReturn(connectivity).`when`(ctx).getSystemService(Context.CONNECTIVITY_SERVICE)
        `when`(aware.isAvailable).thenReturn(true)
        `when`(location.isLocationEnabled).thenReturn(true)
        var persistedFingerprint = if (ordinary) newFingerprint else oldFingerprint
        val cryptoInstances = mutableListOf<EncryptionService>()
        val services = mutableListOf<WifiAwareMeshService>()
        val unifiedInstances = mutableListOf<UnifiedMeshService>()
        var wifiIdentityClears = 0
        val timer = TestScope()
        val previousPanic = controllerField("panicSuspended").get(null)
        val previousPanicGeneration = controllerField("panicGeneration").get(null)
        val previousContext = controllerField("appContext").get(null)
        val previousScope = controllerField("scope").get(null)
        @Suppress("UNCHECKED_CAST")
        val enabled = controllerField("_enabled").get(null) as MutableStateFlow<Boolean>
        val previousEnabled = enabled.value
        val inFlight = controllerField("restartInFlight").get(null) as AtomicBoolean
        val requested = controllerField("restartRequested").get(null) as AtomicBoolean
        val previousInFlight = inFlight.getAndSet(false)
        val previousRequested = requested.getAndSet(false)
        val singleton = LiveVoiceManager::class.java.getDeclaredField("instance").apply { isAccessible = true }
        val previousVoice = singleton.get(null)
        val voice = LiveVoiceManager(app, timer.backgroundScope)
        singleton.set(null, voice)
        LiveVoicePreferences.setEnabled(app, true)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        val previousBluetooth = MeshServiceHolder.meshService
        val previousUnified = MeshServiceHolder.unifiedMeshService
        assertNull("Isolated test must start without a holder service", previousBluetooth)
        assertNull("Isolated test must start without a unified service", previousUnified)
        val uiScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(timer.testScheduler))
        val vm = mock(ChatViewModel::class.java, CALLS_REAL_METHODS)
        AndroidViewModel::class.java.getDeclaredField("application").apply { isAccessible = true }.set(vm, app)
        val initializedParent = object : ViewModel(uiScope) {}
        ViewModel::class.java.getDeclaredField("impl").apply { isAccessible = true }.let { it.set(vm, it.get(initializedParent)) }
        val wipeState = MutableStateFlow(PanicWipeState.IDLE)
        set(vm, "_panicWipeState", wipeState)
        set(vm, "panicWipeState", wipeState)
        set(vm, "groupCoordinator", mock(GroupCoordinator::class.java))
        val groups = mock(GroupStore::class.java)
        `when`(groups.wipe()).thenReturn(!failFirst)
        set(vm, "groupStore", groups)
        set(vm, "mediaSendingManager", mock(MediaSendingManager::class.java))
        set(vm, "messageManager", mock(MessageManager::class.java))
        set(vm, "channelManager", mock(ChannelManager::class.java))
        set(vm, "privateChatManager", mock(PrivateChatManager::class.java))
        set(vm, "dataManager", mock(DataManager::class.java))
        set(vm, "conversationListPreferences", mock(ConversationListPreferences::class.java))
        set(vm, "boardManager", mock(BoardManager::class.java))
        set(vm, "notificationManager", mock(com.bitchat.android.ui.NotificationManager::class.java))
        set(vm, "geohashViewModel", mock(GeohashViewModel::class.java))
        set(vm, "state", mock(ChatState::class.java))
        val uiDelegate = mock(MeshDelegateHandler::class.java)
        `when`(uiDelegate.getNickname()).thenReturn("synthetic-nickname")
        set(vm, "meshDelegateHandler", uiDelegate)
        val locationField = LocationChannelManager::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val previousLocation = locationField.get(null)
        locationField.set(null, mock(LocationChannelManager::class.java))
        val favoritesField = FavoritesPersistenceService::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val previousFavorites = favoritesField.get(null)
        favoritesField.set(null, mock(FavoritesPersistenceService::class.java))
        val seenField = SeenMessageStore::class.java.getDeclaredField("INSTANCE").apply { isAccessible = true }
        val previousSeen = seenField.get(null)
        seenField.set(null, mock(SeenMessageStore::class.java))
        val previousOutboxFactory = MessageRouter.outboxStoreFactory
        MessageRouter.outboxStoreFactory = { MessageOutboxStore(it, cipher = InMemoryConversationStorageCipher()) }
        // A separate dispatcher holds the real repository's withContext deletion until released.
        val storageScheduler = TestCoroutineScheduler()
        val repository = spy(ConversationRepository(app, StandardTestDispatcher(storageScheduler),
            "synthetic-panic-identity.db", InMemoryConversationStorageCipher()))
        val repositoryField = AppStateStore::class.java.getDeclaredField("conversationRepository").apply { isAccessible = true }
        val previousRepository = repositoryField.get(null)
        AppStateStore.setConversationRepositoryForTest(repository)
        var observedInterim = false
        suspend fun wipeAndFinish(expectFailure: Boolean) {
            val priorClearCalls = mockingDetails(repository).invocations.count { it.method.name == "clearAllAndWait" }
            vm.panicClearAllData()
            // Earlier cleanup also suspends. Observe the real repository call before
            // injecting startup, and leave its separate dispatcher paused until then.
            withTimeout(10000) {
                while (mockingDetails(repository).invocations.count { it.method.name == "clearAllAndWait" } == priorClearCalls) {
                    timer.runCurrent()
                    yield()
                }
            }
            assertEquals("Wipe must be suspended in repository deletion", PanicWipeState.ERASING, wipeState.value)
            assertNull("The original Wi-Fi service must be stopped before injecting replacement startup", WifiAwareController.getService())
            WifiAwareController.startIfPossible()
            WifiAwareController.restartIfStillEnabled()
            timer.runCurrent()
            WifiAwareController.getService()?.let { intermediate ->
                observedInterim = true
                if (!services.contains(intermediate)) services += intermediate
            }
            withTimeout(10000) {
                while (wipeState.value == PanicWipeState.ERASING) {
                    storageScheduler.runCurrent()
                    timer.runCurrent()
                    yield()
                }
            }
            assertEquals(if (expectFailure) PanicWipeState.FAILED else PanicWipeState.COMPLETE, wipeState.value)
            timer.runCurrent()
        }
        try {
            enabled.value = false
            WifiAwareController.stop()
            MeshServiceHolder.clear()
            controllerField("appContext").set(null, ctx)
            controllerField("scope").set(null, timer.backgroundScope)
            mockConstruction(SecureIdentityStateManager::class.java) { identity, _ ->
                `when`(identity.clearIdentityData()).thenReturn(true)
                `when`(identity.getVerifiedFingerprints()).thenReturn(emptySet())
            }.use {
                mockConstruction(EncryptionService::class.java) { crypto, _ ->
                    cryptoInstances += crypto
                    var localFingerprint = persistedFingerprint
                    `when`(crypto.getIdentityFingerprint()).thenAnswer { localFingerprint }
                    `when`(crypto.getStaticPublicKey()).thenReturn(ByteArray(32) { 1 })
                    `when`(crypto.getSigningPublicKey()).thenReturn(ByteArray(32) { 2 })
                    `when`(crypto.getCombinedPublicKeyData()).thenReturn(ByteArray(64) { 3 })
                    `when`(crypto.getEstablishedPeers()).thenReturn(emptyList())
                    `when`(crypto.getSessionsNeedingRekey()).thenReturn(emptyList())
                    doAnswer {
                        wifiIdentityClears++
                        persistedFingerprint = newFingerprint
                        localFingerprint = newFingerprint
                        null
                    }.`when`(crypto).clearPersistentIdentity()
                }.use {
                    mockConstruction(BluetoothMeshService::class.java) { bluetooth, _ ->
                        val captured = persistedFingerprint.take(16)
                        `when`(bluetooth.myPeerID).thenReturn(captured)
                        `when`(bluetooth.isReusable()).thenReturn(true)
                        `when`(bluetooth.getPeerNicknames()).thenReturn(emptyMap())
                        doAnswer { persistedFingerprint = newFingerprint; null }.`when`(bluetooth).clearAllEncryptionData()
                    }.use {
                        enabled.value = true
                        assertTrue("Fixture support prerequisite", WifiAwareSupport.evaluate(ctx).supported)
                        assertTrue("Fixture availability prerequisite", WifiAwareSupport.evaluate(ctx).available)
                        WifiAwareController.startIfPossible()
                        val initial = requireNotNull(WifiAwareController.getService()) { startupDiagnostics() }
                        services += initial
                        assertFalse(mockingDetails(initial).isMock)
                        assertFalse(mockingDetails(get(initial, "meshCore")).isMock)
                        assertEquals(persistedFingerprint.take(16), initial.myPeerID)
                        val beforeUnified = MeshServiceHolder.getUnifiedOrCreate(app)
                        unifiedInstances += beforeUnified
                        set(vm, "unifiedMeshService", beforeUnified)
                        set(vm, "meshService", MeshServiceHolder.meshService)
                        if (ordinary) {
                            ChatViewModel::class.java.getDeclaredMethod("recreateMeshServiceAfterPanic")
                                .apply { isAccessible = true }.invoke(vm)
                        } else {
                            wipeAndFinish(failFirst)
                            if (failFirst) {
                                WifiAwareController.startIfPossible()
                                WifiAwareController.restartIfStillEnabled()
                                timer.runCurrent()
                                assertNull("Failed wipe keeps Wi-Fi suspended", WifiAwareController.getService())
                                val rowsBefore = AppStateStore.publicMessages.value.size
                                assertTrue("Suspended voice frame is accepted and dropped locally", voice.handleFrame(
                                    "synthetic-peer", "synthetic-sender", LiveVoiceScope.PUBLIC_MESH,
                                    VoiceBurstPacket.create(ByteArray(8) { 3 }, 1,
                                        VoiceBurstPacket.Kind.Frames(listOf(byteArrayOf(1, 2, 3))))!!.encode(),
                                    1700000000000L, voice.currentReceiverGeneration()))
                                assertTrue("Failed wipe admits no live voice assembly", voice.liveMessageIDs.value.isEmpty())
                                assertEquals("Failed wipe admits no voice row", rowsBefore, AppStateStore.publicMessages.value.size)
                                `when`(groups.wipe()).thenReturn(true)
                                wipeAndFinish(false)
                            }
                        }
                        val freshUnified = MeshServiceHolder.unifiedMeshService!!
                        unifiedInstances += freshUnified
                        assertFalse("Final unified service must be real", mockingDetails(freshUnified).isMock)
                        val active = requireNotNull(WifiAwareController.getService()) {
                            "Real final startup failed before identity assertions:\n" + startupDiagnostics()
                        }
                        if (!services.contains(active)) services += active
                        val activeCore = get(active, "meshCore") as MeshCore
                        val activeCrypto = get(active, "encryptionService") as EncryptionService
                        val bluetoothID = MeshServiceHolder.meshService!!.myPeerID
                        val ownCryptoID = activeCrypto.getIdentityFingerprint().take(16)
                        val matchesBluetooth = active.myPeerID == bluetoothID
                        val matchesCrypto = active.myPeerID == ownCryptoID
                        val coreMatchesCrypto = activeCore.myPeerID == ownCryptoID
                        println("IDENTITY ordinary=$ordinary failFirst=$failFirst observedInterim=$observedInterim retainedInitial=${active === initial} " +
                            "wifiMatchesBluetooth=$matchesBluetooth wifiMatchesCrypto=$matchesCrypto coreMatchesCrypto=$coreMatchesCrypto " +
                            "wifiIdentityClears=$wifiIdentityClears actualWifi=true actualCore=true actualFinalRecreation=true")
                        assertTrue(voice.handleFrame("synthetic-peer", "synthetic-sender", LiveVoiceScope.PUBLIC_MESH,
                            VoiceBurstPacket.create(ByteArray(8) { 4 }, 1,
                                VoiceBurstPacket.Kind.Frames(listOf(byteArrayOf(1, 2, 3))))!!.encode(),
                            1700000000000L, voice.currentReceiverGeneration()))
                        assertEquals("Fresh voice admission must remain usable", 1, voice.liveMessageIDs.value.size)
                        assertTrue("Final Wi-Fi and Bluetooth identities must agree", matchesBluetooth)
                        assertTrue("Final Wi-Fi identity must match its encryption identity", matchesCrypto)
                        assertTrue("Final MeshCore identity must match its encryption identity", coreMatchesCrypto)
                        assertFalse("Panic entry must keep requested startup held while storage deletion is suspended", observedInterim)
                        if (!ordinary) {
                            assertNotSame("Successful panic creates a fresh Wi-Fi service", initial, active)
                            assertNotEquals("Successful panic must replace the old peer identity", initial.myPeerID, active.myPeerID)
                            assertEquals("Final Wi-Fi uses the replacement synthetic identity", newFingerprint.take(16), active.myPeerID)
                        }
                    }
                }
            }
        } finally {
            enabled.value = false
            WifiAwareController.stop()
            services.forEach { service ->
                (get(service, "serviceScope") as CoroutineScope).cancel()
                (get(service, "meshCore") as MeshCore).shutdown()
                (get(service, "listenerExec") as ExecutorService).shutdownNow()
            }
            unifiedInstances.distinct().forEach { unified ->
                (get(unified, "serviceScope") as CoroutineScope).cancel()
                (get(unified, "diagnosticsScope") as CoroutineScope).cancel()
            }
            MeshServiceHolder.clear()
            voice.reset()
            timer.backgroundScope.cancel()
            timer.runCurrent()
            uiScope.cancel()
            repository.closeForTest()
            repositoryField.set(null, previousRepository)
            MessageRouter.outboxStoreFactory = previousOutboxFactory
            locationField.set(null, previousLocation)
            favoritesField.set(null, previousFavorites)
            seenField.set(null, previousSeen)
            controllerField("panicSuspended").set(null, previousPanic)
            controllerField("panicGeneration").set(null, previousPanicGeneration)
            singleton.set(null, previousVoice)
            AppStateStore.resumePrivateConversationsAfterPanic()
            AppStateStore.clear()
            controllerField("appContext").set(null, previousContext)
            controllerField("scope").set(null, previousScope)
            enabled.value = previousEnabled
            inFlight.set(previousInFlight)
            requested.set(previousRequested)
        }
    }

    @Test fun panicEntryKeepsWifiHeldUntilFreshIdentityIsRecreated() = probe()
    @Test fun failedPanicKeepsWifiHeldAndSuccessfulRetryRestoresFreshIdentity() = probe(failFirst = true)
    @Test fun ordinaryStartupAndFinalRecreationKeepConsistentIdentity() = probe(ordinary = true)
}
