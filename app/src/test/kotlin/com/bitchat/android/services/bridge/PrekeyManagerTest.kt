package com.bitchat.android.services.bridge

import com.bitchat.android.noise.CourierNoiseCrypto
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrekeyManagerTest {
    @Test
    fun `message retry reuses assigned prekey and recipient grace key`() {
        val now = 1_750_000_000_000L
        val recipientIdentity = identity(seed = 1)
        val senderIdentity = identity(seed = 65)
        var generatedSeed = 100
        val recipient = manager(recipientIdentity) {
            ByteArray(32) { index -> (generatedSeed + index).toByte() }
                .also { generatedSeed += 1 }
        }
        val sender = manager(senderIdentity)
        val bundle = requireNotNull(recipient.currentSignedBundle(now))

        assertTrue(
            sender.verifyAndIngest(
                bundle,
                expectedNoiseKey = requireNotNull(recipientIdentity.staticKey()).second,
                announceBoundSigningKey = requireNotNull(recipientIdentity.signingKey()).second,
                nowMs = now
            )
        )

        val payload = "offline hello".toByteArray()
        val first = sender.seal(
            payload,
            messageId = "message-1",
            recipientNoiseKey = requireNotNull(recipientIdentity.staticKey()).second,
            recipientAdvertisesPrekeys = true,
            nowMs = now
        )
        val retry = sender.seal(
            payload,
            messageId = "message-1",
            recipientNoiseKey = requireNotNull(recipientIdentity.staticKey()).second,
            recipientAdvertisesPrekeys = true,
            nowMs = now + 1
        )

        assertNotNull(first.prekeyId)
        assertEquals(first.prekeyId, retry.prekeyId)
        val firstOpened = recipient.open(first.ciphertext, first.prekeyId, now + 2)
        val retryOpened = recipient.open(retry.ciphertext, retry.prekeyId, now + 3)
        assertArrayEquals(payload, firstOpened.payload)
        assertArrayEquals(payload, retryOpened.payload)
        assertTrue(firstOpened.consumedPrekey)
        assertFalse(retryOpened.consumedPrekey)

        val nextMessage = sender.seal(
            payload,
            messageId = "message-2",
            recipientNoiseKey = requireNotNull(recipientIdentity.staticKey()).second,
            recipientAdvertisesPrekeys = true,
            nowMs = now + 4
        )
        assertNotEquals(first.prekeyId, nextMessage.prekeyId)
    }

    private fun manager(
        identity: PrekeyIdentity,
        randomBytes: () -> ByteArray = { ByteArray(32) { (it + 11).toByte() } }
    ): PrekeyManager =
        PrekeyManager(
            identity = identity,
            localStore = MemoryLocalPrekeyStore(),
            peerStore = MemoryPeerPrekeyStore(),
            randomBytes = randomBytes
        )

    private fun identity(seed: Int): PrekeyIdentity {
        val staticPrivate = ByteArray(32) { (seed + it).toByte() }
        val signingPrivate = Ed25519PrivateKeyParameters(
            ByteArray(32) { (seed + 32 + it).toByte() },
            0
        )
        return FakePrekeyIdentity(
            static = staticPrivate to CourierNoiseCrypto.publicKey(staticPrivate),
            signing = signingPrivate.encoded to signingPrivate.generatePublicKey().encoded
        )
    }

    private class FakePrekeyIdentity(
        private val static: Pair<ByteArray, ByteArray>,
        private val signing: Pair<ByteArray, ByteArray>
    ) : PrekeyIdentity {
        override fun staticKey(): Pair<ByteArray, ByteArray> = static
        override fun signingKey(): Pair<ByteArray, ByteArray> = signing
    }

    private class MemoryLocalPrekeyStore : LocalPrekeyStore {
        private var state = LocalPrekeyState()

        override fun load(): LocalPrekeyState = state

        override fun save(state: LocalPrekeyState) {
            this.state = state
        }

        override fun clear() {
            state = LocalPrekeyState()
        }
    }

    private class MemoryPeerPrekeyStore : PeerPrekeyStore {
        private var bundles = mutableMapOf<String, StoredPeerPrekeyBundle>()

        override fun load(): MutableMap<String, StoredPeerPrekeyBundle> = bundles

        override fun save(bundles: Map<String, StoredPeerPrekeyBundle>) {
            this.bundles = bundles.toMutableMap()
        }

        override fun clear() {
            bundles.clear()
        }
    }
}
