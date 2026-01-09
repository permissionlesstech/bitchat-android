package com.bitchat.mesh

import com.bitchat.android.mesh.PeerManager
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerManagerTest {
    private class CapturingDelegate : com.bitchat.android.mesh.PeerManagerDelegate {
        val updates = mutableListOf<List<String>>()
        override fun onPeerListUpdated(peerIDs: List<String>) {
            updates.add(peerIDs)
        }

        override fun onPeerRemoved(peerID: String) { }
    }

    @Test
    fun updatePeerInfo_emitsUpdateWhenVerificationChanges() {
        val manager = PeerManager()
        val delegate = CapturingDelegate()
        manager.delegate = delegate

        manager.addOrUpdatePeer("peer1", "peer1")
        delegate.updates.clear()

        manager.updatePeerInfo(
            peerID = "peer1",
            nickname = "peer1",
            noisePublicKey = ByteArray(32),
            signingPublicKey = ByteArray(32),
            isVerified = true
        )

        assertEquals(1, delegate.updates.size)
        assertEquals(listOf("peer1"), delegate.updates.first())
    }

    @Test
    fun updatePeerInfo_emitsUpdateWhenNicknameChanges() {
        val manager = PeerManager()
        val delegate = CapturingDelegate()
        manager.delegate = delegate

        manager.addOrUpdatePeer("peer1", "peer1")
        delegate.updates.clear()

        manager.updatePeerInfo(
            peerID = "peer1",
            nickname = "peer1",
            noisePublicKey = ByteArray(32),
            signingPublicKey = ByteArray(32),
            isVerified = true
        )
        delegate.updates.clear()

        manager.updatePeerInfo(
            peerID = "peer1",
            nickname = "peur",
            noisePublicKey = ByteArray(32),
            signingPublicKey = ByteArray(32),
            isVerified = true
        )

        assertEquals(1, delegate.updates.size)
        assertEquals(listOf("peer1"), delegate.updates.first())
    }
}
