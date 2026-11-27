package com.bitchat.android.nostr

import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import com.bitchat.android.identity.scope.IdentityHolder
import com.bitchat.android.panic.testPanicMarker
import com.bitchat.android.mesh.PowerManager
import com.bitchat.android.services.TestMeshStores
import dagger.Lazy
import javax.inject.Provider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Clients for a test that must never open a socket or make a request. */
internal object NoHttpClients : NostrHttpClients {
    override fun httpClient() = error("no HTTP in this test")
    override fun webSocketClient() = error("no relay sockets in this test")
    override val routeChanges = emptyFlow<Unit>()
    override fun routeGeneration() = 0L
    override suspend fun awaitRoute(timeoutMs: Long) = true
}

/** A relay manager of the test's own, so nothing carries over between tests. Needs Robolectric. */
internal fun newRelayManager(
    clients: NostrHttpClients = NoHttpClients,
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
    deduplicator: Provider<NostrEventDeduplicator> = NostrEventDeduplicator().let { shared -> Provider { shared } },
    monotonicClockMs: () -> Long = { System.nanoTime() / 1_000_000L },
    backgroundState: StateFlow<Boolean>? = null,
): NostrRelayManager {
    val powerManager by lazy {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val store = TestMeshStores(context).appStateStore
        val identity = IdentityHolder({ error("no identity is built here") }, { emptyList() }, testPanicMarker())
        PowerManager(context, identity, Provider { store }, kotlinx.coroutines.Dispatchers.Default)
    }
    return NostrRelayManager(
        appContext = ApplicationProvider.getApplicationContext(),
        httpClients = clients,
        relayDirectory = RelayDirectory(NoHttpClients, dispatcher),
        liveLocationGate = LiveLocationPrivacyGate(),
        eventDeduplicator = deduplicator,
        ioDispatcher = dispatcher,
        powerManagerSource = Lazy { powerManager },
        monotonicClockMs = monotonicClockMs,
        backgroundStateOverride = backgroundState,
    )
}
