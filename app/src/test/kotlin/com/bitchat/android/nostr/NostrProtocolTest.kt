package com.bitchat.android.nostr

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class NostrProtocolTest {
    private val gson = Gson()

    @Test
    fun decryptPrivateMessage_acceptsAuthenticatedSeal() {
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val giftWrap = NostrProtocol.createPrivateMessage(
            content = "bitchat1:test",
            recipientPubkey = recipient.publicKeyHex,
            senderIdentity = sender
        ).single()

        val decrypted = NostrProtocol.decryptPrivateMessage(giftWrap, recipient)

        assertEquals("bitchat1:test", decrypted?.first)
        assertEquals(sender.publicKeyHex, decrypted?.second)
    }

    @Test
    fun decryptPrivateMessage_rejectsSealWhoseSignerDoesNotMatchRumor() {
        val claimedSender = NostrIdentity.generate()
        val attacker = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val giftWrap = forgedGiftWrap(
            content = "bitchat1:forged",
            claimedSender = claimedSender,
            sealSigner = attacker,
            recipient = recipient
        )

        val decrypted = NostrProtocol.decryptPrivateMessage(giftWrap, recipient)

        assertNull(decrypted)
    }

    @Test
    fun createGeohashTextNote_addsExpirationAndUrgentTags() = runBlocking {
        val identity = NostrIdentity.generate()

        val event = NostrProtocol.createGeohashTextNote(
            content = "road closed",
            geohash = "u33dc",
            senderIdentity = identity,
            nickname = "alice",
            expiresAt = 1_700_086_400,
            urgent = true
        )

        assertEquals(NostrKind.TEXT_NOTE, event.kind)
        assertTrue(event.tags.contains(listOf("g", "u33dc")))
        assertTrue(event.tags.contains(listOf("n", "alice")))
        assertTrue(event.tags.contains(listOf("expiration", "1700086400")))
        assertTrue(event.tags.contains(listOf("t", "urgent")))
        assertTrue(event.isValidSignature())
    }

    @Test
    fun createDeleteEvent_isSignedNip09Request() = runBlocking {
        val identity = NostrIdentity.generate()

        val event = NostrProtocol.createDeleteEvent("event-id", identity)

        assertEquals(NostrKind.DELETION, event.kind)
        assertEquals(listOf(listOf("e", "event-id")), event.tags)
        assertTrue(event.isValidSignature())
    }

    @Test
    fun rejectsMalformedAuthenticatedEnvelopes() {
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()
        val malformed = listOf(
            forgedGiftWrap("hello", sender, sender, recipient, rumorKind = NostrKind.TEXT_NOTE),
            forgedGiftWrap("hello", sender, sender, recipient, rumorTags = listOf(listOf("p", sender.publicKeyHex))),
            forgedGiftWrap("hello", sender, sender, recipient, sealTags = listOf(listOf("p", recipient.publicKeyHex))),
            forgedGiftWrap("hello", sender, sender, recipient, rumorTimestamp = 1),
            forgedGiftWrap("hello", sender, sender, recipient, outerTags = emptyList())
        )
        malformed.forEach { assertNull(NostrProtocol.decryptPrivateMessage(it, recipient)) }
        val ios = forgedGiftWrap("hello", sender, sender, recipient, rumorTags = emptyList())
        assertEquals("hello", NostrProtocol.decryptPrivateMessage(ios, recipient)?.first)
    }

    @Test
    fun createPrivateMessage_reservesSlackInsideIosLookback() {
        val sender = NostrIdentity.generate()
        val recipient = NostrIdentity.generate()

        assertEquals(
            IOS_DM_LOOKBACK_SECONDS - TIMESTAMP_SAFETY_SLACK_SECONDS,
            NostrCrypto.NIP17_DEFAULT_MAX_PAST_SECONDS
        )

        repeat(20) {
            val beforeCreation = (System.currentTimeMillis() / 1000).toInt()
            val giftWrap = NostrProtocol.createPrivateMessage(
                content = "bitchat1:test",
                recipientPubkey = recipient.publicKeyHex,
                senderIdentity = sender
            ).single()
            val afterCreation = (System.currentTimeMillis() / 1000).toInt()
            val sealJson = NostrCrypto.decryptNIP44(
                ciphertext = giftWrap.content,
                senderPublicKeyHex = giftWrap.pubkey,
                recipientPrivateKeyHex = recipient.privateKeyHex
            )
            val seal = gson.fromJson(sealJson, NostrEvent::class.java)

            assertTimestampWithinIosLookback("gift wrap", giftWrap.createdAt, beforeCreation, afterCreation)
            assertTimestampWithinIosLookback("seal", seal.createdAt, beforeCreation, afterCreation)
        }
    }

    private fun assertTimestampWithinIosLookback(
        envelope: String,
        createdAt: Int,
        beforeCreation: Int,
        afterCreation: Int
    ) {
        assertTrue(
            "$envelope timestamp must leave 2 hours inside the iOS lookback",
            createdAt >= beforeCreation - MAX_OUTBOUND_BACKDATE_SECONDS
        )
        assertTrue(
            "$envelope timestamp must not be in the future",
            createdAt <= afterCreation
        )
    }

    private fun forgedGiftWrap(
        content: String,
        claimedSender: NostrIdentity,
        sealSigner: NostrIdentity,
        recipient: NostrIdentity,
        rumorKind: Int = NostrKind.DIRECT_MESSAGE,
        rumorTags: List<List<String>> = listOf(listOf("p", recipient.publicKeyHex)),
        sealTags: List<List<String>> = emptyList(),
        rumorTimestamp: Int = (System.currentTimeMillis() / 1000).toInt(),
        outerTags: List<List<String>> = listOf(listOf("p", recipient.publicKeyHex))
    ): NostrEvent {
        val rumorBase = NostrEvent(
            pubkey = claimedSender.publicKeyHex,
            createdAt = rumorTimestamp,
            kind = rumorKind,
            tags = rumorTags,
            content = content
        )
        val rumor = rumorBase.copy(id = rumorBase.computeEventIdHex())
        val sealContent = NostrCrypto.encryptNIP44(
            plaintext = gson.toJson(rumor),
            recipientPublicKeyHex = recipient.publicKeyHex,
            senderPrivateKeyHex = sealSigner.privateKeyHex
        )
        val seal = NostrEvent(
            pubkey = sealSigner.publicKeyHex,
            createdAt = NostrCrypto.randomizeTimestampUpToPast(),
            kind = NostrKind.SEAL,
            tags = sealTags,
            content = sealContent
        ).sign(sealSigner.privateKeyHex)

        val (wrapPrivateKey, wrapPublicKey) = NostrCrypto.generateKeyPair()
        val giftWrapContent = NostrCrypto.encryptNIP44(
            plaintext = gson.toJson(seal),
            recipientPublicKeyHex = recipient.publicKeyHex,
            senderPrivateKeyHex = wrapPrivateKey
        )
        return NostrEvent(
            pubkey = wrapPublicKey,
            createdAt = NostrCrypto.randomizeTimestampUpToPast(),
            kind = NostrKind.GIFT_WRAP,
            tags = outerTags,
            content = giftWrapContent
        ).sign(wrapPrivateKey)
    }

    private companion object {
        const val IOS_DM_LOOKBACK_SECONDS = 86_400
        const val TIMESTAMP_SAFETY_SLACK_SECONDS = 7_200
        const val MAX_OUTBOUND_BACKDATE_SECONDS =
            IOS_DM_LOOKBACK_SECONDS - TIMESTAMP_SAFETY_SLACK_SECONDS
    }
}
