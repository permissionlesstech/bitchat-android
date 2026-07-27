package com.bitchat.android.services

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.ChatState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Date

class ConversationAliasResolverTest {
    private lateinit var scope: CoroutineScope
    private lateinit var state: ChatState

    @Before
    fun setUp() {
        AppStateStore.clear()
        scope = CoroutineScope(SupervisorJob())
        state = ChatState(scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
        AppStateStore.clear()
    }

    @Test
    fun `explicit alias merge keeps arrival order when peer clocks differ`() {
        val firstArrival = BitchatMessage(
            id = "first-arrival",
            sender = "alice",
            content = "sent from a clock that is ahead",
            timestamp = Date(3)
        )
        val secondArrival = BitchatMessage(
            id = "second-arrival",
            sender = "alice",
            content = "sent from a clock that is behind",
            timestamp = Date(1)
        )
        state.setPrivateChats(
            linkedMapOf(
                "target-peer" to listOf(firstArrival),
                "source-alias" to listOf(secondArrival)
            )
        )
        AppStateStore.addPrivateMessage("target-peer", firstArrival)
        AppStateStore.addPrivateMessage("source-alias", secondArrival)

        ConversationAliasResolver.unifyChatsIntoPeer(
            state = state,
            targetPeerID = "target-peer",
            keysToMerge = listOf("source-alias")
        )

        assertEquals(
            listOf(firstArrival, secondArrival),
            state.getPrivateChatsValue()["target-peer"]
        )
        assertEquals(
            listOf(firstArrival, secondArrival),
            AppStateStore.privateMessages.value["target-peer"]
        )
    }
}
