package com.bitchat.android.mesh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthenticatedBleLinkPolicyTest {
    private val claim = AuthenticatedBleLinkPolicy.Claim(
        deviceAddress = "AA:BB:CC:DD:EE:FF",
        linkID = "connection-a"
    )

    @Test
    fun `accepts completion from exact claimed connection`() {
        assertTrue(
            AuthenticatedBleLinkPolicy.matches(
                claim,
                authenticatedAddress = claim.deviceAddress,
                authenticatedLinkID = claim.linkID
            )
        )
    }

    @Test
    fun `rejects replacement connection reusing device address`() {
        assertFalse(
            AuthenticatedBleLinkPolicy.matches(
                claim,
                authenticatedAddress = claim.deviceAddress,
                authenticatedLinkID = "connection-b"
            )
        )
    }

    @Test
    fun `rejects completion on another address or without a claim`() {
        assertFalse(AuthenticatedBleLinkPolicy.matches(claim, "11:22:33:44:55:66", claim.linkID))
        assertFalse(AuthenticatedBleLinkPolicy.matches(null, claim.deviceAddress, claim.linkID))
    }
}
