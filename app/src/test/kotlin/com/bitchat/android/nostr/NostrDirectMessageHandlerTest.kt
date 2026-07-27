package com.bitchat.android.nostr

import android.os.Build
import com.bitchat.android.model.NdrFeatureGate
import com.bitchat.android.services.AppStateStore
import com.bitchat.android.services.SeenMessageStore
import com.bitchat.android.ui.ChatState
import com.bitchat.android.ui.DataManager
import com.bitchat.android.ui.MessageManager
import com.bitchat.android.ui.NoiseSessionDelegate
import com.bitchat.android.ui.PrivateChatManager
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class NostrDirectMessageHandlerTest {
    private val gson = Gson()
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        NdrFeatureGate.setEnabledForTests(false)
        NostrInboundAccountLifecycle.invalidate()
        AppStateStore.clear()
    }

    @After
    fun tearDown() {
        NostrInboundAccountLifecycle.invalidate()
        NdrFeatureGate.setEnabledForTests(false)
        AppStateStore.clear()
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
            state = state,
            privateChatManager = privateChatManager,
            updateDeliveryStatus = { _, _ -> },
            scope = scope,
            repo = GeohashRepository(application, state, dataManager),
            dataManager = dataManager,
            seenStoreProvider = { seenStore },
            legacyNostrInboundAllowed = { true }
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

        val accountEpoch = handler.configureAccount(recipient)
        handler.onGiftWrap(first, "", recipient, accountEpoch)
        waitForMessage(state, firstId)
        handler.onGiftWrap(second, "", recipient, accountEpoch)
        waitForMessage(state, secondId)

        val messages = state.getPrivateChatsValue().values.single()
        assertEquals(listOf(firstId, secondId), messages.map { it.id })
        assertEquals(firstRumorTime * 1000L, messages[0].timestamp.time)
        assertEquals(secondRumorTime * 1000L, messages[1].timestamp.time)
    }

    @Test
    fun `legacy inbound policy rejects before a valid gift wrap reaches chat state`() {
        val application = RuntimeEnvironment.getApplication()
        val state = ChatState(scope).apply { setNickname("recipient") }
        val dataManager = DataManager(application)
        val privateChatManager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = dataManager,
            noiseSessionDelegate = mock<NoiseSessionDelegate>()
        )
        val seenStore = mock<SeenMessageStore>()
        val policyChecked = CompletableDeferred<String>()
        val handler = NostrDirectMessageHandler(
            application = application,
            state = state,
            privateChatManager = privateChatManager,
            updateDeliveryStatus = { _, _ -> },
            scope = scope,
            repo = GeohashRepository(application, state, dataManager),
            dataManager = dataManager,
            seenStoreProvider = { seenStore },
            legacyNostrInboundAllowed = { senderPubkey ->
                policyChecked.complete(senderPubkey)
                false
            }
        )
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val now = (System.currentTimeMillis() / 1000).toInt()
        val giftWrap = privateMessageGiftWrap(
            content = requireNotNull(
                NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                    content = "must-not-arrive",
                    messageID = "blocked-legacy",
                    senderPeerID = "0011223344556677"
                )
            ),
            sender = sender,
            recipient = recipient,
            rumorCreatedAt = now - 60,
            giftWrapCreatedAt = now - 5
        )

        val accountEpoch = handler.configureAccount(recipient)
        handler.onGiftWrap(giftWrap, "", recipient, accountEpoch)

        kotlinx.coroutines.runBlocking {
            assertEquals(sender.publicKeyHex, withTimeout(5_000) { policyChecked.await() })
            delay(10)
        }
        assertEquals(0, state.getPrivateChatsValue().values.flatten().size)
    }

    @Test
    fun `account invalidation prevents an in-flight legacy gift wrap from restoring chat state`() {
        val application = RuntimeEnvironment.getApplication()
        val state = ChatState(scope).apply { setNickname("recipient") }
        val dataManager = DataManager(application)
        val privateChatManager = PrivateChatManager(
            state = state,
            messageManager = MessageManager(state),
            dataManager = dataManager,
            noiseSessionDelegate = mock<NoiseSessionDelegate>()
        )
        val seenStore = mock<SeenMessageStore>()
        whenever(seenStore.hasDelivered(any())).thenReturn(true)
        whenever(seenStore.hasBeenReadLocally(any())).thenReturn(false)
        val policyEntered = CountDownLatch(1)
        val releasePolicy = CountDownLatch(1)
        val blockFirstPolicyCheck = AtomicBoolean(true)
        val handler = NostrDirectMessageHandler(
            application = application,
            state = state,
            privateChatManager = privateChatManager,
            updateDeliveryStatus = { _, _ -> },
            scope = scope,
            repo = GeohashRepository(application, state, dataManager),
            dataManager = dataManager,
            seenStoreProvider = { seenStore },
            legacyNostrInboundAllowed = {
                if (blockFirstPolicyCheck.compareAndSet(true, false)) {
                    policyEntered.countDown()
                    releasePolicy.await(5, TimeUnit.SECONDS)
                }
                true
            }
        )
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val now = (System.currentTimeMillis() / 1000).toInt()
        val staleGiftWrap = privateMessageGiftWrap(
            content = requireNotNull(
                NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                    content = "must-not-return",
                    messageID = "stale-legacy",
                    senderPeerID = "0011223344556677"
                )
            ),
            sender = sender,
            recipient = recipient,
            rumorCreatedAt = now - 60,
            giftWrapCreatedAt = now - 5
        )
        val freshGiftWrap = privateMessageGiftWrap(
            content = requireNotNull(
                NostrEmbeddedBitChat.encodePMForNostrNoRecipient(
                    content = "fresh",
                    messageID = "fresh-legacy",
                    senderPeerID = "0011223344556677"
                )
            ),
            sender = sender,
            recipient = recipient,
            rumorCreatedAt = now - 30,
            giftWrapCreatedAt = now - 4
        )

        val staleEpoch = handler.configureAccount(recipient)
        val staleJob = requireNotNull(
            handler.onGiftWrap(staleGiftWrap, "", recipient, staleEpoch)
        )
        try {
            assertTrue(policyEntered.await(5, TimeUnit.SECONDS))
            handler.invalidateAccount()
        } finally {
            releasePolicy.countDown()
        }
        kotlinx.coroutines.runBlocking {
            withTimeout(5_000) { staleJob.join() }
        }
        assertEquals(0, state.getPrivateChatsValue().values.flatten().size)

        val freshEpoch = handler.configureAccount(recipient)
        val freshJob = requireNotNull(
            handler.onGiftWrap(freshGiftWrap, "", recipient, freshEpoch)
        )
        kotlinx.coroutines.runBlocking {
            withTimeout(5_000) { freshJob.join() }
        }
        waitForMessage(state, "fresh-legacy")
        assertEquals(
            listOf("fresh-legacy"),
            state.getPrivateChatsValue().values.flatten().map { it.id }
        )
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
