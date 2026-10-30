package com.bitchat.android.net

import dagger.Lazy
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ArtiTorManagerRouteResetTest {

    @Test
    fun artiRouteChange_resetsOkHttpClientsBeforeReturning() {
        lateinit var okHttp: OkHttpProvider
        val tor = ArtiTorManager(Lazy { okHttp }, TorPreferenceManager())
        okHttp = OkHttpProvider(tor)
        val cachedHttp = okHttp.routedHttpClient()
        val cachedWebSocket = okHttp.webSocketClient()

        tor.resetNetworkConnections()

        assertNotSame(cachedHttp.client, okHttp.routedHttpClient().client)
        assertNotSame(cachedWebSocket, okHttp.webSocketClient())
    }
}
