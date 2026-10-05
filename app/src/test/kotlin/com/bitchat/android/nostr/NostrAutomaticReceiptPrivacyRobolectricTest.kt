package com.bitchat.android.nostr

import java.util.Base64
import com.bitchat.android.model.DeliveryStatus
import com.bitchat.android.identity.SecureIdentityStateManager
import org.mockito.Mockito
import org.mockito.MockedConstruction
import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.services.*
import com.bitchat.android.ui.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import okhttp3.WebSocket
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config


@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@OptIn(ExperimentalCoroutinesApi::class)
class NostrAutomaticReceiptPrivacyRobolectricTest {
    private val mesh = "0123456789abcdef"
    private val a = NostrIdentity.fromPrivateKey("1".padStart(64, '0'))
    private val b = NostrIdentity.fromPrivateKey("2".padStart(64, '0'))
    private val peer = NostrIdentity.fromPrivateKey("3".padStart(64, '0'))
    private val outbound = ArrayDeque<String>()
    private val dispatcher = StandardTestDispatcher()
    private lateinit var transport: NostrTransport
    private lateinit var scope: CoroutineScope
    private lateinit var relayScope: CoroutineScope
    private lateinit var transportScope: CoroutineScope
    private lateinit var originalRelayScope: CoroutineScope
    private lateinit var originalTransportScope: CoroutineScope
    private lateinit var repository: ConversationRepository
    private lateinit var secureStorage: MockedConstruction<SecureIdentityStateManager>
    private lateinit var seenStore: SeenMessageStore
    private lateinit var state: ChatState
    private lateinit var data: DataManager
    private lateinit var handler: NostrDirectMessageHandler
    private val application get() = RuntimeEnvironment.getApplication()

