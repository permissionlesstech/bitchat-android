package com.bitchat.android.wifiaware

import android.content.Context
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.identity.scope.IdentityTeardown
import com.bitchat.android.panic.testPanicMarker
import com.bitchat.android.mesh.PeerFingerprintManager
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.services.TestMeshStores
import com.bitchat.android.services.VerificationService
import com.bitchat.android.services.meshgraph.MeshGraphService
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.ui.debug.DebugPreferenceManager
import com.bitchat.android.ui.debug.DebugSettingsManager
import dagger.Lazy
import kotlinx.coroutines.CoroutineDispatcher
import javax.inject.Provider
import kotlinx.coroutines.Dispatchers
import org.mockito.kotlin.mock

/**
 * A real [WifiAwareMeshService] for the controller and recovery tests, with its collaborators as
 * plain instances or mocks. Callers stub the constructions the JVM cannot run (the Keystore-backed
 * EncryptionService, MeshCore, the notification manager) with Mockito's mockConstruction, as
 * upstream's tests do, and attach a mock Bluetooth mesh to [holder], which the service reads at init.
 */
internal fun testWifiAwareMesh(
    context: Context,
    controller: WifiAwareController,
    holder: MeshServiceHolder,
    ioDispatcher: CoroutineDispatcher,
    transportBridgeService: TransportBridgeService = TransportBridgeService(),
    teardown: IdentityTeardown = IdentityTeardown(),
): WifiAwareMeshService {
    val stores = TestMeshStores(context)
    // Never started: it only answers the maintenance loop's schedule with its default profile.
    val powerManager = PowerManager(
        context,
        IdentityHolder({ error("no identity is built here") }, { emptyList() }, testPanicMarker()),
        Provider { stores.appStateStore },
        Dispatchers.Default,
    )
    return WifiAwareMeshService(
        context = context,
        meshServiceHolder = holder,
        messageRouterHolder = mock(),
        wifiAwareController = controller,
        appStateStore = stores.appStateStore,
        transportBridgeService = transportBridgeService,
        debugSettingsManager = Lazy { DebugSettingsManager(DebugPreferenceManager()) },
        spamFilter = SpamFilter(),
        transferProgressManager = mock(),
        peerFingerprintManager = PeerFingerprintManager(),
        liveVoiceManager = Lazy { mock() },
        meshGraphService = MeshGraphService(),
        favoritesPersistenceService = Lazy { stores.favorites },
        contactDirectory = stores.contactDirectory,
        verificationService = VerificationService(),
        seenMessageStore = Lazy { mock() },
        conversationListPreferences = mock(),
        powerManager = powerManager,
        ioDispatcher = ioDispatcher,
        teardown = teardown,
    )
}
