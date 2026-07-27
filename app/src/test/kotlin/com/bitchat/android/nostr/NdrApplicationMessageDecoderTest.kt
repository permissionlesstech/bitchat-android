package com.bitchat.android.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrApplicationMessageDecoderTest {
    private val sender = "ab".repeat(32)

    @Test
    fun decodesOwnerBoundPairwiseRumor() {
        val event = pairwiseRumor(sender, "bitchat1:payload", 123)

        val decoded = NdrApplicationMessageDecoder.decode(
            decrypted(event)
        )

        assertEquals("bitchat1:payload", decoded?.content)
        assertEquals(123_000L, decoded?.timestampMs)
        assertNull(decoded?.expiresAtSeconds)
    }

    @Test
    fun rejectsRumorClaimingAnotherOwner() {
        val event = pairwiseRumor("cd".repeat(32), "bitchat1:payload", 123)

        val decoded = NdrApplicationMessageDecoder.decode(
            decrypted(event)
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
            decrypted(event)
        )

        assertNull(decoded)
    }

    @Test
    fun rejectsLegacyDirectEmbeddedPacket() {
        val decoded = NdrApplicationMessageDecoder.decode(
            NdrDecryptedMessage(
                content = "bitchat1:legacy",
                senderPubkeyHex = sender,
                eventId = "01".repeat(32),
                actionId = "action-1"
            )
        )

        assertNull(decoded)
    }

    @Test
    fun rejectsLegacyPacketWithMalformedAuthenticatedSender() {
        val decoded = NdrApplicationMessageDecoder.decode(
            NdrDecryptedMessage(
                content = "bitchat1:legacy",
                senderPubkeyHex = "not-a-pubkey",
                eventId = "01".repeat(32),
                actionId = "action-1"
            )
        )

        assertNull(decoded)
    }

    @Test
    fun malformedJsonShapeIsRejectedWithoutEscapingAnException() {
        val decoded = NdrApplicationMessageDecoder.decode(
            NdrDecryptedMessage(
                content = """{"id":"${"01".repeat(32)}","kind":14}""",
                senderPubkeyHex = sender,
                eventId = "01".repeat(32),
                actionId = "action-1"
            )
        )

        assertNull(decoded)
    }

    @Test
    fun rejectsSignedRumor() {
        val event = pairwiseRumor(sender, "bitchat1:payload", 123)
            .copy(sig = "01".repeat(64))

        val decoded = NdrApplicationMessageDecoder.decode(
            decrypted(event)
        )

        assertNull(decoded)
    }

    @Test
    fun rejectsTamperedEmbeddedDeterministicId() {
        val event = pairwiseRumor(sender, "bitchat1:payload", 123)
        val tampered = event.copy(id = "01".repeat(32))

        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(tampered, eventId = tampered.id)
            )
        )
    }

    @Test
    fun rejectsMissingInvalidOrMismatchedAuthenticatedEventId() {
        val event = pairwiseRumor(sender, "bitchat1:payload", 123)

        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(event, eventId = "")
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(event, eventId = "not-an-event-id")
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(event, eventId = "02".repeat(32))
            )
        )
    }

    @Test
    fun rejectsDuplicateOrConflictingVersionMarkers() {
        val duplicate = pairwiseRumor(
            sender,
            "bitchat1:payload",
            123,
            extraTags = listOf(listOf("ndr-version", "1"))
        )
        val conflicting = pairwiseRumor(
            sender,
            "bitchat1:payload",
            123,
            extraTags = listOf(listOf("ndr-version", "2"))
        )

        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(duplicate)
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(conflicting)
            )
        )
    }

    @Test
    fun requiresExactlyOneUnsignedMillisecondTimestampAndUsesItDirectly() {
        val valid = pairwiseRumor(
            sender,
            "bitchat1:payload",
            createdAt = 123,
            timestampMs = 42
        )
        val missingBase = NostrEvent(
            pubkey = sender,
            createdAt = 123,
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(
                listOf("ndr-protocol", "pairwise-rumor"),
                listOf("ndr-version", "1")
            ),
            content = "bitchat1:payload"
        )
        val missing = missingBase.copy(id = missingBase.computeEventIdHex())
        val malformed = pairwiseRumor(
            sender,
            "bitchat1:payload",
            createdAt = 123,
            timestampTagValue = "-1"
        )
        val duplicate = pairwiseRumor(
            sender,
            "bitchat1:payload",
            createdAt = 123,
            extraTags = listOf(listOf("ms", "124000"))
        )

        assertEquals(
            42L,
            NdrApplicationMessageDecoder.decode(
                decrypted(valid)
            )?.timestampMs
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(missing)
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(malformed)
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(duplicate)
            )
        )
    }

    @Test
    fun extractsExpirationForLastMomentHostRecheck() {
        val event = pairwiseRumor(
            sender,
            "bitchat1:payload",
            123,
            extraTags = listOf(listOf("expiration", "500"))
        )

        val decoded = NdrApplicationMessageDecoder.decode(
            decrypted(event, expiresAtSeconds = 500uL)
        )

        assertEquals(500L, decoded?.expiresAtSeconds)
        assertFalse(decoded!!.isExpiredAt(499L))
        assertTrue(decoded.isExpiredAt(500L))
    }

    @Test
    fun rejectsMalformedOrDuplicateExpiration() {
        val malformed = pairwiseRumor(
            sender,
            "bitchat1:payload",
            123,
            extraTags = listOf(listOf("expiration", "tomorrow"))
        )
        val duplicate = pairwiseRumor(
            sender,
            "bitchat1:payload",
            123,
            extraTags = listOf(
                listOf("expiration", "500"),
                listOf("expiration", "501")
            )
        )

        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(malformed)
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(duplicate)
            )
        )
    }

    @Test
    fun rejectsMissingOrMismatchedActionExpiration() {
        val expiring = pairwiseRumor(
            sender,
            "bitchat1:payload",
            123,
            extraTags = listOf(listOf("expiration", "500"))
        )
        val nonExpiring = pairwiseRumor(sender, "bitchat1:payload", 123)

        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(expiring)
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(expiring, expiresAtSeconds = 501uL)
            )
        )
        assertNull(
            NdrApplicationMessageDecoder.decode(
                decrypted(nonExpiring, expiresAtSeconds = 500uL)
            )
        )
    }

    private fun decrypted(
        event: NostrEvent,
        eventId: String = event.id,
        expiresAtSeconds: ULong? = null
    ): NdrDecryptedMessage = NdrDecryptedMessage(
        content = event.toJsonString(),
        senderPubkeyHex = sender,
        eventId = eventId,
        actionId = "action-1",
        expiresAtSeconds = expiresAtSeconds
    )

    private fun pairwiseRumor(
        pubkey: String,
        content: String,
        createdAt: Int,
        timestampMs: Long = createdAt.toLong() * 1_000L,
        timestampTagValue: String = timestampMs.toString(),
        extraTags: List<List<String>> = emptyList()
    ): NostrEvent {
        val unsigned = NostrEvent(
            pubkey = pubkey,
            createdAt = createdAt,
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(
                listOf("ndr-protocol", "pairwise-rumor"),
                listOf("ndr-version", "1"),
                listOf("ms", timestampTagValue)
            ) + extraTags,
            content = content
        )
        return unsigned.copy(id = unsigned.computeEventIdHex())
    }
}
