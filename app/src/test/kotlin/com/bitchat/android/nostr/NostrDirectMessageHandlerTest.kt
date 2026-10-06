package com.bitchat.android.nostr

import android.app.Application
import android.content.Context
import com.bitchat.android.favorites.FavoriteControlMessage
import com.bitchat.android.favorites.FavoritesPersistenceService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.model.*
import com.bitchat.android.services.*
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Date
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class NostrDirectMessageHandlerTest {
    private lateinit var application: Application
    private lateinit var repository: ConversationRepository
    private lateinit var favorites: FavoritesPersistenceService
    private lateinit var handler: NostrDirectMessageHandler
    private lateinit var databaseName: String
    private var failDeliveryWrites = false
    private val backingCipher = InMemoryConversationStorageCipher()
    private val cipher = object : ConversationStorageCipher by backingCipher {
        override fun encrypt(plaintext: ByteArray, associatedData: ByteArray): ByteArray {
            if (failDeliveryWrites && associatedData.toString(Charsets.UTF_8).startsWith("delivery:")) error("synthetic storage failure")
            return backingCipher.encrypt(plaintext, associatedData)
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val sender = NostrIdentity.generate()
    private val recipient = NostrIdentity.generate()
    private val noise = ByteArray(32) { 7 }
    private val conversation = ContactIdentityResolver.contactConversationIdForNoiseKey(noise)

    @Before fun setup() {
        application = RuntimeEnvironment.getApplication()
        databaseName = "dm-test-${UUID.randomUUID()}.db"
        repository = ConversationRepository(application, Dispatchers.Unconfined, databaseName, cipher)
        AppStateStore.resumePrivateConversationsAfterPanic()
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(repository)
        val secure = SecureIdentityStateManager(application.getSharedPreferences("identity-${UUID.randomUUID()}", Context.MODE_PRIVATE), testOnly = true)
        favorites = FavoritesPersistenceService(application, secure)
        favorites.updateFavoriteStatus(noise, "Synthetic contact", true)
        favorites.updateNostrPublicKey(noise, sender.publicKeyHex)
        favorites.updatePeerFavoritedUs(noise, true)
        handler = NostrDirectMessageHandler(application, scope, favoritesProvider = { favorites }, repositoryProvider = { repository },
            wakeDeliveries = {}, isViewing = { false })
    }

    @After fun cleanup() {
        scope.cancel()
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        repository.closeForTest()
        application.deleteDatabase(databaseName)
    }

    private fun message(id: String, text: String, author: NostrIdentity = sender, timestamp: Long = System.currentTimeMillis()): NostrEvent {
        val embedded = requireNotNull(NostrEmbeddedBitChat.encodePMForNostrNoRecipient(text, id, "0011223344556677", timestamp))
        return NostrProtocol.createPrivateMessage(embedded, recipient.publicKeyHex, author, timestamp).single()
    }

    @Test fun `background delivery is unread and duplicate replays recreate receipt intent`() = runBlocking {
        val event = message("hello", "Synthetic message")
        assertTrue(handler.process(event, "", recipient))
        assertEquals(1, AppStateStore.unreadPrivateMessageCounts.value[conversation])
        assertEquals("hello", AppStateStore.privateMessages.value[conversation]!!.single().wireMessageID)
        val receipt = repository.deliveryJobs().single()
        repository.removeDelivery(receipt.id) // Simulate a published receipt lost downstream.
        assertTrue(handler.process(event, "", recipient))
        assertEquals(1, repository.deliveryJobs().size)
        assertEquals(1, AppStateStore.privateMessages.value[conversation]!!.size)
        assertEquals(1, AppStateStore.unreadPrivateMessageCounts.value[conversation])
    }

    @Test fun `unknown and non-mutual account senders cannot inject messages`() = runBlocking {
        assertTrue(handler.process(message("unknown", "ignored", NostrIdentity.generate()), "", recipient))
        favorites.updatePeerFavoritedUs(noise, false)
        assertTrue(handler.process(message("non-mutual", "ignored"), "", recipient))
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
        assertTrue(repository.deliveryJobs().isEmpty())
    }

    @Test fun `favorite control cannot target another authenticated contact`() = runBlocking {
        val impostor = NostrIdentity.generate()
        val otherNoise = ByteArray(32) { 9 }
        favorites.updateNostrPublicKey(otherNoise, impostor.publicKeyHex)
        val forged = FavoriteControlMessage.encode(false, sender.npub)
        assertTrue(handler.process(message("forged-control", forged, impostor), "", recipient))
        assertTrue(favorites.getFavoriteStatus(noise)!!.theyFavoritedUs)
    }

    @Test fun `receipt from wrong contact cannot advance an outgoing message`() = runBlocking {
        val otherConversation = "contact_" + "aa".repeat(32)
        val outgoing = BitchatMessage(id = "outgoing", sender = "self", content = "test", timestamp = Date(),
            isPrivate = true, senderPeerID = "ffeeddccbbaa0099", deliveryStatus = DeliveryStatus.Sent)
        assertTrue(AppStateStore.addPrivateMessageDurably(otherConversation, outgoing))
        val content = requireNotNull(NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(NoisePayloadType.READ_RECEIPT, outgoing.id, "0011223344556677"))
        val event = NostrProtocol.createPrivateMessage(content, recipient.publicKeyHex, sender).single()
        assertTrue(handler.process(event, "", recipient))
        assertEquals(DeliveryStatus.Sent, repository.storedMessage(otherConversation, outgoing.id)!!.deliveryStatus)
    }

    @Test fun `same wire ID in different contacts does not merge histories`() = runBlocking {
        val other = NostrIdentity.generate()
        val otherNoise = ByteArray(32) { 11 }
        favorites.updateFavoriteStatus(otherNoise, "Other synthetic contact", true)
        favorites.updateNostrPublicKey(otherNoise, other.publicKeyHex)
        favorites.updatePeerFavoritedUs(otherNoise, true)
        assertTrue(handler.process(message("shared-wire-id", "first"), "", recipient))
        assertTrue(handler.process(message("shared-wire-id", "second", other), "", recipient))
        assertEquals(2, AppStateStore.privateMessages.value.size)
        assertEquals(2, AppStateStore.privateMessages.value.values.flatten().map { it.id }.toSet().size)
    }

    @Test fun `twenty-nine-day-old message is admitted using its authenticated timestamp`() = runBlocking {
        val timestamp = System.currentTimeMillis() - 29L * 86_400_000
        assertTrue(handler.process(message("old", "retained", timestamp = timestamp), "", recipient))
        assertEquals(timestamp / 1000 * 1000, AppStateStore.privateMessages.value[conversation]!!.single().timestamp.time)
    }
    @Test fun `receipt persistence failure rolls back admission and permits retransmission`() = runBlocking {
        val event = message("retry-storage", "Synthetic retry")
        failDeliveryWrites = true
        assertFalse(handler.process(event, "", recipient))
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
        assertTrue(repository.deliveryJobs().isEmpty())
        failDeliveryWrites = false
        assertTrue(handler.process(event, "", recipient))
        assertEquals(1, AppStateStore.privateMessages.value[conversation]!!.size)
        assertEquals(1, repository.deliveryJobs().size)
    }

    @Test fun `deleted conversation replay is consumed without recreating messages or receipts`() = runBlocking {
        val event = message("deleted", "Synthetic deleted message")
        assertTrue(handler.process(event, "", recipient))
        repository.deleteConversationAndWait(conversation, setOf(conversation))
        AppStateStore.clear()
        assertTrue(handler.process(event, "", recipient))
        assertTrue(repository.deliveryJobs().isEmpty())
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
    }

    @Test fun `stale control cannot reverse a newer authenticated unfavorite`() = runBlocking {
        val now = System.currentTimeMillis()
        assertTrue(handler.process(message("newer", FavoriteControlMessage.encode(false, sender.npub), timestamp = now), "", recipient))
        assertTrue(handler.process(message("older", FavoriteControlMessage.encode(true, sender.npub), timestamp = now - 1000), "", recipient))
        assertFalse(favorites.getFavoriteStatus(noise)!!.theyFavoritedUs)
    }

    @Test fun `event queued before a state reset cannot mutate the new generation`() = runBlocking {
        val token = AppStateStore.privateConversationToken()
        val event = message("stale-epoch", "Synthetic stale event")
        AppStateStore.clear()
        assertFalse(handler.process(event, "", recipient, token))
        assertTrue(repository.deliveryJobs().isEmpty())
    }

}
