package com.bitchat.android.service

import com.bitchat.android.services.AppStateStore
import com.bitchat.android.sync.GossipSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MeshServiceHolderNeighborsTest {

    private lateinit var scope: CoroutineScope
    private lateinit var manager: GossipSyncManager

    private val config = object : GossipSyncManager.ConfigProvider {
        override fun seenCapacity(): Int = 100
        override fun gcsMaxBytes(): Int = 400
        override fun gcsTargetFpr(): Double = 0.01
    }

    @Before
    fun setUp() {
        AppStateStore.clearTransportDirectPeers("BLE")
        AppStateStore.clearTransportDirectPeers("WIFI")
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        manager = GossipSyncManager(myPeerID = "1122334455667788", scope = scope, configProvider = config)
        MeshServiceHolder.setGossipManager(manager) { it }
    }

    @After
    fun tearDown() {
        AppStateStore.clearTransportDirectPeers("BLE")
        AppStateStore.clearTransportDirectPeers("WIFI")
        scope.cancel()
    }

    @Test
    fun `the shared manager is offered the direct peers of every transport`() {
        AppStateStore.setTransportDirectPeers("BLE", setOf("aaaabbbbccccdddd"))
        AppStateStore.setTransportDirectPeers("WIFI", setOf("1111222233334444"))

        assertEquals(setOf("aaaabbbbccccdddd", "1111222233334444"), manager.delegate!!.connectedPeerIDs().toSet())
    }

    @Test
    fun `with no transport reporting peers the shared manager is offered none`() {
        assertTrue(manager.delegate!!.connectedPeerIDs().isEmpty())
    }
}
