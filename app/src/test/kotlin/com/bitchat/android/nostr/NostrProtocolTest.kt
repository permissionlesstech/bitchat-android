package com.bitchat.android.nostr

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
