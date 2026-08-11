package com.bitchat.android.nostr

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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
            "$envelope timestamp must leave 15 minutes inside the iOS lookback",
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
        recipient: NostrIdentity
    ): NostrEvent {
        val rumorBase = NostrEvent(
            pubkey = claimedSender.publicKeyHex,
            createdAt = (System.currentTimeMillis() / 1000).toInt(),
            kind = NostrKind.DIRECT_MESSAGE,
            tags = listOf(listOf("p", recipient.publicKeyHex)),
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
            tags = emptyList(),
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
            tags = listOf(listOf("p", recipient.publicKeyHex)),
            content = giftWrapContent
        ).sign(wrapPrivateKey)
    }

    private companion object {
        const val IOS_DM_LOOKBACK_SECONDS = 86_400
        const val TIMESTAMP_SAFETY_SLACK_SECONDS = 900
        const val MAX_OUTBOUND_BACKDATE_SECONDS =
            IOS_DM_LOOKBACK_SECONDS - TIMESTAMP_SAFETY_SLACK_SECONDS
    }
}
