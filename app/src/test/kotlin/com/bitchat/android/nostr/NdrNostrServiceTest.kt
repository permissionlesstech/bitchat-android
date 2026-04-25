package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrNostrServiceTest {

    @Test
    fun configureCachesInviteAndSkipsOobSubscriptions() {
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager().apply {
            drainedEvents += NdrPubSubEvent(
                kind = "publish_signed",
                eventJson = """
                    {"id":"invite1","pubkey":"sender","created_at":1,"kind":30078,"tags":[["l","double-ratchet/invites"]],"content":"invite","sig":"sig"}
                """.trimIndent()
            )
            drainedEvents += NdrPubSubEvent(
                kind = "subscribe",
                subid = "giftwrap-oob",
                filterJson = """{"kinds":[1059],"#p":["peer"]}"""
            )
            drainedEvents += NdrPubSubEvent(
                kind = "subscribe",
                subid = "messages",
                filterJson = """{"authors":["peer"],"kinds":[1060]}"""
            )
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )

        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "11".repeat(32),
                publicKeyHex = "22".repeat(32),
                npub = "npub-test",
                createdAt = 1L
            )
        )

        assertEquals("invite1", NostrEvent.fromJsonString(service.currentInviteEventJson()!!)?.id)
        assertEquals(listOf("messages"), relayManager.subscriptions.map { it.id })
    }

    @Test
    fun processOutOfBandInviteReturnsGiftWrapResponseWithoutPublishingIt() {
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager().apply {
            acceptInviteEvents += NdrPubSubEvent(
                kind = "publish_signed",
                eventJson = """
                    {"id":"response1","pubkey":"sender","created_at":1,"kind":1059,"tags":[["p","peer"]],"content":"wrapped","sig":"sig"}
                """.trimIndent()
            )
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )
        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "11".repeat(32),
                publicKeyHex = "22".repeat(32),
                npub = "npub-test",
                createdAt = 1L
            )
        )

        val outbound = service.processOutOfBandEventJson(
            """
                {"id":"invite1","pubkey":"sender","created_at":1,"kind":30078,"tags":[["l","double-ratchet/invites"]],"content":"invite","sig":"sig"}
            """.trimIndent()
        )

        assertEquals(1, outbound.outboundPayloads.size)
        assertEquals("response1", NostrEvent.fromJsonString(outbound.outboundPayloads.single())?.id)
        assertTrue(relayManager.sentEvents.isEmpty())
    }

    @Test
    fun inboundDecryptedMessageCallsCallback() {
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager().apply {
            processEvents += NdrPubSubEvent(
                kind = "decrypted_message",
                senderPubkeyHex = "ab".repeat(32),
                content = "bitchat1:payload",
                eventId = "inner-1"
            )
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )
        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "11".repeat(32),
                publicKeyHex = "22".repeat(32),
                npub = "npub-test",
                createdAt = 1L
            )
        )

        var message: NdrDecryptedMessage? = null
        service.onDecryptedMessage = { message = it }

        service.processInboundRelayEvent(
            NostrEvent(
                id = "outer-1",
                pubkey = "cd".repeat(32),
                createdAt = 123,
                kind = 1060,
                tags = listOf(listOf("p", "22".repeat(32))),
                content = "ciphertext",
                sig = "sig"
            )
        )

        assertEquals("inner-1", message?.eventId)
        assertEquals("bitchat1:payload", message?.content)
        assertEquals("ab".repeat(32), message?.senderPubkeyHex)
        assertNull(message?.innerEventJson)
    }

    @Test
    fun processOutOfBandResponseUsesAcceptedOwnerAsSessionLookupKey() {
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager(
            activeSessionPeers = mutableSetOf("cc".repeat(32))
        ).apply {
            acceptInviteEventResult = NdrAcceptInviteResult(
                ownerPubkeyHex = "cc".repeat(32),
                inviterDevicePubkeyHex = "aa".repeat(32),
                deviceId = "device-1",
                createdNewSession = true
            )
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )
        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "11".repeat(32),
                publicKeyHex = "22".repeat(32),
                npub = "npub-test",
                createdAt = 1L
            )
        )

        val result = service.processOutOfBandEventJson(
            """
                {"id":"invite1","pubkey":"${"aa".repeat(32)}","created_at":1,"kind":30078,"tags":[["l","double-ratchet/invites"]],"content":"invite","sig":"sig"}
            """.trimIndent()
        )

        assertEquals("cc".repeat(32), result.sessionLookupPubkeyHex)
    }

    @Test
    fun sendIfPossibleReturnsFalseWhenNoActiveSessionExists() {
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager().apply {
            sendTextResult = listOf("outer-1")
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )
        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "11".repeat(32),
                publicKeyHex = "22".repeat(32),
                npub = "npub-test",
                createdAt = 1L
            )
        )

        assertFalse(service.sendIfPossible("hello", "aa".repeat(32)))
        assertTrue(runtime.sendTextCalls.isEmpty())
    }

    @Test
    fun sendIfPossibleReturnsTrueWhenActiveSessionQueuesNoRelayPublish() {
        val peer = "aa".repeat(32)
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager(mutableSetOf(peer)).apply {
            sendTextResult = emptyList()
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )
        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "11".repeat(32),
                publicKeyHex = "22".repeat(32),
                npub = "npub-test",
                createdAt = 1L
            )
        )

        assertTrue(service.sendIfPossible("hello", peer))
        assertEquals(listOf(peer), runtime.sendTextCalls)
    }

    private fun extractNostrKind(eventJson: String): Int {
        return requireNotNull(NostrEvent.fromJsonString(eventJson)?.kind)
    }

    private class FakeNdrRuntimeFactory(
        private val runtime: FakeNdrSessionManager
    ) : NdrSessionManagerFactory {
        override fun newWithStoragePath(
            ourPubkeyHex: String,
            ourIdentityPrivkeyHex: String,
            deviceId: String,
            storagePath: String,
            ownerPubkeyHex: String?
        ): NdrSessionManager = runtime
    }

    private class FakeRelayManager : NdrRelayManager {
        data class Subscription(val id: String, val filter: NostrFilter)

        val subscriptions = mutableListOf<Subscription>()
        val unsubscribed = mutableListOf<String>()
        val sentEvents = mutableListOf<NostrEvent>()

        override fun subscribe(filter: NostrFilter, id: String, handler: (NostrEvent) -> Unit) {
            subscriptions += Subscription(id = id, filter = filter)
        }

        override fun unsubscribe(id: String) {
            unsubscribed += id
        }

        override fun sendEvent(event: NostrEvent) {
            sentEvents += event
        }
    }

    private class FakeNdrSessionManager(
        private val activeSessionPeers: MutableSet<String> = mutableSetOf()
    ) : NdrSessionManager {
        val drainedEvents = ArrayDeque<NdrPubSubEvent>()
        val processedEvents = mutableListOf<String>()
        val acceptedInvites = mutableListOf<String>()
        val acceptedInviteUrls = mutableListOf<String>()
        val acceptInviteEvents = mutableListOf<NdrPubSubEvent>()
        val acceptInviteUrlEvents = mutableListOf<NdrPubSubEvent>()
        val processEvents = mutableListOf<NdrPubSubEvent>()
        val acceptedInviteOwnerHints = mutableListOf<String?>()
        val acceptedInviteUrlOwnerHints = mutableListOf<String?>()
        val sendTextCalls = mutableListOf<String>()
        var acceptInviteEventResult = NdrAcceptInviteResult(
            ownerPubkeyHex = "aa".repeat(32),
            inviterDevicePubkeyHex = "bb".repeat(32),
            deviceId = "device-1",
            createdNewSession = true
        )
        var acceptInviteUrlResult = NdrAcceptInviteResult(
            ownerPubkeyHex = "aa".repeat(32),
            inviterDevicePubkeyHex = "bb".repeat(32),
            deviceId = "device-1",
            createdNewSession = true
        )
        var sendTextResult: List<String> = listOf("outer-1")

        override fun init() = Unit

        override fun acceptInviteFromEventJson(
            eventJson: String,
            ownerPubkeyHintHex: String?
        ): NdrAcceptInviteResult {
            acceptedInvites += eventJson
            acceptedInviteOwnerHints += ownerPubkeyHintHex
            drainedEvents.addAll(acceptInviteEvents)
            return acceptInviteEventResult
        }

        override fun acceptInviteFromUrl(
            inviteUrl: String,
            ownerPubkeyHintHex: String?
        ): NdrAcceptInviteResult {
            acceptedInviteUrls += inviteUrl
            acceptedInviteUrlOwnerHints += ownerPubkeyHintHex
            drainedEvents.addAll(acceptInviteUrlEvents)
            return acceptInviteUrlResult
        }

        override fun processEvent(eventJson: String) {
            processedEvents += eventJson
            drainedEvents.addAll(processEvents)
        }

        override fun drainEvents(): List<NdrPubSubEvent> = buildList {
            while (drainedEvents.isNotEmpty()) {
                add(drainedEvents.removeFirst())
            }
        }

        override fun getActiveSessionState(peerPubkeyHex: String): String? {
            return peerPubkeyHex.takeIf { activeSessionPeers.contains(it.lowercase()) }?.let { """{"peer":"$it"}""" }
        }

        override fun sendText(
            recipientPubkeyHex: String,
            text: String,
            expiresAtSeconds: ULong?
        ): List<String> {
            sendTextCalls += recipientPubkeyHex
            return sendTextResult
        }

        override fun getOurPubkeyHex(): String = "22".repeat(32)

        override fun getTotalSessions(): ULong = 0u

        override fun destroy() = Unit
    }
}
