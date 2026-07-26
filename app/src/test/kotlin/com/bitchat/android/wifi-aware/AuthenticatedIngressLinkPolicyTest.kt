package com.bitchat.android.wifiaware

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedIngressLinkPolicyTest {
    @Test
    fun `promotion claim must match the challenged relay and link`() {
        val claim = AuthenticatedIngressLinkPolicy.Claim("provisional", "challenged-link")

        assertTrue(
            AuthenticatedIngressLinkPolicy.matches(
                claim,
                authenticatedRelayAddress = "provisional",
                authenticatedLinkID = "challenged-link"
            )
        )
        assertFalse(
            AuthenticatedIngressLinkPolicy.matches(
                claim,
                authenticatedRelayAddress = "provisional",
                authenticatedLinkID = "different-link"
            )
        )
        assertFalse(
            AuthenticatedIngressLinkPolicy.matches(
                expected = null,
                authenticatedRelayAddress = "provisional",
                authenticatedLinkID = "challenged-link"
            )
        )
    }

    @Test
    fun `authentication promotes only the exact ingress link`() {
        val attackerSocket = Any()
        val victimSocket = Any()
        val links = mapOf(
            "attacker-link" to AuthenticatedIngressLinkPolicy.Link("provisional-attacker", attackerSocket),
            "victim-link" to AuthenticatedIngressLinkPolicy.Link("provisional-victim", victimSocket)
        )
        val current = mapOf(
            "provisional-attacker" to attackerSocket,
            "provisional-victim" to victimSocket
        )

        val resolved = AuthenticatedIngressLinkPolicy.resolve(
            authenticatedLinkID = "victim-link",
            authenticatedRelayAddress = "provisional-victim",
            links = links,
            currentTransportForRelay = current::get
        )

        assertSame(victimSocket, resolved?.transport)
    }

    @Test
    fun `stale replaced or mismatched ingress links cannot be promoted`() {
        val completedSocket = Any()
        val replacementSocket = Any()
        val links = mapOf(
            "completed-link" to AuthenticatedIngressLinkPolicy.Link("provisional", completedSocket)
        )

        assertNull(
            AuthenticatedIngressLinkPolicy.resolve(
                authenticatedLinkID = "missing-link",
                authenticatedRelayAddress = "provisional",
                links = links,
                currentTransportForRelay = { completedSocket }
            )
        )
        assertNull(
            AuthenticatedIngressLinkPolicy.resolve(
                authenticatedLinkID = "completed-link",
                authenticatedRelayAddress = "different-provisional",
                links = links,
                currentTransportForRelay = { completedSocket }
            )
        )
        assertNull(
            AuthenticatedIngressLinkPolicy.resolve(
                authenticatedLinkID = "completed-link",
                authenticatedRelayAddress = "provisional",
                links = links,
                currentTransportForRelay = { replacementSocket }
            )
        )
    }
}
