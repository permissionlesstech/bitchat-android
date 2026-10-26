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

    private fun event(identity: NostrIdentity, content: String, extraTags: List<List<String>> = emptyList()) = NostrEvent(
        pubkey = identity.publicKeyHex,
        createdAt = (System.currentTimeMillis() / 1000).toInt(),
        kind = NostrKind.EPHEMERAL_EVENT,
        tags = listOf(listOf("g", geohash)) + extraTags,
        content = content
    ).sign(identity.privateKeyHex)

    private fun randomText(i: Int) = (0 until 6).joinToString(" ") { w -> "w${i}x${w}" + "abcdefghij".take(1 + (i + w) % 9) }

    @Test
    fun `fresh identities share the channel pool while a time-locked identity gets through`() {
        val savedIterations = GeohashTimeLock.requiredIterations
        GeohashTimeLock.requiredIterations = 2_000
        try {
            repeat(50) { handler.onEvent(event(NostrIdentity.generate(), randomText(it)), geohash) }
            assertEquals(6, chatState.getChannelMessagesValue()["geo:$geohash"]?.size)

            val person = NostrIdentity.generate()
            val token = GeohashTimeLock.compute(person.publicKeyHex, geohash, iterations = 2_000)!!
            handler.onEvent(event(person, "hello from a real person", listOf(token.toTag())), geohash)
            handler.onEvent(event(NostrIdentity.generate(), "a fresh key without a token"), geohash)
            val shown = chatState.getChannelMessagesValue()["geo:$geohash"].orEmpty().map { it.content }
            assertEquals(7, shown.size)
            assert("hello from a real person" in shown)

            // A token copied onto another key proves nothing.
            handler.onEvent(event(NostrIdentity.generate(), "stolen token", listOf(token.toTag())), geohash)
            assertEquals(7, chatState.getChannelMessagesValue()["geo:$geohash"]?.size)
        } finally {
            GeohashTimeLock.requiredIterations = savedIterations
        }
    }

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
}
