package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.spam.SpamFilterConfig
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
        SpamFilter.shared.config = SpamFilterConfig()
        SpamFilter.shared.enabled = true
        SpamFilter.shared.debugBypass = false
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
        SpamFilter.shared.config = SpamFilterConfig()
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
        // Isolate the pre-existing rate rule from the deliberately stricter short-text classifier.
        SpamFilter.shared.config = SpamFilterConfig(englishGibberishEnabled = false)
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
    fun `English model suppresses the signed random identity flood while ordinary messages arrive`() {
        verifyRandomIdentityFlood(spaced = true)
    }

    @Test
    fun `long unbroken gibberish from fresh signed identities is filtered before the timeline`() {
        verifyRandomIdentityFlood(spaced = false)
    }

    private fun verifyRandomIdentityFlood(spaced: Boolean) {
        val seed = System.getenv("TEST_SEED")?.toIntOrNull() ?: 999
        val random = kotlin.random.Random(seed)
        val sentIds = HashSet<String>()
        val senders = HashSet<String>()
        val events = ArrayList<NostrEvent>()
        repeat(500) {
            val privateKey = buildString {
                repeat(64) { append("0123456789abcdef"[random.nextInt(16)]) }
            }
            val identity = NostrIdentity.fromPrivateKey(privateKey)
            val content = buildString {
                repeat(80) { index -> append(if (spaced && index % 8 == 7) ' ' else 'a' + random.nextInt(26)) }
            }
            val signed = event(identity, content)
            sentIds.add(signed.id)
            senders.add(identity.publicKeyHex)
            events.add(signed)
        }
        SpamFilter.shared.config = SpamFilterConfig(englishGibberishEnabled = false)
        events.forEach { handler.onEvent(it, geohash) }
        assertEquals(500, chatState.getChannelMessagesValue()["geo:$geohash"].orEmpty().size)

        setup()
        events.forEach { handler.onEvent(it, geohash) }
        val received = chatState.getChannelMessagesValue()["geo:$geohash"].orEmpty()
        println("TEST_SEED=$seed signed-random-identities spaced=$spaced sent=${sentIds.size} baselineReceived=500 received=${received.size}")
        assertEquals(500, senders.size)
        assertTrue("TEST_SEED=$seed admitted=${received.size}", received.size <= 5)
        assertTrue(received.all { it.id in sentIds && it.senderNostrPubkey in senders })

        val ordinary = event(
            NostrIdentity.fromSeed("synthetic English model ordinary control"),
            "Could somebody explain the difference between these two examples?"
        )
        handler.onEvent(ordinary, geohash)
        assertTrue(chatState.getChannelMessagesValue()["geo:$geohash"].orEmpty().any { it.id == ordinary.id })
    }
}
