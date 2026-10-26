package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeohashMessageHandlerSpamTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private val geohash = "zzzzzz"

    private lateinit var chatState: ChatState
    private lateinit var handler: GeohashMessageHandler

    @Before
    fun setup() {
        com.bitchat.android.services.AppStateStore.clear()
        SpamFilter.shared.reset()
        chatState = ChatState(scope = testScope)
        val dataManager = DataManager(context = application)
        handler = GeohashMessageHandler(
            application = application,
            repo = GeohashRepository(application, chatState, dataManager, testScope),
            scope = testScope,
            dataManager = dataManager,
            addChannelMessage = MessageManager(state = chatState)::addChannelMessage,
            signatureVerificationDispatcher = testDispatcher,
            spamFilterDispatcher = testDispatcher
        )
    }

    @After
    fun tearDown() {
        SpamFilter.shared.reset()
        com.bitchat.android.services.AppStateStore.clear()
    }

    private fun event(identity: NostrIdentity, content: String) = NostrEvent(
        pubkey = identity.publicKeyHex,
        createdAt = (System.currentTimeMillis() / 1000).toInt(),
        kind = NostrKind.EPHEMERAL_EVENT,
        tags = listOf(listOf("g", geohash)),
        content = content
    ).sign(identity.privateKeyHex)

    @Test
    fun `flood from one pubkey is cut off after the burst`() {
        val flooder = NostrIdentity.generate()
        (1..8).forEach { handler.onEvent(event(flooder, "hi $it"), geohash) }

        assertEquals(6, chatState.getChannelMessagesValue()["geo:$geohash"]?.size)
    }

    @Test
    fun `same text from many pubkeys is cut off after three copies`() {
        repeat(5) {
            handler.onEvent(event(NostrIdentity.generate(), "Free sats for everyone, claim yours now"), geohash)
        }

        assertEquals(3, chatState.getChannelMessagesValue()["geo:$geohash"]?.size)
    }

    @Test
    fun `random text from fresh signed identities reaches the timeline`() {
        val seed = System.getenv("TEST_SEED")?.toIntOrNull() ?: 999
        val random = kotlin.random.Random(seed)
        val sentIds = HashSet<String>()
        val senders = HashSet<String>()
        repeat(500) {
            val privateKey = buildString {
                repeat(64) { append("0123456789abcdef"[random.nextInt(16)]) }
            }
            val identity = NostrIdentity.fromPrivateKey(privateKey)
            val content = buildString {
                repeat(80) { index -> append(if (index % 8 == 7) ' ' else 'a' + random.nextInt(26)) }
            }
            val signed = event(identity, content)
            sentIds.add(signed.id)
            senders.add(identity.publicKeyHex)
            handler.onEvent(signed, geohash)
        }
        val received = chatState.getChannelMessagesValue()["geo:$geohash"].orEmpty()
        println("TEST_SEED=$seed signed-random-identities sent=${sentIds.size} received=${received.size}")
        assertEquals(500, senders.size)
        assertEquals(500, received.size)
        assertEquals(sentIds, received.map { it.id }.toSet())
        assertEquals(senders, received.map { it.senderNostrPubkey }.toSet())
    }
}
