package com.bitchat.android.wifiaware

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IngressLinkPolicyTest {
    @Test
    fun `observation resolves only the exact ingress link`() {
        val attackerSocket = Any()
        val victimSocket = Any()
        val links = mapOf(
            "attacker-link" to IngressLinkPolicy.Link("provisional-attacker", attackerSocket),
            "victim-link" to IngressLinkPolicy.Link("provisional-victim", victimSocket)
        )
        val current = mapOf(
            "provisional-attacker" to attackerSocket,
            "provisional-victim" to victimSocket
        )

        val resolved = IngressLinkPolicy.resolve(
            ingressLinkID = "victim-link",
            relayAddress = "provisional-victim",
            links = links,
            currentTransportForRelay = current::get
        )

        assertSame(victimSocket, resolved?.transport)
    }

    @Test
    fun `stale replaced or mismatched ingress links cannot be observed`() {
        val completedSocket = Any()
        val replacementSocket = Any()
        val links = mapOf(
            "completed-link" to IngressLinkPolicy.Link("provisional", completedSocket)
        )

        assertNull(
            IngressLinkPolicy.resolve(
                ingressLinkID = "missing-link",
                relayAddress = "provisional",
                links = links,
                currentTransportForRelay = { completedSocket }
            )
        )
        assertNull(
            IngressLinkPolicy.resolve(
                ingressLinkID = "completed-link",
                relayAddress = "different-provisional",
                links = links,
                currentTransportForRelay = { completedSocket }
            )
        )
        assertNull(
            IngressLinkPolicy.resolve(
                ingressLinkID = "completed-link",
                relayAddress = "provisional",
                links = links,
                currentTransportForRelay = { replacementSocket }
            )
        )
    }
}
