package com.bitchat.android.noise

import android.os.Build
import com.bitchat.android.noise.southernstorm.protocol.Noise
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.P], manifest = Config.NONE)
class NoiseSessionReplayVectorTest {
    // Synthetic key 00..1f, plaintext UTF-8 "synthetic", no associated data.
    // Generated independently with JDK ChaCha20-Poly1305, not the Noise encoder.
    private val vectors = mapOf(
        0 to "000000006bc12c45c583d2b8708b09291e7287cde40d0d3f5f16eb8ca5",
        1 to "00000001ec2e9d2bdd4fdb714aaf50b33f3aa7fea5365bbe958401c2e5",
        7 to "000000078276ea3c4a8d0ee001cf5426fa87b19d208058715c39c39ab3",
        8 to "00000008bf88d5580e1c1e7b417f66d504c07742cc09e9092cdd0d9853",
        93 to "0000005d284e7cca18570c53257293c45c112c2c93590ee3cdc987535d",
        99 to "000000638fcdc1e108369659fe53d1dd99b65410b6489287417c7c6703",
        100 to "00000064318044f77594bf07fee76827755da3c3ba122c03af8e6a5205",
        1023 to "000003ff8291afa28670db1ff15acd82645132aa92f9862c907ea51eed",
        1024 to "00000400702be091cf018f9c1534eddc8b33e874b797bd71552696699d"
    )
    private val plaintext = "synthetic".toByteArray(Charsets.UTF_8)

    @Test
    fun `literal transport vectors decrypt once across byte and bit shifts`() = withReceiver { session ->
        val nonces = listOf(0, 1, 7, 8, 93, 99, 100)
        nonces.forEach { assertArrayEquals(plaintext, session.decrypt(frame(it))) }
        nonces.forEach { assertRejected(session, frame(it)) }
    }

    @Test
    fun `reordered unseen transport vectors remain eligible exactly once`() = withReceiver { session ->
        listOf(100, 93, 99).forEach { nonce ->
            assertArrayEquals(plaintext, session.decrypt(frame(nonce)))
            assertRejected(session, frame(nonce))
        }
    }

    @Test
    fun `window keeps the oldest seen bit and rejects the first expired nonce`() = withReceiver { session ->
        assertArrayEquals(plaintext, session.decrypt(frame(0)))
        assertArrayEquals(plaintext, session.decrypt(frame(1023)))
        assertRejected(session, frame(0))
        assertArrayEquals(plaintext, session.decrypt(frame(1024)))
        assertRejected(session, frame(0))
        assertArrayEquals(plaintext, session.decrypt(frame(1)))
    }

    @Test
    fun `unseen old transport frames obey the window boundary`() = withReceiver { session ->
        assertArrayEquals(plaintext, session.decrypt(frame(1024)))
        assertRejected(session, frame(0))
        assertArrayEquals(plaintext, session.decrypt(frame(1)))
        assertRejected(session, frame(1))
    }

    @Test
    fun `an unauthenticated high nonce cannot expire unseen transport frames`() = withReceiver { session ->
        val tampered = frame(1024).apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertRejected(session, tampered)
        assertArrayEquals(plaintext, session.decrypt(frame(0)))
        assertArrayEquals(plaintext, session.decrypt(frame(1024)))
        assertRejected(session, frame(1024))
    }

    @Test
    fun `authentication failure does not consume a transport nonce`() = withReceiver { session ->
        val tampered = frame(100).apply { this[lastIndex] = (last().toInt() xor 1).toByte() }
        assertRejected(session, tampered)
        assertArrayEquals(plaintext, session.decrypt(frame(100)))
        assertRejected(session, frame(100))
    }

    private fun frame(nonce: Int): ByteArray =
        vectors.getValue(nonce).chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun assertRejected(session: NoiseSession, frame: ByteArray) {
        assertThrows(SessionError.DecryptionFailed::class.java) { session.decrypt(frame) }
    }

    private fun withReceiver(block: (NoiseSession) -> Unit) {
        val session = NoiseSession("synthetic-peer", false, ByteArray(32) { 1 }, ByteArray(32) { 2 })
        val receiver = Noise.createCipher("ChaChaPoly").apply {
            initializeKey(ByteArray(32) { it.toByte() }, 0)
        }
        // Inject only the established transport fixture; exercise the real receive path.
        NoiseSession::class.java.getDeclaredField("receiveCipher").apply {
            isAccessible = true
            set(session, receiver)
        }
        NoiseSession::class.java.getDeclaredField("state").apply {
            isAccessible = true
            set(session, NoiseSession.NoiseSessionState.Established)
        }
        try {
            block(session)
        } finally {
            session.destroy()
        }
    }
}
