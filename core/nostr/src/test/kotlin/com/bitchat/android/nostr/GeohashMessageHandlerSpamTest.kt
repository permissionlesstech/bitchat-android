package com.bitchat.android.nostr

import android.app.Application
import com.bitchat.android.model.BitchatMessage
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.spam.SpamFilter
import com.bitchat.android.ui.DataManager
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

    // What the handler emitted, by channel key.
    private val channelMessages = mutableMapOf<String, MutableList<BitchatMessage>>()
    private lateinit var handler: GeohashMessageHandler

    @Before
    fun setup() {
        com.bitchat.android.services.AppStateStore.clear()
        SpamFilter.shared.reset()
        val dataManager = DataManager(context = application)
        handler = GeohashMessageHandler(
            application = application,
            repo = GeohashRepository(application, dataManager, testScope, NostrIdentityBridge) { "me" },
            scope = testScope,
            dataManager = dataManager,
            addChannelMessage = { channel, message ->
                channelMessages.getOrPut(channel) { mutableListOf() } += message
            },
            powPreferences = PoWPreferenceManager(application),
            signatureVerificationDispatcher = testDispatcher,
            spamFilterDispatcher = testDispatcher,
            geohashAliases = GeohashAliasRegistry,
            nostrIdentity = NostrIdentityBridge,
            spamFilter = SpamFilter.shared,
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

        assertEquals(6, channelMessages["geo:$geohash"]?.size)
    }

    @Test
    fun `same text from many pubkeys is cut off after three copies`() {
        repeat(5) {
            handler.onEvent(event(NostrIdentity.generate(), "Free sats for everyone, claim yours now"), geohash)
        }

        assertEquals(3, channelMessages["geo:$geohash"]?.size)
    }
}
