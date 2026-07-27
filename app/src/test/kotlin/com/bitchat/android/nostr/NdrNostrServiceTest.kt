package com.bitchat.android.nostr

import com.bitchat.android.model.NdrFeatureGate
import kotlinx.coroutines.Job
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
    private val localPubkey = "22".repeat(32)
    private val peerPubkey = "cc".repeat(32)

    @Before
    fun enableNdrForTest() {
        NostrInboundAccountLifecycle.invalidate()
        NdrFeatureGate.setEnabledForTests(true)
    }

    @After
    fun resetNdrGate() {
        NostrInboundAccountLifecycle.invalidate()
        NdrFeatureGate.setEnabledForTests(false)
    }

    @Test
    fun processExitInvalidatesAccountWideReceiveWork() {
        val parentJob = Job()
        val accountContext =
            NostrInboundAccountLifecycle.begin(localPubkey, parentJob)
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(FakeNdrPairwiseRuntime()))

        assertTrue(NostrInboundAccountLifecycle.isCurrent(accountContext.epoch))
        assertTrue(accountContext.receiveJob.isActive)

        service.shutdownForProcessExit()

        assertFalse(NostrInboundAccountLifecycle.isCurrent(accountContext.epoch))
        assertTrue(accountContext.receiveJob.isCancelled)
        parentJob.cancel()
    }

    @Test
    fun processExitCannotReopenPairwiseRuntime() {
        val factory = FakeNdrRuntimeFactory(FakeNdrPairwiseRuntime())
        val service = service(runtimeFactory = factory)
        val identity = testIdentity()
        assertTrue(service.configureIfNeeded(identity))

        service.shutdownForProcessExit()

        assertFalse(service.configureIfNeeded(identity))
        assertFalse(service.retirePeerForMaintenance(identity, peerPubkey))
        assertEquals(1, factory.createdCount)
    }

    @Test
    fun disabledRolloutGateRefusesToCreateRuntime() {
        NdrFeatureGate.setEnabledForTests(false)
        val runtime = FakeNdrPairwiseRuntime()
        val factory = FakeNdrRuntimeFactory(runtime)
        val service = service(runtimeFactory = factory)

        service.configureIfNeeded(testIdentity())

        assertFalse(service.isConfigured)
        assertEquals(0, factory.createdCount)
        assertEquals(NdrSendResult.NO_SESSION, service.sendIfPossible("hello", peerPubkey))
    }

    @Test
    fun disabledRolloutCanRetirePinnedPeerWithoutStartingTransport() {
        NdrFeatureGate.setEnabledForTests(false)
        val relay = FakeRelayManager()
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey)).apply {
            pendingEvents += NdrPubSubEvent(
                kind = "subscribe",
                actionId = "must-stay-dormant",
                subid = "messages",
                filterJson = """{"authors":["$peerPubkey"],"kinds":[1060]}"""
            )
        }
        val factory = FakeNdrRuntimeFactory(runtime)
        val service = service(relay, factory)

        assertTrue(service.retirePeerForMaintenance(testIdentity(), peerPubkey))

        assertFalse(service.isConfigured)
        assertEquals(1, factory.createdCount)
        assertEquals(listOf(peerPubkey), runtime.retiredPeers)
        assertTrue(relay.subscriptions.isEmpty())
    }

    @Test
    fun configureCachesPairwiseInviteAndInstallsOnlyKind1060Subscription() {
        val relay = FakeRelayManager()
        val runtime = FakeNdrPairwiseRuntime().apply {
            currentInvite = inviteEvent(peerPubkey)
            pendingEvents += NdrPubSubEvent(
                kind = "subscribe",
                actionId = "message-sub",
                subid = "messages",
                filterJson = """{"authors":["$peerPubkey"],"kinds":[1060]}"""
            )
            pendingEvents += NdrPubSubEvent(
                kind = "subscribe",
                actionId = "appkeys-sub",
                subid = "appkeys",
                filterJson = """{"authors":["$peerPubkey"],"kinds":[37368]}"""
            )
            pendingEvents += NdrPubSubEvent(
                kind = "subscribe",
                actionId = "invite-sub",
                subid = "invites",
                filterJson = """{"authors":["$peerPubkey"],"kinds":[30078]}"""
            )
            pendingEvents += NdrPubSubEvent(
                kind = "subscribe",
                actionId = "recipient-sub",
                subid = "recipient",
                filterJson = """{"kinds":[1060],"#p":["$localPubkey"]}"""
            )
        }
        val factory = FakeNdrRuntimeFactory(runtime)
        val service = service(relay, factory)

        service.configureIfNeeded(testIdentity())

        assertEquals(
            inviteEvent(peerPubkey),
            service.currentInviteEventJson()
        )
        assertEquals(listOf("messages"), relay.subscriptions.map { it.id })
        assertEquals(
            setOf("message-sub", "appkeys-sub", "invite-sub", "recipient-sub"),
            runtime.ackedActionIds.toSet()
        )
        assertEquals(
            "/tmp/ndr-test/pairwise-v1/$localPubkey",
            factory.lastStoragePath
        )
    }

    @Test
    fun appKeysRelayTrafficIsRejectedWithoutEnteringRuntimeOrRelay() {
        val relay = FakeRelayManager()
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += NdrPubSubEvent(
                kind = "publish",
                actionId = "appkeys-publish",
                sessionId = "appkeys-session",
                eventJson = appKeysEvent(peerPubkey)
            )
        }
        val service = service(relay, FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())

        service.processInboundRelayEvent(
            NostrEvent(
                id = "01".repeat(32),
                pubkey = peerPubkey,
                createdAt = 1,
                kind = 37368,
                tags = listOf(listOf("type", "app_keys_roster_snapshot")),
                content = "roster",
                sig = "sig"
            )
        )

        assertTrue(runtime.processedEvents.isEmpty())
        assertTrue(relay.sentEvents.isEmpty())
        assertTrue("appkeys-publish" in runtime.ackedActionIds)
    }

    @Test
    fun relayPublishIsAcknowledgedOnlyAfterHostQueueAcceptsIt() {
        val event = messageEvent(peerPubkey)
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += NdrPubSubEvent(
                kind = "publish",
                actionId = "relay-publish",
                sessionId = "relay-session",
                eventJson = event.toJsonString()
            )
        }
        val failingRelay = FakeRelayManager(failSend = true)
        val scheduler = FakeRetryScheduler()
        val service = service(
            failingRelay,
            FakeNdrRuntimeFactory(runtime),
            retryScheduler = scheduler
        )

        service.configureIfNeeded(testIdentity())
        assertFalse("relay-publish" in runtime.ackedActionIds)
        assertEquals(1_000L, scheduler.scheduled.single().delayMs)

        failingRelay.failSend = false
        scheduler.runNext()

        assertTrue("relay-publish" in runtime.ackedActionIds)
        assertEquals(listOf(event.id), failingRelay.sentEvents.map { it.id })
    }

    @Test
    fun recipientTaggedRelayPublishIsRejectedWithoutLeavingHost() {
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += NdrPubSubEvent(
                kind = "publish",
                actionId = "recipient-publish",
                sessionId = "recipient-session",
                eventJson = messageEvent(peerPubkey).copy(
                    tags = listOf(listOf("p", localPubkey))
                ).toJsonString()
            )
        }
        val relay = FakeRelayManager()
        val service = service(relay, FakeNdrRuntimeFactory(runtime))

        service.configureIfNeeded(testIdentity())

        assertTrue(relay.sentEvents.isEmpty())
        assertEquals(listOf("recipient-publish"), runtime.ackedActionIds)
    }

    @Test
    fun outOfBandAdmissionBlocksOnlyTheMatchingSessionPublish() {
        val blockedEvent = messageEvent(peerPubkey).copy(id = "31".repeat(32))
        val unrelatedEvent = messageEvent(peerPubkey).copy(id = "32".repeat(32))
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += NdrPubSubEvent(
                kind = "publish",
                actionId = "blocked-publish",
                sessionId = "session-a",
                eventJson = blockedEvent.toJsonString()
            )
            pendingEvents += NdrPubSubEvent(
                kind = "out_of_band",
                actionId = "oob-a",
                sessionId = "session-a",
                eventJson = giftWrapEvent(),
                peerPubkeyHex = peerPubkey
            )
            pendingEvents += NdrPubSubEvent(
                kind = "publish",
                actionId = "unrelated-publish",
                sessionId = "session-b",
                eventJson = unrelatedEvent.toJsonString()
            )
        }
        var oobCompletion: ((Boolean) -> Unit)? = null
        val relay = FakeRelayManager()
        val service = service(relay, FakeNdrRuntimeFactory(runtime))
        service.onOutOfBandPayload = { _, completion -> oobCompletion = completion }

        service.configureIfNeeded(testIdentity())

        assertEquals(listOf(unrelatedEvent.id), relay.sentEvents.map(NostrEvent::id))
        assertFalse("blocked-publish" in runtime.ackedActionIds)
        assertTrue("unrelated-publish" in runtime.ackedActionIds)

        oobCompletion?.invoke(true)

        assertEquals(
            listOf(unrelatedEvent.id, blockedEvent.id),
            relay.sentEvents.map(NostrEvent::id)
        )
        assertTrue("oob-a" in runtime.ackedActionIds)
        assertTrue("blocked-publish" in runtime.ackedActionIds)
    }

    @Test
    fun pendingOrRejectedRelayConfirmationNeverAcknowledgesOrDuplicatesInFlightSend() {
        val event = messageEvent(peerPubkey)
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += NdrPubSubEvent(
                kind = "publish",
                actionId = "relay-publish",
                sessionId = "relay-session",
                eventJson = event.toJsonString()
            )
        }
        val relay = FakeRelayManager(confirmationResult = null)
        val scheduler = FakeRetryScheduler()
        val service = service(
            relay,
            FakeNdrRuntimeFactory(runtime),
            retryScheduler = scheduler
        )

        service.configureIfNeeded(testIdentity())
        service.processInboundRelayEvent(messageEvent(peerPubkey))

        assertEquals(1, relay.sentEvents.size)
        assertFalse("relay-publish" in runtime.ackedActionIds)

        relay.confirmations.removeFirst().completion(false)
        service.processInboundRelayEvent(messageEvent(peerPubkey))

        assertEquals(1, relay.sentEvents.size)
        assertEquals(1_000L, scheduler.scheduled.last().delayMs)
        scheduler.runNext()

        assertEquals(2, relay.sentEvents.size)
        assertFalse("relay-publish" in runtime.ackedActionIds)

        relay.confirmations.removeFirst().completion(false)
        assertEquals(2_000L, scheduler.scheduled.last().delayMs)
        relay.reconnect()
        assertEquals(3, relay.sentEvents.size)

        relay.confirmations.removeFirst().completion(true)
        assertTrue("relay-publish" in runtime.ackedActionIds)
    }

    @Test
    fun teardownCancelsScheduledPublishRetry() {
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += relayPublishAction("relay-publish", peerPubkey)
        }
        val relay = FakeRelayManager(failSend = true)
        val scheduler = FakeRetryScheduler()
        val service = service(
            relayManager = relay,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageResetter = {},
            retryScheduler = scheduler
        )
        service.configureIfNeeded(testIdentity())
        val scheduled = scheduler.scheduled.single()

        assertTrue(service.resetForPanic())
        assertTrue(service.completePanicReset())
        relay.failSend = false
        scheduler.runNext()

        assertTrue(scheduled.canceled)
        assertTrue(relay.sentEvents.isEmpty())
    }

    @Test
    fun bufferedDeliveryIsAcknowledgedOnlyAfterConsumerCompletion() {
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += decryptedAction("delivery-1", peerPubkey, "bitchat1:pending")
        }
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())

        var delivered: NdrDecryptedMessage? = null
        var completion: ((NdrDeliveryResult) -> Unit)? = null
        service.onDecryptedMessage = { message, callback ->
            delivered = message
            completion = callback
        }

        assertEquals("delivery-1", delivered?.actionId)
        assertFalse("delivery-1" in runtime.ackedActionIds)

        completion?.invoke(NdrDeliveryResult.CONSUMED)

        assertTrue("delivery-1" in runtime.ackedActionIds)
    }

    @Test
    fun retryResultLeavesDeliveryPendingAndAllowsRedelivery() {
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += decryptedAction("delivery-retry", peerPubkey, "bitchat1:pending")
        }
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        var deliveryCount = 0
        service.onDecryptedMessage = { _, completion ->
            deliveryCount += 1
            completion(NdrDeliveryResult.RETRY)
        }
        service.configureIfNeeded(testIdentity())

        assertEquals(1, deliveryCount)
        assertFalse("delivery-retry" in runtime.ackedActionIds)

        service.processInboundRelayEvent(messageEvent(peerPubkey))

        assertEquals(2, deliveryCount)
        assertFalse("delivery-retry" in runtime.ackedActionIds)
    }

    @Test
    fun definitiveRejectedDeliveryIsAcknowledged() {
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += decryptedAction("delivery-rejected", peerPubkey, "invalid")
        }
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.onDecryptedMessage = { _, completion ->
            completion(NdrDeliveryResult.REJECTED)
        }

        service.configureIfNeeded(testIdentity())

        assertTrue("delivery-rejected" in runtime.ackedActionIds)
    }

    @Test
    fun missingConsumerLeavesEveryDeliveryDurableWithoutEviction() {
        val runtime = FakeNdrPairwiseRuntime().apply {
            repeat(129) { index ->
                pendingEvents += decryptedAction(
                    actionId = "delivery-$index",
                    sender = peerPubkey,
                    content = "bitchat1:pending-$index"
                )
            }
        }
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())
        assertTrue(runtime.ackedActionIds.isEmpty())
        assertEquals(129, runtime.pendingEvents.size)

        val delivered = mutableListOf<String>()
        service.onDecryptedMessage = { message, completion ->
            delivered += message.content
            completion(NdrDeliveryResult.CONSUMED)
        }

        assertEquals(129, delivered.size)
        assertEquals("bitchat1:pending-0", delivered.first())
        assertEquals("bitchat1:pending-128", delivered.last())
        assertEquals(129, runtime.ackedActionIds.distinct().size)
    }

    @Test
    fun outOfBandInviteReturnsResponseWithoutRelayPublishOrEarlyAck() {
        val response = giftWrapEvent()
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey)).apply {
            acceptInviteEvents += outOfBandAction("response-action", response)
            acceptInviteResult = NdrAcceptInviteResult(peerPubkey, createdNewSession = true)
        }
        val relay = FakeRelayManager()
        val service = service(
            relayManager = relay,
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            invitePeerResolver = { peerPubkey }
        )
        service.configureIfNeeded(testIdentity())

        val result = service.processOutOfBandEventJson(
            inviteEvent(peerPubkey),
            expectedPeerPubkeyHex = peerPubkey
        )

        assertEquals(peerPubkey, result.sessionLookupPubkeyHex)
        assertEquals(listOf("response-action"), result.outboundPayloads.map { it.actionId })
        assertEquals(response, result.outboundPayloads.single().eventJson)
        assertEquals(peerPubkey, result.outboundPayloads.single().peerPubkeyHex)
        assertTrue(relay.sentEvents.isEmpty())
        assertFalse("response-action" in runtime.ackedActionIds)

        assertTrue(service.acknowledgeOutOfBandPayload(result.outboundPayloads.single()))
        assertTrue("response-action" in runtime.ackedActionIds)
    }

    @Test
    fun pendingOutOfBandActionReplaysOnConfigureAndAcksOnlyAfterAsyncAdmission() {
        val response = giftWrapEvent()
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += outOfBandAction("pending-oob", response)
        }
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        var delivered: NdrOutOfBandPayload? = null
        var completion: ((Boolean) -> Unit)? = null
        service.onOutOfBandPayload = { payload, callback ->
            delivered = payload
            completion = callback
        }

        service.configureIfNeeded(testIdentity())

        assertEquals("pending-oob", delivered?.actionId)
        assertEquals(peerPubkey, delivered?.peerPubkeyHex)
        assertFalse("pending-oob" in runtime.ackedActionIds)

        completion?.invoke(true)

        assertTrue("pending-oob" in runtime.ackedActionIds)
    }

    @Test
    fun failedOutOfBandAdmissionRemainsDurableAndReplaysOnReconnect() {
        val relay = FakeRelayManager()
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += outOfBandAction("pending-oob", giftWrapEvent())
        }
        val service = service(relay, FakeNdrRuntimeFactory(runtime))
        val completions = mutableListOf<(Boolean) -> Unit>()
        service.onOutOfBandPayload = { _, completion ->
            completions += completion
        }
        service.configureIfNeeded(testIdentity())

        assertEquals(1, completions.size)
        completions.single().invoke(false)
        assertFalse("pending-oob" in runtime.ackedActionIds)

        relay.reconnect()

        assertEquals(2, completions.size)
        completions.last().invoke(true)
        assertTrue("pending-oob" in runtime.ackedActionIds)
    }

    @Test
    fun outOfBandRetriesAreBoundedUntilRouteAvailabilityChanges() {
        val scheduler = FakeRetryScheduler()
        val runtime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += outOfBandAction("pending-oob", giftWrapEvent())
        }
        val service = service(
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            retryScheduler = scheduler
        )
        var attempts = 0
        service.onOutOfBandPayload = { _, completion ->
            attempts += 1
            completion(false)
        }

        service.configureIfNeeded(testIdentity())
        repeat(5) { scheduler.runNext() }

        assertEquals(6, attempts)
        assertTrue(scheduler.scheduled.isEmpty())
        assertFalse("pending-oob" in runtime.ackedActionIds)

        service.onOutOfBandTransportAvailable()

        assertEquals(7, attempts)
        assertTrue(scheduler.scheduled.isNotEmpty())
    }

    @Test
    fun inviteWhosePeerDoesNotMatchAuthenticatedFavoriteIsRejectedBeforeRuntime() {
        val runtime = FakeNdrPairwiseRuntime()
        val service = service(
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            invitePeerResolver = { "aa".repeat(32) }
        )
        service.configureIfNeeded(testIdentity())

        val result = service.processOutOfBandEventJson(
            inviteEvent("aa".repeat(32)),
            expectedPeerPubkeyHex = peerPubkey
        )

        assertTrue(result.outboundPayloads.isEmpty())
        assertTrue(runtime.acceptedInvites.isEmpty())
    }

    @Test
    fun outOfBandProcessingRequiresAuthenticatedPeer() {
        val runtime = FakeNdrPairwiseRuntime()
        val service = service(
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            invitePeerResolver = { peerPubkey }
        )
        service.configureIfNeeded(testIdentity())

        val result = service.processOutOfBandEventJson(inviteEvent(peerPubkey))

        assertTrue(result.outboundPayloads.isEmpty())
        assertTrue(runtime.acceptedInvites.isEmpty())
    }

    @Test
    fun markerFailureTearsDownAndLatchesBeforeSessionCreatingFfiCall() {
        val markers = InMemoryMarkerStore().apply {
            markFailure = java.io.IOException("marker storage unavailable")
        }
        val runtime = FakeNdrPairwiseRuntime()
        val factory = FakeNdrRuntimeFactory(runtime)
        val service = service(
            runtimeFactory = factory,
            markerStore = markers,
            invitePeerResolver = { peerPubkey }
        )
        service.configureIfNeeded(testIdentity())

        val result = service.processOutOfBandEventJson(
            inviteEvent(peerPubkey),
            expectedPeerPubkeyHex = peerPubkey
        )

        assertTrue(result.outboundPayloads.isEmpty())
        assertTrue(runtime.acceptedInvites.isEmpty())
        assertTrue(runtime.destroyed)
        assertEquals(NdrSendResult.FAILED, service.sendIfPossible("blocked", peerPubkey))
        service.configureIfNeeded(testIdentity())
        assertEquals(1, factory.createdCount)
    }

    @Test
    fun authenticatedGiftWrapResponseMayUseEphemeralOuterPubkey() {
        val runtime = FakeNdrPairwiseRuntime()
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())
        val response = giftWrapEvent()

        service.processOutOfBandEventJson(response, peerPubkey)

        assertEquals(listOf(response to peerPubkey), runtime.processedOutOfBandResponses)
        assertTrue(runtime.processedEvents.isEmpty())
    }

    @Test
    fun relayPathAcceptsOnlyKind1060() {
        val runtime = FakeNdrPairwiseRuntime()
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())

        service.processInboundRelayEvent(
            NostrEvent(
                id = "02".repeat(32),
                pubkey = peerPubkey,
                createdAt = 1,
                kind = NostrKind.TEXT_NOTE,
                tags = emptyList(),
                content = "not-ndr",
                sig = "sig"
            )
        )
        service.processInboundRelayEvent(
            messageEvent(peerPubkey).copy(
                id = "03".repeat(32),
                tags = listOf(listOf("p", localPubkey))
            )
        )
        service.processInboundRelayEvent(messageEvent(peerPubkey))

        assertEquals(1, runtime.processedEvents.size)
        assertEquals(1060, NostrEvent.fromJsonString(runtime.processedEvents.single())?.kind)
    }

    @Test
    fun latePublishConfirmationFromPreviousAccountCannotAckReplacementRuntime() {
        val oldRuntime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += relayPublishAction("shared-action", peerPubkey)
        }
        val newRuntime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += relayPublishAction("shared-action", peerPubkey)
        }
        val relay = FakeRelayManager(confirmationResult = null)
        val service = service(
            relayManager = relay,
            runtimeFactory = SequencedNdrRuntimeFactory(listOf(oldRuntime, newRuntime))
        )
        service.configureIfNeeded(testIdentity())

        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "33".repeat(32),
                publicKeyHex = "44".repeat(32),
                npub = "npub-replacement",
                createdAt = 2L
            )
        )

        assertEquals(1, relay.canceledConfirmations.size)
        relay.canceledConfirmations.single().completion(true)
        assertTrue(newRuntime.ackedActionIds.isEmpty())

        relay.confirmations.single().completion(true)
        assertEquals(listOf("shared-action"), newRuntime.ackedActionIds)
        assertTrue(oldRuntime.ackedActionIds.isEmpty())
    }

    @Test
    fun latePublishConfirmationAfterPanicCannotAckSameAccountReplacement() {
        val oldRuntime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += relayPublishAction("shared-action", peerPubkey)
        }
        val newRuntime = FakeNdrPairwiseRuntime().apply {
            pendingEvents += relayPublishAction("shared-action", peerPubkey)
        }
        val relay = FakeRelayManager(confirmationResult = null)
        val service = service(
            relayManager = relay,
            runtimeFactory = SequencedNdrRuntimeFactory(listOf(oldRuntime, newRuntime)),
            storageResetter = {}
        )
        service.configureIfNeeded(testIdentity())

        assertTrue(service.resetForPanic())
        assertTrue(service.completePanicReset())
        service.configureIfNeeded(testIdentity())

        relay.canceledConfirmations.single().completion(true)
        assertTrue(newRuntime.ackedActionIds.isEmpty())

        relay.confirmations.single().completion(true)
        assertEquals(listOf("shared-action"), newRuntime.ackedActionIds)
        assertTrue(oldRuntime.ackedActionIds.isEmpty())
    }

    @Test
    fun lateDeliveryAndOobCompletionCannotAckReplacementRuntime() {
        val oldRuntime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey)).apply {
            pendingEvents += decryptedAction("shared-delivery", peerPubkey, "bitchat1:pending")
            acceptInviteEvents += outOfBandAction("shared-oob", giftWrapEvent())
            acceptInviteResult = NdrAcceptInviteResult(peerPubkey, createdNewSession = true)
        }
        val newRuntime = FakeNdrPairwiseRuntime()
        val service = service(
            runtimeFactory = SequencedNdrRuntimeFactory(listOf(oldRuntime, newRuntime)),
            invitePeerResolver = { peerPubkey }
        )
        var deliveryCompletion: ((NdrDeliveryResult) -> Unit)? = null
        service.onDecryptedMessage = { _, completion ->
            deliveryCompletion = completion
        }
        service.configureIfNeeded(testIdentity())
        val oldPayload = service.processOutOfBandEventJson(
            inviteEvent(peerPubkey),
            peerPubkey
        ).outboundPayloads.single()

        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "33".repeat(32),
                publicKeyHex = "44".repeat(32),
                npub = "npub-replacement",
                createdAt = 2L
            )
        )
        deliveryCompletion?.invoke(NdrDeliveryResult.CONSUMED)

        assertFalse(service.acknowledgeOutOfBandPayload(oldPayload))
        assertTrue(newRuntime.ackedActionIds.isEmpty())
        assertTrue(oldRuntime.ackedActionIds.isEmpty())
    }

    @Test
    fun panicResetDestroysRuntimeAndClearsPersistentState() {
        val runtime = FakeNdrPairwiseRuntime()
        var storageReset = false
        val service = service(
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageResetter = { storageReset = true }
        )
        service.configureIfNeeded(testIdentity())
        service.onDecryptedMessage = { _, completion ->
            completion(NdrDeliveryResult.CONSUMED)
        }

        assertTrue(service.resetForPanic())
        assertTrue(service.completePanicReset())

        assertFalse(service.isConfigured)
        assertNull(service.currentInviteEventJson())
        assertNull(service.onDecryptedMessage)
        assertTrue(runtime.destroyed)
        assertTrue(storageReset)
    }

    @Test
    fun failedPanicStorageWipeKeepsNdrDisabled() {
        val markers = InMemoryMarkerStore()
        val runtime = FakeNdrPairwiseRuntime()
        val factory = FakeNdrRuntimeFactory(runtime)
        val service = service(
            runtimeFactory = factory,
            storageResetter = { throw java.io.IOException("busy") },
            markerStore = markers
        )
        service.configureIfNeeded(testIdentity())

        assertFalse(service.resetForPanic())
        assertTrue(service.isPanicWipeRequired)
        service.configureIfNeeded(testIdentity())

        assertFalse(service.isConfigured)
        assertEquals(1, factory.createdCount)

        val restartedFactory = FakeNdrRuntimeFactory(FakeNdrPairwiseRuntime())
        val restarted = service(
            runtimeFactory = restartedFactory,
            storageResetter = {},
            markerStore = markers
        )
        restarted.configureIfNeeded(testIdentity())
        assertTrue(restarted.isPanicWipeRequired)
        assertFalse(restarted.isConfigured)
        assertEquals(0, restartedFactory.createdCount)

        assertTrue(restarted.resetForPanic())
        assertTrue(restarted.completePanicReset())
        restarted.configureIfNeeded(testIdentity())
        assertFalse(restarted.isPanicWipeRequired)
        assertTrue(restarted.isConfigured)
        assertEquals(1, restartedFactory.createdCount)
    }

    @Test
    fun failedPrimaryPanicMarkerStillBlocksRestartViaQuarantine() {
        val markers = InMemoryMarkerStore().apply {
            panicMarkFailure = java.io.IOException("marker unavailable")
        }
        val quarantine = InMemoryPanicStorageQuarantine()
        val service = service(
            runtimeFactory = FakeNdrRuntimeFactory(FakeNdrPairwiseRuntime()),
            storageResetter = {},
            markerStore = markers,
            panicStorageQuarantine = quarantine
        )
        service.configureIfNeeded(testIdentity())

        assertTrue(service.resetForPanic())
        assertFalse(markers.isPanicWipeRequired())
        assertTrue(quarantine.pending)

        val restartedFactory = FakeNdrRuntimeFactory(FakeNdrPairwiseRuntime())
        val restarted = service(
            runtimeFactory = restartedFactory,
            storageResetter = {},
            markerStore = markers,
            panicStorageQuarantine = quarantine
        )
        restarted.configureIfNeeded(testIdentity())

        assertTrue(restarted.isPanicWipeRequired)
        assertFalse(restarted.isConfigured)
        assertEquals(0, restartedFactory.createdCount)

        markers.panicMarkFailure = null
        assertTrue(restarted.resetForPanic())
        assertTrue(restarted.completePanicReset())
        assertFalse(quarantine.pending)
        assertFalse(restarted.isPanicWipeRequired)
    }

    @Test
    fun panicResetWaitsForInFlightRuntimeMutation() {
        val sendEntered = CountDownLatch(1)
        val releaseSend = CountDownLatch(1)
        val resetFinished = CountDownLatch(1)
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey)).apply {
            sendTextEntered = sendEntered
            releaseSendText = releaseSend
        }
        val service = service(
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            storageResetter = {}
        )
        service.configureIfNeeded(testIdentity())

        val sendThread = thread(start = true, name = "ndr-test-send") {
            service.sendIfPossible("hello", peerPubkey)
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
        assertTrue(service.completePanicReset())
        assertTrue(runtime.destroyedAfterSendCompleted)
    }

    @Test
    fun sendRequiresActivePairwiseSessionAndPublishesExactlyOneKind1060() {
        val relay = FakeRelayManager()
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey)).apply {
            sendTextEvents += NdrPubSubEvent(
                kind = "publish",
                actionId = "message-action",
                sessionId = "message-session",
                eventJson = messageEvent(localPubkey).toJsonString()
            )
        }
        val service = service(relay, FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())

        assertEquals(
            NdrSendResult.NO_SESSION,
            service.sendIfPossible("hello", "aa".repeat(32))
        )
        assertEquals(NdrSendResult.SENT, service.sendIfPossible("hello", peerPubkey))

        assertEquals(listOf(peerPubkey), runtime.sendTextCalls)
        assertEquals(listOf(1060), relay.sentEvents.map { it.kind })
        assertTrue("message-action" in runtime.ackedActionIds)
    }

    @Test
    fun outboundAbsoluteExpirationIsForwardedToPairwiseRuntime() {
        val expiresAtSeconds = 4_102_444_800uL
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey))
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())

        val result = service.sendIfPossible(
            text = "disappearing",
            peerPubkeyHex = peerPubkey,
            expiresAtSeconds = expiresAtSeconds
        )

        assertEquals(NdrSendResult.SENT, result)
        assertEquals(listOf(peerPubkey), runtime.sendTextCalls)
        assertEquals(listOf(expiresAtSeconds), runtime.sendTextExpirationCalls)
    }

    @Test
    fun activePairwiseSessionSendFailureIsNotReportedAsNoSession() {
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey)).apply {
            sendTextFailure = java.io.IOException("storage unavailable")
        }
        val relay = FakeRelayManager()
        val service = service(relay, FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())

        val result = service.sendIfPossible("hello", peerPubkey)

        assertEquals(NdrSendResult.FAILED, result)
        assertTrue(relay.sentEvents.isEmpty())
    }

    @Test
    fun postCommitMarkerFailureStillReportsDurableNdrAdmission() {
        val activePeers = mutableSetOf<String>()
        val runtime = FakeNdrPairwiseRuntime(activePeers)
        val markers = InMemoryMarkerStore()
        val service = service(
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            markerStore = markers
        )
        service.configureIfNeeded(testIdentity())
        activePeers += peerPubkey
        markers.markFailure = java.io.IOException("marker storage unavailable")

        val result = service.sendIfPossible("admitted-once", peerPubkey)

        assertEquals(NdrSendResult.SENT, result)
        assertEquals(listOf(peerPubkey), runtime.sendTextCalls)
        assertTrue(runtime.sendTextCompleted)
        assertTrue(runtime.destroyed)
    }

    @Test
    fun persistedHalfReadyPairwiseSessionNeverFallsBackAfterRestart() {
        val markers = InMemoryMarkerStore()
        val runtime = FakeNdrPairwiseRuntime().apply {
            halfReadySessionPeers += peerPubkey
        }
        val service = service(
            runtimeFactory = FakeNdrRuntimeFactory(runtime),
            markerStore = markers
        )
        service.configureIfNeeded(testIdentity())

        assertTrue(service.hasPairwiseSession(peerPubkey))
        assertFalse(service.hasActiveSession(peerPubkey))
        assertEquals(
            NdrSendResult.FAILED,
            service.sendIfPossible("must-not-downgrade", peerPubkey)
        )
        assertTrue(runtime.sendTextCalls.isEmpty())
        assertTrue(markers.contains(localPubkey))

        val replacementFactory = FakeNdrRuntimeFactory(FakeNdrPairwiseRuntime())
        val restartedWithMissingState = service(
            runtimeFactory = replacementFactory,
            markerStore = markers,
            pairwiseStateExists = { false }
        )
        restartedWithMissingState.configureIfNeeded(testIdentity())

        assertFalse(restartedWithMissingState.isConfigured)
        assertEquals(0, replacementFactory.createdCount)
    }

    @Test
    fun activeSessionLookupFailureFailsClosed() {
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey))
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())
        assertTrue(service.hasActiveSession(peerPubkey))
        runtime.activeSessionLookupFailure = java.io.IOException("database unavailable")

        assertEquals(
            NdrSendResult.FAILED,
            service.sendIfPossible("hello", peerPubkey)
        )
        assertTrue(runtime.sendTextCalls.isEmpty())
    }

    @Test
    fun configurationFailureFailsClosedUntilIdentityChanges() {
        val replacementRuntime = FakeNdrPairwiseRuntime()
        val factory = FailingThenSucceedingNdrRuntimeFactory(replacementRuntime)
        val service = service(runtimeFactory = factory)

        service.configureIfNeeded(testIdentity())
        service.configureIfNeeded(testIdentity())

        assertFalse(service.isConfigured)
        assertEquals(1, factory.createdCount)
        assertEquals(
            NdrSendResult.FAILED,
            service.sendIfPossible("must-not-downgrade", peerPubkey)
        )

        service.configureIfNeeded(
            NostrIdentity(
                privateKeyHex = "33".repeat(32),
                publicKeyHex = "44".repeat(32),
                npub = "npub-replacement",
                createdAt = 2L
            )
        )

        assertTrue(service.isConfigured)
        assertEquals(2, factory.createdCount)
    }

    @Test
    fun establishedMarkerWithMissingPairwiseStateFailsClosedBeforeRuntimeOpen() {
        val markers = InMemoryMarkerStore().apply { mark(localPubkey) }
        val factory = FakeNdrRuntimeFactory(FakeNdrPairwiseRuntime())
        val service = service(
            runtimeFactory = factory,
            markerStore = markers,
            pairwiseStateExists = { false }
        )

        service.configureIfNeeded(testIdentity())

        assertFalse(service.isConfigured)
        assertEquals(0, factory.createdCount)
        assertEquals(
            NdrSendResult.FAILED,
            service.sendIfPossible("must-not-downgrade", peerPubkey)
        )
    }

    @Test
    fun peerRetirementIsDurableAndFailureAbortsHostRebind() {
        val failingPeer = "aa".repeat(32)
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey, failingPeer))
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())

        assertTrue(service.retirePeer(peerPubkey))
        assertEquals(listOf(peerPubkey), runtime.retiredPeers)
        assertFalse(service.hasActiveSession(peerPubkey))
        assertTrue(service.retirePeer(peerPubkey))

        runtime.retirePeerFailure = java.io.IOException("storage unavailable")
        assertFalse(service.retirePeer(failingPeer))
    }

    @Test
    fun peerRetirementExceptionAfterDurableRemovalIsIdempotentSuccess() {
        val runtime = FakeNdrPairwiseRuntime(mutableSetOf(peerPubkey)).apply {
            retirePeerFailureAfterRemoval = java.io.IOException("response lost")
        }
        val service = service(runtimeFactory = FakeNdrRuntimeFactory(runtime))
        service.configureIfNeeded(testIdentity())

        assertTrue(service.retirePeer(peerPubkey))
        assertFalse(service.hasActiveSession(peerPubkey))
    }

    @Test
    fun runtimeInitializationFailureDestroysPartialRuntimeAndFailsClosed() {
        val runtime = FakeNdrPairwiseRuntime().apply {
            knownPeerPubkeysFailure = java.io.IOException("database unavailable")
        }
        val factory = FakeNdrRuntimeFactory(runtime)
        val service = service(runtimeFactory = factory)

        service.configureIfNeeded(testIdentity())
        service.configureIfNeeded(testIdentity())

        assertTrue(runtime.destroyed)
        assertFalse(service.isConfigured)
        assertEquals(1, factory.createdCount)
        assertEquals(NdrSendResult.FAILED, service.sendIfPossible("hello", peerPubkey))
    }

    @Test
    fun successfulPanicResetClearsConfigurationFailureLatch() {
        val replacementRuntime = FakeNdrPairwiseRuntime()
        val factory = FailingThenSucceedingNdrRuntimeFactory(replacementRuntime)
        val service = service(
            runtimeFactory = factory,
            storageResetter = {}
        )
        service.configureIfNeeded(testIdentity())

        assertEquals(NdrSendResult.FAILED, service.sendIfPossible("hello", peerPubkey))
        assertTrue(service.resetForPanic())
        assertTrue(service.completePanicReset())
        service.configureIfNeeded(testIdentity())

        assertTrue(service.isConfigured)
        assertEquals(2, factory.createdCount)
    }

    @Test
    fun legacyFallbackPolicyAllowsOnlyMissingPairwiseSession() {
        assertTrue(shouldUseLegacyNostrFallback(NdrSendResult.NO_SESSION))
        assertFalse(shouldUseLegacyNostrFallback(NdrSendResult.FAILED))
        assertFalse(shouldUseLegacyNostrFallback(NdrSendResult.SENT))
        assertFalse(
            shouldUseLegacyNostrFallback(
                NdrSendResult.NO_SESSION,
                ndrRequired = true
            )
        )
        assertFalse(
            shouldUseLegacyNostrFallback(
                NdrSendResult.NO_SESSION,
                rebindBlocked = true
            )
        )
        assertTrue(
            isLegacyNostrAllowedWhenNdrDisabled(
                ndrRequired = false,
                rebindBlocked = false
            )
        )
        assertFalse(
            isLegacyNostrAllowedWhenNdrDisabled(
                ndrRequired = true,
                rebindBlocked = false
            )
        )
        assertFalse(
            isLegacyNostrAllowedWhenNdrDisabled(
                ndrRequired = false,
                rebindBlocked = true
            )
        )
    }

    @Test
    fun ndrAdmissionDispositionRetriesEveryProtectedOrUnreadyState() {
        assertEquals(
            NdrSendDisposition.ADMITTED,
            ndrSendDisposition(NdrSendResult.SENT)
        )
        assertEquals(
            NdrSendDisposition.LEGACY_FALLBACK,
            ndrSendDisposition(NdrSendResult.NO_SESSION)
        )
        assertEquals(
            NdrSendDisposition.RETRYABLE,
            ndrSendDisposition(NdrSendResult.NO_SESSION, ndrRequired = true)
        )
        assertEquals(
            NdrSendDisposition.RETRYABLE,
            ndrSendDisposition(NdrSendResult.NO_SESSION, pairwiseOnly = true)
        )
        assertEquals(
            NdrSendDisposition.RETRYABLE,
            ndrSendDisposition(NdrSendResult.FAILED)
        )
        assertEquals(
            NdrSendDisposition.RETRYABLE,
            ndrSendDisposition(
                NdrSendResult.NO_SESSION,
                ndrRequired = true,
                rebindBlocked = true
            )
        )
    }

    private fun service(
        relayManager: FakeRelayManager = FakeRelayManager(),
        runtimeFactory: NdrPairwiseRuntimeFactory,
        storageResetter: () -> Unit = {},
        invitePeerResolver: (String) -> String? = { null },
        retryScheduler: NdrRetryScheduler = FakeRetryScheduler(),
        markerStore: NdrEstablishedSessionMarkerStore = InMemoryMarkerStore(),
        panicStorageQuarantine: NdrPanicStorageQuarantine =
            InMemoryPanicStorageQuarantine(),
        pairwiseStateExists: (String) -> Boolean = { false }
    ) = NdrNostrService(
        relayManager = relayManager,
        runtimeFactory = runtimeFactory,
        storageDirectoryProvider = { "/tmp/ndr-test" },
        storageResetter = storageResetter,
        establishedSessionMarkers = markerStore,
        panicStorageQuarantine = panicStorageQuarantine,
        pairwiseStateExists = pairwiseStateExists,
        invitePeerResolver = invitePeerResolver,
        retryScheduler = retryScheduler
    )

    private fun testIdentity() = NostrIdentity(
        privateKeyHex = "11".repeat(32),
        publicKeyHex = localPubkey,
        npub = "npub-test",
        createdAt = 1L
    )

    private fun inviteEvent(pubkey: String): String = """
        {"id":"${"10".repeat(32)}","pubkey":"$pubkey","created_at":1,"kind":30078,"tags":[["l","double-ratchet/invites"]],"content":"invite","sig":"sig"}
    """.trimIndent()

    private fun giftWrapEvent(): String = """
        {"id":"${"11".repeat(32)}","pubkey":"${"ee".repeat(32)}","created_at":1,"kind":1059,"tags":[["p","$localPubkey"]],"content":"wrapped","sig":"sig"}
    """.trimIndent()

    private fun appKeysEvent(pubkey: String): String = """
        {"id":"${"12".repeat(32)}","pubkey":"$pubkey","created_at":1,"kind":37368,"tags":[["type","app_keys_roster_snapshot"]],"content":"roster","sig":"sig"}
    """.trimIndent()

    private fun messageEvent(pubkey: String) = NostrEvent(
        id = "13".repeat(32),
        pubkey = pubkey,
        createdAt = 1,
        kind = 1060,
        tags = emptyList(),
        content = "ciphertext",
        sig = "sig"
    )

    private fun relayPublishAction(actionId: String, pubkey: String) = NdrPubSubEvent(
        kind = "publish",
        actionId = actionId,
        sessionId = "session-$actionId",
        eventJson = messageEvent(pubkey).toJsonString()
    )

    private fun outOfBandAction(
        actionId: String,
        eventJson: String,
        peer: String = peerPubkey
    ) = NdrPubSubEvent(
        kind = "out_of_band",
        actionId = actionId,
        sessionId = "session-$actionId",
        eventJson = eventJson,
        peerPubkeyHex = peer
    )

    private fun decryptedAction(
        actionId: String,
        sender: String,
        content: String
    ) = NdrPubSubEvent(
        kind = "delivery",
        actionId = actionId,
        senderPubkeyHex = sender,
        content = content,
        eventId = actionId.hashCode().toUInt().toString(16).padStart(64, '0')
    )

    private class FakeNdrRuntimeFactory(
        private val runtime: FakeNdrPairwiseRuntime
    ) : NdrPairwiseRuntimeFactory {
        var lastStoragePath: String? = null
        var createdCount: Int = 0

        override fun newWithStoragePath(
            ourPubkeyHex: String,
            ourIdentityPrivkeyHex: String,
            storagePath: String
        ): NdrPairwiseRuntime {
            lastStoragePath = storagePath
            createdCount += 1
            return runtime
        }
    }

    private class SequencedNdrRuntimeFactory(
        runtimes: List<FakeNdrPairwiseRuntime>
    ) : NdrPairwiseRuntimeFactory {
        private val remaining = ArrayDeque(runtimes)

        override fun newWithStoragePath(
            ourPubkeyHex: String,
            ourIdentityPrivkeyHex: String,
            storagePath: String
        ): NdrPairwiseRuntime = remaining.removeFirst()
    }

    private class FailingThenSucceedingNdrRuntimeFactory(
        private val runtime: FakeNdrPairwiseRuntime
    ) : NdrPairwiseRuntimeFactory {
        var createdCount = 0

        override fun newWithStoragePath(
            ourPubkeyHex: String,
            ourIdentityPrivkeyHex: String,
            storagePath: String
        ): NdrPairwiseRuntime {
            createdCount += 1
            if (createdCount == 1) throw java.io.IOException("database unavailable")
            return runtime
        }
    }

    private class FakeRelayManager(
        var failSend: Boolean = false,
        var failSubscribe: Boolean = false,
        var confirmationResult: Boolean? = true
    ) : NdrRelayManager {
        data class Subscription(
            val id: String,
            val filter: NostrFilter,
            val handler: (NostrEvent) -> Boolean
        )
        data class Confirmation(
            val eventId: String,
            val completion: (Boolean) -> Unit
        )

        val subscriptions = mutableListOf<Subscription>()
        val unsubscribed = mutableListOf<String>()
        val sentEvents = mutableListOf<NostrEvent>()
        val confirmations = ArrayDeque<Confirmation>()
        val canceledConfirmations = mutableListOf<Confirmation>()
        private var connectionAvailableHandler: (() -> Unit)? = null

        override fun subscribe(filter: NostrFilter, id: String, handler: (NostrEvent) -> Boolean) {
            if (failSubscribe) throw java.io.IOException("subscribe failed")
            subscriptions += Subscription(id, filter, handler)
        }

        override fun unsubscribe(id: String) {
            unsubscribed += id
        }

        override fun sendEventConfirmed(
            event: NostrEvent,
            completion: (accepted: Boolean) -> Unit
        ) {
            if (failSend) throw java.io.IOException("send failed")
            sentEvents += event
            val result = confirmationResult
            if (result == null) {
                confirmations.addLast(Confirmation(event.id, completion))
            } else {
                completion(result)
            }
        }

        override fun cancelConfirmedEvent(eventId: String) {
            val retained = ArrayDeque<Confirmation>()
            while (confirmations.isNotEmpty()) {
                val confirmation = confirmations.removeFirst()
                if (confirmation.eventId == eventId) {
                    canceledConfirmations += confirmation
                    confirmation.completion(false)
                } else {
                    retained.addLast(confirmation)
                }
            }
            confirmations.addAll(retained)
        }

        override fun setOnConnectionAvailable(handler: () -> Unit) {
            connectionAvailableHandler = handler
        }

        fun reconnect() {
            connectionAvailableHandler?.invoke()
        }
    }

    private class FakeRetryScheduler : NdrRetryScheduler {
        data class ScheduledTask(
            val delayMs: Long,
            val task: () -> Unit,
            var canceled: Boolean = false
        )

        val scheduled = ArrayDeque<ScheduledTask>()

        override fun schedule(delayMs: Long, task: () -> Unit): NdrRetryCancellation {
            val scheduledTask = ScheduledTask(delayMs, task)
            scheduled.addLast(scheduledTask)
            return NdrRetryCancellation { scheduledTask.canceled = true }
        }

        fun runNext() {
            while (scheduled.isNotEmpty()) {
                val scheduledTask = scheduled.removeFirst()
                if (!scheduledTask.canceled) {
                    scheduledTask.task()
                    return
                }
            }
        }
    }

    private class InMemoryMarkerStore : NdrEstablishedSessionMarkerStore {
        private val marked = mutableSetOf<String>()
        var markFailure: Throwable? = null
        var panicMarkFailure: Throwable? = null
        private var panicWipeRequired = false

        override fun contains(accountPubkeyHex: String): Boolean =
            accountPubkeyHex.lowercase() in marked

        override fun mark(accountPubkeyHex: String) {
            markFailure?.let { throw it }
            marked += accountPubkeyHex.lowercase()
        }

        override fun clearEstablishedSessions() {
            marked.clear()
        }

        override fun isPanicWipeRequired(): Boolean = panicWipeRequired

        override fun markPanicWipeRequired() {
            panicMarkFailure?.let { throw it }
            panicWipeRequired = true
        }

        override fun clearPanicWipeRequired() {
            panicWipeRequired = false
        }
    }

    private class InMemoryPanicStorageQuarantine : NdrPanicStorageQuarantine {
        var pending = false
        var nativeStateWiped = false

        override fun isPending(): Boolean = pending

        override fun begin() {
            pending = true
        }

        override fun wipeNativeState() {
            check(pending)
            nativeStateWiped = true
        }

        override fun clear() {
            check(nativeStateWiped)
            pending = false
        }
    }

    private class FakeNdrPairwiseRuntime(
        private val activeSessionPeers: MutableSet<String> = mutableSetOf()
    ) : NdrPairwiseRuntime {
        val halfReadySessionPeers = mutableSetOf<String>()
        val pendingEvents = mutableListOf<NdrPubSubEvent>()
        val ackedActionIds = mutableListOf<String>()
        val processedEvents = mutableListOf<String>()
        val processedOutOfBandResponses = mutableListOf<Pair<String, String>>()
        val acceptedInvites = mutableListOf<String>()
        val acceptedInviteUrls = mutableListOf<String>()
        val acceptInviteEvents = mutableListOf<NdrPubSubEvent>()
        val acceptInviteUrlEvents = mutableListOf<NdrPubSubEvent>()
        val processEvents = mutableListOf<NdrPubSubEvent>()
        val sendTextEvents = mutableListOf<NdrPubSubEvent>()
        val sendTextCalls = mutableListOf<String>()
        val sendTextExpirationCalls = mutableListOf<ULong?>()
        var acceptInviteResult = NdrAcceptInviteResult(
            peerPubkeyHex = "aa".repeat(32),
            createdNewSession = true
        )
        var acceptInviteUrlResult = acceptInviteResult
        var currentInvite: String? = null
        var destroyed = false
        var sendTextEntered: CountDownLatch? = null
        var releaseSendText: CountDownLatch? = null
        var sendTextFailure: Throwable? = null
        var activeSessionLookupFailure: Throwable? = null
        var knownPeerPubkeysFailure: Throwable? = null
        var retirePeerFailure: Throwable? = null
        var retirePeerFailureAfterRemoval: Throwable? = null
        val retiredPeers = mutableListOf<String>()
        @Volatile
        var sendTextCompleted = false
        var destroyedAfterSendCompleted = false

        override fun currentInviteEventJson(): String? = currentInvite

        override fun currentInviteUrl(root: String): String? = null

        override fun acceptInviteFromEventJson(
            eventJson: String,
            expectedPeerPubkeyHex: String
        ): NdrAcceptInviteResult {
            acceptedInvites += eventJson
            pendingEvents += acceptInviteEvents
            return acceptInviteResult
        }

        override fun acceptInviteFromUrl(
            inviteUrl: String,
            expectedPeerPubkeyHex: String
        ): NdrAcceptInviteResult {
            acceptedInviteUrls += inviteUrl
            pendingEvents += acceptInviteUrlEvents
            return acceptInviteUrlResult
        }

        override fun processEvent(eventJson: String) {
            processedEvents += eventJson
            pendingEvents += processEvents
        }

        override fun processOutOfBandResponse(
            eventJson: String,
            expectedPeerPubkeyHex: String
        ) {
            processedOutOfBandResponses += eventJson to expectedPeerPubkeyHex
        }

        override fun pendingActions(nowSeconds: ULong): List<NdrPubSubEvent> =
            pendingEvents.toList()

        override fun ackActions(actionIds: List<String>) {
            ackedActionIds += actionIds
            pendingEvents.removeAll { it.actionId in actionIds }
        }

        override fun sessionInfo(peerPubkeyHex: String): NdrPairwiseSessionInfo? {
            activeSessionLookupFailure?.let { throw it }
            val peer = peerPubkeyHex.lowercase()
            return when {
                peer in activeSessionPeers ->
                    NdrPairwiseSessionInfo(
                        sendReady = true,
                        receiveReady = true,
                        trackedSenderPubkeys = listOf(peer)
                    )
                peer in halfReadySessionPeers ->
                    NdrPairwiseSessionInfo(
                        sendReady = false,
                        receiveReady = false,
                        trackedSenderPubkeys = emptyList()
                    )
                else -> null
            }
        }

        override fun knownPeerPubkeys(): List<String> {
            knownPeerPubkeysFailure?.let { throw it }
            return (activeSessionPeers + halfReadySessionPeers).toList()
        }

        override fun retirePeer(peerPubkeyHex: String): Boolean {
            retirePeerFailure?.let { throw it }
            retiredPeers += peerPubkeyHex.lowercase()
            val removed = activeSessionPeers.remove(peerPubkeyHex.lowercase()) ||
                halfReadySessionPeers.remove(peerPubkeyHex.lowercase())
            retirePeerFailureAfterRemoval?.let { throw it }
            return removed
        }

        override fun sendText(
            recipientPubkeyHex: String,
            text: String,
            expiresAtSeconds: ULong?
        ): NdrPairwiseSendResult {
            sendTextCalls += recipientPubkeyHex
            sendTextExpirationCalls += expiresAtSeconds
            sendTextEntered?.countDown()
            releaseSendText?.await(2, TimeUnit.SECONDS)
            sendTextFailure?.let { throw it }
            sendTextCompleted = true
            pendingEvents += sendTextEvents
            return NdrPairwiseSendResult(
                innerEventId = "21".repeat(32),
                outerEventId = "22".repeat(32)
            )
        }

        override fun getOurPubkeyHex(): String = "22".repeat(32)

        override fun getTotalSessions(): ULong = activeSessionPeers.size.toULong()

        override fun destroy() {
            destroyedAfterSendCompleted = sendTextCompleted
            destroyed = true
        }
    }
}
