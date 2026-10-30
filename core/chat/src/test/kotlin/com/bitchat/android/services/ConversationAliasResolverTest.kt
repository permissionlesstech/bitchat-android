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
import org.mockito.kotlin.mock

class ConversationAliasResolverTest {
    private val stores = TestMeshStores(mock())
    private lateinit var scope: CoroutineScope
    private lateinit var state: ChatState

    @Before
    fun setUp() {
        scope = CoroutineScope(SupervisorJob())
        state = ChatState(scope, stores.appStateStore)
    }

    @After
    fun tearDown() {
        scope.cancel()
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
        stores.appStateStore.addPrivateMessage("target-peer", firstArrival)
        stores.appStateStore.addPrivateMessage("source-alias", secondArrival)
        stores.appStateStore.addPrivateMessage("target-peer", thirdArrival)
        state.setPrivateChats(
            linkedMapOf(
                "target-peer" to listOf(firstArrival, thirdArrival),
                "source-alias" to listOf(secondArrival)
            )
        )

        ConversationAliasResolver.unifyChatsIntoPeer(
            state = state,
            targetPeerID = "target-peer",
            keysToMerge = listOf("source-alias"),
            contactDirectory = stores.contactDirectory,
            appStateStore = stores.appStateStore,
        )

        assertEquals(
            listOf(firstArrival, secondArrival, thirdArrival),
            state.getPrivateChatsValue()["target-peer"]
        )
        assertEquals(
            listOf(firstArrival, secondArrival, thirdArrival),
            stores.appStateStore.privateMessages.value["target-peer"]
        )
    }
}
