package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

class IncomingMessageAdmissionTest {
    @Before
    fun setUp() {
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
    }

    @Test
    fun `private message rejected during panic cannot continue transport dispatch`() {
        assertTrue(runBlocking { AppStateStore.panicClearPrivateConversations() })

        assertFalse(
            IncomingMessageAdmission.admitToAppState(
                privateMessage(id = "during-panic")
            )
        )
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
    }

    @Test
    fun `duplicate private transport delivery is rejected before downstream effects`() {
        val message = privateMessage(id = "same-message-over-two-transports")

        assertTrue(IncomingMessageAdmission.admitToAppState(message))
        assertFalse(IncomingMessageAdmission.admitToAppState(message))
    }

    @Test
    fun `public and channel messages preserve best effort admission`() {
        val public = BitchatMessage(
            id = "public",
            sender = "alice",
            content = "hello",
            timestamp = Date(1L)
        )
        val channel = public.copy(id = "channel", channel = "#mesh")

        assertTrue(IncomingMessageAdmission.admitToAppState(public))
        assertTrue(IncomingMessageAdmission.admitToAppState(channel))
        assertTrue(AppStateStore.publicMessages.value.contains(public))
    }

    private fun privateMessage(id: String) = BitchatMessage(
        id = id,
        sender = "alice",
        content = "secret",
        timestamp = Date(1L),
        isPrivate = true,
        senderPeerID = "peer-a"
    )
}
