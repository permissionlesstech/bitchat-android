package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The proof-of-work setting only adds work to this device's own messages, as on iOS. It must
 * not hide peers who mine less (iOS mines 8 bits) or not at all (Android's default).
 */
@RunWith(RobolectricTestRunner::class)
class GeohashMessageHandlerPowTest {

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
        PoWPreferenceManager.init(application)
        PoWPreferenceManager.setPowEnabled(true)
        PoWPreferenceManager.setPowDifficulty(12)
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
        PoWPreferenceManager.setPowEnabled(false)
        SpamFilter.shared.reset()
        com.bitchat.android.services.AppStateStore.clear()
    }

    private fun unsigned(identity: NostrIdentity, content: String) = NostrEvent(
        pubkey = identity.publicKeyHex,
        createdAt = (System.currentTimeMillis() / 1000).toInt(),
        kind = NostrKind.EPHEMERAL_EVENT,
        tags = listOf(listOf("g", geohash)),
        content = content
    )

    private fun shown() = chatState.getChannelMessagesValue()["geo:$geohash"]?.size ?: 0

    @Test
    fun `with PoW on, messages without PoW are still shown`() {
        val peer = NostrIdentity.generate()
        handler.onEvent(unsigned(peer, "hello from a peer with PoW off").sign(peer.privateKeyHex), geohash)

        assertEquals(1, shown())
    }

    @Test
    fun `with PoW on, messages mined below this device's difficulty are still shown`() {
        val peer = NostrIdentity.generate()
        val mined = runBlocking { NostrProofOfWork.mineEvent(unsigned(peer, "hello from iOS"), 8) }!!
        handler.onEvent(mined.sign(peer.privateKeyHex), geohash)

        assertEquals(1, shown())
    }
}