    private fun reflectedField(instance: Any, name: String) =
        instance.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun <T> relayField(name: String): T =
        reflectedField(NostrRelayManager.shared, name).get(NostrRelayManager.shared) as T

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
        scope = CoroutineScope(SupervisorJob() + dispatcher)
        relayScope = CoroutineScope(SupervisorJob() + dispatcher)
        transportScope = CoroutineScope(SupervisorJob() + dispatcher)
        AppStateStore.clear()
        repository = ConversationRepository(
            context = application,
            dispatcher = dispatcher,
            databaseName = "n1-synthetic.db",
            storageCipher = InMemoryConversationStorageCipher()
        )
        AppStateStore.setConversationRepositoryForTest(repository)
        // Exercise the real receipt-ID sets; encrypted persistence is outside this fixture.
        secureStorage = Mockito.mockConstruction(SecureIdentityStateManager::class.java)
        SeenMessageStore::class.java.getDeclaredField("INSTANCE").apply {
            isAccessible = true
            set(null, null)
        }
        seenStore = SeenMessageStore.getInstance(application)
        transport = NostrTransport.getInstance(application)
        transport.senderPeerID = mesh
        originalTransportScope = reflectedField(transport, "transportScope").get(transport) as CoroutineScope
        reflectedField(transport, "transportScope").set(transport, transportScope)
        originalRelayScope = relayField("scope")
        reflectedField(NostrRelayManager.shared, "scope").set(NostrRelayManager.shared, relayScope)
        val socket = mock<WebSocket>()
        whenever(socket.send(any<String>())).thenAnswer {
            outbound.addLast(it.getArgument(0))
            true
        }
        relayField<MutableList<NostrRelayManager.Relay>>("relaysList").apply {
            clear()
            add(NostrRelayManager.Relay("wss://relay.example", true))
        }
        relayField<MutableMap<String, WebSocket>>("connections").apply {
            clear()
            put("wss://relay.example", socket)
        }
        state = ChatState(scope).apply { setNickname("synthetic recipient") }
        data = DataManager(application)
        val manager = PrivateChatManager(state, MessageManager(state), data, mock<NoiseSessionDelegate>())
        handler = NostrDirectMessageHandler(
            application, state, manager, { _, _ -> }, scope,
            GeohashRepository(application, state, data), data
        ) { seenStore }
    }

    private fun finishPendingWork() {
        dispatcher.scheduler.advanceUntilIdle()
        assertFalse("transport worker still active", transportScope.coroutineContext[Job]!!.children.any())
        assertFalse("relay worker still active", relayScope.coroutineContext[Job]!!.children.any())
        assertEquals(0, relayField<NostrPendingEventQueue>("messageQueue").size())
    }

    @After
    fun teardown() {
        try {
            finishPendingWork()
        } finally {
            scope.cancel()
            transportScope.cancel()
            relayScope.cancel()
            reflectedField(transport, "transportScope").set(transport, originalTransportScope)
            reflectedField(NostrRelayManager.shared, "scope").set(NostrRelayManager.shared, originalRelayScope)
            transport.cleanup()
            NostrTransport::class.java.getDeclaredField("INSTANCE").apply {
                isAccessible = true
                set(null, null)
            }
            relayField<MutableList<NostrRelayManager.Relay>>("relaysList").clear()
            relayField<MutableMap<String, WebSocket>>("connections").clear()
            relayField<NostrPendingEventQueue>("messageQueue").clear()
            SeenMessageStore::class.java.getDeclaredField("INSTANCE").apply {
                isAccessible = true
                set(null, null)
            }
            secureStorage.close()
            AppStateStore.clear()
            AppStateStore.setConversationRepositoryForTest(null)
            repository.closeForTest()
            application.deleteDatabase("n1-synthetic.db")
            Dispatchers.resetMain()
        }
    }

    private fun captured(identity: NostrIdentity): BitchatPacket {
        finishPendingWork()
        assertTrue("no captured local WebSocket event", outbound.isNotEmpty())
        val event = Gson().fromJson(JsonParser.parseString(outbound.removeFirst()).asJsonArray[1], NostrEvent::class.java)
        val opened = requireNotNull(NostrProtocol.decryptPrivateMessage(event, peer))
        assertEquals(identity.publicKeyHex, opened.second)
        return requireNotNull(BitchatPacket.fromBinaryData(Base64.getUrlDecoder().decode(opened.first.removePrefix("bitchat1:"))))
    }

    private fun inbound(identity: NostrIdentity, id: String, blocked: Boolean = false, geohash: String = "00000") {
        if (blocked) data.addGeohashBlockedUser(peer.publicKeyHex)
        val content = requireNotNull(NostrEmbeddedBitChat.encodePMForNostrNoRecipient("synthetic input", id))
        handler.onGiftWrap(NostrProtocol.createPrivateMessage(content, identity.publicKeyHex, peer).single(), geohash, identity)
        finishPendingWork()
    }

    @Test(timeout = 10000)
    fun changingConfiguredMeshIdDoesNotEnterAutomaticReceipts() {
        transport.senderPeerID = "1111111111111111"
        inbound(a, "counter-a")
        val first = captured(a)
        transport.senderPeerID = "2222222222222222"
        inbound(b, "counter-b")
        val second = captured(b)
        assertFalse(first.senderID.contentEquals(second.senderID))
        assertFalse(first.senderID.contentEquals(ByteArray(8) { 0x11 }))
        assertFalse(second.senderID.contentEquals(ByteArray(8) { 0x22 }))
        assertEquals(NoisePayloadType.DELIVERED, NoisePayload.decode(first.payload)?.type)
        assertEquals("counter-a", String(requireNotNull(NoisePayload.decode(first.payload)).data))
    }
    @Test(timeout = 10000)
    fun automaticReceiptsUseDifferentHeadersWithoutUserReplyOrRead() {
        inbound(a, "input-a")
        val first = captured(a)
        inbound(b, "input-b")
        val second = captured(b)
        assertFalse(first.senderID.contentEquals(byteArrayOf(1, 35, 69, 103, -119, -85, -51, -17)))
        assertFalse(first.senderID.contentEquals(second.senderID))
        assertEquals(NoisePayloadType.DELIVERED, NoisePayload.decode(first.payload)?.type)
    }
    @Test(timeout = 10000)
    fun accountInboxReceiptPreservesIdWithRandomHeader() {
        inbound(a, "account-inbox-id", geohash = "")
        val packet = captured(a)
        val payload = requireNotNull(NoisePayload.decode(packet.payload))
        assertEquals(NoisePayloadType.DELIVERED, payload.type)
        assertEquals("account-inbox-id", String(payload.data, Charsets.UTF_8))
        assertFalse(packet.senderID.contentEquals(byteArrayOf(1, 35, 69, 103, -119, -85, -51, -17)))
    }
    @Test(timeout = 10000)
    fun randomHeaderReceiptsPreserveStatusAndConversationInGeohashAndAccountContexts() {
        val observed = mutableListOf<Pair<String, DeliveryStatus>>()
        val receiptHandler = NostrDirectMessageHandler(
            application, state, mock<PrivateChatManager>(),
            { id, status -> observed.add(id to status) }, scope,
            GeohashRepository(application, state, data), data
        ) { seenStore }
        val conversation = "nostr_${peer.publicKeyHex.take(16)}"
        for (geohash in listOf("00000", "")) {
            for (type in listOf(NoisePayloadType.DELIVERED, NoisePayloadType.READ_RECEIPT)) {
                val id = "receipt-${type.name}-$geohash"
                val content = requireNotNull(NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type, id))
                receiptHandler.onGiftWrap(
                    NostrProtocol.createPrivateMessage(content, a.publicKeyHex, peer).single(), geohash, a
                )
                finishPendingWork()
                val (observedId, status) = observed.removeAt(0)
                assertEquals(id, observedId)
                when (type) {
                    NoisePayloadType.DELIVERED -> {
                        assertTrue(status is DeliveryStatus.Delivered)
                        assertEquals(conversation, (status as DeliveryStatus.Delivered).to)
                    }
                    NoisePayloadType.READ_RECEIPT -> {
                        assertTrue(status is DeliveryStatus.Read)
                        assertEquals(conversation, (status as DeliveryStatus.Read).by)
                    }
                    else -> error("unexpected test type")
                }
                assertTrue(observed.isEmpty())
            }
        }
    }

    @Test(timeout = 10000)
    fun sharedReceiptStoreSuppressesKnownIdsAndAllowsFreshIdsAcrossPersonas() {
        seenStore.markDelivered("known-a")
        seenStore.markDelivered("known-b")
        inbound(a, "known-a")
        inbound(b, "known-b")
        assertTrue(outbound.isEmpty())
        val admittedIds = AppStateStore.privateMessages.value.values.flatten().map { it.id }
        assertTrue(admittedIds.containsAll(listOf("known-a", "known-b")))
        inbound(b, "fresh-id")
        val packet = captured(b)
        assertEquals("fresh-id", String(requireNotNull(NoisePayload.decode(packet.payload)).data, Charsets.UTF_8))
        assertTrue(seenStore.hasDelivered("fresh-id"))
        // This replay checks duplicate silence; the preseeded IDs above isolate the store guard.
        inbound(a, "fresh-id")
        assertTrue(outbound.isEmpty())
    }

    @Test(timeout = 10000)
    fun blockedSenderProducesNeitherAdmittedMessageNorOutboundReceipt() {
        inbound(a, "blocked-input", blocked = true)
        assertTrue(outbound.isEmpty())
        assertTrue(AppStateStore.privateMessages.value.isEmpty())
    }
    @Test(timeout = 10000)
    fun outgoingGeohashMessageDoesNotEmbedMeshId() {
        val cacheField = NostrIdentityBridge::class.java.getDeclaredField("geohashIdentityCache").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val cache = cacheField.get(NostrIdentityBridge) as MutableMap<String, NostrIdentity>
        cache["00000"] = a
        try {
            transport.sendPrivateMessageGeohash("synthetic outgoing", peer.publicKeyHex, "outgoing-id", "00000")
            val result = captured(a)
            assertFalse(result.senderID.contentEquals(byteArrayOf(1, 35, 69, 103, -119, -85, -51, -17)))
            assertEquals(NoisePayloadType.PRIVATE_MESSAGE, NoisePayload.decode(result.payload)?.type)
        } finally {
            cache.remove("00000")
        }
    }
    @Test(timeout = 10000)
    fun readReceiptDoesNotEmbedMeshId() {
        transport.sendReadReceiptGeohash("read-id", peer.publicKeyHex, a)
        val result = captured(a)
        assertFalse(result.senderID.contentEquals(byteArrayOf(1, 35, 69, 103, -119, -85, -51, -17)))
        assertEquals(NoisePayloadType.READ_RECEIPT, NoisePayload.decode(result.payload)?.type)
    }
}
