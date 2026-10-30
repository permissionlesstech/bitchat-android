package com.bitchat.android.net

import dagger.Lazy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OkHttpProviderTest {
    private lateinit var okHttp: OkHttpProvider

    @Before
    fun setUp() {
        okHttp = OkHttpProvider(ArtiTorManager(Lazy { okHttp }, TorPreferenceManager()))
    }

    @Test
    fun `reset clears cached clients without changing the route`() {
        okHttp.reset()
        val cachedHttp = okHttp.routedHttpClient()
        val cachedWebSocket = okHttp.webSocketClient()

        okHttp.reset()

        val rebuiltHttp = okHttp.routedHttpClient()
        val rebuiltWebSocket = okHttp.webSocketClient()
        assertEquals(cachedHttp.route, rebuiltHttp.route)
        assertNotSame(cachedHttp.client, rebuiltHttp.client)
        assertNotSame(cachedWebSocket, rebuiltWebSocket)
    }
}
