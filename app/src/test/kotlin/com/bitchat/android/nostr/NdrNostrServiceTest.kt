package com.bitchat.android.nostr

import com.bitchat.android.model.NdrFeatureGate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NdrNostrServiceTest {
    @Before
    fun enableNdrForTest() {
        NdrFeatureGate.setEnabledForTests(true)
    }

    @After
    fun resetNdrGate() {
        NdrFeatureGate.setEnabledForTests(false)
    }

    @Test
    fun disabledRolloutGateRefusesToCreateRuntime() {
        NdrFeatureGate.setEnabledForTests(false)
        val runtime = FakeNdrSessionManager()
        val runtimeFactory = FakeNdrRuntimeFactory(runtime)
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = runtimeFactory,
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )

        service.configureIfNeeded(testIdentity())

        assertFalse(service.isConfigured)
        assertEquals(0, runtimeFactory.createdCount)
        assertFalse(service.sendIfPossible("hello", "aa".repeat(32)))
    }

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
                subid = "unlabeled-invite-discovery",
                filterJson = """{"authors":["peer"],"kinds":[30078]}"""
            )
            drainedEvents += NdrPubSubEvent(
                kind = "subscribe",
                subid = "messages",
                filterJson = """{"authors":["peer"],"kinds":[1060]}"""
            )
        }
        val runtimeFactory = FakeNdrRuntimeFactory(runtime)
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = runtimeFactory,
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
        assertEquals("/tmp/ndr-test/${"22".repeat(32)}", runtimeFactory.lastStoragePath)
    }

    @Test
    fun configureRestoresKnownPeerAppKeysFeedAndForwardsLaterRoster() {
        val peer = "ab".repeat(32)
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager().apply {
            knownPeerOwners += peer
            setupUserEvents[peer] = listOf(
                NdrPubSubEvent(
                    kind = "subscribe",
                    subid = "restored-app-keys",
                    filterJson = """{"authors":["$peer"],"kinds":[37368]}"""
                ),
                NdrPubSubEvent(
                    kind = "subscribe",
                    subid = "restored-invite-discovery",
                    filterJson = """{"authors":["$peer"],"kinds":[30078]}"""
                )
            )
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )

        service.configureIfNeeded(testIdentity())

        assertEquals(listOf(peer), runtime.setupUserCalls)
        val rosterSubscription = relayManager.subscriptions.single()
        assertEquals(listOf(37368), rosterSubscription.filter.kinds)
        assertEquals(listOf(peer), rosterSubscription.filter.authors)

        relayManager.emit(
            rosterSubscription.id,
            NostrEvent(
                id = "01".repeat(32),
                pubkey = peer,
                createdAt = 2,
                kind = 37368,
                tags = listOf(listOf("type", "app_keys_roster_snapshot")),
                content = "later-signed-roster",
                sig = "sig"
            )
        )

        assertEquals(1, runtime.processedEvents.size)
        assertEquals(
            "01".repeat(32),
            NostrEvent.fromJsonString(runtime.processedEvents.single())?.id
        )
    }

    @Test
    fun configureBuffersDecryptedMessagesUntilCallbackIsInstalled() {
        val runtime = FakeNdrSessionManager().apply {
            drainedEvents += NdrPubSubEvent(
                kind = "decrypted_message",
                senderPubkeyHex = "ab".repeat(32),
                content = "bitchat1:pending",
                eventId = "01".repeat(32)
            )
        }
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )

        service.configureIfNeeded(testIdentity())
        var delivered: NdrDecryptedMessage? = null
        service.onDecryptedMessage = { delivered = it }

        assertEquals("bitchat1:pending", delivered?.content)
        assertEquals("ab".repeat(32), delivered?.conversationPubkeyHex)
    }

    @Test
    fun decryptedMessageBufferIsBoundedAndDropsOldest() {
        val runtime = FakeNdrSessionManager().apply {
            repeat(129) { index ->
                drainedEvents += NdrPubSubEvent(
                    kind = "decrypted_message",
                    senderPubkeyHex = "ab".repeat(32),
                    content = "bitchat1:pending-$index",
                    eventId = index.toString(16).padStart(64, '0')
                )
            }
        }
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )

        service.configureIfNeeded(testIdentity())
        val delivered = mutableListOf<String>()
        service.onDecryptedMessage = { delivered += it.content }

        assertEquals(128, delivered.size)
        assertEquals("bitchat1:pending-1", delivered.first())
        assertEquals("bitchat1:pending-128", delivered.last())
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
            deviceIdProvider = { "device-1" },
            inviteOwnerResolver = ::eventPubkey
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
                {"id":"invite1","pubkey":"${"cc".repeat(32)}","created_at":1,"kind":30078,"tags":[["l","double-ratchet/invites"]],"content":"invite","sig":"sig"}
            """.trimIndent(),
            expectedPeerPubkeyHex = "cc".repeat(32)
        )

        assertEquals(1, outbound.outboundPayloads.size)
        assertEquals("response1", NostrEvent.fromJsonString(outbound.outboundPayloads.single())?.id)
        assertTrue(relayManager.sentEvents.isEmpty())
    }

    @Test
    fun successfulOwnerBootstrapPromotesDurableAppKeysFeed() {
        val owner = "cc".repeat(32)
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager(mutableSetOf(owner)).apply {
            acceptInviteEventResult = NdrAcceptInviteResult(
                ownerPubkeyHex = owner,
                inviterDevicePubkeyHex = "aa".repeat(32),
                deviceId = "owner-device",
                createdNewSession = true
            )
            setupUserEvents[owner] = listOf(
                NdrPubSubEvent(
                    kind = "subscribe",
                    subid = "durable-owner-app-keys",
                    filterJson = """{"authors":["$owner"],"kinds":[37368]}"""
                ),
                NdrPubSubEvent(
                    kind = "subscribe",
                    subid = "owner-invite-discovery",
                    filterJson = """{"authors":["$owner"],"kinds":[30078]}"""
                )
            )
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" },
            inviteOwnerResolver = { owner }
        )
        service.configureIfNeeded(testIdentity())

        service.processOutOfBandEventJson(
            """
                {"id":"invite1","pubkey":"${"aa".repeat(32)}","created_at":1,"kind":30078,"tags":[["l","double-ratchet/invites"]],"content":"invite","sig":"sig"}
            """.trimIndent(),
            expectedPeerPubkeyHex = owner
        )

        assertEquals(listOf(owner), runtime.setupUserCalls)
        assertEquals(listOf(37368), relayManager.subscriptions.single().filter.kinds)
        assertTrue(relayManager.subscriptions.none { 30078 in it.filter.kinds.orEmpty() })
    }

    @Test
    fun outOfBandProcessingRequiresAuthenticatedOwner() {
        val runtime = FakeNdrSessionManager()
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" },
            inviteOwnerResolver = { "cc".repeat(32) }
        )
        service.configureIfNeeded(testIdentity())

        val result = service.processOutOfBandEventJson(
            """
                {"id":"invite1","pubkey":"${"cc".repeat(32)}","created_at":1,"kind":30078,"tags":[["l","double-ratchet/invites"]],"content":"invite","sig":"sig"}
            """.trimIndent()
        )

        assertTrue(result.outboundPayloads.isEmpty())
        assertTrue(runtime.acceptedInvites.isEmpty())
    }

    @Test
    fun outOfBandPathRejectsNonHandshakeEvents() {
        val runtime = FakeNdrSessionManager()
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )
        service.configureIfNeeded(testIdentity())

        service.processOutOfBandEventJson(
            """
                {"id":"message1","pubkey":"${"cc".repeat(32)}","created_at":1,"kind":1060,"tags":[],"content":"ciphertext","sig":"sig"}
            """.trimIndent(),
            expectedPeerPubkeyHex = "cc".repeat(32)
        )

        assertTrue(runtime.processedEvents.isEmpty())
        assertTrue(runtime.processedOutOfBandResponses.isEmpty())
    }

    @Test
    fun relayPathRejectsKindsOutsideNdrProtocol() {
        val runtime = FakeNdrSessionManager()
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" }
        )
        service.configureIfNeeded(testIdentity())

        service.processInboundRelayEvent(
            NostrEvent(
                id = "event1",
                pubkey = "cc".repeat(32),
                createdAt = 1,
                kind = NostrKind.TEXT_NOTE,
                tags = emptyList(),
                content = "not-ndr",
                sig = "sig"
            )
        )

        assertTrue(runtime.processedEvents.isEmpty())
    }

    @Test
    fun inboundDecryptedMessageCallsCallback() {
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager().apply {
            processEvents += NdrPubSubEvent(
                kind = "decrypted_message",
                senderPubkeyHex = "ab".repeat(32),
                senderDevicePubkeyHex = "bc".repeat(32),
                conversationOwnerPubkeyHex = "cd".repeat(32),
                content = "bitchat1:payload",
                eventId = "01".repeat(32)
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

        assertEquals("01".repeat(32), message?.eventId)
        assertEquals("bitchat1:payload", message?.content)
        assertEquals("ab".repeat(32), message?.senderPubkeyHex)
        assertEquals("bc".repeat(32), message?.senderDevicePubkeyHex)
        assertEquals("cd".repeat(32), message?.conversationOwnerPubkeyHex)
    }

    @Test
    fun processOutOfBandInviteUsesOwnerRatherThanDeviceSigner() {
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
            deviceIdProvider = { "device-1" },
            inviteOwnerResolver = { "cc".repeat(32) }
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
            """.trimIndent(),
            expectedPeerPubkeyHex = "cc".repeat(32)
        )

        assertEquals("cc".repeat(32), result.sessionLookupPubkeyHex)
        assertEquals(listOf("cc".repeat(32)), runtime.acceptedInviteOwnerHints)
    }

    @Test
    fun rejectsInviteWhoseOwnerDoesNotMatchAuthenticatedFavorite() {
        val runtime = FakeNdrSessionManager()
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" },
            inviteOwnerResolver = ::eventPubkey
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
            """.trimIndent(),
            expectedPeerPubkeyHex = "cc".repeat(32)
        )

        assertTrue(result.outboundPayloads.isEmpty())
        assertTrue(runtime.acceptedInvites.isEmpty())
    }

    @Test
    fun acceptsAuthenticatedGiftWrapResponseWithEphemeralOuterPubkey() {
        val runtime = FakeNdrSessionManager()
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
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
        val giftWrap = """
            {"id":"response1","pubkey":"${"ee".repeat(32)}","created_at":1,"kind":1059,"tags":[["p","${"22".repeat(32)}"]],"content":"wrapped","sig":"sig"}
        """.trimIndent()

        service.processOutOfBandEventJson(
            giftWrap,
            expectedPeerPubkeyHex = "cc".repeat(32)
        )

        assertEquals(
            listOf(giftWrap to "cc".repeat(32)),
            runtime.processedOutOfBandResponses
        )
        assertTrue(runtime.processedEvents.isEmpty())
    }

    @Test
    fun missingOwnerRosterRetainsAndRetriesInviteOnceAppKeysArrive() {
        val owner = "cc".repeat(32)
        val device = "aa".repeat(32)
        val response = """
            {"id":"response1","pubkey":"sender","created_at":1,"kind":1059,"tags":[["p","peer"]],"content":"wrapped","sig":"sig"}
        """.trimIndent()
        val invite = """
            {"id":"invite1","pubkey":"$device","created_at":1,"kind":30078,"tags":[["l","double-ratchet/invites"]],"content":"invite","sig":"sig"}
        """.trimIndent()
        val relayManager = FakeRelayManager()
        val runtime = FakeNdrSessionManager(mutableSetOf(owner)).apply {
            acceptInviteFailuresRemaining = 1
            acceptInviteEventResult = NdrAcceptInviteResult(
                ownerPubkeyHex = owner,
                inviterDevicePubkeyHex = device,
                deviceId = "owner-device",
                createdNewSession = true
            )
            blockedAcceptInviteEvents += NdrPubSubEvent(
                kind = "subscribe",
                subid = "invite-owner-app-keys",
                filterJson = """{"authors":["$owner"],"kinds":[37368],"limit":16}"""
            )
            setupUserEvents[owner] = listOf(
                NdrPubSubEvent(
                    kind = "subscribe",
                    subid = "duplicate-owner-app-keys",
                    filterJson = """{"authors":["$owner"],"kinds":[37368]}"""
                ),
                NdrPubSubEvent(
                    kind = "subscribe",
                    subid = "owner-invite-discovery",
                    filterJson = """{"authors":["$owner"],"kinds":[30078]}"""
                )
            )
            acceptInviteEvents += NdrPubSubEvent(
                kind = "publish_signed",
                eventJson = response
            )
        }
        val service = NdrNostrService(
            relayManager = relayManager,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" },
            inviteOwnerResolver = { owner }
        )
        service.configureIfNeeded(testIdentity())
        val retriedPayloads = mutableListOf<Pair<String, List<String>>>()
        service.onOutOfBandPayloadsReady = { peerOwner, payloads ->
            retriedPayloads += peerOwner to payloads
        }

        val first = service.processOutOfBandEventJson(invite, owner)
        val duplicate = service.processOutOfBandEventJson(invite, owner)
        val rotatedInvite = service.processOutOfBandEventJson(
            invite.replace("\"id\":\"invite1\"", "\"id\":\"invite2\""),
            owner
        )

        assertTrue(first.outboundPayloads.isEmpty())
        assertTrue(duplicate.outboundPayloads.isEmpty())
        assertTrue(rotatedInvite.outboundPayloads.isEmpty())
        assertEquals(1, runtime.acceptedInvites.size)
        assertEquals(
            listOf("invite-owner-app-keys"),
            relayManager.subscriptions.map { it.id }
        )

        relayManager.emit(
            "invite-owner-app-keys",
            NostrEvent(
                id = "not-app-keys",
                pubkey = owner,
                createdAt = 2,
                kind = 37368,
                tags = listOf(listOf("type", "something_else")),
                content = "ignored",
                sig = "sig"
            )
        )
        assertEquals(1, runtime.acceptedInvites.size)

        relayManager.emit(
            "invite-owner-app-keys",
            NostrEvent(
                id = "app-keys-1",
                pubkey = owner,
                createdAt = 2,
                kind = 37368,
                tags = listOf(listOf("type", "app_keys_roster_snapshot")),
                content = "signed-roster",
                sig = "sig"
            )
        )

        assertEquals(2, runtime.acceptedInvites.size)
        assertEquals(listOf(owner), runtime.setupUserCalls)
        assertEquals(
            listOf("invite-owner-app-keys"),
            relayManager.subscriptions.map { it.id }
        )
        assertEquals(listOf(owner to listOf(response)), retriedPayloads)
    }

    @Test
    fun panicResetDestroysRuntimeAndClearsPersistentState() {
        val runtime = FakeNdrSessionManager()
        var storageReset = false
        var deviceIdReset = false
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" },
            storageResetter = { storageReset = true },
            deviceIdResetter = { deviceIdReset = true }
        )
        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "11".repeat(32),
                publicKeyHex = "22".repeat(32),
                npub = "npub-test",
                createdAt = 1L
            )
        )
        service.onDecryptedMessage = {}
        service.onOutOfBandPayloadsReady = { _, _ -> }

        assertTrue(service.resetForPanic())

        assertFalse(service.isConfigured)
        assertNull(service.currentInviteEventJson())
        assertNull(service.onDecryptedMessage)
        assertNull(service.onOutOfBandPayloadsReady)
        assertTrue(runtime.destroyed)
        assertTrue(storageReset)
        assertTrue(deviceIdReset)
    }

    @Test
    fun failedPanicStorageWipeKeepsNdrDisabled() {
        val runtime = FakeNdrSessionManager()
        val runtimeFactory = FakeNdrRuntimeFactory(runtime)
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = runtimeFactory,
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" },
            storageResetter = { throw java.io.IOException("busy") }
        )
        val identity = NostrIdentity(
            privateKeyHex = "11".repeat(32),
            publicKeyHex = "22".repeat(32),
            npub = "npub-test",
            createdAt = 1L
        )
        service.configureIfNeeded(identity)

        assertFalse(service.resetForPanic())
        service.configureIfNeeded(identity)

        assertFalse(service.isConfigured)
        assertEquals(1, runtimeFactory.createdCount)
    }

    @Test
    fun panicResetWaitsForInFlightRuntimeMutation() {
        val peer = "aa".repeat(32)
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val runtime = FakeNdrSessionManager(mutableSetOf(peer)).apply {
            sendTextEntered = sendEntered
            releaseSendText = releaseSend
        }
        val service = NdrNostrService(
            relayManager = FakeRelayManager(),
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageDirectoryProvider = { "/tmp/ndr-test" },
            deviceIdProvider = { "device-1" },
            storageResetter = {}
        )
        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "11".repeat(32),
                publicKeyHex = "22".repeat(32),
                npub = "npub-test",
                createdAt = 1L
            )
        )

        val sendThread = thread(start = true, name = "ndr-test-send") {
            service.sendIfPossible("hello", peer)
        }
        assertTrue(sendEntered.await(2, TimeUnit.SECONDS))

        var resetSucceeded = false
        val resetThread = thread(start = true, name = "ndr-test-reset") {
            resetSucceeded = service.resetForPanic()
            resetFinished.countDown()
        }
        try {
            assertFalse(resetFinished.await(150, TimeUnit.MILLISECONDS))
        } finally {
            releaseSend.countDown()
            sendThread.join(2_000)
            resetThread.join(2_000)
        }

        assertTrue(resetSucceeded)
        assertTrue(runtime.destroyedAfterSendCompleted)
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

    private fun eventPubkey(eventJson: String): String? {
        return NostrEvent.fromJsonString(eventJson)?.pubkey
    }

    private fun testIdentity() = NostrIdentity(
        privateKeyHex = "11".repeat(32),
        publicKeyHex = "22".repeat(32),
        npub = "npub-test",
        createdAt = 1L
    )

    private class FakeNdrRuntimeFactory(
        private val runtime: FakeNdrSessionManager
    ) : NdrSessionManagerFactory {
        var lastStoragePath: String? = null
        var createdCount: Int = 0

        override fun newWithStoragePath(
            ourPubkeyHex: String,
            ourIdentityPrivkeyHex: String,
            deviceId: String,
            storagePath: String,
            ownerPubkeyHex: String?
        ): NdrSessionManager {
            lastStoragePath = storagePath
            createdCount += 1
            return runtime
        }
    }

    private class FakeRelayManager : NdrRelayManager {
        data class Subscription(
            val id: String,
            val filter: NostrFilter,
            val handler: (NostrEvent) -> Unit
        )

        val subscriptions = mutableListOf<Subscription>()
        val unsubscribed = mutableListOf<String>()
        val sentEvents = mutableListOf<NostrEvent>()

        override fun subscribe(filter: NostrFilter, id: String, handler: (NostrEvent) -> Unit) {
            subscriptions += Subscription(id = id, filter = filter, handler = handler)
        }

        override fun unsubscribe(id: String) {
            unsubscribed += id
        }

        override fun sendEvent(event: NostrEvent) {
            sentEvents += event
        }

        fun emit(id: String, event: NostrEvent) {
            requireNotNull(subscriptions.lastOrNull { it.id == id }).handler(event)
        }
    }

    private class FakeNdrSessionManager(
        private val activeSessionPeers: MutableSet<String> = mutableSetOf()
    ) : NdrSessionManager {
        val drainedEvents = ArrayDeque<NdrPubSubEvent>()
        val processedEvents = mutableListOf<String>()
        val processedOutOfBandResponses = mutableListOf<Pair<String, String>>()
        val acceptedInvites = mutableListOf<String>()
        val acceptedInviteUrls = mutableListOf<String>()
        val acceptInviteEvents = mutableListOf<NdrPubSubEvent>()
        val acceptInviteUrlEvents = mutableListOf<NdrPubSubEvent>()
        val blockedAcceptInviteEvents = mutableListOf<NdrPubSubEvent>()
        val processEvents = mutableListOf<NdrPubSubEvent>()
        val acceptedInviteOwnerHints = mutableListOf<String?>()
        val acceptedInviteUrlOwnerHints = mutableListOf<String?>()
        val sendTextCalls = mutableListOf<String>()
        val knownPeerOwners = mutableListOf<String>()
        val setupUserCalls = mutableListOf<String>()
        val setupUserEvents = mutableMapOf<String, List<NdrPubSubEvent>>()
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
        var acceptInviteFailuresRemaining: Int = 0
        var destroyed: Boolean = false
        var sendTextEntered: CountDownLatch? = null
        var releaseSendText: CountDownLatch? = null
        @Volatile
        var sendTextCompleted: Boolean = false
        var destroyedAfterSendCompleted: Boolean = false

        override fun init() = Unit

        override fun knownPeerOwnerPubkeys(): List<String> =
            knownPeerOwners.toList()

        override fun setupUser(userPubkeyHex: String) {
            setupUserCalls += userPubkeyHex
            drainedEvents.addAll(setupUserEvents[userPubkeyHex].orEmpty())
        }

        override fun acceptInviteFromEventJson(
            eventJson: String,
            ownerPubkeyHintHex: String?
        ): NdrAcceptInviteResult {
            acceptedInvites += eventJson
            acceptedInviteOwnerHints += ownerPubkeyHintHex
            if (acceptInviteFailuresRemaining > 0) {
                acceptInviteFailuresRemaining -= 1
                drainedEvents.addAll(blockedAcceptInviteEvents)
                throw NdrSessionNotReadyException("missing owner roster")
            }
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

        override fun processOutOfBandResponse(
            eventJson: String,
            expectedOwnerPubkeyHex: String
        ) {
            processedOutOfBandResponses += eventJson to expectedOwnerPubkeyHex
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
            sendTextEntered?.countDown()
            releaseSendText?.await(2, TimeUnit.SECONDS)
            sendTextCompleted = true
            return sendTextResult
        }

        override fun getOurPubkeyHex(): String = "22".repeat(32)

        override fun getTotalSessions(): ULong = 0u

        override fun destroy() {
            destroyedAfterSendCompleted = sendTextCompleted
            destroyed = true
        }
    }
}
