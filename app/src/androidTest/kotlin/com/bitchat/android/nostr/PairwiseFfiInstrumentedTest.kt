package com.bitchat.android.nostr

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import uniffi.ndr_ffi.FfiKeyPair
import uniffi.ndr_ffi.PairwiseAction
import uniffi.ndr_ffi.PairwiseInvite
import uniffi.ndr_ffi.PairwiseManager
import uniffi.ndr_ffi.generateKeypair

@RunWith(AndroidJUnit4::class)
class PairwiseFfiInstrumentedTest {
    @Test
    fun directFfiHandshakeSendRestartDeduplicateExpiryAndRetirement() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testRoot = File(
            context.filesDir,
            "ndr-pairwise-ffi-${UUID.randomUUID()}"
        )
        assertTrue(testRoot.mkdirs())

        val aliceKeys = generateKeypair()
        val bobKeys = generateKeypair()
        val alicePath = File(testRoot, "alice")
        val bobPath = File(testRoot, "bob")
        var alice: PairwiseManager? = null
        var bob: PairwiseManager? = null

        try {
            alice = manager(aliceKeys, alicePath)
            bob = manager(bobKeys, bobPath)

            val inviteJson = alice.currentInviteEventJson()
            PairwiseInvite.fromEventJson(inviteJson).use { invite ->
                assertEquals(aliceKeys.publicKeyHex, invite.getPeerPubkeyHex())
            }
            val accepted = bob.acceptInviteFromEventJson(
                inviteJson,
                aliceKeys.publicKeyHex
            )
            assertEquals(aliceKeys.publicKeyHex, accepted.peerPubkeyHex)
            assertTrue(accepted.createdNewSession)

            val handshakeActions = bob.pendingActions()
            assertEquals(handshakeActions, bob.pendingActions())
            val response = handshakeActions.single { it.kind == "out_of_band" }
            val bootstrap = handshakeActions.single { it.kind == "publish" }
            assertNotNull(response.sessionId)
            assertEquals(response.sessionId, bootstrap.sessionId)
            assertEquals(aliceKeys.publicKeyHex, response.peerPubkeyHex)
            assertPairwiseWireEvent(response, expectedKind = 1059)
            assertPairwiseWireEvent(bootstrap, expectedKind = 1060)

            bob.close()
            bob = manager(bobKeys, bobPath)
            assertEquals(handshakeActions, bob.pendingActions())

            alice.processOutOfBandResponse(
                requireNotNull(response.eventJson),
                bobKeys.publicKeyHex
            )
            val halfReady = requireNotNull(alice.sessionInfo(bobKeys.publicKeyHex))
            assertFalse(halfReady.sendReady)
            alice.processEvent(requireNotNull(bootstrap.eventJson))
            assertTrue(requireNotNull(alice.sessionInfo(bobKeys.publicKeyHex)).sendReady)

            bob.ackActions(handshakeActions.map(PairwiseAction::actionId))
            bob.close()
            bob = manager(bobKeys, bobPath)
            assertTrue(
                bob.pendingActions().none { pending ->
                    pending.actionId in handshakeActions.map(PairwiseAction::actionId)
                }
            )

            val expiresAtSeconds =
                (System.currentTimeMillis() / 1_000L).toULong() + 3_600UL
            val text = "bitchat1:expiring-direct-ffi"
            val sent = alice.sendText(
                bobKeys.publicKeyHex,
                text,
                expiresAtSeconds
            )
            val publish = alice.pendingActions().single { action ->
                action.kind == "publish" && action.outerEventId == sent.outerEventId
            }
            assertPairwiseWireEvent(publish, expectedKind = 1060)
            val publishJson = requireNotNull(publish.eventJson)

            alice.close()
            alice = manager(aliceKeys, alicePath)
            val replayedPublish = alice.pendingActions().single { action ->
                action.actionId == publish.actionId
            }
            assertEquals(publishJson, replayedPublish.eventJson)

            bob.processEvent(publishJson)
            bob.processEvent(publishJson)
            val deliveries = bob.pendingActions().filter { it.kind == "delivery" }
            assertEquals(1, deliveries.size)
            val delivery = deliveries.single()
            assertEquals(aliceKeys.publicKeyHex, delivery.peerPubkeyHex)
            assertEquals(sent.innerEventId, delivery.innerEventId)
            assertTrue(requireNotNull(delivery.innerEventId).matches(HEX_32))
            assertEquals(expiresAtSeconds, delivery.expiresAtSeconds)

            val innerJson = requireNotNull(delivery.innerEventJson)
            val inner = JSONObject(innerJson)
            assertEquals(14, inner.getInt("kind"))
            assertEquals(aliceKeys.publicKeyHex, inner.getString("pubkey"))
            assertEquals(text, inner.getString("content"))
            assertTrue(
                containsTag(
                    inner.getJSONArray("tags"),
                    "expiration",
                    expiresAtSeconds.toString()
                )
            )

            bob.close()
            bob = manager(bobKeys, bobPath)
            val replayedDelivery = bob.pendingActions().single { action ->
                action.actionId == delivery.actionId
            }
            assertEquals(innerJson, replayedDelivery.innerEventJson)
            bob.ackActions(listOf(delivery.actionId))
            bob.close()
            bob = manager(bobKeys, bobPath)
            assertTrue(bob.pendingActions().none { it.actionId == delivery.actionId })

            alice.ackActions(listOf(publish.actionId))
            alice.close()
            alice = manager(aliceKeys, alicePath)
            assertTrue(alice.pendingActions().none { it.actionId == publish.actionId })

            assertTrue(alice.retirePeer(bobKeys.publicKeyHex))
            assertFalse(alice.retirePeer(bobKeys.publicKeyHex))
            alice.close()
            alice = manager(aliceKeys, alicePath)
            assertFalse(alice.knownPeerPubkeys().contains(bobKeys.publicKeyHex))
        } finally {
            alice?.close()
            bob?.close()
            testRoot.deleteRecursively()
        }
    }

    private fun manager(keys: FfiKeyPair, path: File): PairwiseManager =
        PairwiseManager.newWithStoragePath(
            keys.publicKeyHex,
            keys.privateKeyHex,
            path.absolutePath
        )

    private fun assertPairwiseWireEvent(action: PairwiseAction, expectedKind: Int) {
        val event = JSONObject(requireNotNull(action.eventJson))
        assertEquals(expectedKind, event.getInt("kind"))
        assertNotEquals(37368, event.getInt("kind"))
        if (expectedKind == 1060) {
            assertFalse(containsTag(event.getJSONArray("tags"), "p"))
        }
    }

    private fun containsTag(
        tags: JSONArray,
        name: String,
        expectedValue: String? = null
    ): Boolean =
        (0 until tags.length()).any { index ->
            val tag = tags.getJSONArray(index)
            tag.length() > 0 &&
                tag.getString(0) == name &&
                (expectedValue == null ||
                    (tag.length() > 1 && tag.getString(1) == expectedValue))
        }

    companion object {
        private val HEX_32 = Regex("^[0-9a-f]{64}$")
    }
}
