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
    fun `explicit alias merge restores global order for interleaved arrivals`() {
        val firstArrival = BitchatMessage(
            id = "alias-a-first",
            sender = "alice",
            content = "first arrival through alias A",
            timestamp = Date(300)
        )
        val secondArrival = BitchatMessage(
            id = "alias-b-first",
            sender = "alice",
            content = "second arrival through alias B",
            timestamp = Date(100)
        )
        val thirdArrival = BitchatMessage(
            id = "alias-a-second",
            sender = "alice",
            content = "third arrival through alias A",
            timestamp = Date(200)
        )
        AppStateStore.addPrivateMessage("target-peer", firstArrival)
        AppStateStore.addPrivateMessage("source-alias", secondArrival)
        AppStateStore.addPrivateMessage("target-peer", thirdArrival)
        state.setPrivateChats(
            linkedMapOf(
                "target-peer" to listOf(firstArrival, thirdArrival),
                "source-alias" to listOf(secondArrival)
            )
        )

        ConversationAliasResolver.unifyChatsIntoPeer(
            state = state,
            targetPeerID = "target-peer",
            keysToMerge = listOf("source-alias")
        )

        assertEquals(
            listOf(firstArrival, secondArrival, thirdArrival),
            state.getPrivateChatsValue()["target-peer"]
        )
        assertEquals(
            listOf(firstArrival, secondArrival, thirdArrival),
            AppStateStore.privateMessages.value["target-peer"]
        )
    }
}
