package com.bitchat.android.nostr

import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class NostrNetworkTest {

    @After
    fun uninstall() = NostrNetwork.uninstallForTesting()

    @Test
    fun `asking for a client before one is installed fails rather than connecting directly`() {
        NostrNetwork.uninstallForTesting()

        assertThrows(IllegalStateException::class.java) { NostrNetwork.clients() }
    }

    @Test
    fun `the installed clients are the ones handed out`() {
        val clients = object : NostrHttpClients {
            override fun httpClient() = error("not called")
            override fun webSocketClient() = error("not called")
        }
        NostrNetwork.install(clients)

        assertSame(clients, NostrNetwork.clients())
    }
}
