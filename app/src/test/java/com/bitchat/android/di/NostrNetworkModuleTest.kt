package com.bitchat.android.di

import com.bitchat.android.net.ArtiTorManager
import com.bitchat.android.net.OkHttpProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import okhttp3.OkHttpClient
import org.junit.Assert.assertSame
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

/**
 * Nostr's HTTP clients are the app's OkHttp clients, asked for on every call because the app
 * rebuilds them when the Tor mode changes, and they report Tor's route changes.
 */
class NostrNetworkModuleTest {

    @Test
    fun clients_areTheAppsOkHttpClientsOnEveryCall_andFollowTorRouteChanges() {
        val beforeRouteChange = mock<OkHttpClient>()
        val afterRouteChange = mock<OkHttpClient>()
        val webSocket = mock<OkHttpClient>()
        val routeChanges = MutableSharedFlow<Unit>()
        val okHttp = mock<OkHttpProvider> {
            on { httpClient() }.thenReturn(beforeRouteChange, afterRouteChange)
            on { webSocketClient() } doReturn webSocket
        }
        val tor = mock<ArtiTorManager> { on { this.routeChanges } doReturn routeChanges }

        val clients = NostrNetworkModule.clients(okHttp, tor)

        assertSame(beforeRouteChange, clients.httpClient())
        assertSame(afterRouteChange, clients.httpClient())
        assertSame(webSocket, clients.webSocketClient())
        assertSame(routeChanges, clients.routeChanges)
    }
}
