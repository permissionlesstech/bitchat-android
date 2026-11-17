package com.bitchat.android.nostr

import com.bitchat.android.model.NoisePayload
import com.bitchat.android.model.NoisePayloadType
import com.bitchat.android.protocol.BitchatPacket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class NostrSenderLinkPrivacyRobolectricTest {
    @Test
    fun `no recipient envelopes match v1 wire fixtures outside timestamp and random sender`() {
        val fixtures = requireNotNull(javaClass.getResourceAsStream("/contracts/nostr-no-recipient-v1.txt"))
            .bufferedReader().use { it.readLines() }.filter { it.isNotBlank() && !it.startsWith("#") }
        for (line in fixtures) {
            val (name, hex) = line.split(" ")
            val type = NoisePayloadType.valueOf(name)
            val expected = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val content = requireNotNull(
                if (type == NoisePayloadType.PRIVATE_MESSAGE) {
                    NostrEmbeddedBitChat.encodePMForNostrNoRecipient("hi", "id")
                } else {
                    NostrEmbeddedBitChat.encodeAckForNostrNoRecipient(type, "id")
                }
            )
            val bytes = Base64.getUrlDecoder().decode(content.removePrefix("bitchat1:"))
            assertEquals(256, bytes.size)
            // v1 timestamp occupies bytes 3..10; random sender occupies bytes 14..21.
            val normalized = bytes.copyOfRange(0, expected.size)
            normalized.fill(0, 3, 11)
            normalized.fill(0, 14, 22)
            assertArrayEquals(name, expected, normalized)
            val padding = (256 - expected.size).toByte()
            assertTrue(bytes.drop(expected.size).all { it == padding })
            val decoded = requireNotNull(BitchatPacket.fromBinaryData(expected))
            assertEquals(type, NoisePayload.decode(decoded.payload)?.type)
            assertNull(decoded.recipientID)
            assertNull(decoded.signature)
        }
    }
}
