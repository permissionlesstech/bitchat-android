package com.bitchat.android.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class OkHttpProviderTest {

    @Test
    fun `reset clears cached clients without changing the route`() {
        OkHttpProvider.reset()
        val cachedHttp = OkHttpProvider.routedHttpClient()
        val cachedWebSocket = OkHttpProvider.webSocketClient()

        OkHttpProvider.reset()

        val rebuiltHttp = OkHttpProvider.routedHttpClient()
        val rebuiltWebSocket = OkHttpProvider.webSocketClient()
        assertEquals(cachedHttp.route, rebuiltHttp.route)
        assertNotSame(cachedHttp.client, rebuiltHttp.client)
        assertNotSame(cachedWebSocket, rebuiltWebSocket)
    }
}
