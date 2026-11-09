package com.bitchat.android.service

import com.bitchat.android.identity.scope.IdentityTeardown
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.sync.GossipSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import org.mockito.kotlin.doReturn
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

    @Test
    fun `an ended holder stops what it holds and builds nothing for a late reader`() {
        val teardown = IdentityTeardown()
        val holder = testMeshServiceHolder(teardown)
        val mesh = mock<BluetoothMeshService> { on { isReusable() } doReturn false }
        holder.attach(mesh)

        assertEquals(emptyList<String>(), teardown.runAll())

        verify(mesh).stopServices()
        // The factories in this holder throw, so a build would fail this test.
        assertSame(mesh, holder.getOrCreate(mock()))
    }

    @Test
    fun `an ended holder with no mesh refuses rather than builds one`() {
        val teardown = IdentityTeardown()
        val holder = testMeshServiceHolder(teardown)
        teardown.runAll()

        val failure = assertThrows(IllegalStateException::class.java) { holder.getUnifiedOrCreate(mock()) }

        assertEquals("the identity has ended", failure.message)
    }
}
