package com.bitchat.android.nostr

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression coverage for GH-733: geohash events (kind 20000/20001) were accepted from the
 * relay without Schnorr signature verification, allowing full pubkey impersonation.
 */
@RunWith(RobolectricTestRunner::class)
class GeohashMessageHandlerSignatureTest {

    private val application: Application = ApplicationProvider.getApplicationContext()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var chatState: ChatState
    private lateinit var dataManager: DataManager
    private lateinit var messageManager: MessageManager
    private lateinit var repo: GeohashRepository
    private lateinit var handler: GeohashMessageHandler

    private val geohash = "u4pruy"

    @Before
    fun setup() {
        chatState = ChatState(scope = testScope)
        dataManager = DataManager(context = application)
        messageManager = MessageManager(state = chatState)
        repo = GeohashRepository(application, chatState, dataManager)
        handler = GeohashMessageHandler(
            application = application,
            repo = repo,
            scope = testScope,
            dataManager = dataManager,
            addChannelMessage = messageManager::addChannelMessage,
            signatureVerificationDispatcher = testDispatcher
        )
    }

    private fun buildSignedEvent(identity: NostrIdentity, content: String): NostrEvent {
        val unsigned = NostrEvent(
            pubkey = identity.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.EPHEMERAL_EVENT,
            tags = listOf(listOf("g", geohash)),
            content = content
        )
        return unsigned.sign(identity.privateKeyHex)
    }

    @Test
    fun onEvent_acceptsGenuinelySignedEvent() {
        val victim = NostrIdentity.generate()
        val genuine = buildSignedEvent(victim, "hello from the real victim")

        handler.onEvent(genuine, geohash)

        val stored = chatState.getChannelMessagesValue()["geo:$geohash"]
        assertEquals(1, stored?.size)
        assertEquals("hello from the real victim", stored?.first()?.content)
    }

    @Test
    fun onEvent_rejectsEventWithForgedSignature() {
        val victim = NostrIdentity.generate()
        val attacker = NostrIdentity.generate()

        // Attacker claims the victim's pubkey but signs with their own key - a forged event.
        val forged = buildSignedEvent(victim, "impersonated message").copy(
            sig = NostrEvent(
                pubkey = attacker.publicKeyHex,
                createdAt = (System.currentTimeMillis() / 1000).toInt(),
                kind = NostrKind.EPHEMERAL_EVENT,
                tags = listOf(listOf("g", geohash)),
                content = "impersonated message"
            ).sign(attacker.privateKeyHex).sig
        )

        // Sanity check: this event is indeed invalid before we even touch the handler.
        assertEquals(false, forged.isValidSignature())

        handler.onEvent(forged, geohash)

        val stored = chatState.getChannelMessagesValue()["geo:$geohash"]
        assertNull("Forged event must not be rendered as a legitimate message", stored)
    }

    @Test
    fun onEvent_stillAcceptsGenuineEventAfterForgedCopyWithSameIdWasRejected() {
        val victim = NostrIdentity.generate()
        val attacker = NostrIdentity.generate()
        val genuine = buildSignedEvent(victim, "hello from the real victim")

        // A forged event carrying the SAME id as the genuine one (ids are a content hash,
        // independent of the signature), but signed by an attacker - relays can deliver this
        // before the genuine copy arrives from another relay.
        val forgedWithSameId = genuine.copy(
            sig = NostrEvent(
                pubkey = attacker.publicKeyHex,
                createdAt = genuine.createdAt,
                kind = NostrKind.EPHEMERAL_EVENT,
                tags = listOf(listOf("g", geohash)),
                content = "hello from the real victim"
            ).sign(attacker.privateKeyHex).sig
        )
        assertEquals(genuine.id, forgedWithSameId.id)
        assertEquals(false, forgedWithSameId.isValidSignature())

        // Forged copy arrives first and must be rejected without poisoning the dedup cache.
        handler.onEvent(forgedWithSameId, geohash)
        assertNull(chatState.getChannelMessagesValue()["geo:$geohash"])

        // The genuine copy (same id, valid signature) arrives afterwards from another relay -
        // it must still be rendered, not silently dropped as a "duplicate".
        handler.onEvent(genuine, geohash)

        val stored = chatState.getChannelMessagesValue()["geo:$geohash"]
        assertEquals(1, stored?.size)
        assertEquals("hello from the real victim", stored?.first()?.content)
    }
}
