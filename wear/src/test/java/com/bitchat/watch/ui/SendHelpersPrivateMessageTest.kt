package com.bitchat.watch.ui

import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.TestMeshStores
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The DM's session wait runs in the scope the caller passes (the mesh service's), so it outlives
 * the input screen, and it stops at the deadline or when the mesh stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SendHelpersPrivateMessageTest {

    private val store = TestMeshStores(ApplicationProvider.getApplicationContext()).appStateStore
    private var meshRunning = true

    private fun TestScope.serviceScope() =
        CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))

    private fun TestScope.send(mesh: FakeWearMesh, scope: CoroutineScope = serviceScope()) =
        sendPrivateMessage(
            store, mesh, "synthetic-self", PEER_ID, PEER_NICKNAME, CONTENT,
            scope = scope,
            isRunning = { meshRunning },
            nowMs = { testScheduler.currentTime },
        )

    @Test
    fun `dm typed before the session exists is sent once the session is established`() = runTest {
        val mesh = FakeWearMesh()

        send(mesh)
        assertEquals(listOf(PEER_ID), mesh.handshakes)
        assertEquals(DeliveryStatus.Sending, onlyEcho().deliveryStatus)

        mesh.established = true
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(CONTENT, mesh.privateSends.single().content)
        assertEquals(DeliveryStatus.Sent, onlyEcho().deliveryStatus)
    }

    @Test
    fun `echo is marked sent when the session is ready as soon as the wait starts`() = runTest {
        val mesh = object : FakeWearMesh() {
            override fun initiateNoiseHandshake(peerID: String) {
                super.initiateNoiseHandshake(peerID)
                established = true
            }
        }
        val eagerScope = CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
        )

        send(mesh, eagerScope)

        assertEquals(CONTENT, mesh.privateSends.single().content)
        assertEquals(DeliveryStatus.Sent, onlyEcho().deliveryStatus)
    }

    @Test
    fun `dm is not sent after the wait deadline passes`() = runTest {
        val mesh = FakeWearMesh()

        send(mesh)
        advanceTimeBy(15_500)
        mesh.established = true
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(mesh.privateSends.isEmpty())
        assertEquals(DeliveryStatus.Sending, onlyEcho().deliveryStatus)
    }

    @Test
    fun `dm is not reported sent when the mesh stops during the wait`() = runTest {
        val mesh = FakeWearMesh()

        send(mesh)
        runCurrent()
        meshRunning = false
        mesh.established = true
        advanceTimeBy(1_000)
        runCurrent()

        assertTrue(mesh.privateSends.isEmpty())
        assertEquals(DeliveryStatus.Sending, onlyEcho().deliveryStatus)
    }

    @Test
    fun `dm with an established session is sent immediately`() = runTest {
        val mesh = FakeWearMesh().apply { established = true }

        send(mesh)

        assertEquals(CONTENT, mesh.privateSends.single().content)
        assertTrue(mesh.handshakes.isEmpty())
        assertEquals(DeliveryStatus.Sent, onlyEcho().deliveryStatus)
    }

    private fun onlyEcho() = store.privateMessages.value.getValue(PEER_ID).single()

    private companion object {
        const val PEER_ID = "bbbbbbbbbbbbbbbb"
        const val PEER_NICKNAME = "synthetic-peer"
        const val CONTENT = "hello"
    }
}
