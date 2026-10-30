package com.bitchat.android.mesh

import com.bitchat.android.model.AuthenticatedPeerState
import com.bitchat.android.model.PeerCapabilities
import com.bitchat.android.noise.AuthenticatedNoiseSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMediaSecurityTest {
    private val peerID = "0011223344556677"
    private var authenticatedSession: AuthenticatedNoiseSession? = AuthenticatedNoiseSession(
        ByteArray(32) { (it + 1).toByte() },
        ByteArray(32) { 0x51 }
    )
    private var status: AuthenticatedPeerStateStatus = AuthenticatedPeerStateStatus.Awaiting
    private var pinned = false
    private val controller = PrivateMediaSecurityController(
        authenticatedSessionProvider = { authenticatedSession },
        peerStateStatusProvider = { _, _ -> status },
        isPrivateMediaPinned = { pinned }
    )

    @Test
    fun `live session waits for fresh generation proof`() {
        assertEquals(PrivateMediaPolicyDecision.AwaitingPeerState, controller.sendPolicy(peerID))
    }

    @Test
    fun `valid private-media proof enables encrypted wire`() {
        status = AuthenticatedPeerStateStatus.Proven(
            AuthenticatedPeerState(PeerCapabilities.PRIVATE_MEDIA, ByteArray(32) { 1 })
        )
        assertTrue(controller.sendPolicy(peerID) is PrivateMediaPolicyDecision.Encrypted)
    }

    @Test
    fun `no-bit proof permits consent only when capability was never pinned`() {
        status = AuthenticatedPeerStateStatus.Proven(
            AuthenticatedPeerState(PeerCapabilities.NONE, ByteArray(32) { 1 })
        )
        assertEquals(PrivateMediaPolicyDecision.RequiresLegacyConsent, controller.sendPolicy(peerID))

        pinned = true
        assertTrue(controller.sendPolicy(peerID) is PrivateMediaPolicyDecision.Blocked)
    }

    @Test
    fun `no-proof timeout permits old-client consent but blocks pinned downgrade`() {
        status = AuthenticatedPeerStateStatus.TimedOut
        assertEquals(PrivateMediaPolicyDecision.RequiresLegacyConsent, controller.sendPolicy(peerID))

        pinned = true
        assertTrue(controller.sendPolicy(peerID) is PrivateMediaPolicyDecision.Blocked)
    }

    @Test
    fun `live session missing coordinator generation waits and never unlocks legacy`() {
        status = AuthenticatedPeerStateStatus.Missing
        assertEquals(PrivateMediaPolicyDecision.AwaitingPeerState, controller.sendPolicy(peerID))
    }

    @Test
    fun `pin never bypasses requirement for live authenticated session`() {
        pinned = true
        authenticatedSession = null
        assertEquals(PrivateMediaPolicyDecision.NeedsHandshake, controller.sendPolicy(peerID))
    }
}
