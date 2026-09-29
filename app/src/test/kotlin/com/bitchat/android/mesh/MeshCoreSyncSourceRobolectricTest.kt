package com.bitchat.android.mesh

import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.sync.GossipSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mockConstruction
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MeshCoreSyncSourceRobolectricTest {
    @Test
    fun `the shared core resolves transport addresses before matching requests`() {
        val neighbor = "aaaabbbbccccdddd"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val transport = object : MeshTransport {
            override val id = "SYNTHETIC"
            override fun broadcastPacket(routed: RoutedPacket) = true
            override fun sendPacketToPeer(peerID: String, packet: BitchatPacket) = true
            override fun peerIDForRelayAddress(relayAddress: String): String? =
                if (relayAddress == "synthetic-link" || relayAddress == "provisional-alias") neighbor else null
        }
        // Storage is irrelevant to link lookup; exclude AndroidKeyStore construction.
        mockConstruction(SecureIdentityStateManager::class.java).use {
            val core = MeshCore(RuntimeEnvironment.getApplication(), scope, transport,
                SecurityManagerTest.FakeEncryptionService(), "1111222233334444", 7u, null,
                object : GossipSyncManager.ConfigProvider {
                    override fun seenCapacity() = 100
                    override fun gcsMaxBytes() = 400
                    override fun gcsTargetFpr() = 0.01
                })
            try {
                val field = MeshCore::class.java.getDeclaredField("securityManager")
                field.isAccessible = true
                val delegate = (field.get(core) as SecurityManager).delegate!!
                core.setDirectConnection(neighbor, true)
                assertEquals(neighbor, delegate.peerIDForRelayAddress("synthetic-link"))
                assertEquals(neighbor, delegate.peerIDForRelayAddress("provisional-alias"))
                assertNull(delegate.peerIDForRelayAddress("unknown-link"))
                assertEquals(listOf(neighbor), core.gossipSyncManager.delegate!!.connectedPeerIDs())
                core.setDirectConnection(neighbor, false)
                assertNull(delegate.peerIDForRelayAddress("synthetic-link"))
            } finally { core.shutdown() }
        }
        scope.cancel()
    }
}
