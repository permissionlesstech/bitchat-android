package com.bitchat.android.nostr

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NostrProtocol].
 *
 * Covers gift-wrap decryption seal authentication, and the structure and
 * validity of the NIP-09 (kind:5) deletion event produced when a user
 * requests removal of one of their own location notes.
 */
class NostrProtocolTest {
    private val gson = Gson()

    // Deterministic secp256k1 identity derived from a fixed seed — no Android context required.
    private val senderIdentity = NostrIdentity.fromSeed("test-sender-seed-nostr-protocol")

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

    // ─── NIP-09 deletion event (kind:5) ──────────────────────────────────────

    // ─── kind ────────────────────────────────────────────────────────────────

    @Test
    fun `createDeletionEvent has kind 5 (NIP-09)`() = runBlocking {
        val event = NostrProtocol.createDeletionEvent("target123", senderIdentity)
        assertEquals("Event kind must be 5 per NIP-09", NostrKind.DELETION, event.kind)
    }

    // ─── e-tag ───────────────────────────────────────────────────────────────

    @Test
    fun `createDeletionEvent contains e-tag referencing the target event`() = runBlocking {
        val targetId = "abc123def456deadbeef"
        val event = NostrProtocol.createDeletionEvent(targetId, senderIdentity)

        val eTag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" }
        assertNotNull("Must contain an e-tag", eTag)
        assertEquals("e-tag value must equal targetEventId", targetId, eTag!![1])
    }

    @Test
    fun `createDeletionEvent contains exactly one e-tag`() = runBlocking {
        val event = NostrProtocol.createDeletionEvent("singleTarget", senderIdentity)

        val eTags = event.tags.filter { it.size >= 2 && it[0] == "e" }
        assertEquals("Exactly one e-tag expected for a single-note deletion", 1, eTags.size)
    }

    // ─── content ─────────────────────────────────────────────────────────────

    @Test
    fun `createDeletionEvent has empty content per NIP-09`() = runBlocking {
        val event = NostrProtocol.createDeletionEvent("anyid", senderIdentity)
        assertEquals("Content must be empty string", "", event.content)
    }

    // ─── pubkey ──────────────────────────────────────────────────────────────

    @Test
    fun `createDeletionEvent pubkey matches sender identity`() = runBlocking {
        val event = NostrProtocol.createDeletionEvent("anyid", senderIdentity)
        assertEquals("Pubkey must equal sender's public key", senderIdentity.publicKeyHex, event.pubkey)
    }

    // ─── signature ───────────────────────────────────────────────────────────

    @Test
    fun `createDeletionEvent has a non-null signature`() = runBlocking {
        val event = NostrProtocol.createDeletionEvent("anyid", senderIdentity)
        assertNotNull("Event must be signed", event.sig)
    }

    @Test
    fun `createDeletionEvent has a valid BIP-340 Schnorr signature`() = runBlocking {
        val event = NostrProtocol.createDeletionEvent("anyid", senderIdentity)
        assertTrue("Schnorr signature must verify correctly", event.isValidSignature())
    }

    // ─── timestamp ───────────────────────────────────────────────────────────

    @Test
    fun `createDeletionEvent timestamp is within the current second`() = runBlocking {
        val before = (System.currentTimeMillis() / 1000).toInt()
        val event = NostrProtocol.createDeletionEvent("ts-check", senderIdentity)
        val after = (System.currentTimeMillis() / 1000).toInt()

        assertTrue("createdAt must be >= start of test", event.createdAt >= before)
        assertTrue("createdAt must be <= end of test", event.createdAt <= after)
    }

    // ─── event ID ────────────────────────────────────────────────────────────

    @Test
    fun `createDeletionEvent has a non-empty event id`() = runBlocking {
        val event = NostrProtocol.createDeletionEvent("anyid", senderIdentity)
        assertTrue("Event id must not be empty", event.id.isNotEmpty())
    }

    @Test
    fun `createDeletionEvent id is consistent with content (NIP-01 hash)`() = runBlocking {
        val event = NostrProtocol.createDeletionEvent("anyid", senderIdentity)
        // isValidSignature() internally recalculates the id and checks it matches the stored id,
        // so a passing signature check implies the id is correct too.
        assertTrue("Event id and signature must be mutually consistent", event.isValidSignature())
    }
}
