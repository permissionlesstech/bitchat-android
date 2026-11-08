package com.bitchat.watch.ui

import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import com.bitchat.watch.mesh.WearMeshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify

@OptIn(ExperimentalCoroutinesApi::class)
class SendHelpersPrivateMessageTest {

    private var sessionEstablished = false
    private var meshRunning = true
    private var handshakeCompletesImmediately = false

    private fun meshMock(scope: CoroutineScope) = mock<WearMeshService> {
        on { serviceScope } doReturn scope
        on { nickname } doReturn "synthetic-self"
        on { myPeerID } doReturn "aaaaaaaaaaaaaaaa"
        on { isRunning() } doAnswer { meshRunning }
        on { hasEstablishedSession(any()) } doAnswer { sessionEstablished }
        on { initiateNoiseHandshake(any()) } doAnswer {
            if (handshakeCompletesImmediately) sessionEstablished = true
        }
    }

    private fun TestScope.serviceScope() =
        CoroutineScope(backgroundScope.coroutineContext + StandardTestDispatcher(testScheduler))

    private fun TestScope.send(mesh: WearMeshService) =
        sendPrivateMessage(mesh, PEER_ID, PEER_NICKNAME, CONTENT) { testScheduler.currentTime }

    @Before
    fun setUp() {
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    @Test
    fun `dm typed before the session exists is sent once the session is established`() = runTest {
        val mesh = meshMock(serviceScope())

        send(mesh)
        verify(mesh).initiateNoiseHandshake(PEER_ID)
        assertEquals(DeliveryStatus.Sending, onlyEcho().deliveryStatus)

        sessionEstablished = true
        advanceTimeBy(1_000)
        runCurrent()

        verifySent(mesh)
        assertEquals(DeliveryStatus.Sent, onlyEcho().deliveryStatus)
    }

    @Test
    fun `echo is marked sent when the session is ready as soon as the wait starts`() = runTest {
        handshakeCompletesImmediately = true
        val eagerScope = CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler)
        )
        val mesh = meshMock(eagerScope)

        send(mesh)

        verifySent(mesh)
        assertEquals(DeliveryStatus.Sent, onlyEcho().deliveryStatus)
    }

    @Test
    fun `dm is not sent after the wait deadline passes`() = runTest {
        val mesh = meshMock(serviceScope())

        send(mesh)
        advanceTimeBy(15_500)
        sessionEstablished = true
        advanceTimeBy(1_000)
        runCurrent()

        verifyNotSent(mesh)
        assertEquals(DeliveryStatus.Sending, onlyEcho().deliveryStatus)
    }

    @Test
    fun `dm is not reported sent when the mesh stops during the wait`() = runTest {
        val mesh = meshMock(serviceScope())

        send(mesh)
        runCurrent()
        meshRunning = false
        sessionEstablished = true
        advanceTimeBy(1_000)
        runCurrent()

        verifyNotSent(mesh)
        assertEquals(DeliveryStatus.Sending, onlyEcho().deliveryStatus)
    }

    @Test
    fun `dm with an established session is sent immediately`() = runTest {
        sessionEstablished = true
        val mesh = meshMock(serviceScope())

        send(mesh)

        verifySent(mesh)
        verify(mesh, never()).initiateNoiseHandshake(any())
        assertEquals(DeliveryStatus.Sent, onlyEcho().deliveryStatus)
    }

    private fun verifySent(mesh: WearMeshService) {
        verify(mesh).sendPrivateMessageWithId(
            eq(CONTENT), eq(PEER_ID), eq(PEER_NICKNAME), anyOrNull()
        )
    }

    private fun verifyNotSent(mesh: WearMeshService) {
        verify(mesh, never()).sendPrivateMessageWithId(any(), any(), any(), anyOrNull())
    }

    private fun onlyEcho() = AppStateStore.privateMessages.value.getValue(PEER_ID).single()

    private companion object {
        const val PEER_ID = "bbbbbbbbbbbbbbbb"
        const val PEER_NICKNAME = "synthetic-peer"
        const val CONTENT = "hello"
    }
}
