package com.bitchat.android.mesh

import com.bitchat.android.model.PeerCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateMediaSecurityTest {
    private class MemoryPins : PrivateMediaCapabilityPinStore {
        val pins = mutableSetOf<String>()
        override fun contains(fingerprint: String): Boolean = fingerprint in pins
        override fun insert(fingerprint: String) {
            pins += fingerprint
        }
    }

    private val peerID = "0011223344556677"
    private val remoteStatic = ByteArray(32) { (it + 1).toByte() }
    private var peerInfo: PeerInfo? = null
    private var authenticatedRemoteStatic: ByteArray? = null
    private val pins = MemoryPins()
    private val controller = PrivateMediaSecurityController(
        peerInfoProvider = { peerInfo },
        authenticatedRemoteStaticProvider = { authenticatedRemoteStatic?.copyOf() },
        pinStore = pins
    )

    @Test
    fun `announce before handshake promotes only after authenticated key arrives`() {
        peerInfo = peer(capabilities = PeerCapabilities.PRIVATE_MEDIA)

        assertFalse(controller.refreshAuthenticatedCapability(peerID))
        assertTrue(pins.pins.isEmpty())

        authenticatedRemoteStatic = remoteStatic
        assertTrue(controller.refreshAuthenticatedCapability(peerID))
        assertEquals(PrivateMediaPolicyDecision.Encrypted, controller.sendPolicy(peerID))
        assertEquals(1, pins.pins.size)
    }

    @Test
    fun `handshake before announce promotes only after verified announce arrives`() {
        authenticatedRemoteStatic = remoteStatic

        assertFalse(controller.refreshAuthenticatedCapability(peerID))
        assertTrue(pins.pins.isEmpty())

        peerInfo = peer(capabilities = PeerCapabilities.PRIVATE_MEDIA)
        assertTrue(controller.refreshAuthenticatedCapability(peerID))
        assertEquals(PrivateMediaPolicyDecision.Encrypted, controller.sendPolicy(peerID))
    }

    @Test
    fun `self certified or mismatched announcement never creates pin`() {
        authenticatedRemoteStatic = remoteStatic
        peerInfo = peer(
            capabilities = PeerCapabilities.PRIVATE_MEDIA,
            noiseKey = ByteArray(32) { 0x55 }
        )

        assertFalse(controller.refreshAuthenticatedCapability(peerID))
        assertTrue(pins.pins.isEmpty())
        assertTrue(controller.sendPolicy(peerID) is PrivateMediaPolicyDecision.Blocked)

        // A normal peer refresh may carry a new current key, but it cannot
        // transfer capability trust away from the key in the signed announce.
        peerInfo = peer(
            capabilities = PeerCapabilities.PRIVATE_MEDIA,
            noiseKey = remoteStatic,
            verifiedAnnouncementNoiseKey = ByteArray(32) { 0x55 }
        )
        assertFalse(controller.refreshAuthenticatedCapability(peerID))
        assertTrue(controller.sendPolicy(peerID) is PrivateMediaPolicyDecision.Blocked)

        peerInfo = peer(
            capabilities = PeerCapabilities.PRIVATE_MEDIA,
            hasVerifiedAnnouncement = false
        )
        assertFalse(controller.refreshAuthenticatedCapability(peerID))
        assertTrue(pins.pins.isEmpty())
    }

    @Test
    fun `signed absent and explicit empty capabilities both require one shot consent`() {
        authenticatedRemoteStatic = remoteStatic

        peerInfo = peer(capabilities = null)
        assertEquals(
            PrivateMediaPolicyDecision.RequiresLegacyConsent,
            controller.sendPolicy(peerID)
        )

        peerInfo = peer(capabilities = PeerCapabilities.NONE)
        assertEquals(
            PrivateMediaPolicyDecision.RequiresLegacyConsent,
            controller.sendPolicy(peerID)
        )
        assertTrue(pins.pins.isEmpty())
    }

    @Test
    fun `pin is HSTS and prevents later capability downgrade`() {
        authenticatedRemoteStatic = remoteStatic
        peerInfo = peer(capabilities = PeerCapabilities.PRIVATE_MEDIA)
        assertEquals(PrivateMediaPolicyDecision.Encrypted, controller.sendPolicy(peerID))

        peerInfo = peer(capabilities = null)
        assertEquals(PrivateMediaPolicyDecision.Encrypted, controller.sendPolicy(peerID))
    }

    @Test
    fun `pin never bypasses requirement for a live authenticated static key`() {
        authenticatedRemoteStatic = remoteStatic
        peerInfo = peer(capabilities = PeerCapabilities.PRIVATE_MEDIA)
        assertEquals(PrivateMediaPolicyDecision.Encrypted, controller.sendPolicy(peerID))

        authenticatedRemoteStatic = null
        assertEquals(PrivateMediaPolicyDecision.NeedsHandshake, controller.sendPolicy(peerID))
    }

    private fun peer(
        capabilities: PeerCapabilities?,
        noiseKey: ByteArray = remoteStatic,
        hasVerifiedAnnouncement: Boolean = true,
        verifiedAnnouncementNoiseKey: ByteArray = noiseKey
    ) = PeerInfo(
        id = peerID,
        nickname = "peer",
        isConnected = true,
        isDirectConnection = true,
        noisePublicKey = noiseKey,
        signingPublicKey = ByteArray(32) { 9 },
        isVerifiedNickname = true,
        lastSeen = 1,
        capabilities = capabilities,
        hasVerifiedAnnouncement = hasVerifiedAnnouncement,
        verifiedAnnouncementNoisePublicKey = if (hasVerifiedAnnouncement) {
            verifiedAnnouncementNoiseKey
        } else {
            null
        }
    )
}
