package com.bitchat.android.ui

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.services.AppStateStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Date

class MessageManagerDeliveryStatusTest {
    private lateinit var state: ChatState
    private lateinit var manager: MessageManager

    @Before
    fun setUp() {
        AppStateStore.clear()
        state = ChatState(CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
        manager = MessageManager(state)
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
    }

    @Test
    fun `local failure replaces Sending but never downgrades admitted delivery evidence`() {
        val statuses = listOf(
            DeliveryStatus.Sending,
            DeliveryStatus.Sent,
            DeliveryStatus.Delivered("peer", Date(2)),
            DeliveryStatus.Read("peer", Date(3))
        )
        state.setPrivateChats(
            mapOf(
                "peer" to statuses.mapIndexed { index, status ->
                    BitchatMessage(
                        id = "message-$index",
                        sender = "me",
                        content = "hello",
                        timestamp = Date(1),
                        isPrivate = true,
                        deliveryStatus = status
                    )
                }
            )
        )

        statuses.indices.forEach { index ->
            manager.updateMessageDeliveryStatus(
                "message-$index",
                DeliveryStatus.Failed("local terminal failure")
            )
        }

        val resulting = state.getPrivateChatsValue()
            .values
            .flatten()
            .associate { it.id to it.deliveryStatus }
        assertTrue(resulting["message-0"] is DeliveryStatus.Failed)
        assertTrue(resulting["message-1"] is DeliveryStatus.Sent)
        assertTrue(resulting["message-2"] is DeliveryStatus.Delivered)
        assertTrue(resulting["message-3"] is DeliveryStatus.Read)
    }
}
