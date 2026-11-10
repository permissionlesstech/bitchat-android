package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import java.util.Date
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MessageManagerPublicRetentionTest {
    @Before fun setUp() = AppStateStore.clear()
    @After fun tearDown() = AppStateStore.clear()

    @Test
    fun `clear and leave do not resurrect retained messages on the next append`() = runTest {
        val state = ChatState(backgroundScope)
        val manager = MessageManager(state)
        manager.addMessage(message(0))
        manager.addChannelMessage("fixture-channel", message(1))
        manager.clearMessages()
        manager.addMessage(message(2))
        assertEquals(listOf("fixture-2"), state.messages.value.map { it.id })
        assertTrue(AppStateStore.channelMessages.value.isEmpty())

        manager.addChannelMessage("fixture-channel", message(3))
        manager.clearChannelMessages("fixture-channel")
        manager.addChannelMessage("fixture-channel", message(4))
        assertEquals(listOf("fixture-4"), state.channelMessages.value["fixture-channel"]?.map { it.id })

        manager.removeChannelMessages("fixture-channel")
        manager.addChannelMessage("fixture-channel", message(5))
        assertEquals(listOf("fixture-5"), state.channelMessages.value["fixture-channel"]?.map { it.id })
    }

    private fun message(index: Int) = BitchatMessage(
        id = "fixture-$index", sender = "fixture-sender", content = "synthetic-$index",
        timestamp = Date(index.toLong()),
    )
}
