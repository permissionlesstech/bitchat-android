package com.bitchat.android.nostr

import com.bitchat.android.model.NdrFeatureGate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NdrSubscriptionAdmissionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @After
    fun resetFeatureGate() {
        NdrFeatureGate.setEnabledForTests(false)
    }

    @Test
    fun `rejected native subscription remains pending instead of being acknowledged`() {
        NdrFeatureGate.setEnabledForTests(true)
        val runtime = SubscriptionRuntime()
        val relay = RecordingRelayManager()
        val storageDirectory = temporaryFolder.newFolder("ndr").absolutePath
        val service = NdrNostrService(
            relayManager = relay,
            runtimeFactory = object : NdrPairwiseRuntimeFactory {
                override fun newWithStoragePath(
                    ourPubkeyHex: String,
                    ourIdentityPrivkeyHex: String,
                    storagePath: String
                ): NdrPairwiseRuntime = runtime
            },
            storageDirectoryProvider = { storageDirectory }
        )

        assertTrue(service.configureIfNeeded(testIdentity()))
        assertTrue(relay.subscriptions.isEmpty())
        assertTrue(runtime.ackedActionIds.isEmpty())
        assertEquals(
            listOf(SUBSCRIPTION_ACTION_ID),
            runtime.pendingActions(0u).map { it.actionId }
        )
    }

    @Test
    fun `reset-blocked pairwise subscription installs and acknowledges once after reset`() {
        NdrFeatureGate.setEnabledForTests(true)
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val relayManager = NostrRelayManager(
            scope = scope,
            eventDeduplicator = NostrEventDeduplicator(maxCapacity = 8)
        )
        val runtime = SubscriptionRuntime(
            NdrPubSubEvent(
                kind = "subscribe",
                actionId = SUBSCRIPTION_ACTION_ID,
                subid = "messages",
                filterJson =
                    """{"authors":["${"aa".repeat(32)}"],"kinds":[1060]}"""
            )
        )
        val storageDirectory = temporaryFolder.newFolder("reset-ndr").absolutePath
        val service = NdrNostrService(
            relayManager = BitchatNdrRelayAdapter(
                relayManager,
                accountRelayUrls = emptyList()
            ),
            runtimeFactory = object : NdrPairwiseRuntimeFactory {
                override fun newWithStoragePath(
                    ourPubkeyHex: String,
                    ourIdentityPrivkeyHex: String,
                    storagePath: String
                ): NdrPairwiseRuntime = runtime
            },
            storageDirectoryProvider = { storageDirectory }
        )
        val identity = testIdentity()
        val resetToken = relayManager.beginAccountReset()

        try {
            assertTrue(service.configureIfNeeded(identity))
            assertTrue(relayManager.getActiveSubscriptions().isEmpty())
            assertTrue(runtime.ackedActionIds.isEmpty())

            assertTrue(relayManager.discardForAccountReset(resetToken))
            assertTrue(relayManager.completeAccountReset(resetToken))
            assertTrue(service.configureIfNeeded(identity))
            assertEquals(
                setOf("messages"),
                relayManager.getActiveSubscriptions().keys
            )
            assertEquals(listOf(SUBSCRIPTION_ACTION_ID), runtime.ackedActionIds)

            assertTrue(service.configureIfNeeded(identity))
            assertEquals(
                setOf("messages"),
                relayManager.getActiveSubscriptions().keys
            )
            assertEquals(listOf(SUBSCRIPTION_ACTION_ID), runtime.ackedActionIds)
        } finally {
            val cleanupReset = relayManager.discardForAccountReset()
            relayManager.completeAccountReset(cleanupReset)
            scope.cancel()
        }
    }

    private fun testIdentity(): NostrIdentity =
        NostrIdentity.fromPrivateKey("0".repeat(63) + "1")

    @Test
    fun `rotating authors replace the stable subscription and retry rejected replacements`() {
        NdrFeatureGate.setEnabledForTests(true)
        fun subscribeAction(actionId: String, author: String) = NdrPubSubEvent(
            kind = "subscribe",
            actionId = actionId,
            subid = "messages",
            filterJson = """{"authors":["$author"],"kinds":[1060]}"""
        )
        val firstAuthor = "aa".repeat(32)
        val nextAuthor = "bb".repeat(32)
        val runtime = SubscriptionRuntime(subscribeAction("initial", firstAuthor))
        val relay = RecordingRelayManager()
        val storageDirectory = temporaryFolder.newFolder("rotating-authors").absolutePath
        val service = NdrNostrService(
            relayManager = relay,
            runtimeFactory = object : NdrPairwiseRuntimeFactory {
                override fun newWithStoragePath(
                    ourPubkeyHex: String,
                    ourIdentityPrivkeyHex: String,
                    storagePath: String
                ): NdrPairwiseRuntime = runtime
            },
            storageDirectoryProvider = { storageDirectory }
        )

        assertTrue(service.configureIfNeeded(testIdentity()))
        assertEquals(listOf(firstAuthor), relay.filters["messages"]?.authors)
        assertEquals(listOf("initial"), runtime.ackedActionIds)

        runtime.action = subscribeAction("rotation", nextAuthor)
        relay.rejectSubscriptions = true
        assertTrue(service.configureIfNeeded(testIdentity()))
        assertEquals(listOf(firstAuthor), relay.filters["messages"]?.authors)
        assertEquals(listOf("initial"), runtime.ackedActionIds)
        assertEquals(listOf("rotation"), runtime.pendingActions(0u).map { it.actionId })

        relay.rejectSubscriptions = false
        assertTrue(service.configureIfNeeded(testIdentity()))
        assertEquals(listOf(nextAuthor), relay.filters["messages"]?.authors)
        assertEquals(listOf("initial", "rotation"), runtime.ackedActionIds)
        assertEquals(listOf("messages", "messages"), relay.subscriptions)
        assertTrue(runtime.pendingActions(0u).isEmpty())
    }

    private class RecordingRelayManager : NdrRelayManager {
        val subscriptions = mutableListOf<String>()
        val filters = mutableMapOf<String, NostrFilter>()
        var rejectSubscriptions = false

        override fun subscribe(
            filter: NostrFilter,
            id: String,
            handler: (NostrEvent) -> Boolean
        ) {
            check(!rejectSubscriptions) { "subscription temporarily unavailable" }
            subscriptions += id
            filters[id] = filter
        }

        override fun unsubscribe(id: String) = Unit

        override fun sendEventConfirmed(
            event: NostrEvent,
            completion: (accepted: Boolean) -> Unit
        ) = completion(true)

        override fun cancelConfirmedEvent(eventId: String) = Unit

        override fun setOnConnectionAvailable(handler: () -> Unit) = Unit
    }

    private class SubscriptionRuntime(
        var action: NdrPubSubEvent = NdrPubSubEvent(
            kind = "subscribe",
            actionId = SUBSCRIPTION_ACTION_ID,
            subid = "messages",
            filterJson = """
                {
                  "kinds": [1060],
                  "authors": ["${"aa".repeat(32)}"],
                  "#p": ["${"bb".repeat(32)}"]
                }
            """.trimIndent()
        )
    ) : NdrPairwiseRuntime {
        val ackedActionIds = mutableListOf<String>()

        override fun currentInviteEventJson(): String? = null

        override fun acceptInviteFromEventJson(
            eventJson: String,
            expectedPeerPubkeyHex: String
        ): NdrAcceptInviteResult = unsupported()

        override fun acceptInviteFromUrl(
            inviteUrl: String,
            expectedPeerPubkeyHex: String
        ): NdrAcceptInviteResult = unsupported()

        override fun processEvent(eventJson: String) = Unit

        override fun processOutOfBandResponse(
            eventJson: String,
            expectedPeerPubkeyHex: String
        ) = Unit

        override fun pendingActions(nowSeconds: ULong): List<NdrPubSubEvent> =
            if (action.actionId in ackedActionIds) emptyList() else listOf(action)

        override fun ackActions(actionIds: List<String>) {
            ackedActionIds += actionIds
        }

        override fun sessionInfo(peerPubkeyHex: String): NdrPairwiseSessionInfo? = null
        override fun knownPeerPubkeys(): List<String> = emptyList()
        override fun retirePeer(peerPubkeyHex: String): Boolean = false

        override fun sendText(
            recipientPubkeyHex: String,
            text: String,
            expiresAtSeconds: ULong?
        ): Unit = unsupported()

        override fun destroy() = Unit

        private fun <T> unsupported(): T = error("not used")
    }

    companion object {
        private const val SUBSCRIPTION_ACTION_ID = "subscription-action"
    }
}
