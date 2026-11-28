package com.bitchat.android.service

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.AppStateStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Date

class PeerAvailabilityWaveReceiverTest {
    @Before
    fun setUp() {
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    @Test
    fun `wave is broadcast on the public mesh and echoed into the public timeline`() {
        val mesh = mock<MeshService>()
        whenever(mesh.myPeerID).thenReturn("0011223344556677")
        val timestamp = Date(1_700_000_000_000L)

        val wave = PeerAvailabilityWaveReceiver.sendMeshWave(mesh, "alice", timestamp)

        verify(mesh).sendMessage("👋", emptyList(), null)
        assertEquals(listOf(wave), AppStateStore.publicMessages.value)
        assertEquals("👋", wave.content)
        assertEquals("alice", wave.sender)
        assertEquals("0011223344556677", wave.senderPeerID)
        assertEquals(timestamp, wave.timestamp)
        assertFalse(wave.isPrivate)
        assertNull(wave.channel)
    }
}
