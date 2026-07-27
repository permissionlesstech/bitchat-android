package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NdrApplicationMessageDecoderTest {
    private val sender = "ab".repeat(32)

    @Test
    fun decodesOwnerBoundPairwiseRumor() {
        val event = pairwiseRumor(sender, "bitchat1:payload", 123)

        val decoded = NdrApplicationMessageDecoder.decode(
            NdrDecryptedMessage(
                content = event.toJsonString(),
                senderPubkeyHex = sender,
                eventId = "01".repeat(32)
            )
        )

        assertEquals("bitchat1:payload", decoded?.content)
        assertEquals(123_000L, decoded?.timestampMs)
    }

    @Test
    fun rejectsRumorClaimingAnotherOwner() {
        val event = pairwiseRumor("cd".repeat(32), "bitchat1:payload", 123)

        val decoded = NdrApplicationMessageDecoder.decode(
            NdrDecryptedMessage(
                content = event.toJsonString(),
                senderPubkeyHex = sender
            )
        )

        assertNull(decoded)
    }

    @Test
    fun rejectsRumorWithoutCurrentProtocolMarker() {
        val unsigned = NostrEvent(
            pubkey = sender,
            createdAt = 123,
            kind = NostrKind.DIRECT_MESSAGE,
            tags = emptyList(),
            content = "bitchat1:payload"
        )
        val event = unsigned.copy(id = unsigned.computeEventIdHex())

        val decoded = NdrApplicationMessageDecoder.decode(
            NdrDecryptedMessage(
                content = event.toJsonString(),
                senderPubkeyHex = sender
            )
        )

        assertNull(decoded)
    }

    @Test
    fun acceptsLegacyDirectEmbeddedPacket() {
        val decoded = NdrApplicationMessageDecoder.decode(
            NdrDecryptedMessage(
                content = "bitchat1:legacy",
                senderPubkeyHex = sender
            ),
            fallbackTimestampMs = 456L
        )

        assertEquals("bitchat1:legacy", decoded?.content)
        assertEquals(456L, decoded?.timestampMs)
    }

    @Test
    fun rejectsLegacyPacketWithMalformedAuthenticatedSender() {
        val decoded = NdrApplicationMessageDecoder.decode(
            NdrDecryptedMessage(
                content = "bitchat1:legacy",
                senderPubkeyHex = "not-a-pubkey"
            )
        )

        assertNull(decoded)
    }

    @Test
    fun rejectsMalformedMultiDeviceMetadata() {
        val event = pairwiseRumor(sender, "bitchat1:payload", 123)

        assertNull(
            NdrApplicationMessageDecoder.decode(
                NdrDecryptedMessage(
                    content = event.toJsonString(),
                    senderPubkeyHex = sender,
                    senderDevicePubkeyHex = "invalid"
                )
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                NdrDecryptedMessage(
                    content = event.toJsonString(),
                    senderPubkeyHex = sender,
                    conversationOwnerPubkeyHex = "invalid"
                )
            )
        )
    }

    @Test
    fun localSiblingRoutesToConversationOwnerWhileKeepingAuthenticatedSender() {
        val conversationOwner = "cd".repeat(32)
        val message = NdrDecryptedMessage(
            content = "bitchat1:payload",
            senderPubkeyHex = sender,
            senderDevicePubkeyHex = "bc".repeat(32),
            conversationOwnerPubkeyHex = conversationOwner
        )

        assertEquals(sender, message.senderPubkeyHex)
        assertEquals(conversationOwner, message.conversationPubkeyHex)
        org.junit.Assert.assertTrue(message.isLocalSiblingCopy)
    }

    @Test
    fun localSiblingMarkerRequiresAuthenticatedLocalAccountAuthor() {
        val localAccount = "ef".repeat(32)
        val validSibling = NdrDecryptedMessage(
            content = "bitchat1:payload",
            senderPubkeyHex = localAccount,
            conversationOwnerPubkeyHex = sender
        )
        val misattributedSibling = validSibling.copy(senderPubkeyHex = "cd".repeat(32))

        org.junit.Assert.assertTrue(validSibling.isAttributedToLocalAccount(localAccount))
        org.junit.Assert.assertFalse(
            misattributedSibling.isAttributedToLocalAccount(localAccount)
        )
    }

    private fun pairwiseRumor(
        pubkey: String,
        content: String,
        createdAt: Int
    ): NostrEvent {
        val unsigned = NostrEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(
                listOf("ndr-protocol", "pairwise-rumor"),
                listOf("ndr-version", "1")
            ),
            content = content
        )
        return unsigned.copy(id = unsigned.computeEventIdHex())
    }
}
