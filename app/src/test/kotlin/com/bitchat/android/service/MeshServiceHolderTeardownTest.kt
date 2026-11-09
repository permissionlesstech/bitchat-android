package com.bitchat.android.service

import com.bitchat.android.identity.scope.IdentityTeardown
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.sync.GossipSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify

class MeshServiceHolderTeardownTest {
    @Test
    fun `holder teardown clears the shared gossip manager and the attached mesh`() {
        val teardown = IdentityTeardown()
        val holder = testMeshServiceHolder(teardown)
        val gossip: GossipSyncManager = mock()
        holder.setGossipManager(gossip) { it }
        holder.attach(mock<BluetoothMeshService>())
        assertNotNull(holder.sharedGossipSyncManager)

        assertEquals(emptyList<String>(), teardown.runAll())

        assertNull(holder.sharedGossipSyncManager)
        verify(gossip).stop()
    }
}
