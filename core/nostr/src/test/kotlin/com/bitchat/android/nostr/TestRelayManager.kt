package com.bitchat.android.nostr

import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.geohash.LiveLocationPrivacyGate
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/** Clients for a test that must never open a socket or make a request. */
internal object NoHttpClients : NostrHttpClients {
    override fun httpClient() = error("no HTTP in this test")
    override fun webSocketClient() = error("no relay sockets in this test")
    override val routeChanges = emptyFlow<Unit>()
}

/** A relay manager of the test's own, so nothing carries over between tests. Needs Robolectric. */
internal fun newRelayManager(
    clients: NostrHttpClients = NoHttpClients,
    dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(),
) = NostrRelayManager(
    appContext = ApplicationProvider.getApplicationContext(),
    httpClients = clients,
    relayDirectory = RelayDirectory(NoHttpClients, dispatcher),
    liveLocationGate = LiveLocationPrivacyGate(),
    eventDeduplicator = NostrEventDeduplicator(),
    ioDispatcher = dispatcher,
)
