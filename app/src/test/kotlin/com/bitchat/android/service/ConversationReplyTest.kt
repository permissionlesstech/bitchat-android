package com.bitchat.android.service

import com.bitchat.android.mesh.MeshService
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.MessageRouter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions

/** A reply belongs to the identity whose notification it answers, and to no later one. */
class ConversationReplyTest {

    private var current = true
    private val mesh = mock<MeshService> { on { myPeerID } doReturn "synthetic0000000" }
    private val router = mock<MessageRouter>()
    private var meshReads = 0
    private val store = mock<AppStateStore> {
        onBlocking { addPrivateMessageDurably(any(), any(), any()) } doReturn true
        onBlocking { setPrivateConversationRead(any(), any()) } doReturn true
    }

    private fun reply(appStateStore: AppStateStore = store) = ConversationReply(
        identityIsCurrent = { current },
        appStateStore = appStateStore,
        mesh = {
            meshReads++
            mesh
        },
        router = { router },
    )

    private fun send(sender: ConversationReply = reply()) =
        runBlocking { sender.send("synthetic-peer", "synthetic reply", "anon0000") }

    @Test
    fun `a reply within its identity is stored and sent`() {
        assertTrue(send())

        verify(router).sendPrivate(any(), any(), any(), any())
    }

    @Test
    fun `a reply that lands after its identity ended reads nothing and sends nothing`() {
        current = false

        assertFalse(send())

        assertTrue("it must not ask for a mesh", meshReads == 0)
        verifyNoInteractions(store)
        verifyNoInteractions(router)
    }

    @Test
    fun `the identity ending while the reply is stored stops the send`() {
        val endingWhileStored = mock<AppStateStore> {
            onBlocking { addPrivateMessageDurably(any(), any(), any()) } doAnswer {
                current = false
                true
            }
        }

        assertFalse(send(reply(endingWhileStored)))

        verifyNoInteractions(router)
    }

    @Test
    fun `a closed admission gate stops the send`() {
        val closed = mock<AppStateStore> {
            onBlocking { addPrivateMessageDurably(any(), any(), any()) } doReturn false
        }

        assertFalse(send(reply(closed)))

        verify(router, never()).sendPrivate(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `an ended holder with no mesh to give back drops the reply`() {
        val sender = ConversationReply(
            identityIsCurrent = { true },
            appStateStore = store,
            mesh = { error("the identity has ended") },
            router = { router },
        )

        assertFalse(send(sender))

        verifyNoInteractions(router)
    }
}
