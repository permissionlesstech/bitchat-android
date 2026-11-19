package com.bitchat.watch.ui

import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.TestMeshStores
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SendHelpersTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store = TestMeshStores(ApplicationProvider.getApplicationContext()).appStateStore
    private val mesh = FakeWearMesh()
    private val peerID = "bbbb1111bbbb1111"

    @Test
    fun sendPublicMessage_sendsOnTheGlobalChannelWithNoMentions() {
        sendPublicMessage(store, mesh, "watch-test", "hello mesh")

        assertEquals(listOf(FakeWearMesh.PublicSend("hello mesh", emptyList(), null)), mesh.publicSends)
        val echo = store.publicMessages.value.single()
        assertEquals("hello mesh", echo.content)
        assertEquals("watch-test", echo.sender)
        assertEquals(mesh.myPeerID, echo.senderPeerID)
        assertEquals(DeliveryStatus.Sent, echo.deliveryStatus)
    }

    @Test
    fun sendPrivateMessage_withSession_sendsWithTheMessageId() {
        mesh.established = true
        val scope = TestScope(StandardTestDispatcher())

        sendPrivateMessage(store, mesh, "watch-test", peerID, "Peer", "psst", scope)

        val sent = mesh.privateSends.single()
        val echo = store.privateMessages.value.getValue(peerID).single()
        assertEquals(FakeWearMesh.PrivateSend("psst", peerID, "Peer", echo.id), sent)
        assertEquals(DeliveryStatus.Sent, echo.deliveryStatus)
        assertTrue(mesh.handshakes.isEmpty())
    }

    @Test
    fun sendPrivateMessage_withoutSession_handshakesFirst() {
        val scope = TestScope(StandardTestDispatcher())

        sendPrivateMessage(store, mesh, "watch-test", peerID, "Peer", "psst", scope)
        scope.runCurrent()

        assertEquals(listOf("initiateNoiseHandshake"), mesh.calls)
        assertEquals(listOf(peerID), mesh.handshakes)
        val echo = store.privateMessages.value.getValue(peerID).single()
        assertEquals(DeliveryStatus.Sending, echo.deliveryStatus)

        scope.advanceTimeBy(1_000)
        assertTrue("no send while the session is missing", mesh.privateSends.isEmpty())

        mesh.established = true
        scope.advanceTimeBy(400)
        scope.runCurrent()

        assertEquals(listOf("initiateNoiseHandshake", "sendPrivateMessage"), mesh.calls)
        assertEquals(FakeWearMesh.PrivateSend("psst", peerID, "Peer", echo.id), mesh.privateSends.single())
        assertEquals(
            DeliveryStatus.Sent,
            store.privateMessages.value.getValue(peerID).single().deliveryStatus
        )
    }

    @Test
    fun sendVoiceNote_inGlobalChat_broadcastsTheFile() {
        val file = tmp.newFile("note.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var privateSent = false

        sendVoiceNote(store, mesh, "watch-test", null, file.path) { _, _ -> privateSent = true }

        val packet = mesh.broadcastFiles.single()
        assertEquals("note.m4a", packet.fileName)
        assertEquals(3L, packet.fileSize)
        assertTrue(!privateSent)
        assertEquals(file.path, store.publicMessages.value.single().content)
    }

    @Test
    fun sendVoiceNote_inDm_usesThePrivateSender() {
        val file = tmp.newFile("note.m4a").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        var target: String? = null

        sendVoiceNote(store, mesh, "watch-test", peerID, file.path) { id, _ -> target = id }

        assertEquals(peerID, target)
        assertTrue(mesh.broadcastFiles.isEmpty())
        assertEquals(file.path, store.privateMessages.value.getValue(peerID).single().content)
    }

    @Test
    fun sendVoiceNote_missingFile_sendsNothing() {
        sendVoiceNote(store, mesh, "watch-test", null, File(tmp.root, "absent.m4a").path) { _, _ -> }

        assertTrue(mesh.calls.isEmpty())
        assertNull(store.publicMessages.value.firstOrNull())
    }
}
