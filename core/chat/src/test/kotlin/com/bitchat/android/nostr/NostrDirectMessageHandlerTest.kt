package com.bitchat.android.nostr

import android.os.Build
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.ConversationRepository
import com.bitchat.android.services.InMemoryConversationStorageCipher
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatPrivateMessageInbox
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NoiseSessionDelegate
import com.bitchat.android.ui.PrivateChatManager
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class NostrDirectMessageHandlerTest {
    private val gson = Gson()
    private lateinit var scope: CoroutineScope
    private lateinit var conversationRepository: ConversationRepository
    private lateinit var conversationDatabaseName: String

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        AppStateStore.clear()
        conversationDatabaseName = "nostr-dm-${UUID.randomUUID()}.db"
        conversationRepository = ConversationRepository(
            context = RuntimeEnvironment.getApplication(),
            dispatcher = Dispatchers.Unconfined,
            databaseName = conversationDatabaseName,
            storageCipher = InMemoryConversationStorageCipher()
        )
        AppStateStore.setConversationRepositoryForTest(conversationRepository)
    }

    @After
    fun tearDown() {
        AppStateStore.clear()
        AppStateStore.setConversationRepositoryForTest(null)
        conversationRepository.closeForTest()
        RuntimeEnvironment.getApplication()
            .deleteDatabase(conversationDatabaseName)
        scope.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun `private messages use authenticated rumor time instead of randomized gift wrap time`() {
        val application = RuntimeEnvironment.getApplication()
        val state = ChatState(scope).apply { setNickname("recipient") }
        val dataManager = DataManager(application)
        val messageManager = MessageManager(state)
        val privateChatManager = PrivateChatManager(
            state = state,
            messageManager = messageManager,
            dataManager = dataManager,
            noiseSessionDelegate = mock<NoiseSessionDelegate>()
        )
        val seenStore = mock<SeenMessageStore>()
        whenever(seenStore.hasDelivered(any())).thenReturn(true)
        whenever(seenStore.hasBeenReadLocally(any())).thenReturn(false)
        val handler = NostrDirectMessageHandler(
            application = application,
            inbox = ChatPrivateMessageInbox(state, privateChatManager),
            updateDeliveryStatus = { _, _ -> },
            scope = scope,
            repo = GeohashRepository(application, dataManager) { state.getNicknameValue() },
            dataManager = dataManager,
            seenStoreProvider = { seenStore }
        )
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val now = (System.currentTimeMillis() / 1000).toInt()
        val firstRumorTime = now - 120
        val secondRumorTime = now - 60
        val firstId = "first-real-time"
        val secondId = "second-real-time"

        val first = privateMessageGiftWrap(
            content = requireNotNull(
                NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                    content = "first",
                    messageID = firstId,
                    senderPeerID = "0011223344556677"
                )
            ),
            sender = sender,
            recipient = recipient,
            rumorCreatedAt = firstRumorTime,
            giftWrapCreatedAt = now - 5
        )
        val second = privateMessageGiftWrap(
            content = requireNotNull(
                NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                    content = "second",
                    messageID = secondId,
                    senderPeerID = "0011223344556677"
                )
            ),
            sender = sender,
            recipient = recipient,
            rumorCreatedAt = secondRumorTime,
            giftWrapCreatedAt = now - 86_400
        )

        handler.onGiftWrap(first, "", recipient)
        waitForMessage(state, firstId)
        handler.onGiftWrap(second, "", recipient)
        waitForMessage(state, secondId)

        val messages = state.getPrivateChatsValue().values.single()
        assertEquals(listOf(firstId, secondId), messages.map { it.id })
        assertEquals(firstRumorTime * 1000L, messages[0].timestamp.time)
        assertEquals(secondRumorTime * 1000L, messages[1].timestamp.time)
    }

    /** The sender is told a message arrived only once the session has kept it. */
    @Test
    fun `a message the inbox does not admit is never acknowledged`() {
        val application = RuntimeEnvironment.getApplication()
        val dataManager = DataManager(application)
        val seenStore = mock<SeenMessageStore>()
        whenever(seenStore.hasDelivered(any())).thenReturn(false)
        whenever(seenStore.hasBeenReadLocally(any())).thenReturn(false)
        val offered = mutableListOf<String>()
        val refusingInbox = object : PrivateMessageInbox {
            override val myNickname = "recipient"
            override val selectedConversationID: String? = null
            override fun contains(conversationID: String, messageID: String) = false
            override suspend fun admit(message: BitchatMessage, suppressUnread: Boolean): Boolean {
                offered += message.id
                return false
            }
        }
        val handler = NostrDirectMessageHandler(
            application = application,
            inbox = refusingInbox,
            updateDeliveryStatus = { _, _ -> },
            scope = scope,
            repo = GeohashRepository(application, dataManager) { "recipient" },
            dataManager = dataManager,
            seenStoreProvider = { seenStore }
        )
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val now = (System.currentTimeMillis() / 1000).toInt()
        val messageId = "refused-message"

        handler.onGiftWrap(
            privateMessageGiftWrap(
                content = requireNotNull(
                    NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                        content = "refused",
                        messageID = messageId,
                        senderPeerID = "0011223344556677"
                    )
                ),
                sender = sender,
                recipient = recipient,
                rumorCreatedAt = now - 60,
                giftWrapCreatedAt = now - 5
            ),
            "",
            recipient
        )
        kotlinx.coroutines.runBlocking {
            withTimeout(5_000) { while (messageId !in offered) delay(10) }
        }

        verify(seenStore, never()).markDelivered(messageId)
    }

    private fun waitForMessage(state: ChatState, messageId: String) {
        kotlinx.coroutines.runBlocking {
            withTimeout(5_000) {
                while (state.getPrivateChatsValue().values.flatten().none { it.id == messageId }) {
                    delay(10)
                }
            }
        }
    }

    private fun privateMessageGiftWrap(
        content: String,
        sender: NostrIdentity,
        recipient: NostrIdentity,
        rumorCreatedAt: Int,
        giftWrapCreatedAt: Int
    ): NostrEvent {
        val rumorBase = NostrEvent(
            pubkey = sender.publicKeyHex,
            createdAt = rumorCreatedAt,
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", recipient.publicKeyHex)),
            content = content
        )
        val rumor = rumorBase.copy(id = rumorBase.computeEventIdHex())
        val sealContent = NostrCrypto.encryptNIP44(
            plaintext = gson.toJson(rumor),
            recipientPublicKeyHex = recipient.publicKeyHex,
            senderPrivateKeyHex = sender.privateKeyHex
        )
        val seal = NostrEvent(
            pubkey = sender.publicKeyHex,
            createdAt = giftWrapCreatedAt,
            kind = NostrKind.SEAL,
            tags = emptyList(),
            content = sealContent
        ).sign(sender.privateKeyHex)

        val (wrapPrivateKey, wrapPublicKey) = NostrCrypto.generateKeyPair()
        val giftWrapContent = NostrCrypto.encryptNIP44(
            plaintext = gson.toJson(seal),
            recipientPublicKeyHex = recipient.publicKeyHex,
            senderPrivateKeyHex = wrapPrivateKey
        )
        return NostrEvent(
            pubkey = wrapPublicKey,
            createdAt = giftWrapCreatedAt,
            kind = NostrKind.GIFT_WRAP,
            tags = listOf(listOf("p", recipient.publicKeyHex)),
            content = giftWrapContent
        ).sign(wrapPrivateKey)
    }
}
